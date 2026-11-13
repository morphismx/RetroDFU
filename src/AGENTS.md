# AGENTS.md

Notes for `src/` — everything shipped in `retrodfu-<version>.jar` plus the ClassTransform transformer sources that
patch upstream. Project-wide environment, commands, pipeline wiring and gates are in the root `AGENTS.md`.

## `main` source set

- `main/java/net/morphismmc/retrodfu/guava/GuavaCompat`, `.../fastutil/FastutilCompat`: the compatibility
  implementations, one class per third-party library. They compile on JDK 25 against the oldest game deps (gson 2.2.4,
  guava 17.0, fastutil 7.1.0, log4j-api 2.0-beta9) as `compileOnly`, so only members that exist on
  every supported game version resolve; the classes are then downgraded together with the rest of the jar.
  scanJar re-checks this against the baselines.
- `main/resources/META-INF/licenses/`: licenses of everything bundled.

## Jar constraints

- Shipped classes must live under `net/morphismmc/retrodfu/` — the gate only also allows the patched
  `com/mojang/*` trees and `META-INF/` entries.
- `org/slf4j`, `java/lang/Record`, `java/lang/runtime/` and `StringConcatFactory`
  references must never survive into the jar. JvmDG rewrites JDK 9+ call sites; `scanJar` catches what
  stays. Injected handlers keep their ClassTransform annotation markers — inert, like the JvmDG ones. The
  full gate rules are in `build-logic/AGENTS.md`.

## Runtime baselines

Nothing is bundled: guava is provided by the game (17.0/21.0), fastutil is provided by 1.12.2 (7.1.0) while 1.7.10
ships none — the README tells downstreams to supply it there. The DFU call sites that postdate those baselines are
redirected to GuavaCompat/FastutilCompat, which stick to guava-17 / fastutil-7.1.0 APIs.

## `transformer` source set

- `transformer/java/net/morphismmc/retrodfu/`: the ClassTransform transformer classes
  (`net.lenni0451.classtransform:core`, mixin style) in one flat package. Each affected DFU class gets a
  `@CTransformer` abstract class whose `@CRedirect` methods forward once to a `GuavaCompat`/`FastutilCompat`
  static method; the transformer itself must hold no fields or helpers, because every non-annotation member is
  copied into the target class — shared `@CTarget` descriptors therefore live in `Targets`, whose compile-time
  constants inline into the annotation.
- `transformer/resources/*.transformers.json` are mixin-style configs (`package` + `renames` type-rename map +
  `transformers` list) — `patchJar` uses `dfu.transformers.json`,
  `testPatch` `test.transformers.json`, so each task patches only the classes its list names. The
  source set compiles against the same `compileOnly` baselines as main plus ClassTransform core, and is never
  merged into the jar.

## Redirect semantics

- The rewrite covers invokes, fields, types, descriptors, signatures, invokedynamic and method handles. Type renames
  retarget slf4j's `Logger`/`LoggerFactory` onto log4j2's `Logger`/`LogManager`, which stay legal only because every
  member DFU calls exists on both game baselines — the fixed-arity `(String, Object, Object)` overloads being the one
  gap on log4j-api 2.0-beta9, covered by `DataFixerBuilderTransformer`/`DataFixerBuilderResultTransformer` redirecting
  those call sites onto the varargs overloads. `@CRedirect` pins a call
  site by containing method plus exact owner/name/descriptor; the handler is copied into the target class and an
  instance call's receiver becomes its first argument. A misspelled method or missing call site fails the build,
  and every registered transformer target must appear in the input — there is no "never matched" warning anymore.
- Redirect matching is on the call's literal owner and descriptor: calls through a subtype owner, INVOKESPECIAL, and
  method references inside invokedynamic are never retargeted — scanJar catches whatever stays unresolved.

## `test` source set

`test/java` holds our own tests: the `GuavaCompat`/`FastutilCompat` unit tests, which compile against
the same old-API baselines as main — members absent on any lane's deps fail at compile time.
`test` runs them on JDK 25 straight from the `main` output; the retro lanes run them downgraded next to upstream's
`upstreamTest` tree (see the root `AGENTS.md`), so the compat classes are also exercised against the real game
dependencies on Java 8.

## Validating changes

`./gradlew scanJar` re-checks the jar contract without running lanes; before finishing, run a game lane
(`./gradlew testMc1122` or `testMc1710`, `--tests '*Name'` for one class) — `check` runs every lane.
Java style is enforced by open-java-format: `./gradlew formatDiff` fixes the lines you changed,
`./gradlew spotlessApply` everything; `spotlessCheck` runs in `check`.
