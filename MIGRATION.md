# Multi-version layout

One checkout builds every supported Minecraft version. There is no branch per version:
the shared source lives in `src/`, and the versions differ inside it, in comments the
compiler ignores and Stonecutter reads.

Supported: 1.21.1, 1.21.4, 1.21.6, 1.21.8, 1.21.11, 26.1, 26.2, 26.3 (NeoForge).

The active node is the **oldest** supported version. That is deliberate: this mod's own
development targets 1.21.1, and older versions (1.16, 1.18) may be wanted later, so anchoring
at the bottom means each new older port is one more node rather than another rewrite of
every condition in the tree.

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

### After every shared-source edit, build the nodes below the one you were fixing

A change that helps a newer node can silently corrupt an older one. This is not hypothetical:
a bidirectional `replace` rule written for the 26.x nodes turned `GuiGraphics` into
`GuiGraphicsExtractor` on the 1.21.x nodes as well, and three of them stopped compiling at
once. The fifteen-second check is worth more than the diagnosis.

## Writing version conditions

The active node is **1.21.1**, the oldest supported version, so `src/` on disk holds the
1.21.1 state: that version's branch is plain code and every other branch is commented out.

```java
//? if >=26.3 {
/*pose.rotate(Axis.ZP, angle);
*///?} else {
pose.mulPose(Axis.ZP.rotation(angle));
//?}
```

Available in conditions: `>=26.1`, `<26.1`, `1.21.4`, ranges, and the loader constants
declared in `stonecutter.gradle.kts` (`neoforge`, and `fabric` when it arrives).

### The three traps

Each of these cost a build cycle, and more than once. They are worth reading before writing
a condition.

**Conditions do not nest.** A `/* ... *///?}` block inside another closes the outer one, and
whatever followed it becomes live code on every node. In a whole-file condition this shows up
as the *rest of the class* coming alive, which reads as unrelated errors somewhere else. Two
ways out: write javadoc inside a fenced file as `//` lines so there is no inner `*/` to close
it, and use independent markers (`//? if A {` … `//?}` then `//? if B {` … `//?}`) rather than
`if`/`else if`/`else` when either arm declares the same local — an else pair was observed
emitting *both* arms on the newer node.

**A condition that is TRUE on the anchor cannot wrap a whole file.** The anchor's copy must be
plain text, and the fence form only expresses the commented direction. Conditions that hold at
1.21.1 belong inside a method, not around a file.

**Replacements are applied as one set, not in sequence.** No rule ever sees another rule's
output, so "rename it, then rename it back for one method" cannot work. Stonecutter also
applies *no* replacements at all to the active node, because `src/` on disk is already that
node's source — which is why a placeholder token written in the anchor's branch survives into
javac unresolved.

**`src/main/resources` is shared too, and a literal in it is right on one node.** The
`neoforge.mods.toml` declared `versionRange = "[1.21.1,1.21.2)"` as a literal for a while, so
every jar claimed to be 1.21.1 and the 26.2 one refused to load:

```
Mod howtogo requires minecraft 1.21.1 or above, and below 1.21.2
Currently, minecraft is 26.2
```

That reads like a version mix-up in the build and is really a stale resource. Anything in
`src/main/resources` that names a version must go through `expand(...)` in `processResources` —
`minecraft_version`, `neoforge_version` and `mod_version` are already set up there, and
`minecraft_range`/`neoforge_range` were added for this. When a node behaves as though it is the
wrong version, read the built jar's `META-INF/neoforge.mods.toml` before anything else.

Mechanical renames belong in `replacements` in `stonecutter.gradle.kts`. Two rules about them:

- The two-argument `replace(a, b)` is **bidirectional**: it applies the pair in whichever
  direction a node needs, so a rule meant for newer nodes also runs reversed on older ones and
  corrupts them. Use the four-argument form with both directions pinned: `replace(p, v,
  "NO_REVERSE", "NO_REVERSE")`, one `replace` per `regex(...)` block.
- A rule matches text, not types. It cannot tell one receiver's method from another's, and it
  cannot be told to skip a file. When one receiver disagrees — Xaero's `MapElementGraphics`
  kept `drawString` when vanilla renamed it to `text` — move the dissenting call out of the
  rule's reach: `MapLabels` exists for exactly that, and its two branches differ in more than
  a type name because of the anchor rule above.

### Check the version against the node's own jar

The 26.x line is not uniform across its own minor versions. Three boundaries that look like
they belong at 26.1 are at 26.2 or 26.3:

| Change | Actually landed at |
|---|---|
| `Minecraft.screen`/`setScreen` → `gui.screen()`/`gui.setScreen()` | 26.2 |
| Xaero's element renderer takes `XaeroBufferProvider` rather than `MultiBufferSource` | 26.2 |
| `RenderPipeline` → `com.mojang.renderpearl.api.pipeline` | 26.3 |
| `InputConstants.Type.KEYBOARD` (was `KEYSYM`), `KeyEvent.keycode()` (was `scancode()`) | 26.3 |
| `PoseStack.rotate(Axis, float)` | 26.3 |

