import dev.morphismx.retrodfu.task.CheckSignature
import dev.morphismx.retrodfu.task.PatchFiles
import dev.morphismx.retrodfu.task.PatchJar
import dev.morphismx.retrodfu.task.ScanJar
import org.gradle.api.tasks.testing.logging.TestLogEvent
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar
import xyz.wagyourtail.jvmdg.gradle.task.files.DowngradeFiles

plugins {
    java
    `maven-publish`
    alias(libs.plugins.jvmdg)
    alias(libs.plugins.spotless)
    alias(libs.plugins.openjavaformat)
    id("retrodfu")
}

group = "dev.morphismx"
version = "11.0.22"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
}

// ── Source sets ─────────────────────────────────────────────────────────────

// The transformer source set sits beside main/test: one flat package holds
// the @CTransformer classes for the DFU jar and the downgraded test classes;
// each patch task picks its subset via the mixin-style JSON in
// src/transformer/resources. Same old-API baselines as main, sees main's
// output for the compat forwards, never depends on DFU — target names travel
// as annotation strings.
val transformer = sourceSets.create("transformer") {
    java.srcDir("src/transformer/java")
    compileClasspath += sourceSets.main.get().compileClasspath
    compileClasspath += sourceSets.main.get().output
}

// Upstream's own test tree, compiled against upstream's real dependencies —
// unlike `test` (src/test/java), which sees only the old-API baselines of main
// so its code resolves on every lane's deps.
val upstreamTest = sourceSets.create("upstreamTest") {
    java.srcDir("upstream/src/test/java")
}

// Both test trees take the same downgrade + patch path.
val testTrees = listOf(sourceSets.test.get(), upstreamTest)

// ── Configurations ──────────────────────────────────────────────────────────

fun resolvableUsage(name: String, configure: Configuration.() -> Unit = {}) =
    configurations.create(name) {
        isCanBeConsumed = false
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME))
        configure()
    }

// A resolvable, non-transitive dependency bucket.
fun bucket(name: String) = configurations.create(name) {
    isCanBeConsumed = false
    isTransitive = false
}

// The upstream DFU jar alone: the input of patchJar.
val patchTarget = resolvableUsage("patchTarget") { isTransitive = false }

// DFU's runtime classpath — ClassTransform's frame computer must see the
// dependencies the rewritten classes compiled against.
val patchClasspath = resolvableUsage("patchClasspath")

val mc1122 = bucket("mc1122")
val mc1710 = bucket("mc1710")
// junit and the JvmDG api jar for the downgraded test classpaths.
val retroTestRuntime = bucket("retroTestRuntime")
// The java18 API signature for checkSignature; the tool itself is a
// build-logic dependency.
val javaSignature = bucket("javaSignature")

// junit pulls in hamcrest-core 1.3, but hamcrest 3.0 already contains all of
// its classes, so keep the redundant artifact off every classpath.
configurations.configureEach {
    exclude(mapOf("group" to "org.hamcrest", "module" to "hamcrest-core"))
}

dependencies {
    patchTarget("com.mojang:datafixerupper")
    patchClasspath("com.mojang:datafixerupper")

    // Oldest supported game deps, so only members present on every supported
    // version resolve; scanJar re-checks the baselines.
    implementation(libs.mc1710.gson)
    implementation(libs.mc1710.guava)
    implementation(libs.mc1710.log4j.api)
    implementation(libs.mc1122.fastutil)

    // ClassTransform annotations are compile-time only: the @CTransformer
    // classes are consumed as bytecode, while the config's renames are applied
    // inside the patch tasks. None of it reaches the jar.
    add(transformer.compileOnlyConfigurationName, libs.classtransform.core)

    // The JDK 25 test lane runs the unmodified upstream build as the control.
    // The substituted project brings gson, guava, fastutil, jspecify and
    // slf4j-api along through its variants.
    "upstreamTestImplementation"("com.mojang:datafixerupper")
    "upstreamTestImplementation"(libs.junit)
    "upstreamTestImplementation"(libs.hamcrest)
    "upstreamTestRuntimeOnly"(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.hamcrest)

    retroTestRuntime(libs.junit)
    retroTestRuntime(libs.hamcrest)
    // Prebuilt API jar: the Java 8 lanes resolve JvmDG stubs from it at
    // runtime instead of shading them into the test classes.
    retroTestRuntime(variantOf(libs.jvmdg.java.api) { classifier("downgraded-8") })

    mc1122(libs.mc1122.gson)
    mc1122(libs.mc1122.guava)
    mc1122(libs.mc1122.log4j.api)
    mc1122(libs.mc1122.log4j.core)
    mc1122(libs.mc1122.fastutil)

    mc1710(libs.mc1710.gson)
    mc1710(libs.mc1710.guava)
    mc1710(libs.mc1710.log4j.api)
    mc1710(libs.mc1710.log4j.core)
    // 1.7.10 ships no fastutil, so this lane supplies the minimum version
    // itself, as a downstream would have to.
    mc1710(libs.mc1122.fastutil)

    javaSignature(variantOf(libs.java18Signature) { artifactType("signature") })
}

