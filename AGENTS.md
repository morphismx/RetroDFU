# AGENTS.md

RetroDFU repackages Mojang's DataFixerUpper to run on Java 8 with Minecraft 1.7.10 and 1.12.2 — the build
patches and downgrades the upstream jar rather than forking it. Maintainer notes below; user-facing docs are
in `README.md` and `README.zh-CN.md`, keep the two in sync.

Area notes: `src/AGENTS.md` covers the shipped sources (compat classes, slf4j replacement, ClassTransform
transformers, unit tests); `build-logic/AGENTS.md` covers the included build (`ClassPatcher`, task types,
`ClassScanner` internals).

## Environment

- JDK 25 builds and runs the native test lane. JDK 8 runs the retro lanes.
- Toolchains resolve via auto-detection; the foojay resolver (`settings.gradle.kts`) auto-downloads what's missing —
  for the root build only, which is what covers the Java 8 lanes. Included builds resolve toolchains independently,
  so `upstream/`'s JDK 25 toolchain must already be installed: run Gradle on JDK 25 or keep one auto-detectable.
  Build cache and configuration cache are on, so task inputs/outputs must stay declared.
- Dependency versions live in `gradle/libs.versions.toml`; the project version (`version = "11.0.22"`, the DFU release
  it corresponds to) is in `build.gradle.kts`. DFU's own dependency versions come from the `upstream/` included build;
  never hand-copy them.
- `upstream/` is a git submodule for Mojang/DataFixerUpper, pinned to the commit matching the project version.
  `includeBuild("upstream")` makes its project substitute `com.mojang:datafixerupper` wherever the build requests it,
  so the input jar, the downgrade classpath and the test lanes all consume the jar it builds from source. Its test tree
  is compiled in place (`sourceSets.test` reads `upstream/src/test/java`) — nothing is vendored, and `check` needs no
  network for upstream. A fresh clone needs `git submodule update --init` before anything builds.

## Commands

```
./gradlew build                  # everything below: jar + all gates + all test lanes
./gradlew check                  # scanJar/checkSignature, build-logic unit tests, test + testUpstream + testMc1122 + testMc1710
./gradlew shadeDowngradedApi     # final jar -> build/libs/retrodfu-<version>.jar
./gradlew scanJar                # jar gate only; report in build/reports/retro/scan-jar.txt
./gradlew formatDiff             # open-java-format: fix the lines in `git diff HEAD` only
./gradlew spotlessApply          # format every own .java (root src/ + build-logic/src/, never upstream/)
./gradlew testMc1122             # one Java 8 lane (testMc1710; `testUpstream` = JDK 25 control)
./gradlew testMc1122 --tests '*RoundtripTest'    # one upstream test class in a lane
./gradlew -p build-logic test    # ClassScanner unit tests
./gradlew publishToMavenLocal
```

Use the narrowest command that exercises the change while iterating (one lane, one test class);
run `./gradlew check` before finishing.

## Layout

- `src/`: everything shipped in the jar — compat classes, slf4j replacement, ClassTransform transformer
  classes/configs, licenses — plus our unit tests. Details in `src/AGENTS.md`.
- `upstream/`: see Environment above. Its `src/test/java` (`CodecTests`, `RoundtripTest`) is upstream's checkout,
  compiled in place as the `upstreamTest` source set against upstream's real dependencies; every lane runs it.
- `build-logic/`: included build providing the `retrodfu` plugin and the pipeline task types. Details in
  `build-logic/AGENTS.md`.
- `build.gradle.kts`: registers every pipeline task directly so inputs and outputs stay visible.
- `environment.yaml`: Devin Cloud session blueprint — initialize installs a Temurin 25 JDK (daemon +
  `upstream/` toolchain), inits the submodule and warms Gradle caches with a full `build`.

## Pipeline

```
patchJar             ClassTransform over the upstream jar — type renames
                       (slf4j -> ours) + @CRedirect retargets (newer
                       guava/fastutil members -> GuavaCompat/FastutilCompat)
                       — repacked with our classes
                       + licenses merged in
  -> downgradeJar      JvmDG: class major 69 -> 52, JDK 9+ API calls -> stubs
  -> shadeDowngradedApi  JvmDG api stubs shaded under dev/morphismx/retrodfu/shadow/jvmdg
  -> scanJar, checkSignature
```

