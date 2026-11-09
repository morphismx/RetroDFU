import dev.morphismx.retrodfu.task.*
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.testing.logging.TestLogEvent
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar
import xyz.wagyourtail.jvmdg.gradle.task.files.DowngradeFiles

plugins {
    java
    `maven-publish`
    alias(libs.plugins.jvmdg)
    id("retrodfu")
}

group = "dev.morphismx"
version = "11.0.22"
base.archivesName = "retrodfu"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
}

val patchTarget = configurations.create("patchTarget") {
    isTransitive = false
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME))
}

// A resolvable, non-transitive bucket.
fun detached(name: String) = configurations.create(name) {
    isCanBeConsumed = false
    isTransitive = false
}

val mc1122 = detached("mc1122")
val mc1710 = detached("mc1710")
// junit and the JvmDG api jar for the downgraded test classpaths.
val retroTestRuntime = detached("retroTestRuntime")
// The java18 API signature for AnimalSniffer; the tool itself is a
// build-logic dependency.
val javaSignature = detached("javaSignature")

// junit pulls in hamcrest-core 1.3, but hamcrest 3.0 already contains all of
// its classes, so keep the redundant artifact off every classpath.
configurations.configureEach {
    exclude(mapOf("group" to "org.hamcrest", "module" to "hamcrest-core"))
}

dependencies {
    "patchTarget"("com.mojang:datafixerupper")

    // The patch classes compile against the oldest supported game dependencies,
    // so every member they use exists on all supported Minecraft versions.
    // CompatScan re-checks this against the baselines.
    compileOnly(libs.mc1710.gson)
    compileOnly(libs.mc1710.guava)
    compileOnly(libs.mc1710.log4j.api)
    compileOnly(libs.mc1122.fastutil)

    // The JDK 25 test lane runs the unmodified upstream build as the control.
    // The substituted project brings gson, guava, fastutil, jspecify and
    // slf4j-api along through its variants.
    testImplementation("com.mojang:datafixerupper")
    testImplementation(libs.junit)
    testImplementation(libs.hamcrest)
    testRuntimeOnly(libs.slf4j.simple)

    "retroTestRuntime"(libs.junit)
    "retroTestRuntime"(libs.hamcrest)
    // Use prebuilt API JAR to speed up: the Java 8 lanes resolve JvmDG stubs
    // from it at runtime instead of shading them into the test classes.
    "retroTestRuntime"(variantOf(libs.jvmdg.java.api) { classifier("downgraded-8") })

    "mc1122"(libs.mc1122.gson)
    "mc1122"(libs.mc1122.guava)
    "mc1122"(libs.mc1122.log4j.api)
    "mc1122"(libs.mc1122.log4j.core)
    "mc1122"(libs.mc1122.fastutil)

    "mc1710"(libs.mc1710.gson)
    "mc1710"(libs.mc1710.guava)
    "mc1710"(libs.mc1710.log4j.api)
    "mc1710"(libs.mc1710.log4j.core)
    // 1.7.10 ships no fastutil, so this lane supplies the minimum version
    // itself, as a downstream would have to.
    "mc1710"(libs.mc1122.fastutil)

    "javaSignature"(variantOf(libs.java18Signature) { artifactType("signature") })
}

val retroGroup = "retrodfu"

// The rules jar gets its own name: with the default empty classifier it
// would share retrodfu-<version>.jar with the shaded artifact.
val rulesJar = tasks.named<Jar>("jar") {
    archiveClassifier = "patches"
}

val patchJar = tasks.register<PatchJar>("patchJar") {
    group = retroGroup
    description = "Patches the upstream jar and merges in the patch classes"
    inputFile.fileProvider(patchTarget.elements.map { it.single().asFile })
    patches = rulesJar.flatMap { it.archiveFile }
    archiveClassifier = "patched"
    from(sourceSets.main.get().output)
}

val downgradeJar = tasks.named<DowngradeJar>("downgradeJar") {
    group = retroGroup
    description = "Downgrades the merged jar to Java 8 bytecode, rewriting newer JDK API calls to JvmDG stubs"
    inputFile = patchJar.flatMap { it.archiveFile }
    classpath = sourceSets.main.get().compileClasspath
    archiveClassifier = "downgraded"
}

val shadeDowngradedApi = tasks.named<ShadeJar>("shadeDowngradedApi") {
    group = retroGroup
    description = "Shades the used JvmDG api stubs into the jar under dev/morphismx/retrodfu/shadow/jvmdg"
    shadePath = "dev/morphismx/retrodfu/shadow/jvmdg/"
    archiveClassifier = ""
}