// ── Jar pipeline: patch -> downgrade -> shade ───────────────────────────────

val taskGroup = "retrodfu"

// The only file of a single-artifact configuration, as a lazy provider.
fun singleFile(config: Configuration) = config.elements.map { it.single().asFile }

// Nothing consumes the plain classes jar: patchJar reads the main output
// directly and the published artifact is the shaded jar.
tasks.jar {
    enabled = false
}

val patchJar = tasks.register<PatchJar>("patchJar") {
    group = taskGroup
    description = "Patches the upstream jar with ClassTransform and merges in the compat classes"
    inputFile.fileProvider(singleFile(patchTarget))
    transformers.from(transformer.output)
    transformerConfig = "dfu.transformers.json"
    classpath.from(patchClasspath, sourceSets.main.get().output)
    archiveClassifier = "patched"
    from(sourceSets.main.get().output)
}

tasks.named<DowngradeJar>("downgradeJar") {
    group = taskGroup
    description = "Downgrades the patched jar to Java 8 bytecode, rewriting newer JDK API calls to JvmDG stubs"
    inputFile = patchJar.flatMap { it.archiveFile }
    classpath = sourceSets.main.get().compileClasspath
    archiveClassifier = "downgraded"
}

val shadeDowngradedApi = tasks.named<ShadeJar>("shadeDowngradedApi") {
    group = taskGroup
    description = "Shades the used JvmDG api stubs into the jar under dev/morphismx/retrodfu/shadow/jvmdg"
    shadePath = "dev/morphismx/retrodfu/shadow/jvmdg/"
    archiveClassifier = ""
}

tasks.assemble { dependsOn(shadeDowngradedApi) }

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    group = taskGroup
    description = "Sources jar: upstream DFU sources plus our patch classes"
    archiveClassifier = "sources"
    from("upstream/src/main/java")
    from(sourceSets.main.get().allSource)
}

// ── Gates ───────────────────────────────────────────────────────────────────

// The jars of one artifact in a game dependency set, by file-name prefix.
fun jarsNamed(config: Configuration, prefix: String) = config.elements.map { files ->
    files.map { it.asFile }.filter { it.name.startsWith(prefix) }
}

val scanJar = tasks.register<ScanJar>("scanJar") {
    group = taskGroup
    description = "Jar gate: entry allowlist, bytecode major, forbidden references, member resolution on the MC baselines"
    inputJar = shadeDowngradedApi.flatMap { it.archiveFile }
    selfPrefixes = listOf("com/mojang/", "dev/morphismx/retrodfu/")
    fun baseline(prefix: String, vararg sources: Any) {
        baselines.add(project.objects.newInstance(ScanJar.Baseline::class.java).apply {
            this.prefix.set(prefix)
            jars.from(*sources)
        })
    }
    baseline("com/google/gson", jarsNamed(mc1710, "gson-"), jarsNamed(mc1122, "gson-"))
    baseline("com/google/common", jarsNamed(mc1710, "guava-"), jarsNamed(mc1122, "guava-"))
    baseline("it/unimi/dsi/fastutil", jarsNamed(mc1122, "fastutil-"))
    baseline("org/apache/logging/log4j", jarsNamed(mc1710, "log4j-api-"), jarsNamed(mc1122, "log4j-api-"))
    reportFile = layout.buildDirectory.file("reports/retro/scan-jar.txt")
}

