# AGENTS.md

Notes for `build-logic/`, the included build providing the `retrodfu` plugin used by the root
`build.gradle.kts`. Pipeline wiring and the test matrix are in the root `AGENTS.md`; transformer configs
and their semantics are in `src/AGENTS.md`.

- `RetroDFUPlugin` is an empty marker; it only puts the task types on the build-script classpath.
- `ClassPatcher`: runs a ClassTransform `TransformerManager` over the input. The config's
  `renames` map drives a remap bytecode transformer ahead of everything else (slf4j type renames); the
  `@CTransformer` classes listed by `transformers` retarget their annotated call sites. Injected handlers keep
  their ClassTransform annotation markers — inert metadata, accepted the same way the JvmDG markers are.
  ClassTransform's
  `additionalclassprovider` providers read the input roots and the task classpath for frame computation,
  falling back to the JDK.
- `ClassScanner`: the class-scan engine behind `scanJar` — entry allowlist, bytecode major cap, byte-scan
  needles and member resolution, all driven by a `Rules` parameter so the engine stays project-agnostic.
- `task/`: Gradle task wrappers (`PatchJar`/`PatchFiles` sharing the `PatchTask` input interface,
  `ScanJar`, `CheckSignature`). `task/ScanJar` only declares the gate's inputs — the policy itself
  (entry prefixes, forbidden needles, byte-scan exemptions, `META-INF/versions` exclusion) is
  configured in `build.gradle.kts`'s `scanJar` registration, keeping the task type project-agnostic
  like the engine.

## `scanJar` — jar gate rules

`scanJar` (`task/ScanJar`, engine `ClassScanner`) fails when:

- the jar has entries outside `com/mojang/datafixers/`, `com/mojang/serialization/`, `net/morphismmc/retrodfu/` and
  `META-INF/`;
- a class exceeds major 52, or `module-info` / `META-INF/versions` is present;
- forbidden references remain outside the shadow trees (our own `net/morphismmc/retrodfu/` classes are exempt from the
  string match, since the JvmDG stubs name their unrelocated selves): `org/slf4j`,
  unrelocated `xyz/wagyourtail`, `java/lang/Record`, `java/lang/runtime/`, `StringConcatFactory`;
- a gson member ref doesn't resolve on both gson 2.2.4 (1.7.10) and 2.8.0 (1.12.2), a guava member ref doesn't resolve
  on both guava 17.0 and 21.0, a fastutil member ref doesn't resolve on fastutil 7.1.0, or a log4j member ref doesn't
  resolve on both log4j-api 2.0-beta9 and 2.8.1;
- a `com.mojang.*` / `net.morphismmc.*` member ref doesn't resolve inside the jar, including through bundled interfaces;
- any other referenced type resolves nowhere (jar, JDK, or a baseline jar).

The report goes to `build/reports/retro/scan-jar.txt`. `task/CheckSignature` backs `checkSignature`: animal-sniffer
with the java18 signature and both game dependency sets on the classpath.

## Validating changes

Unit tests live in `src/test/`; from the repo root run `./gradlew -p build-logic test`. After changing the
patcher or gate logic, also run `./gradlew scanJar` — root `check` runs everything.
