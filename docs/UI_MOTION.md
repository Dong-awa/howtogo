# HowToGo 界面动效规范（UI Motion Spec）

> 适用范围：本模组在游戏内绘制的**全部**界面 —— 9 个 `Screen`、地图筛选浮层、导航 HUD、MCphone 目的地页。
> 唯一实现入口：`src/main/java/bili/dongsz/howtogo/client/UiMotion.java`
> 配置项：`ui_animations`（客户端配置，默认 **开**，下一帧生效）

---

## 1. 为什么要一份规范

在加动效之前，界面里每个"变化"都是瞬间发生的：按钮在光标进入的那一帧换底色，面板在打开的那一帧出现，列表滚动是干脆的跳格。这类瞬间切换单独看都没问题，摆在一起就读作"没做过"。

动效的第一条原则因此不是"加动画"，而是**统一**：所有该动的东西共用同一套时长与曲线。任一界面自己写一个 180ms，另一个写 90ms，第三个用线性 —— 那不如全都不动。

本文件就是那套时长的唯一定义，`UiMotion` 是它的唯一实现。**新界面照表取值，不要写字面量。**

---

## 2. 时长令牌（`UiMotion` 中的常量）

| 令牌 | 值 | 用在哪 | 为什么是这个值 |
|---|---|---|---|
| `ENTER` | **200 ms** | 界面出现：遮罩、面板、卡片淡入 | 全表最长的"被动"动画。玩家没要求它发生，所以要看得见；但一次点击之后绝不能等他。200ms 是"能察觉是动效"与"绝不会觉得慢"的交界。 |
| `EXIT` | **140 ms** | 界面退出（`Screens.set` 延迟换屏期间） | 比进入短：离场还恋恋不舍的界面读作卡顿。 |
| `HOVER` | **130 ms** | 光标进出：按钮、行、勾选框、chip | 再短就读作开关而不是渐变；再长则扫过一排按钮会留拖影。 |
| `PRESS` | **90 ms** | 按下：面缩进 1px | 必须在松手前结束。 |
| `EXPAND` | **240 ms** | 展开：筛选面板、可折叠区块 | 展开=布局变化，太快读作闪屏；全表最长，因为它是玩家主动要求的。 |
| `COLLAPSE` | **190 ms** | 收起 | 收起不是表演，比展开快。 |
| `SCROLL` | **200 ms** | 列表滚到新位置 | 一次滚轮的位移，用跟随曲线而非线性。 |
| `HUD` | **220 ms** | 导航面板出现/消失、行程条 | HUD 是常驻物，进出要软一点。 |
| `STAGGER` | **16 ms / 行**（上限） | 列表逐行入场延迟 | 每行晚一点开始、每行固定用 120ms 到位；延迟被压缩到"入场剩余的时间"里（当前令牌下约 10ms/行 × 8 行），所以最后一行也在 `ENTER` 内到齐、不会永远偏暗。超过 `STAGGER_LIMIT`(8) 行共用最大延迟。 |

## 3. 曲线（`UiMotion.Easing`）

**不允许线性。** 现实中没有任何东西以恒定速度启停，线性曲线是一张界面"像机械"的首要原因。

| 曲线 | 形状 | 用在哪 |
|---|---|---|
| `OUT_QUINT` | 前 1/3 走完大部分距离，长尾收束 | **界面入场**。80ms 内基本就位，剩下 120ms 是几乎看不见的漂移 —— "贵"的感觉来自这个尾。 |
| `OUT_CUBIC` | 减速，工作马 | 光标、显隐、逐行入场等一切"出现" |
| `OUT_QUAD` | 同上更缓 | 长距离位移 |
| `IN_CUBIC` | 加速到最后一帧 | 一切"离开"，不让它拖尾 |
| `IN_OUT_CUBIC` | 两端整形 | 展开/收起（起点终点都在画面里） |
| `OUT_BACK` | 过冲约 10% 再回位 | **仅**按钮松手的一下"回弹"，绝不用在面板/界面上 |

## 4. 两个原语