val checkSignature = tasks.register<CheckSignature>("checkSignature") {
    group = taskGroup
    description = "animal-sniffer: java18 signature + both game dep sets on the classpath"
    inputJar = shadeDowngradedApi.flatMap { it.archiveFile }
    signatureFile.fileProvider(singleFile(javaSignature))
    apiJars.from(mc1710, mc1122)
    // MethodHandle.invokeExact is signature-polymorphic, which animal-sniffer
    // cannot model; the class exists since Java 7 anyway.
    ignores = setOf("sun.misc", "java.lang.invoke.MethodHandle")
    reportFile = layout.buildDirectory.file("reports/retro/check-signature.txt")
}

// The target is explicit because the upstreamTest source set lives inside the
// upstream/ submodule, which must never be reformatted.
spotless {
    java {
        target("src/**/*.java")
    }
}

// The root format tasks cover the included build too.
val buildLogic = gradle.includedBuild("build-logic")
tasks.spotlessCheck { dependsOn(buildLogic.task(":spotlessCheck")) }
tasks.spotlessApply { dependsOn(buildLogic.task(":spotlessApply")) }

// ── Test lanes ──────────────────────────────────────────────────────────────

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        showExceptions = true
    }
}

val testDowngrade = tasks.register<DowngradeFiles>("testDowngrade") {
    group = taskGroup
    description = "Downgrades the test classes to Java 8 bytecode"
    inputCollection = files(testTrees.map { it.output })
    classpath = files(testTrees.map { it.compileClasspath })
    // Each input root lands at temporaryDir/<input-name>. DowngradeFiles
    // registers no output for a root that doesn't exist at configuration
    // time, so declare them here.
    testTrees.forEach { outputs.dir(temporaryDir.resolve(it.name)) }
}

val testPatch = tasks.register<PatchFiles>("testPatch") {
    group = taskGroup
    description = "Applies the same patch rules to the test classes"
    inputDirs.from(testDowngrade)
    outputDir = layout.buildDirectory.dir("retrotest")
    transformers.from(transformer.output)
    transformerConfig = "test.transformers.json"
    // The frame computer sees both test trees' compile classpaths — the
    // upstream build's test dependencies plus our old-API baselines — and our
    // compat classes, which the test transformers' handler bodies call into.
    classpath.from(testTrees.map { it.compileClasspath }, sourceSets.main.get().output)
}

// The JDK 25 control: upstream's own tests against the unmodified upstream
// build. A failure on a retro lane that also fails here is upstream's, not the
// downgrade's. It stays apart from `test` because upstream's guava would
// clash with the old-API baselines that `test` runs on.
val testUpstream = tasks.register<Test>("testUpstream") {
    group = taskGroup
    description = "Runs upstream's CodecTests and RoundtripTest on JDK 25 against the unmodified upstream jar"
    testClassesDirs = upstreamTest.output.classesDirs
    classpath = upstreamTest.runtimeClasspath
    shouldRunAfter("test")
}

// The retro lanes: the final jar's downgraded suite on Java 8, one lane per
// game's dependency set.
val java8Launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(8)
}
val retroLanes = mapOf("testMc1122" to mc1122, "testMc1710" to mc1710).map { (laneName, gameDeps) ->
    tasks.register<Test>(laneName) {
        group = taskGroup
        description = "Runs the downgraded suite on Java 8 with ${gameDeps.name} dependencies"
        javaLauncher = java8Launcher
        testClassesDirs = files(testPatch)
        classpath = files(gameDeps, testPatch, shadeDowngradedApi, retroTestRuntime)
        shouldRunAfter("test")
    }
}

tasks.check {
    dependsOn(scanJar, checkSignature, buildLogic.task(":test"), testUpstream, retroLanes)
}

// ── Publishing ──────────────────────────────────────────────────────────────

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifact(shadeDowngradedApi)
            artifact(sourcesJar)

            pom {
                name = "RetroDFU"
                description = "Mojang's DataFixerUpper repackaged for Java 8 and Minecraft 1.7.10 / 1.12.2"
                url = "https://github.com/morphismx/RetroDFU"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                scm {
                    url = "https://github.com/morphismx/RetroDFU"
                    connection = "scm:git:https://github.com/morphismx/RetroDFU.git"
                    developerConnection = "scm:git:ssh://git@github.com/morphismx/RetroDFU.git"
                }
            }
        }
    }
}
