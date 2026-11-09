# AGENTS.md

Maintainer notes for RetroDFU. User-facing docs are in `README.md` and `README.zh-CN.md`; keep the two in sync.

## Environment

- JDK 25 builds and runs the native test lane. JDK 8 runs the retro lanes.
- Gradle finds JDKs only through `JAVA8_HOME` / `JAVA_HOME_8_X64` and `JAVA25_HOME` / `JAVA_HOME_25_X64`. Auto-download
  is off (`gradle.properties`). Build cache and configuration cache are on, so task inputs/outputs must stay declared.
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
./gradlew check                  # compatScan/verifyJar, build-logic unit tests, test + testMc1122 + testMc1710
./gradlew shadeDowngradedApi     # final jar -> build/libs/retrodfu-<version>.jar
./gradlew compatScan             # jar gate only; report in build/reports/retro/compat-scan.txt
./gradlew testMc1122             # one lane (testMc1710; plain `test` = JDK 25 control)
./gradlew testMc1122 --tests '*RoundtripTest'    # one upstream test class in a lane
./gradlew -p build-logic test    # unit tests of ClassPatcher / PatchSpec / CompatScan
./gradlew publishToMavenLocal
```

## Layout

- `src/main/java/dev/morphismx/retrodfu/annotation/`: the patch annotations (`@Replaces` = whole-type rename,
  `@Redirect` = call-site retarget to a static handler).
- `src/main/java/dev/morphismx/retrodfu/guava/`, `.../fastutil/`: the `@Redirect` handlers, one `*Compat` class per
  patched target type. They compile on JDK 25 against the oldest game deps (gson 2.2.4, guava 17.0, fastutil 7.1.0,
  log4j-api 2.0-beta9) as `compileOnly`, so only members that exist on every supported game version resolve; the
  classes are then downgraded together with the rest of the jar. CompatScan re-checks this against the baselines.
- `src/main/java/dev/morphismx/retrodfu/slf4j/`: the slf4j replacement (`Logger`, `LoggerFactory`, `LoggerImpl`
  forwarding to log4j2), each marked `@Replaces`. Same compile baselines.
- `src/main/resources/META-INF/licenses/`: licenses of everything bundled.
- `upstream/`: see above. Its `src/test/java` (`CodecTests`, `RoundtripTest`) is the project's whole test tree; treat it
  as read-only (it's upstream's checkout — handwritten tests have nowhere to live).
- `build-logic/`: included build providing the `retrodfu` plugin (`RetroDFUPlugin` is an empty marker; it only puts the
  task types on the build-script classpath).
    - `patch/ClassPatcher`: ASM patcher. Scans the input jar (plus `patches` jars) for the patch annotations, then
      applies type renames (`ClassRemapper`) and call-site redirects (`INVOKESTATIC` to the handler) in one pass.
      Handlers that never matched are logged as a warning; a misspelled descriptor leaves the call untouched, which
      CompatScan then reports.
    - `patch/PatchSpec`: reads the `@Replaces`/`@Redirect` attributes off class bytes and validates handler signatures.
    - `CompatScan`: the jar checks behind `compatScan` (the Gradle wrapper is `task/CompatScan`).
    - `task/`: Gradle task wrappers (`PatchJar`/`PatchFiles`, `CompatScan`, `AnimalSniffer`).
    - Unit tests in `build-logic/src/test/`; run with `./gradlew -p build-logic test`.
- `build.gradle.kts`: registers every pipeline task directly so inputs and outputs stay visible.
- `.devin/rules/comment.md`: commenting standard — comments explain why, public API gets Javadoc, no comments that
  restate the code, update or delete comments when the code changes.

## Pipeline

```
patchJar             ClassPatcher over the upstream jar — @Replaces type
                       renames (slf4j -> ours) + @Redirect retargets
                       (new-guava/fastutil members -> the guava/ and
                       fastutil/ *Compat classes) — repacked with our classes
                       + licenses merged in
  -> downgradeJar      JvmDG: class major 69 -> 52, JDK 9+ API calls -> stubs
  -> shadeDowngradedApi  JvmDG api stubs shaded under dev/morphismx/retrodfu/shadow/jvmdg
  -> compatScan, verifyJar
