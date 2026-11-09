# RetroDFU

[English](README.md) | 简体中文

将 Mojang 的 [DataFixerUpper](https://github.com/Mojang/DataFixerUpper)（DFU）重新打包，使其可在 Java 8 环境下配合
Minecraft 1.7.10 与 1.12.2 使用。

## 特性

- Java 8 字节码（class 版本 52），由 [JvmDowngrader](https://github.com/unimined/JvmDowngrader) 降级生成。
- 使用游戏自带的 Gson、Guava 和 log4j2；对较新 Guava / fastutil API 的调用会被重定向到兼容类。
- 两个游戏均不提供 slf4j，因此以转发到 log4j2 的轻量实现替代。
- 通过上游自带的 `CodecTests` 和 `RoundtripTest` 测试，在 Java 8 上分别使用 1.7.10 与 1.12.2 的依赖运行。

## 运行要求

RetroDFU 仅在 Minecraft 1.7.10 与 1.12.2 上测试，其他版本未经验证。

需要 Java 8 及以上版本，并在 classpath 中提供下列库。表中为游戏自带的版本：

| 库       | 1.7.10     | 1.12.2 |
|----------|------------|--------|
| Gson     | 2.2.4      | 2.8.0  |
| Guava    | 17.0       | 21.0   |
| log4j2   | 2.0-beta9  | 2.8.1  |
| fastutil | **不提供** | 7.1.0  |

> [!WARNING]
> Minecraft 1.7.10 不提供 fastutil，需自行提供 7.1.0 及以上版本。

## 使用

```kotlin
repositories { mavenLocal() }

dependencies { implementation("dev.morphismx:retrodfu:<version>") }
```

## 构建

DFU 源码位于 `upstream/` 子模块，克隆时需一并获取子模块：

```sh
git clone --recurse-submodules https://github.com/morphismx/RetroDFU.git
```

对于已克隆的仓库，运行 `git submodule update --init`。

构建需要 JDK 8 和 JDK 25，并通过环境变量 `JAVA8_HOME` 与 `JAVA25_HOME` 指定路径。Gradle 不会自动下载 JDK。

```sh
./gradlew build
```

产物位于 `build/libs/retrodfu-<version>.jar`。构建过程同时会运行全部检查和测试。

## 工作原理

构建依次执行以下步骤：

1. **`patchJar`**：将 slf4j 重命名为替代实现，并将较新的 Guava / fastutil 调用重定向到兼容类。
2. **`downgradeJar`**：将字节码转换为 Java 8，并将 JDK 9+ 的 API 调用替换为桩代码。
3. **`shadeDowngradedApi`**：将桩代码重定位到 `dev.morphismx.retrodfu.shadow.jvmdg`。
4. **`compatScan`**：验证 jar 中的所有引用在两个游戏版本的依赖上均可解析。
5. **`verifyJar`**：使用 animal-sniffer 对照 Java 8 API 检查 jar。

## 许可证

RetroDFU 以 [MIT 许可证](LICENSE)发布。jar 中还包含：

- DataFixerUpper（MIT）
- JvmDowngrader 的 `java-api` 桩代码（LGPL 2.1）

相应的许可证文本位于 jar 的 `META-INF/licenses/` 目录。

---

*RetroDFU 与 Mojang 及 Microsoft 无关联。Minecraft 是 Mojang 和 Microsoft 的商标。*