Read the node's own `build/moddev/artifacts/*-sources.jar` before writing the bound rather
than assuming from the major version. `javap` against the jar works too and is faster for a
signature question:

```powershell
javap -cp path\to\xaero-26.3-1.47.0.jar xaero.map.element.MapElementGraphics
```

## The API waves this project spans

Each is a place the mod had to be written twice. They are listed because they are what
makes an older node more than a version-number change.

| Wave | Versions | What changed |
|---|---|---|
| GUI extraction | 26.1+ | `Screen.render` became `extractRenderState`, `GuiGraphics` became `GuiGraphicsExtractor`; drawing describes the frame instead of emitting into buffers |
| Screen ownership | 26.2+ | `Minecraft.screen`/`setScreen` moved onto `Minecraft.gui`; the overlay message onto `Gui.hud` |
| Renderer rewrite | 26.3 | `RenderPipeline` moved to the renderpearl API; `PoseStack.mulPose` became `rotate`; `InputConstants.Type.KEYSYM` became `KEYBOARD`; `KeyEvent.scancode()` became `keycode()`; NeoForge's own `getKeyCode()`/`getScanCode()` helpers are gone |
| Buffer providers | 26.2 | `MultiBufferSource` was removed; Xaero's renderer takes its own `XaeroBufferProvider` |
| Deferred GUI | 1.21.6+ | `GuiGraphics.bufferSource()`/`flush()` were removed; custom geometry submits as a `GuiElementRenderState` |
| GUI element hook | 1.21.9 | `buildVertices` lost its depth argument — the reason `HudBatch` is two files |
| Custom pipelines | 1.21.5+ | the hand-built `RenderType` no longer works; a `RenderPipeline` is registered instead |
| Identifier rename | 1.21.9+ | `ResourceLocation` → `Identifier`, `location()` → `identifier()`, `RenderType` → `RenderTypes` |
| Input records | 1.21.9 | mouse and key input became records; `Screen.hasControlDown()` moved to `Minecraft` |
| GUI matrix stack | 1.21.6+ | `GuiGraphics.pose()` returns JOML's `Matrix3x2fStack`, not `PoseStack`. Xaero's own `MapElementGraphics` did **not** follow and still returns `PoseStack` |
| Item ids | 1.21.4+ | `Item.Properties` must carry its id: register through `DeferredRegister.Items.registerItem`, not `register` with a supplier |
| Immediate mode | 1.21.1-1.21.4 | `GuiGraphics.bufferSource()`, `RenderType.create`, `HudDraw.consumer(...)` -- the old model, still current at the anchor |

Where two versions differ only in a name, prefer a `replacement`. Where they differ in
which classes exist, use a condition. Where they differ in a whole subsystem (the HUD's quad
submission, above all), put the difference behind a small mod-owned façade. Three exist and
each is worth reading before adding a fourth:

- `Screens` — one question ("what screen is up?") that 26.2 moved.
- `MapLabels` — one method whose receiver did not follow a rename.
- `HudDraw.VertexSink` with `HudBatchLegacy`/`HudBatchNewer` — the deferred-GUI seam, whose one
  differing hook is why it is two files.

## Node status

| Node | State | Jar |
|---|---|---|
| 1.21.1 (anchor), 1.21.4, 1.21.6, 1.21.8, 1.21.11 | building | 621-623 KB |
| 26.1, 26.2, 26.3 | building | 623 KB |

All eight are **compile-verified only**. Nothing in this tree has been run in game since the
multi-version layout was adopted, and the 26.x nodes changed enough of the rendering path —
a new extractor, a new pipeline API, a different buffer provider — that all three want a look
on screen before they are trusted.

`./gradlew build` targets the active node, so a broken node never blocks work on a working
one. `chiseledBuild` builds them all.

### Known gaps

- No Fabric structure yet. `settings.gradle.kts` and `build.neoforge.gradle.kts` are shaped
  for it: a loader entry plus a `build.fabric.gradle.kts`, with nodes named `<mc>-fabric`.
- The 1.21.6 node needed a dependency substitution (FML 9.0.2 has no POMs on either reachable
  host); see `versions/1.21.6-neoforge/gradle.properties`.
- `dist/` is refreshed by hand; there is no Gradle task for it in this layout.

### Fabric: the HUD batch submit, and why it is not an access widener

`HudBatchLegacy` (>=1.21.6) and `HudBatchNewer` (>=1.21.9) both end with

    graphics.submitGuiElementRenderState(snapshot());

`submitGuiElementRenderState` is **added by NeoForge**, not vanilla. Two independent checks:

- It appears **0 times** in the 1.21.6 layered mappings (`mappings.tiny`), where every vanilla
  member is listed by official, intermediary and named name.
- Vanilla `GuiGraphics` has a family of specific submitters -- `submitBlit`,
  `submitColoredRectangle`, `submitItem`, `submitEntityRenderState`, `submitMapRenderState` and
  several more -- and **no generic one** taking a `GuiElementRenderState`.

The vanilla route is one level down. `GuiRenderState` (intermediary `class_11246`) has

    submitGuiElement(Lnet/minecraft/client/gui/render/state/GuiElementRenderState;)V

and `GuiGraphics` holds one in a **private** field:

    guiRenderState    Lnet/minecraft/client/gui/render/state/GuiRenderState;    (field f)

So the Fabric side reaches through that field, and an access widener IS the right tool after all:
`accessible` widens a private field as readily as a package-private one, and the modern widener is
already scoped to `>=1.21.6`, which is exactly the range in which both members exist. No Mixin was
needed. The fix is `HudSubmit.submit(graphics, state)`, which calls `graphics.guiRenderState`
`.submitGuiElement(state)` on Fabric and `graphics.submitGuiElementRenderState(state)` on NeoForge.

The helper exists because the two call sites, `HudBatchLegacy` and `HudBatchNewer`, are each fenced
whole by a version condition, so the loader difference cannot go inline in either.

**Do not repeat the mistake made here**: an access widener entry was written for
`submitGuiElementRenderState` first, and its acceptance by Loom proved nothing, because Loom
validates an access widener when it *remaps the jar* -- after `compileJava` has already run.
The compile error was identical with and without the entry. Verify a member exists in the
mappings before writing anything that targets it.

One structural consequence, since it decides how the fix has to be written: `HudBatchLegacy` is
fenced whole by `//? if >=1.21.6 && <1.21.9`. A loader condition **inside** that fence would nest,
and on the nodes where the fence is false the inner `*/` would close the outer comment early and
leave the rest of the class live. So the differing line goes in a helper whose own conditions are
top level (`fabric && >=1.21.6 && <1.21.9` against `neoforge && ...`), not inline in the fenced
file.

### Forge on Gradle 9: the version check is optional

ForgeGradle 6.0.54 refuses Gradle 9 by design:

    Found Gradle version Gradle 9.5.1. Versions Gradle 9.0 and newer are not supported yet.

That check is skippable, and the switch is in the plugin's own constant pool rather than in its
documentation. `net.minecraftforge.gradle.common.util.EnvironmentChecks` declares

    ENABLE_GRADLE_CHECK_VARIABLE = "net.minecraftforge.gradle.check.gradle"

and the range it enforces is 8.1 to 9.0. Running Gradle with

    -Dnet.minecraftforge.gradle.check.gradle=false

turns it off, and the plugin then applies on Gradle 9.5.1 -- the scratch project gets past the
refusal and on to ForgeGradle's own "Missing 'minecraft' dependency" check, which is the next
thing it validates.

This means the two-build split the tree was going to need may not be necessary after all: the
refusal was the only thing keeping Forge on Gradle 8. It has NOT been proven past that point --
nothing has resolved a Forge userdev artifact yet -- so the split stays the fallback until a real
Forge node builds.

### Forge: where the mapped artifact stops, and what it is not

Both builds stop in the same place, which is what makes it a different problem than the Gradle
version it first looked like:

    Could not find net.minecraftforge:forge:1.20.1-47.4.28_mapped_official_1.20.1
    Searched in .../forge_gradle/bundeled_repo/...

On Gradle 8.14.6 ForgeGradle is plainly working -- `reobfJar`, `reobfJarJar`, `createMcpToSrg`,
`createSrgToMcp`, `downloadMcpConfig`, `extractSrg` and `extractNatives` are all registered, and
`downloadMcpConfig` and `extractSrg` run green. So the Gradle 9 finding from the round before was
incomplete: the plugin not registering tasks on Gradle 9 was real, but it was not the whole story.

The caches say where it actually stops:

    minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.28/
        forge-1.20.1-47.4.28-inject_src.jar      604 bytes      <- only this

    minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.28_mapped_official_1.20.1/
        ..._mapped_official_1.20.1.pom          9013 bytes
        ..._mapped_official_1.20.1.pom.input     184 bytes
        ..._mapped_official_1.20.1.pom.sha1       40 bytes      <- no jar

So the userdev artifact itself is incomplete: only `inject_src.jar` was fetched, with no userdev
jar and no pom beside it. The mapped POM was generated from it -- which is why the directory exists
at all -- but with no userdev jar to map there is nothing to put in the jar.

The next question is therefore why the userdev fetch is partial, not anything about Gradle versions.
The likely candidate is the same network problem that shaped the rest of this tree: the Forge
artifact resolution goes to maven.minecraftforge.net, which is reachable for metadata but may not
be serving every classifier this needs.
