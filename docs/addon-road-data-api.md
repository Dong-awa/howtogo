# 附属道路数据 API

给其他模组用的写入接口：向 HowToGo 提供**点**和**线**，由它画在你的地图上。

数据不存在玩家自己的路网里：HowToGo 为每个附属单独持有一份副本，**不落盘、不进编辑器、不参与寻路**。
玩家的 `config/howtogo/<world>/<dimension>.json` 不会被触碰。

API 版本：`HowToGoApi.API_VERSION`（当前 `1`）。

---

## 1. 声明依赖

`META-INF/neoforge.mods.toml`：

```toml
[[dependencies.你的modid]]
modId = "howtogo"
type = "required"          # 或 optional，按需
versionRange = "[0.2.0,)"
ordering = "AFTER"
side = "CLIENT"            # HowToGo 是客户端模组
```

代码只在客户端加载（`Dist.CLIENT`）。

---

## 2. 实现一个数据源

```java
import bili.dongsz.howtogo.api.*;
import bili.dongsz.howtogo.road.RoadClass;

public final class MyRoadSource implements RoadDataSource {

    /** 数据每变一次就 +1；HowToGo 靠它判断要不要重读。 */
    private volatile int version;

    @Override
    public int version() {
        return version;
    }

    @Override
    public void write(RoadDataWriter writer, String dimension) {
        if (!"minecraft:overworld".equals(dimension)) {
            return;                       // 别的维度没东西可写
        }
        writer.point("gate-n", 100, 64, 200, "北门", PointKind.STATION);
        writer.point("gate-s", 140, 64, 200, "南门", PointKind.ENDPOINT);
        writer.line("road-1", "gate-n", "gate-s", RoadClass.PATH, 0, false);
    }

    /** 你的数据变了之后调它。 */
    public void markChanged() {
        version++;
    }
}
```

## 3. 注册

```java
HowToGoApi.registerRoadDataSource("mymod", new MyRoadSource());
```

注册要一个 id（日志与冲突检查用，重复 id 会被拒绝并写日志）。可以在自己的构造器里注册，也可以等
`HowToGoRegistrationEvent`——后者是 HowToGo 表示"全都加载好了"的信号。注册线程无限制。

---

## 4. 点与线的语义

### `writer.point(id, x, y, z, name, kind)`

| 参数 | 说明 |
| --- | --- |
| `id` | 你自己的字符串 id，**一次 write 内唯一**；`line` 用它指定两端 |
| `x y z` | 方块坐标 |
| `name` | 显示名，可为 `null` |
| `kind` | 见下表 |

`PointKind` 与内部的区别：

| `PointKind` | 含义 | 画出来 |
| --- | --- | --- |
| `JUNCTION` | 路口 | 路网顶点 |
| `ENDPOINT` | 尽头 | 路网顶点 |
| `PLACE` | 地标 | 标记 + 名字 |
| `RESOURCE` | 资源点 | 标记 + 名字，远缩放时最后被筛掉 |
| `SHOP` | 商店 | 标记 + 名字 |
| `STATION` | 交通站点 | 标记 + 名字 |

点**不挂在线上也会画**：标记和名字都按 `PlaceKind` 走地图右侧筛选器的规则。

### `writer.line(id, fromPoint, toPoint, roadClass, layer, oneWay)`

| 参数 | 说明 |
| --- | --- |
| `id` | 一次 write 内唯一的线 id |
| `fromPoint` / `toPoint` | 本仓库中 `point()` 写过的 id；写没写过的会抛错 |
| `roadClass` | `HIGHWAY` / `ROAD` / `PATH` / `RAIL` / `WATER` / `ICE`，决定颜色与速度 |
| `layer` | 层数：`0` 地表，正数在上、负数在下（`-32..32`）。桥/隧道靠它区分，不靠 `y` |
| `oneWay` | `true` = 只允许 `fromPoint → toPoint` 单向 |

线是两点间的**直线**；要弯就拆成多条线——玩家编辑器也是这么存的。线的 `y` 取起始点的 `y`。

---

## 5. 版本号 = 重读协议

HowToGo 每个客户端 tick 读一次 `version()`，与它"画着的那份数据读到时记下的数字"比较：

- 相等 → 什么都不做（无分配、无拷贝，正常会话里每 tick 都是这种情况）；
- 不相等 → 针对当前维度**整体重读**，你写什么就是什么（不是增量）。

所以：

- 数据变了必须 **同时** 改这个数字；
- 数字一直不变 = 这份图层只读一次、永不刷新；
- 用计数器、结构体的 identity hash、存档序号都行，比较是 `!=` 而不是 `>`，回绕也无妨。

`write()` 里抛异常会被捕获并按 source 记日志，**上一份图层保留**——一个附属出问题不会把地图清空。
点 id 重复、线 id 重复、线引用了没写过的点、`roadClass` 为 `null`，都会抛 `IllegalArgumentException`。

---

## 6. 读回与缓存

```java
WorldDataApi.addonLayerIds();   // 已注册的图层 id，按注册顺序
WorldDataApi.addonRevision();   // 附属图层被重读过就变；世界/维度切换也会变
```

`addonRevision()` 与 `WorldDataApi.roadRevision()` 是同一套契约：把你派生出来的东西（缓存、截图、叠加层）
连同这个数字一起记下，下次比对即可。

---

## 7. 能力边界（有意为之）

| 不做 | 原因 |
| --- | --- |
| 写进玩家路网文件 | 能写就能改；附属不该能悄悄移动玩家的路 |
| 进编辑器 | 附属线是"对世界的陈述"，不是玩家要修的路 |
| 参与寻路 | 规划只走玩家自己的路网；要能导航请改用 `registerDestinationSource` 提供目的地 |
| 增量更新 | 每次重读都是整体重述，避免"删掉的线还在"这类状态不一致 |

---

## 8. 完整最小示例

```java
// 客户端初始化里
HowToGoApi.registerRoadDataSource("mymod", new RoadDataSource() {
    private int version;

    @Override public int version() { return version; }

    @Override public void write(RoadDataWriter w, String dim) {
        w.point("a", 0, 64, 0, "起点", PointKind.PLACE);
        w.point("b", 50, 64, 0, "终点", PointKind.PLACE);
        w.line("ab", "a", "b", RoadClass.ROAD, 0, false);
    }
});

// 之后任意时刻，数据变了：
// version++;
```