```

- The rewrite covers invokes, fields, types, descriptors, signatures, invokedynamic and method handles. Type renames are
  pure renames, which stay legal only because our `Logger` mirrors slf4j's interface shape. `@Redirect` handlers are
  static methods: an instance call's receiver becomes the first argument. A handler that never matched a call site
  (misspelled descriptor, or an upstream call that went away) is logged as a warning; the test classes exercise only
  part of the handler set, so a few warnings there are expected.
- Redirect matching is on the call's literal owner: calls through a subtype owner, INVOKESPECIAL, and method references
  inside invokedynamic are never retargeted — CompatScan catches whatever stays unresolved.
- Nothing is bundled: guava is provided by the game (17.0/21.0), fastutil is provided by 1.12.2 (7.1.0) while 1.7.10
  ships none — the README tells downstreams to supply it there. The DFU call sites that postdate those baselines are
  redirected to the `guava`/`fastutil` patch classes, which stick to guava-17 / fastutil-7.1.0 APIs.
- `patchJar` sits ahead of JvmDG's default chain rather than spliced inside it: it patches the raw upstream
  jar (rules scanned from `patches`, which points at the `jar` task's output — our classes jar, built with classifier
  `patches`, since the input jar carries no patch classes), then merges the patched jar with our classes and licenses
  via `from`/`copy()`, the same shape JvmDG's own `DowngradeJar`/`ShadeJar` use. Only `downgradeJar`'s `inputFile`
  is overridden; `shadeDowngradedApi` keeps its default input and has an empty classifier, so its output is the final
  `retrodfu-<version>.jar`. Task names match their val names and carry no `retro` prefix — the whole build is retro.
- The test classes go through the same pipeline in directory form (`testDowngrade` = `DowngradeFiles`, then
  `testPatch` = `PatchFiles` over `sourceSets.test.output`, written to `build/retrotest`), so every retro lane runs
  downgraded and relocated code without a jar round-trip. JvmDG stub calls resolve from the
  `jvmdowngrader-java-api:downgraded-8` jar on `retroTestRuntime` — no shading step.

## Gates (all part of `check`)

`compatScan` fails when:

- the jar has entries outside `com/mojang/datafixers/`, `com/mojang/serialization/`, `dev/morphismx/retrodfu/` and
  `META-INF/`;
- a class exceeds major 52, or `module-info` / `META-INF/versions` is present;
- forbidden references remain outside the shadow trees (our own `dev/morphismx/retrodfu/` classes are exempt from the
  string match, since `@Replaces` names the types it replaces): `org/slf4j`, unrelocated `xyz/wagyourtail`,
  `java/lang/Record`, `java/lang/runtime/`, `StringConcatFactory`;
- a gson member ref doesn't resolve on both gson 2.2.4 (1.7.10) and 2.8.0 (1.12.2), a guava member ref doesn't resolve
  on both guava 17.0 and 21.0, a fastutil member ref doesn't resolve on fastutil 7.1.0, or a log4j member ref doesn't
  resolve on both log4j-api 2.0-beta9 and 2.8.1;
- a `com.mojang.*` / `dev.morphismx.*` member ref doesn't resolve inside the jar, including through bundled interfaces;
- any other referenced type resolves nowhere (jar, JDK, or a baseline jar).

The report goes to `build/reports/retro/compat-scan.txt`.

`verifyJar` runs animal-sniffer with the java18 signature and both game dependency sets on the classpath.

## Test matrix

Each lane runs upstream's `CodecTests` and `RoundtripTest`:

- `test`: JDK 25, unmodified upstream binary. The control.
- `testMc1122`, `testMc1710`: final jar on Java 8 with each game's gson/guava/log4j/fastutil (the
  1.7.10 lane supplies fastutil 7.1.0 itself, since the game ships none).

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
