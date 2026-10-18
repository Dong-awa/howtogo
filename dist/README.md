# HowToGo 0.2.2 — 已交付的 21 个构建

每个 jar 只对应一个 (Minecraft 版本 × 加载器)。装上对应的那一个即可。

## 矩阵

| Minecraft | Fabric | NeoForge |
|---|---|---|
| 1.20.1 | ✅ `+mc1.20.1-fabric` | — 该版本没有这个加载器 |
| 1.20.4 | ✅ `+mc1.20.4-fabric` | ✅ `+mc1.20.4-neoforge` |
| 1.20.6 | ✅ `+mc1.20.6-fabric` | ✅ `+mc1.20.6-neoforge` |
| 1.21.1 | ✅ `+mc1.21.1-fabric` | ✅ `+mc1.21.1-neoforge` |
| 1.21.4 | ✅ `+mc1.21.4-fabric` | ✅ `+mc1.21.4-neoforge` |
| 1.21.6 | ✅ `+mc1.21.6-fabric` | ✅ `+mc1.21.6-neoforge` |
| 1.21.8 | ✅ `+mc1.21.8-fabric` | ✅ `+mc1.21.8-neoforge` |
| 1.21.11 | ✅ `+mc1.21.11-fabric` | ✅ `+mc1.21.11-neoforge` |
| 26.1 | ✅ `+mc26.1-fabric` | ✅ `+mc26.1-neoforge` |
| 26.2 | ✅ `+mc26.2-fabric` | ✅ `+mc26.2-neoforge` |
| 26.3 | ✅ `+mc26.3-fabric` | ✅ `+mc26.3-neoforge` |

✅ 21 个已交付　⛔ 0 个未交付

## 依赖

- **Fabric** 节点需要 Fabric Loader 与 Fabric API。API 版本按节点写在 `versions/<node>/gradle.properties` 的 `deps.fabric_api`。
- **NeoForge** 节点需要对应版本的 NeoForge。每个节点编译用的 NeoForge 版本写在同目录的 `deps.neo_loader`。
- 两个加载器都需要 **Xaero's World Map**（`libs/` 里的那个 jar 只是编译用的；运行时装玩家自己的版本）。从 26.x 起还需要随它分发的 **XaeroLib**。
- **Create**、**MTR** 与 **阡陌交通（RoadWeaver）** 是可选的，代码里对三者都做了存在性判断。

Forge 已从仓库移除：ForgeGradle 6 需要 Gradle 8.1.x，而 Stonecutter 0.7.11 在 8.1.1 上加载不了，两者互斥。详见 `MIGRATION.md`。

## 怎么重新构建

需要 **Java 25** 和 **Gradle 9.5.1**（Loom 要求 Gradle 的 JVM 本身就是 26.x 需要的 Java 25，而 Gradle 8 跑不了 Java 25）：

```
JAVA_HOME=D:\java\zulu25
gradle -p . :<version>-<loader>:build
```

一次只构建一个节点。全部 21 个节点一起构建时加 `--max-workers=3`：21 个 Loom `remapJar` 并行会打爆根 `gradle.properties` 里设的 2G 堆。Stonecutter 0.7.11 没有 `chiseledBuild` 任务，要把节点名显式列出来。

26.3-neoforge 例外地在自己节点的 `gradle.properties` 里把 `nfrt_version` 钉到 2.0.31——原因写在那个文件里，是 jst-cli-bundle 2.0.6 的一个访问转换器缺陷。

## 这个目录怎么用

`dist/` 只放**当前这一个版本**的 21 个 jar，文件名统一成 `howtogo-<版本>+mc<Minecraft>-<加载器>.jar`。上一版留在它自己的 GitHub release 里，jar 本身在 git 历史里；这个目录不累积。
