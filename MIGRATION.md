# Multi-version layout

One checkout builds every supported Minecraft version. There is no branch per version:
the shared source lives in `src/`, and the versions differ inside it, in comments the
compiler ignores and Stonecutter reads.

```
src/main/java, src/main/resources   the shared source -- the state of the ACTIVE node
src/common/java                     version- and loader-independent Java (roads, routing,
                                    transit model); identical bytes for every node
versions/<mc>-neoforge/             one Gradle subproject per version ("node")
    gradle.properties               that version's NeoForge build, Xaero coordinate, jars
versions/<mc>-neoforge/build/       each node's own build output, including its copy of the
                                    shared source under build/generated/stonecutter/
build.neoforge.gradle.kts           the per-node build script
stonecutter.gradle.kts              active node, constants, swaps, replacements
settings.gradle.kts                 the node tree
```

Adding a mod loader later (Fabric) means a loader entry in `settings.gradle.kts`, a
`build.fabric.gradle.kts`, and nodes named `<mc>-fabric`. The shared source does not move.

## Commands

```powershell
# The env vars are mandatory: JDK 21 runs Gradle, the 26.x nodes compile with the JDK 25
# toolchain, and the workspace mirror is where the blocked-here NeoForge artifacts live.
$env:JAVA_HOME = "D:\java\zulu21"
$env:GRADLE_USER_HOME = "E:\Projects\Minecraft\Mods\howtogo\.gradle-home"
$gradle = "E:\Projects\Minecraft\Mods\howtogo\.gradle-dist\gradle-9.7.1\bin\gradle.bat"
$lm = "-PhtgLocalMaven=E:/Projects/Minecraft/Mods/howtogo/.recon/maven-local"

& $gradle -p . $lm build                      # the ACTIVE node only -- what you work with
& $gradle -p . $lm :26.2-neoforge:build       # one named node
& $gradle -p . $lm chiseledBuild              # every node -- what CI runs
& $gradle -p . $lm "Set active project to 1.21.4-neoforge"   # switch the active node
```

Switching the active node **rewrites `src/`** to that version's state, by commenting and
uncommenting the `//?` markers. Commit before switching; the diff of the switch is the
inventory of that version's differences.

Never pass `--refresh-dependencies`: several dependencies are served only by the workspace
mirror, which stores module metadata without POMs, and revalidating them fails.

## Writing version conditions

The active version's branch is plain code; every other branch is commented out:

```java
//? if >=26.3 {
pose.rotate(Axis.ZP.rotation(angle));
//?} else {
/*pose.mulPose(Axis.ZP.rotation(angle));
*///?}
```

Available in conditions: `>=26.1`, `<26.1`, `1.21.4`, ranges, and the loader constants
declared in `stonecutter.gradle.kts` (`neoforge`, and `fabric` when it arrives).

Mechanical renames do not belong in conditions at all -- they go in `replacements` in
`stonecutter.gradle.kts`, which rewrites the text as each node's copy is generated:

```kotlin
replacements {
    string(current.parsed < "26.1") {
        replace("GuiGraphicsExtractor", "GuiGraphics")
    }
}
```

## The API waves this project spans

Each is a place the mod had to be written twice. They are listed because they are what
makes an older node more than a version-number change.

| Wave | Versions | What changed |
|---|---|---|
| GUI extraction | 26.1+ | `Screen.render` became `extractRenderState`, `GuiGraphics` became `GuiGraphicsExtractor`; drawing describes the frame instead of emitting into buffers |
| Screen ownership | 26.2+ | `Minecraft.screen`/`setScreen` moved onto `Minecraft.gui`; the overlay message onto `Gui.hud` |
| Renderer removal | 26.3 | `RenderPipeline` moved to the renderpearl API; GLFW constants became SDL (`InputConstants`); `PoseStack.mulPose` became `rotate` |
| Deferred GUI | 1.21.6+ | `GuiGraphics.bufferSource()`/`flush()` were removed; custom geometry submits as a `GuiElementRenderState` |
| Custom pipelines | 1.21.5+ | the hand-built `RenderType` no longer works; a `RenderPipeline` is registered instead |
| Item ids | 1.21.4+ | `Item.Properties` must carry its id: register through `DeferredRegister.Items.registerItem`, not `register` with a supplier |
| Immediate mode | 1.21.1-1.21.4 | `GuiGraphics.bufferSource()`, `RenderType.create`, `HudDraw.consumer(...)` -- the old model, still current here |

Where two versions differ only in a name, prefer a `replacement`. Where they differ in
which classes exist, use a condition. Where they differ in a whole subsystem (the HUD's
quad submission, above all), put the difference behind a small mod-owned façade --
`MinecraftCompat` exists for exactly this reason: it absorbed 31 call sites that 26.2 moved.

## Node status

| Node | State |
|---|---|
| 26.1, 26.2, 26.3 | ported, building |
| 1.21.11, 1.21.8, 1.21.6 | deferred-GUI era, in progress |
| 1.21.4, 1.21.1 | immediate-mode era, in progress |

`./gradlew build` targets the active node, so an unported node never blocks work on a
ported one. `chiseledBuild` needs them all.