```java
// 一个朝目标移动的值，可中途反向（这是"顺滑"的来源）
UiMotion.Slide s = new UiMotion.Slide(UiMotion.Easing.OUT_CUBIC, 0.0F, UiMotion.HOVER);
s.to(hovered ? 1.0F : 0.0F);
float t = s.eased();      // 0..1，读一次推进一帧；同帧内重复读不会加速
s.settled();              // 是否已到目标

// 一池按 index 取的值，给"循环里画的行"用（首帧后零分配）
UiMotion.Slides pool = new UiMotion.Slides();
float h = pool.get(rowIndex).to(hovered ? 1.0F : 0.0F).eased();
```

颜色与透明度：

```java
UiMotion.fade(argb, factor)       // 把颜色自身 alpha 乘 factor —— 淡入淡出的唯一写法
UiMotion.mix(from, to, t)         // 两个颜色（含 alpha）按 t 混合
UiMotion.withAlpha(argb, a)       // 直接设置 alpha
UiMotion.stagger(index, open)     // 该行的入场进度（0..1），喂给 fade()
UiMotion.follow(cur, target, s)   // 平滑跟随（滚动位置用）
UiMotion.activeOpacity()          // 当前正在绘制的界面的不透明度（PanelButton 自己读它）
UiMotion.frameDelta()             // 本帧秒数，clamp 到 0.05
```

> `activeOpacity()` 是给"不知道自己画在哪个界面上"的控件用的：`PanelButton` 不接收 open 参数，
> 而是读这个环境值，于是按钮跟着它的面板一起淡入淡出，而九个界面的调用点一行都不用改。
> 只有"游戏正在显示的那个界面"能写它 —— 有三个界面会在自己下面重绘父界面，父界面若把自己
> 的不透明度盖上去，子界面的按钮就会在其余部分淡出时保持全亮。

## 5. 界面进出（跨全部 9 个 Screen）

```java
@Override
public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    UiMotion.ScreenState motion = UiMotion.of(this);   // 每帧一次：推进入场/退场，并驱动延迟换屏
    open = motion.opacity();                           // 0..1，入场与退场相乘
    UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));  // 整帧位移
    //? if <1.20.2 { renderBackground(graphics); //?}  （若有）
    super.render(graphics, mouseX, mouseY, partialTick);
    ...原有绘制...
    UiMotion.popFrame(graphics);                       // 必须是最后一句
}
```

- **入场**：从 +10px（`RISE`）升到位，200ms `OUT_QUINT`，同时整屏淡入。
- **退场**：向上升 6px（`FALL`）并淡出，140ms `IN_CUBIC`。
- **位移是整帧的**：面板、文字、地图、按钮一起移动。这是唯一**不需要裁切**的位移形式 ——
  内容始终在面板矩形内，所以不引入 scissor（原版 scissor 不可嵌套，而多个界面自己就在用）。
- **遮罩要外扩** `UiMotion.SCRIM_MARGIN`(32px)：遮罩自己也被位移了，不外扩会在一条边露出没压暗的世界。
- **命中测试不动**：位移期间最坏 10px 偏差、最长 200ms，且位移只影响绘制坐标；可点区域始终按逻辑坐标。
- **版本围栏只在一处**：1.21.6 起 `PoseStack` 换成 `Matrix3x2fStack`（`pushMatrix/popMatrix`），
  `UiMotion.pushFrame/popFrame` 是唯一处理这件事的地方。**界面文件里不许自己写 pose 围栏。**

### 退场为什么需要延迟换屏

`Screens.set` 不在当帧换屏，而是让旧界面先播 140ms 退场，由仍在绘制的界面每帧推动完成换屏：
只有本模组自己的界面会被延迟（原版界面没有代码画它的退场），超过 400ms 或中途被游戏/别的模组
换屏就立刻放弃延迟。**"是否有待换屏"是一个独立的布尔量**——它曾经写作 `pendingTarget != null`，
而"关闭界面"记录的正是 null，于是关闭主界面时只播了退场、永远不换屏：界面停在 0 透明度、仍然
吃键盘，表现就是"退出卡死、游戏动不了"。这类哨兵值撞车是把状态和值混用同一变量导致的。

