# HowToGo | 问道


```
始于问道，继以寻道，终以行道。问而不迷，寻而不辍，行而不止。道在问中，道在寻中，道在行中。
```
问道模组为 Minecraft 提供了一个完整的路网系统，玩家可以在地图上标记路标点，绘制道路，并通过导航功能找到目的地。

The mod provides Minecraft with a complete road network system, allowing players to mark waypoints on the map, draw roads, and find destinations through navigation.

[下载](../../releases/latest) · [问题反馈](../../issues) · [使用文档](https://howtogo.dongsz.space/wiki/) · [爱发电](https://ifdian.net/a/howtomodteam)

## 支持版本

|         | Fabric | NeoForge | Forge |
|---------|--------|----------|-------|
| 1.20.1  | ✔      | -        | ✖     |
| 1.20.4  | ✔      | ✔        | ✖     |
| 1.20.6  | ✔      | ✔        | -     |
| 1.21.1  | ✔      | ✔        | -     |
| 1.21.4  | ✔      | ✔        | -     |
| 1.21.6  | ✔      | ✔        | -     |
| 1.21.8  | ✔      | ✔        | -     |
| 1.21.11 | ✔      | ✔        | -     |
| 26.1    | ✔      | ✔        | -     |
| 26.2    | ✔      | ✔        | -     |
| 26.3    | ✔      | ✔        | -     |

`✔` = 已支持，`✖` = 计划支持，`-` = 不计划支持。

## 前置

| 模组                | 必要性    | 说明             |
|-------------------|--------|----------------|
| Xaero's World Map | **必需** | 所有道路与路线都画在它上面  |
| Xaero's Minimap   | 可选     | 读取小地图路标点作为目的地  |
| Create（机械动力）      | 可选     | 读取轨道，把铁路映射进路网  |
| MTR（我的世界铁路）       | 可选     | 读取车站与线路        |
| MCPhone           | 可选     | 手机里多一个「问道导航」应用 |
| RoadWeaver        | 可选     | 读取它的路网数据       |

## 构建

需要 Gradle 9.5.1 与 Java 25。每个节点单独构建：

```
JAVA_HOME=<jdk-25> gradle :1.21.8-neoforge:build
```

## 许可

MIT（见 [LICENSE](LICENSE)）。

Minecraft 与 Xaero's World Map 都是闭源的商业软件，本许可只覆盖本模组自己的代码。Xaero 的地图 jar 只以 `compileOnly` 参与编译、运行期通过其公开扩展点调用，**不随本模组分发**。
