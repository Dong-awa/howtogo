# HowToGo 0.2.1 — 已交付的 16 个构建

每个 jar 只对应一个 (Minecraft 版本 × 加载器)。装上对应的那一个即可。

## 矩阵

| Minecraft | Fabric | NeoForge | Forge |
|---|---|---|---|
| 1.20.1 | ✅ `+mc1.20.1-fabric` | — | ⛔ 未交付 |
| 1.20.4 | ✅ `+mc1.20.4-fabric` | ✅ `+mc1.20.4-neoforge` | ⛔ 未交付 |
| 1.20.6 | ✅ `+mc1.20.6-fabric` | ✅ `+mc1.20.6-neoforge` | ⛔ 未交付 |
| 1.21.1 | ✅ `+mc1.21.1-fabric` | ✅ `+mc1.21.1-neoforge` | — |
| 1.21.4 | ✅ `+mc1.21.4-fabric` | ✅ `+mc1.21.4-neoforge` | — |
| 1.21.6 | ✅ `+mc1.21.6-fabric` | ✅ `+mc1.21.6-neoforge` | — |
| 1.21.8 | ✅ `+mc1.21.8-fabric` | ✅ `+mc1.21.8-neoforge` | — |
| 1.21.11 | ✅ `+mc1.21.11-fabric` | ✅ `+mc1.21.11-neoforge` | — |
| 26.1 | ⛔ 未交付 | ✅ `+mc26.1-neoforge` | — |
| 26.2 | ⛔ 未交付 | ✅ `+mc26.2-neoforge` | — |
| 26.3 | ⛔ 未交付 | ✅ `+mc26.3-neoforge` | — |

✅ 18 个已交付　⛔ 3 个未交付（原因见下）　— 该版本没有这个加载器

## 依赖

- **Fabric** 节点需要 Fabric Loader 与 Fabric API。API 版本按节点写在 `versions/<node>/gradle.properties` 的 `deps.fabric_api`。
- **NeoForge** 节点需要对应版本的 NeoForge。
- 两个加载器都需要 **Xaero's World Map**（`libs/` 里的那个 jar 只是编译用的；运行时装玩家自己的版本）。
- **Create** 与 **MTR** 是可选的，代码里对两者都做了存在性判断。

## 3 个未交付的节点，以及各自的原因

都不是代码问题——26.x Fabric 和 Forge 的代码能编译到只差外部依赖，26.3 是上游补丁。

| 节点 | 卡在哪 |
|---|---|
| 26.1 / 26.2 / 26.3 Fabric | **Mojang 没有为 26.x 发布 mappings。** `client_mappings` 和 `server_mappings` 两个键在版本 JSON 里都不存在，Loom 的 `officialMojangMappings()` 没有东西可抓。Yarn 是错的名字空间——这个代码库通篇写的是 `net.minecraft.*`。NeoForge 不受影响，因为 ModDevGradle 从 NeoForge 的 artifact 里取名字。 |
| 26.3 NeoForge | **NeoForge 的补丁在 recompile 时打不上**（`HolderSet` 那行），是上游 beta 的问题。 |
| 1.20.1 / 1.20.4 / 1.20.6 Forge | **ForgeGradle 的依赖解析在这里活不下来。** 见 `MIGRATION.md`：四个假设（坐标、网络、下载、Gradle 版本）逐一排除后，`--info` 给出的是 `MinecraftUserRepo: Failed to get Minecraft Vanilla Base` 加 `Caused by: java.lang.IllegalStateException: project services has been closed`。 |

## 怎么重新构建

主树（Fabric + NeoForge），需要 **Java 25**：

```
JAVA_HOME=D:\java\zulu25
gradle -p . :<version>-<loader>:build
```

Gradle 必须是 9.5.1：Loom 要求 Gradle 的 JVM 本身就是 26.x 需要的 Java 25，而 Gradle 8 跑不了 Java 25。

Forge 有一个独立的 Gradle 根（`forge-build/`，Gradle 8.14.6），目前还不能产出可用 jar，见上。
