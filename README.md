# RetroDFU

English | [简体中文](README.zh-CN.md)

Mojang's [DataFixerUpper](https://github.com/Mojang/DataFixerUpper) (DFU), repackaged to run on Java 8 with Minecraft
1.7.10 and 1.12.2.

## Features

- Java 8 bytecode (class version 52), downgraded with [JvmDowngrader](https://github.com/unimined/JvmDowngrader).
- Uses the Gson, Guava and log4j2 libraries provided by the game. Calls to newer Guava and fastutil APIs are redirected
  to compatibility classes.
- Replaces slf4j, which neither game provides, with a lightweight layer over log4j2.
- Tested with upstream's own `CodecTests` and `RoundtripTest` on Java 8, against the 1.7.10 and 1.12.2 dependency sets.

## Requirements

RetroDFU is tested only on Minecraft 1.7.10 and 1.12.2. Other versions are not verified.

Java 8 or later, and the following libraries on the classpath. The table lists the versions the game ships:

| Library  | 1.7.10           | 1.12.2 |
|----------|------------------|--------|
| Gson     | 2.2.4            | 2.8.0  |
| Guava    | 17.0             | 21.0   |
| log4j2   | 2.0-beta9        | 2.8.1  |
| fastutil | **Not provided** | 7.1.0  |

> [!WARNING]
> Minecraft 1.7.10 does not provide fastutil. Supply version 7.1.0 or later yourself.

## Usage

```kotlin
repositories { mavenLocal() }

dependencies { implementation("net.morphismmc:retrodfu:<version>") }
```

## Building

Clone the repository together with its submodules, since the DFU sources are in `upstream/`:

```sh
git clone --recurse-submodules https://github.com/morphismx/RetroDFU.git
```

For an existing clone, run `git submodule update --init`.

Run Gradle with JDK 25; the Java 8 toolchain the tests need is downloaded automatically.

```sh
./gradlew build
```

The jar is written to `build/libs/retrodfu-<version>.jar`. The build also runs all checks and tests.

## How it works

The build runs the following steps in order:

1. **`patchJar`**: applies [ClassTransform](https://github.com/Lenni0451/ClassTransform) transformers — renames slf4j
   to the replacement implementation and redirects newer Guava / fastutil calls to compatibility classes.
2. **`downgradeJar`**: converts the bytecode to Java 8 and replaces JDK 9+ API calls with stubs.
3. **`shadeDowngradedApi`**: relocates the stubs under `net.morphismmc.retrodfu.shadow.jvmdg`.
4. **`scanJar`**: verifies that every reference in the jar resolves against the dependencies of both games.
5. **`checkSignature`**: checks the jar against the Java 8 API using animal-sniffer.

## License

RetroDFU is released under the [MIT License](LICENSE). The jar also bundles:

- DataFixerUpper (MIT)
- JvmDowngrader `java-api` stubs (LGPL 2.1)

The corresponding license texts are in `META-INF/licenses/` inside the jar.

---

*RetroDFU is not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang and Microsoft.*
