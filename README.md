# HowToGo | 问道

在 [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 上画路，然后像地图 App 一样导航：选目的地、选交通方式、看路线再出发。

Draw roads on Xaero's World Map, then navigate the way a maps app works: pick a destination, pick a travel mode, look at the route before you set off.

[下载](../../releases/latest) · [问题反馈](../../issues) · [使用手册](docs/GUIDE.md) · [爱发电](https://ifdian.net/a/howtomodteam)

## 支持版本

| Minecraft | Fabric | NeoForge |
|---|---|---|
| 1.20.1 | ✅ | — |
| 1.20.4 | ✅ | ⛔ |
| 1.20.6 | ✅ | ⛔ |
| 1.21.1 | ✅ | ✅ |
| 1.21.4 | ✅ | ✅ |
| 1.21.6 | ✅ | ✅ |
| 1.21.8 | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ |
| 26.1 | ⛔ | ✅ |
| 26.2 | ⛔ | ✅ |
| 26.3 | ⛔ | ✅ |

每个 jar 只对应一个版本 × 一个加载器，文件名里带着版本号。装错版本会在启动时报明是哪两个版本对不上，而不是进去以后才出问题。

## 前置

| 模组 | 必要性 | 说明 |
|---|---|---|
| Xaero's World Map | **必需** | 所有道路与路线都画在它上面 |
| Xaero's Minimap | 可选 | 读取小地图路标点作为目的地 |
| Create（机械动力） | 可选 | 读取轨道，把铁路映射进路网 |
| MTR（我的世界铁路） | 可选 | 读取车站与线路 |
| MCPhone | 可选 | 手机里多一个「问道导航」应用 |

纯客户端。服务端不需要装，装了也无害。

## 构建

需要 Gradle 9.5.1 与 Java 25。每个节点单独构建：

```
JAVA_HOME=<jdk-25> gradle :1.21.8-neoforge:build
```

支持的节点名是 `<版本>-fabric` 与 `<版本>-neoforge`。细节见 [使用手册](docs/GUIDE.md)。

## 许可

MIT（见 [LICENSE](LICENSE)）。

Minecraft 与 Xaero's World Map 都是闭源的商业软件，本许可只覆盖本模组自己的代码。Xaero 的地图 jar 只以 `compileOnly` 参与编译、运行期通过其公开扩展点调用，**不随本模组分发**。