- `patchJar` sits ahead of JvmDG's default chain rather than spliced inside it: it patches the raw upstream
  jar (transformers read from the `transformer` source set per `dfu.transformers.json`, since the input jar
  carries none), then
  merges the patched jar with our classes and licenses via `from`/`copy()`, the same shape JvmDG's own
  `DowngradeJar`/`ShadeJar` use. Only `downgradeJar`'s `inputFile` is overridden; `shadeDowngradedApi` keeps its
  default input and has an empty classifier, so its output is the final `retrodfu-<version>.jar`. Task names match
  their val names and carry no `retro` prefix — the whole build is retro.
- Both test trees go through the same pipeline in directory form (`testDowngrade` = `DowngradeFiles` over the
  `test` and `upstreamTest` outputs, then `testPatch` = `PatchFiles` over the downgraded tree with
  `test.transformers.json`, written to `build/retrotest`), so every retro lane runs downgraded and relocated
  code without a jar round-trip. JvmDG stub calls resolve from the `jvmdowngrader-java-api:downgraded-8` jar on
  `retroTestRuntime` — no shading step.
- `patchClasspath` resolves `com.mojang:datafixerupper` transitively so ClassTransform's frame computer sees the DFU
  dependencies; the test side uses the test compile classpath plus main output instead.

`@CRedirect`/rename semantics and the runtime baselines are documented in `src/AGENTS.md`; patcher internals in
`build-logic/AGENTS.md`.

## Java formatting

Java code in this repository is formatted with open-java-format. It has one style and no
options, so never lay out code by hand and never try to match the lines around your change.

After you change Java code, and again before you commit, run:

```sh
git add -N . && ./gradlew formatDiff
```

## Gates (all part of `check`)

`spotlessCheck` enforces open-java-format on our Java trees (`src/**/*.java` and `build-logic/src/**/*.java`
through the included-build wiring; `upstream/` is never a target). Style is enforced by the tool, not by
convention — fix failures with `./gradlew formatDiff` or `spotlessApply`.

`scanJar` enforces the shipped jar's contract — allowed entry trees, class major 52, no
slf4j/JvmDG/JDK-9+ references surviving the downgrade, and every member ref resolving on all game
baselines and inside the jar. The full rule list lives next to the implementation in
`build-logic/AGENTS.md`; the report goes to `build/reports/retro/scan-jar.txt`.

`checkSignature` runs animal-sniffer with the java18 signature and both game dependency sets on the classpath.

## Test matrix

Upstream's `CodecTests` and `RoundtripTest` and our `src/test/java` compat unit tests run in these lanes:

- `testUpstream`: JDK 25, upstream's tests against the unmodified upstream jar. The control: a failure on a retro
  lane that also fails here is upstream's, not the downgrade's.
- `test`: JDK 25, our unit tests only, straight from the `main` output against the oldest baselines (guava 17,
  fastutil 7.1.0, gson 2.2.4, log4j 2.0-beta9). Fast feedback; not downgraded.
- `testMc1122`, `testMc1710`: both suites, downgraded and patched, against the final jar on Java 8 with each game's
  gson/guava/log4j/fastutil (the 1.7.10 lane supplies fastutil 7.1.0 itself, since the game ships none).

`check` also depends on the `build-logic` unit tests.

## Upgrading upstream

1. Find the DFU version the target MC build uses: the version JSON (linked from the piston-meta v2
   `version_manifest_v2.json`) lists `com.mojang:datafixerupper` among its libraries.
2. Upstream tags no 11.x releases. On GitHub, find the commit whose `src/main/java` matches the new `-sources` jar.
3. `git -C upstream fetch && git -C upstream checkout <commit>`, and update `version` in `build.gradle.kts` to the
   release version that commit corresponds to.
4. `./gradlew check`. Re-confirm the game dependency baselines in `gradle/libs.versions.toml` still cover upstream's
   declared dependencies (`upstream/build.gradle`).
5. `./gradlew publishToMavenLocal`.

The READMEs carry no version number; touch them only if requirements or behavior change.

## Publishing

Coordinates: `dev.morphismx:retrodfu:<version>`. The publication contains the final jar and the sources jar, with an
empty dependency list and no Gradle module metadata. The sources jar carries upstream's pre-patch sources — they still
show `org.slf4j` and newer guava/fastutil calls that the bytecode redirects at build time. The target Maven repository
is not decided yet; until then it publishes to `mavenLocal` only.
