# MTR 版本支持

问道的 MTR 集成是**反射只读**的：不引用任何 MTR 类，MTR 没装、换了版本、换成分支，都不会让问道起不来，
只会让这份读数不可用。MTR 自己的数据模型换过一次大版本，所以读取器有两套，装的是哪套由类名决定。

| MTR 数据模型 | 版本 | 读取器 | 能读到的 |
|---|---|---|---|
| 4.x | 4.0.4 及以上 | `MtrClientData` / `MtrWholeMap` / `MtrServerData` | 客户端窗口、单人/局域网的整张路网、服务端同步（1.20.6–1.21.1 节点） |
| 3.x | 3.x 全线，含 YMTR 分支 | `Mtr3Data` | 客户端窗口、单人/局域网的整张路网 |

## 两套模型差在哪

4.x 把数据搬到了与加载器无关的模拟核心里：客户端拿 `SimplifiedRoute` 与 `RailWrapper`，服务端每维度一个
`Simulator`，元素是 getter 访问的。3.x 的数据在客户端与世界的存档数据里：`ClientData` 的静态集合、
`RailwayData`，元素是 public 字段、没有 getter，轨道按两端方块存在 map 里、**没有 id**。

读取器只在这些地方不同，读出来的东西是同一种：车站、站台、线路、轨道，再交给本模组的转换，地图、目的地
列表与寻路不区分读数来自哪套模型。

## 3.x 上几个必须知道的事实

- **站台没有车站字段**。3.x 的 `SavedRailBase` 不像 4.x 那样带 `area`，唯一的精确联系是
  `DataCache.platformIdToStation`；这张表没有时按「站台落在哪个车站矩形里」兜底（重叠取最小矩形）。
- **车站区域没有高度**。`AreaBase.getCenter()` 的 y 恒为 0，所以车站高度取它所有站台的平均高度，
  目的地标记才不会画在地底。
- **轨道 id 是编的**。3.x 的轨道没有 id，而客户端的合并与服务端同步都按 id 对轨道；现在用
  「两端方块坐标（与方向无关）+ 交通类型」拼 `m3:` 开头的 id，跨读数、跨窗口/服务端副本都稳定。
- **3.x 没有「这条线走哪几条轨道」**。4.x 的服务端读数也没有；线路的轨道照旧由「按站序在轨道上规划」
  得到，见 `MtrLineTracks`。
- **3.x 服务端同步要补传输层**。`MtrServerSync` 的网络包只在 1.20.6–1.21.1 编译，而 MTR 3.x 只存在
  1.20.1，所以 1.20.1 上的 3.x 只有客户端窗口与单人/局域网整图两条读数；玩家在别人的服务器上时，
  读到的是 MTR 发给他的窗口。

## 换一个 MTR 版本后怎么证明还认

两条命令，一条核对名字、一条核对读取器本身，都不需要开游戏：

```powershell
.\tools\mtr3-probe\run.ps1          # 3.x：类/字段/方法逐条核对（真 1.20.1-Fabric classpath）
.\tools\mtr3-probe\bind.ps1         # 3.x：把真读取器编进去，用 MTR 自己的类跑一次完整转换
.\tools\routing-harness\run-fast.ps1 -MtrJar <jar>   # 4.x：反射握手检查
```

`run.ps1` 量的是**两套名字**：Fabric 节点自己的 classpath 用官方（Mojang）名字，运行中的 Fabric 游戏用
intermediary 名字（`net.minecraft.class_2338` 之类），所以它跑 dev 与 production 两遍，后者才是运行时真正
会命中的拼法。`bind.ps1` 用的是后者，并真的用 `Station`/`Platform`/`Route`/`Rail` 造一条铁路跑转换。

结果记在 `tools/mtr3-probe/build/*.log`；`-MtrJar <path>` 可以指向别的 3.x 构建。官方 3.x 的包名
（`org.mtr.mod.*` / `org.mtr.*`）由同一张候选表覆盖，但没有 jar 可核对时不会假装验证过。