**只有本模组自己的界面会被"收养"进动画状态。** 延迟换屏的前提是"当前界面会画自己的退场"，
而会画退场的唯一证据就是它每帧调用 `UiMotion.of`——那也正是推动换屏的泵。所以 `Screens.install`
只在目标界面**已经**有动画状态时才重置它，绝不因为换屏而给任何界面新建状态：否则原版/其他模组的
界面（典型是 Xaero 的世界地图）在第一次从本模组面板返回时被登记成"可等退场"，下一次要离开它时
换屏就被记成待换、等一个永远不会到来的泵——表现是**世界地图里所有界面打开过一次之后，第二次就
再也打不开**，必须退出全屏地图再进来（新实例没被登记）才恢复。界面的第一次入场丢失不了：没有状态
的界面在第一帧 `UiMotion.of` 里从 0 开始，和 `install` 里重置是一样的动画。

## 6. 各类控件的规定动作

| 对象 | 动作 | 做法 |
|---|---|---|
| 界面（Screen） | 整帧位移 + 淡入淡出 | `pushFrame` + `offsetY(RISE, FALL)` + `popFrame`，遮罩外扩 `SCRIM_MARGIN` |
| `PanelButton` | 光标进出缓动 + 按下缩进 | `hover`/`press` 两个 Slide；面色 `UiMotion.mix(base, hover, h)`；按下时 face 缩 1px |
| 列表行 | 光标底色缓动 + 逐行入场 | `Row` 自带 `hover` Slide；底色 `fade(rowHoverTint, h * stagger)` |
| 勾选框 / chip | 光标：外描边 alpha 缓动 | 描边 `fade(overlayHoverEdge, h)` |
| 折叠面板（下拉） | **位移推开 + 高度插值 + alpha** | 行从标题下方"展开"到各自位置（`drawnY = from + (y-from)*t`），面板底边同步插值；命中仍按逻辑状态 |
| 目的地选择器（四行选项 ↔ 方案行） | **共享块高度逐帧插值 + 行块上移 + 裁切** | 底部锚定页脚、顶部锚定面板顶；`bottomGroupHeight(fold)` 每帧插值，地图与地点列表拿到的是剩下的高度，所以它们和选项块一起光滑变高变低；四行按 `(1-fold)×块高` 上移并被 `UiMotion.clip` 裁在块内，方案行从块底淡入并下沉 8px |
| 列表滚动 | 平滑跟随 + 半像素吸附 | `scrollShown = follow(scrollShown, scrollTarget, FOLLOW_SECONDS)`，绘制与命中用同一取整值 |
| HUD 卡片 | 竖向滑入 + 淡入 | 局部 `pushFrame(0, (1-open) * HUD_RISE)`；`open < 0.01` 仍整帧早退 |
| 地图内容 | 入场时 alpha 缩放 | `HudDraw.emit*(..., (int)(0xD0 * open), ...)` |

## 7. 铁律

1. **动画只改"看起来"，不改"在哪里"。** 所有命中测试（`hit`/`holds`/`insideList`/`controlX`）
   保持原样、按逻辑状态计算，绝不按插值后的*位移*计算。动效不得改变任何控件可点区域。
   唯一例外是"布局高度本身就是动画"的地方（目的地选择器的折叠）：那里每帧重新量一次布局，
   绘制与命中读的是**同一份当帧几何**，所以点到的仍然是看到的——只是这份几何在 200ms 里连续变化。
2. **不新增任何 Minecraft API 调用**。只允许 `graphics.fill`/`drawString`/`enableScissor` 等
   已经在该文件里用过的调用；跨版本差异一律沿用文件里已有的 `//? if` 围栏写法。
3. **每帧零分配**。Slide 作为字段/Slide 池存在，颜色插值返回 `int`，不得在 `render` 里 new 对象、
   不得用 `String.format`、不得做集合拷贝。
4. **开关关闭时全部退化为瞬时**。`UiMotion.on()` 为假时 Slide 直接落到目标、`stagger` 返回 1 —— 
   任何地方都不需要自己判断开关。
5. **超时上限**：单次动效不超过 `EXPAND`(240ms)；界面进场不超过 `ENTER`(200ms)。
6. **不碰非本任务的代码**：不动版本围栏语义、不动路由/数据层、不动 `RoadConfig`（`ui_animations` 已就位）。

## 8. 配置与自检

- 键：`ui_animations`（NeoForge `howtogo-client.toml` / Fabric `howtogo-client.json`）
- 默认：`true`
- 设置界面：`设置 → 外观 → 界面动效`（`screen.howtogo.settings.group.appearance`）
- 关闭后：本文件第 6 节全部动作变为瞬时，界面功能不变。