tasks.named("assemble") { dependsOn(shadeDowngradedApi) }

// The jars of one artifact in a game dependency set, selected by file name
// prefix such as gson-, guava-, log4j-api- or fastutil-.
fun named(config: Configuration, prefix: String) = config.elements.map { fs ->
    fs.map { it.asFile }.filter { it.name.startsWith(prefix) }
}

val compatScan = tasks.register<CompatScan>("compatScan") {
    group = retroGroup
    description =
        "Member-resolution gate: log4j/gson/guava/fastutil refs resolve on the MC baselines, self refs resolve, no stray types"
    inputJar = shadeDowngradedApi.flatMap { it.archiveFile }
    selfPrefixes = listOf("com/mojang/", "dev/morphismx/retrodfu/")
    fun baseline(prefix: String, vararg sources: Any) {
        baselines.add(project.objects.newInstance(CompatScan.Baseline::class.java).apply {
            this.prefix.set(prefix)
            jars.from(*sources)
        })
    }
    baseline("com/google/gson", named(mc1710, "gson-"), named(mc1122, "gson-"))
    baseline("com/google/common", named(mc1710, "guava-"), named(mc1122, "guava-"))
    baseline("it/unimi/dsi/fastutil", named(mc1122, "fastutil-"))
    baseline("org/apache/logging/log4j", named(mc1710, "log4j-api-"), named(mc1122, "log4j-api-"))
    reportFile = layout.buildDirectory.file("reports/retro/compat-scan.txt")
}

val verifyJar = tasks.register<AnimalSniffer>("verifyJar") {
    group = retroGroup
    description = "animal-sniffer: java18 signature + both game dep sets on the classpath"
    dependsOn(compatScan)
    inputJar = shadeDowngradedApi.flatMap { it.archiveFile }
    signatureFile.fileProvider(javaSignature.elements.map { files -> files.single().asFile })
    apiJars.from(mc1710, mc1122)
    // MethodHandle.invokeExact is signature-polymorphic, which animal-sniffer
    // cannot model; the class exists since Java 7 anyway.
    ignores = setOf("sun.misc", "java.lang.invoke.MethodHandle")
    reportFile = layout.buildDirectory.file("reports/retro/verify-stamp.txt")
}

sourceSets.test {
    java.setSrcDirs(listOf("upstream/src/test/java"))
}

val testDowngrade = tasks.register<DowngradeFiles>("testDowngrade") {
    group = retroGroup
    description = "Downgrades the test classes to Java 8 bytecode"
    inputCollection = sourceSets.test.get().output
    classpath = sourceSets.test.get().compileClasspath
    // Each input root lands at temporaryDir/<input-name>, and the source-set
    // dirs are all named "test". DowngradeFiles registers no output for a
    // root that doesn't exist at configuration time, so declare it here.
    outputs.dir(temporaryDir.resolve("test"))
}

val testPatch = tasks.register<PatchFiles>("testPatch") {
    group = retroGroup
    description = "Applies the same patch rules to the test classes"
    inputDirs.from(testDowngrade)
    outputDir = layout.buildDirectory.dir("retrotest")
    // The test classes carry no patch classes; the rules come from our
    // classes jar, as they do for patchJar.
    patches = rulesJar.flatMap { it.archiveFile }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        showExceptions = true
    }
}

// Registers one test-matrix lane and makes `check` depend on it.
fun registerTestLane(
    taskName: String, desc: String, javaVersion: Int, mcConfig: Configuration,
    classes: TaskProvider<PatchFiles>, vararg extraClasspath: Any
) {
    val t = tasks.register<Test>(taskName) {
        group = retroGroup
        description = desc
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }
        testClassesDirs = files(classes)
        classpath = files(mcConfig, classes, *extraClasspath, retroTestRuntime)
        shouldRunAfter("test")
    }
    tasks.named("check") { dependsOn(t) }
}

registerTestLane(
    "testMc1122",
    "Runs the downgraded suite on Java 8 with mc1122 dependencies", 8, mc1122,
    testPatch, shadeDowngradedApi
)
registerTestLane(
    "testMc1710",
    "Runs the downgraded suite on Java 8 with mc1710 dependencies", 8, mc1710,
    testPatch, shadeDowngradedApi
)

tasks.named("check") {
    dependsOn(
        verifyJar,
        gradle.includedBuild("build-logic").task(":test")
    )
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    group = retroGroup
    description = "Sources jar: upstream DFU sources plus our patch classes"
    archiveClassifier = "sources"
    from("upstream/src/main/java")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("retro") {
            artifactId = "retrodfu"
            artifact(shadeDowngradedApi)
            artifact(sourcesJar)
        }
    }
}
