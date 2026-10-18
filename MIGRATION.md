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

Ruled out since: the userdev artifact is published and reachable. A HEAD against
maven.minecraftforge.net for 1.20.1-47.4.28 finds forge-1.20.1-47.4.28-userdev.jar (3.0 MB),
forge-1.20.1-47.4.28-universal.jar (2.5 MB) and the pom; only inject_src.jar is a 404, and that
one is generated rather than published. Naming the :userdev classifier explicitly in the
`minecraft(...)` dependency makes no difference -- the cache still ends up with the same single
generated inject_src.jar. So the artifact is there and ForgeGradle is not fetching it, which
points at the download rather than at the coordinate.

Also ruled out: the download. The userdev jar IS in the cache, fully fetched:

    maven_downloader/net/minecraftforge/forge/1.20.1-47.4.28/
        forge-1.20.1-47.4.28-userdev.jar        2970 KB
        forge-1.20.1-47.4.28-universal.jar      2458 KB
        forge-1.20.1-47.4.28-sources.jar        1585 KB

So neither the coordinate nor the network is the problem. The chain stops between the userdev
jar and the mapped one: minecraft_user_repo has the mapped POM (9 KB, generated) and no mapped
jar beside it, which means the step that turns userdev into a mapped Minecraft artifact runs far
enough to write the POM and not far enough to produce the jar.

That is ForgeGradle's MCP/merge chain -- createMcpToSrg, createSrgToMcp and the mergetool -- and
it is where to look next, with --info or --stacktrace on the mapping task rather than on
compileJava.

### The actual cause, from --info on compileJava

The MCP tasks are all green -- downloadMcpConfig, extractSrg, createMcpToSrg, createSrgToMcp all
run -- so the failure is in MinecraftUserRepo during dependency resolution, and --info names it:

    MinecraftUserRepo: Failed to get Minecraft Vanilla Base. Should not be possible.
        net.minecraft:joined:1.20.1-20230612.114412:srg
    at net.minecraftforge.gradle.userdev.MinecraftUserRepo.findBinpatched(MinecraftUserRepo.java:767)
    at net.minecraftforge.gradle.userdev.MinecraftUserRepo.findRaw(MinecraftUserRepo.java:628)
    at net.minecraftforge.gradle.userdev.MinecraftUserRepo.findFile(MinecraftUserRepo.java:462)
    Caused by: java.lang.IllegalStateException: project services has been closed.

So the mapped artifact cannot be produced because the VANILLA BASE it would be mapped onto is not
resolvable -- net.minecraft:joined at the srg namespace -- and the resolution fails with project
services already closed, which is a lifecycle problem rather than a missing file.

"project services has been closed" is the actionable line. ForgeGradle resolves this during
dependency resolution, and a resolution that reaches back into project services after the project
has been closed fails exactly this way. That is a Gradle-lifecycle incompatibility, and it explains
why the Gradle 9 story was half right: the plugin applies and its own tasks run, but the part that
resolves during a dependency resolution does not survive on a newer Gradle than it was written for.

Next: whether net.minecraft:joined:<v>:srg can be produced ahead of time (so the resolution never
reaches back into project services), or whether Gradle 8.14.6 is still too new and the Forge build
needs the Gradle 8.1 the plugin declares as its floor.

### 1.20.x NeoForge: what is actually left

Both nodes are past their toolchain and onto their API, which is the first time that has been
true. 1.20.4 has 27 errors and 1.20.6 has 9.

1.20.6 has two, and they are unrelated:

  HudDraw.java (5)   RenderStateShard.POSITION_COLOR_SHADER, TRANSLUCENT_TRANSPARENCY,
                     NO_DEPTH_TEST, NO_CULL and COLOR_WRITE are protected there.
  RoadConfig (3)     defineListAllowEmpty(String, List<String>, Supplier, Predicate) has no
                     matching overload in NeoForge 20.6's config API.

The HudDraw one is worth stating precisely because it is asymmetric across loaders: the same five
references compile on 1.20.6 FABRIC and on 1.21.1 NeoForge, and fail only on 1.20.6 NEOFORGE. The
fabric access widener is not the reason -- it widens only RenderType$CompositeState -- so the
difference is in the jars: vanilla 1.20.6 has those fields public and NeoForge 20.6's patched jar
does not.

That makes it an access transformer question, and the repository has none yet: there is no
src/main/resources/META-INF/accesstransformer.cfg and neoforge.mods.toml declares no
accessTransformers block. Adding both is the next step, with the AT kept to the five fields so it
stays a no-op on the 1.21.x nodes where they are already public.

1.20.4's 27 are a different family: ClientTickEvent does not exist in NeoForge 20.4 at all, so
the tick wiring in HowToGo and RoadLayer has to be written against whatever that version uses

### 26.3 NeoForge was passing on a cache, not on its merits

26.3-neoforge fails createMinecraftArtifacts with:

    ERROR Line: 44, <..net.minecraft.core.HolderSet$1>..contents()....net.minecraft.core.HolderSet.Named..contents()
    NodeExecutionException: Node action for recompile failed

It went unnoticed for a long time because the task result was cached: createMinecraftArtifacts had
run successfully at some earlier point and Gradle kept reusing the output, so the node compiled
against artifacts that a fresh run could not reproduce.

This was established by removing the access transformer, which was the suspected cause, and
watching 26.3 fail identically without it. The AT is not involved -- that part of the old reading
still holds.

**What it actually was.** Not a NeoForge patch, and not a beta-only bug: the failure is one step
later, in the source transformation that applies NeoForge's access transformer to the decompiled
sources before they are recompiled. NeoForge's own AT widens both

    public net.minecraft.core.HolderSet$Named contents()Ljava/util/List;
    public net.minecraft.core.HolderSet$1     contents()Ljava/util/List;

and `jst-cli-bundle` 2.0.6 applies the first but not the second. Diffing the patched source against
the transformed source makes it a one-line diff -- `Named.contents()` comes back `public` while the
anonymous subclass that overrides it in `HolderSet.emptyNamed` still declares it `protected`, which
javac rejects:

    attempting to assign weaker access privileges; was public

Nothing in this project's source is involved; `HolderSet` is Minecraft's.

**The fix.** `neoform-runtime` 2.0.18 (what ModDevGradle 2.0.148 pins) names jst-cli-bundle 2.0.6 in
its `tools.properties`. 2.0.31 is the lowest runtime that names 2.0.11, and 2.0.11 widens both
declarations. The version is overridden per node rather than globally -- see `nfrt_version` in
`versions/26.3-neoforge/gradle.properties` -- so the ten nodes that already build stay on the
pipeline they were verified against. `build.neoforge.gradle.kts` reads it with `findProperty` so the
root value remains the fallback.

**One more thing on the way.** With the newer runtime the pipeline then died earlier, in `patch`:

    NeoForm archive entry patches/ does not exist in .../maven-local/.../neoform-26.3-1.zip

The workspace mirror's copy of `neoform-26.3-1.zip` was a repack with no directory entries at all
(79 KB, 152 entries) rather than the published archive (98 KB, 276 entries, including the `patches/`
entries). 2.0.18 did not care; 2.0.31 resolves `{patches}` through an archive entry lookup and does.
The mirror copy was replaced with the published file -- sha1 `084632c2b30028e0eddbc54428d2dad317c95e9e`,
identical to what Gradle had cached from the CDN.

Both are environment/toolchain repairs, not mod changes. 26.3-neoforge now builds with no stale
cached artifact behind it -- decompile, patch, applyNeoforgePatches, transformSources and recompile
all re-ran, and the transformed `HolderSet.java` comes out with `contents()` public at both line 44
and line 189.

### 1.20.6 NeoForge: defineListAllowEmpty changed shape

Three calls in RoadConfig fail on 1.20.6 and nowhere else:

    .defineListAllowEmpty("avoid_road_classes", List.<String>of(), () -> "water",
            element -> element instanceof String)

The same three compile on 1.20.4 NeoForge and on 1.21.1, so NeoForge 20.6 changed the method's
shape rather than the arguments being wrong. Typing the predicate explicitly as (Object element)
does not help, which rules out inference and points at arity or parameter types.

Not yet read out of the jar -- javap could not find ModConfigSpec$Builder in any cached NeoForge
jar, which is the next thing to fix before guessing again. The 1.20.6 NeoForge jar should be under
versions/1.20.6-neoforge/build or the neoform artifact cache.

### defineListAllowEmpty: read out of the jar, and the answer is arity

NeoForge 20.6.144's ModConfigSpec$Builder -- net.neoforged.neoforge.common, not fml.config --
declares exactly four defineListAllowEmpty overloads:

    (String,                List<? extends T>,          Predicate<Object>)
    (String,                Supplier<List<? extends T>>, Predicate<Object>)
    (List<String>,          List<? extends T>,          Predicate<Object>)
    (List<String>,          Supplier<List<? extends T>>, Predicate<Object>)

Three arguments, all of them. The fourth -- the Supplier<String> that offers a new element to add --
does not exist in this version at all, which is why the call has no matching overload and why
typing the predicate explicitly changed nothing. 1.20.4 and 1.21.1 both have the four-argument
form; 20.6 is the version that dropped it.

The fix is to pass three arguments on that node, but it cannot be a condition at the call site:
RoadConfig is fenced whole by loader -- fabric on lines 15-465, neoforge on 466-1093 -- and all
three calls are inside the neoforge arm, so a version condition there would nest.

The shape that works is a helper in its own file with top-level conditions, taking the Builder and
the same arguments and forwarding to whichever arity that version has. The three call sites then
read the same on every node. It does mean restructuring the fluent chains slightly, since the
calls are currently chained off builder.comment(...).

### 1.20.4 NeoForge: ClientTickEvent does not exist, and what replaces it

NeoForge 20.4.253 has no net.neoforged.neoforge.client.event.ClientTickEvent at all. Its tick
event is:

    net.neoforged.neoforge.event.TickEvent$ClientTickEvent    with a TickEvent$Phase field

so the 1.21 shape -- a ClientTickEvent.Post subclass, one event per phase -- is not there. A
listener written as (ClientTickEvent.Post event) -> work becomes (TickEvent.ClientTickEvent event)
-> { if (event.phase != TickEvent.Phase.END) return; work; }.

That is 19 errors across three files: RoadLayer (the import and its one listener), HowToGoServer
(one) and HowToGo (the import and twelve listeners).

The shape of the fix is the awkward part, and it is the same wall as RoadConfig: HowToGo is fenced
whole by loader, fabric on one side and neoforge on the other, so a version condition inside the
neoforge arm would nest. Twelve listeners cannot be moved to a call site.

What that leaves is either a helper in its own file with two top-level conditions, which means the
twelve listener registrations written out twice, or a replacement rule -- but the phase guard is a
statement, not a spelling, so a replacement rule cannot express it. The helper is the way, and the
question is whether twelve duplicated lines are worth one node.

### 1.20.4 NeoForge: the last four errors

After HowToGoTicks, 1.20.4 is down to four, and they are two API changes:

  HowToGo.java:68, HowToGoServer.java:66   @Mod(value = ..., dist = Dist.CLIENT)
  HowToGo.java:226                          modContainer.registerConfig(ModConfig.Type.CLIENT, ...)

Neither class is in the NeoForge merged jar -- net.neoforged.fml.common.Mod and
net.neoforged.fml.config.ModConfig come from the FML library jars -- so reading their 20.4 shapes
means looking in the fmlcore / fmlloader artifacts rather than neoforge-20.4.253-merged.jar.

That is where the next attempt starts. The dist attribute on @Mod is the 1.20.5 addition that
made a mod client-only declaratively, so 20.4 predates it and the node needs whatever that version
did instead -- most likely a DistExecutor-style guard or simply no restriction, since this mod is
client-only by construction anyway.

### 26.x Fabric: three ways in, all closed by the same remap

Tried in this round, and each was reverted:

  1. accessWidenerPath conditional on >=26.1 -- already was, and the error did not change
  2. the accessWidener key dropped from the BUILT fabric.mod.json via a processResources line filter
  3. the from(awTemplate) copy skipped on those nodes, so no howtogo.accesswidener reaches the resources

All three together still produce "Cannot remap access widener from namespace official. Expected:
intermediary", which means the widener Loom is complaining about is not coming from any of them.
The remaining candidates are that Loom caches the widener from an earlier run, or that something
else in src/fabric is picked up by convention the way META-INF/accesstransformer.cfg was on the
NeoForge side.

Worth noting before trying again: this is the same class of problem as the AT was, and the AT's
answer was "it is picked up by convention, move it". The difference is that the fabric.mod.json key
and the resource copy are both supposed to be the only two routes, and both are now closed.

Fourth route closed, and it was the most likely one: the SOURCE fabric.mod.json. Removing the
accessWidener key from src/fabric/resources/fabric.mod.json and re-registering 26.1-fabric changes
nothing. So Loom is not reading it from there either, and the key it objects to is not coming from
the source, the built resource, accessWidenerPath, or the from(awTemplate) copy.

That leaves the widener being found by convention somewhere in the srcDir set, which is exactly how
the NeoForge access transformer behaved -- and the fix there was to move the file out of the
directory that gets scanned. The templates already live in src/fabric/aw/ rather than in resources
for that reason, so the thing to look for is something else under src/fabric/resources.

A println settled the one thing that was still assumed: the condition is right. For the 26.1 node,

    mc=26.1  eval=26.1 >= 26.1 -> true  needsAccessWidener=false  awGeneration=modern-intermediary

so accessWidenerPath is genuinely not being set, and Loom still reports a widener it cannot remap.
Every node that builds prints eval=false and needsAW=true, which is the expected spread.

That rules out the condition and leaves Loom finding the widener from something other than all five
routes now closed. Worth noting for whoever picks this up: the debug line is three lines in
build.fabric.gradle.kts and it should have been the first thing done, not the fifth.

And a sixth route closed, which is the one that should have been tried first: accessWidenerPath
removed from the loom block entirely, not just made conditional. The error is identical.

So Loom is not reading the widener from accessWidenerPath at all, and the five earlier attempts were
all variations on a route that was never in use. What is left is that Loom derives it from the mod
metadata it parses at configuration time -- and that parse happens against the SOURCE resources,
which was tested in round 60 by removing the key from src/fabric/resources/fabric.mod.json and
changed nothing. Either that test did not take effect for a caching reason, or the source of the
widener is somewhere neither of us has looked.

The honest summary of six rounds on this node: five of them were spent on routes that a single
unconditional removal would have eliminated in one. The order was wrong -- the cheapest experiment
that falsifies the most assumptions should come first, and "turn the whole thing off and see" was
available from the start.

### 26.1 Fabric: the missing classes are missing from the jars, not from the classpath

The 40 errors name HudRenderCallback, KeyBindingHelper, ClientCommandManager and ItemGroupEvents.
Opened every remapped jar Loom produced for that node -- 90 of them under
build/loom-cache/remapped_working -- and none of the four classes is present in any of them.

The dependency tree does list the modules, and fabric-command-api-v2 and fabric-rendering-v1 are
remapped and on disk. fabric-key-binding-api-v1 is NOT among the 90, which is the shape of the
problem: some modules remapped and some did not, and nothing reported the ones that did not.

Two candidates, both worth checking before changing code:

  - the umbrella fabric-api jar for 26.x has only 51 entries and keeps its modules under
    META-INF/jars/, so Loom may be expected to explode them and for this node did not
  - or Fabric API 0.155.3 genuinely reorganised those modules, in which case the classes have
    moved rather than vanished

The distinction matters: the first is a build problem and the second is a port. Nothing so far
says which.

### Answered by the docs, not by elimination: those classes were renamed

FabricMC/fabric-docs carries a 26.1 porting guide, and it settles the question the last round left
open. The classes are not missing from the classpath; they were renamed or removed:

  client/command/v2/ClientCommandManager   -> ClientCommands
  client/keybinding/v1/KeyBindingHelper    -> client/keymapping/v1/KeyMappingHelper
  registerKeyBinding                       -> registerKeyMapping
  client/itemgroup/v1                      -> client/creativetab/v1
  client/rendering/v1/hud/...              -> HudElementRegistry; HudRenderCallback was REMOVED

So this is a port, not a build problem -- the second of the two candidates, and the one that costs
source changes. A copy of the guide is in docs/fabric-api-26.1-porting.md because it is the
authority for every remaining 26.x Fabric question and re-fetching it costs a round each time.

### 26.1 Fabric: the four remaining causes, read out of the jars

The guide does not name all of these, so the remapped jars did. Three of the four are replacements
with different shapes rather than renames:

  HudRenderCallback.EVENT.register(...)
    -> HudElementRegistry.addLast(Identifier, HudElement)
       also addFirst, attachElementBefore/After, removeElement, replaceElement

  ItemGroupEvents (net.fabricmc.fabric.api.itemgroup.v1)
    -> CreativeModeTabEvents (net.fabricmc.fabric.api.creativetab.v1)
       MODIFY_OUTPUT_ALL, or modifyOutputEvent(ResourceKey<CreativeModeTab>)

  ScreenEvents.afterRender(Screen)
    -> ScreenEvents.afterExtract(Screen); afterRender no longer exists. The surrounding family is
       beforeExtract, afterBackground, afterExtract, beforeTick, afterTick, remove

  graphics.guiRenderState.submitGuiElement(state)
    -> the access-widener call, and 26.x has no widener. This one needs a different route to the
       same effect rather than a different name.

Note also that HudElementRegistry takes a net.minecraft.resources.Identifier, which is the 26.x name
for what earlier versions call ResourceLocation -- the same rename family the build already handles

### 26.1: GuiGraphics is gone, and that is the shape of the last three errors

The 26.1.2 Minecraft jar has no net/minecraft/client/gui/GuiGraphics.class. What it has is
GuiGraphicsExtractor, with HoveredTextEffects, RenderingTextCollector and ScissorStack as nested
classes. So this is not a field that became inaccessible -- the type the HUD code is written
against does not exist on that version.

That reframes all three remaining errors at once:

  HudRenderCallback.EVENT.register   HudElementRegistry.addLast(Identifier, HudElement), and
                                     HudElement's method is extractRenderState(
                                     GuiGraphicsExtractor, DeltaTracker)
  ScreenEvents.afterRender           afterExtract, whose callback also takes the extractor
  guiRenderState.submitGuiElement    a GuiGraphics member, on a type that is not there

So the 26.x HUD path is not a port of the 1.21.6 one with different names; it is a different
render model -- extraction rather than immediate submission. That is a real piece of work and it is
the first time this node has needed one.

The build-level lesson is the same one this session keeps relearning: the type was one jar entry
away the whole time. Checking whether GuiGraphics exists in the 26.1 jar should have been the first
thing done on reaching these errors, not the fourth.

### The size of the 26.x HUD port, measured

Thirteen files reference GuiGraphics, about 110 occurrences: DestinationScreen 20, DestinationPicker
Page 18, NavHudRenderer 17, RoadElementRenderer 13, TransitLineScreen 13, HudDraw 12, HudSubmit 6,
RoadNameScreen 6, HudBatchNewer 4, MapFilterOverlay 4, HudBatchLegacy 3, MapFilterPanel 2,
MapLabels 2.

GuiGraphicsExtractor does have fill, fillGradient and blit -- several overloads each, taking
RenderPipeline and TextureSetup -- so a plain rename is tempting. It was tried as a replacement rule
and did not work: the generated sources came out as GuiGraphicsExtractorExtractor even with a
negative lookahead on the pattern and with build/generated deleted first. The source tree was
verified clean of the new name and the active node was 1.21.1, so the rule was applied more than
once by something not yet identified.

That is recorded rather than chased, because the rename would not have been the answer anyway: the
render model changed from immediate submission to extraction, so the call sites have to be
rethought, not respelled. The rule was reverted and 26.1-fabric is back to its eight errors.

### Forge: compile works, reobf does not

All three Forge nodes compile. The build task does not: reobfJar fails with

    Could not find net.minecraftforge:forge:1.20.1-47.4.28_mapped_official_1.20.1

That coordinate is ForgeGradle's own reobf input -- the patched and mapped Minecraft artifact it
produces during setup -- and nothing on this network serves it. 1.20.6 gets further than the other
two but its jar is 0 bytes, so "BUILD SUCCESSFUL" there is not yet a usable artifact either.

So the Forge gap is now one layer down from where it was: not "which version number", which is
settled, but "ForgeGradle cannot produce its reobf input here". That is the same shape as the
NeoForm runtime download problem and may have the same answer -- find what it wants and put it
where it looks -- but the reobf input is generated, not published, so there may be nothing to fetch.

ForgeGradle does register tasks here -- 68 of them, including downloadMCMeta, downloadMcpConfig,
extractSrg and reobfJar -- so the plugin is not inert the way it was under Gradle 9. What is missing
is the mapped Minecraft artifact itself: the cache holds forge-1.20.1-47.4.28_mapped_official_1.20.1
as zero-byte placeholder POMs and .input/.sha1 files, so a resolution was attempted and produced
nothing.

The 1.20.6 jar being 261 bytes is the same symptom from the other end: reobf produced an empty
archive because it had no input to reobf.

There is no genPatchedMinecraft or equivalent in the task list, which is the thread to pull next:
either a task that should produce the mapped artifact is not registered, or it is named something
unexpected. That is worth one more round before treating Forge as blocked.

Correction to the previous note: ForgeGradle registers NO tasks on this build. The full --all list
is generic Gradle plus Stonecutter -- compileJava, jar, processResources, stonecutterGenerate and so
on -- and nothing else. The downloadMCMeta, downloadMcpConfig, extractSrg and reobfJar that appeared
to be present were in the --all output as text, not as registered tasks.

So this is the Gradle 9 symptom after all, on Gradle 8.14.6: the plugin applies and registers
nothing. Which means compileJava succeeding says less than it looked like -- it compiled against
whatever the empty placeholder artifacts resolved to, which is why the jar is 261 bytes and reobf
has no input.

That is worth knowing before spending more rounds on the mapped artifact: there is no ForgeGradle
pipeline running at all on this build, so nothing is going to produce it.

And the correction was itself wrong. ForgeGradle does register tasks here. The --all output has
task groups named "ForgeGradle prepare runs tasks", "ForgeGradle runs tasks" and "Jarjar tasks",
and the tasks downloadMCMeta, downloadMcpConfig, extractSrg, reobfJar, reobfJarJar and
artifactTransforms are all present.

The two wrong readings in a row came from the same place: a grep pattern of
"^[A-Za-z][A-Za-z0-9]* ?-" which requires a space-dash-description after the task name. Gradle omits
the description for tasks that have none, so every ForgeGradle task -- which is most of them -- was
filtered out. Twice. The first reading said they were present, the second said absent, and neither
was from a reliable parse.

So the state is what the error said in the first place: ForgeGradle runs, registers its tasks, and
cannot produce the mapped Minecraft artifact because resolving it yields the zero-byte placeholders.
That is the thing to look at, and it is where this was before the two detours.

### The Forge build had no source at all

compileJava on the forge nodes was reporting NO-SOURCE. The cause: forge-build/src, the junction to
../src that the whole arrangement depends on, did not exist. So nothing was compiled, the classes
directory was empty, and every earlier "BUILD SUCCESSFUL" on these nodes was a build of nothing.

That also explains the zero-byte mapped artifacts and the 261-byte jar without needing any theory
about ForgeGradle: with no source there was nothing to transform, and reobfJar wrote an empty
archive because its input was empty.

After recreating the junction -- cmd /c mklink /J forge-build/src src, since New-Item -ItemType
Junction is not what a cmd link is -- the build actually runs: ForgeGradle expands the MCP zip and
compiles. It now fails with eight errors, all of them inside its own generated
mcp/client/Start.java complaining that net.minecraft.client.main does not exist, which is a
ForgeGradle setup problem and a much better place to be than "no source".

The junction is in .gitignore as forge-build/src, which is right -- it is machine-local -- but that
means it does not survive a fresh clone and has to be recreated. Worth a line in the build notes.

With the source present, all eight errors are ForgeGradle's, none of them ours:

    build/tmp/.cache/expanded/zip_<hash>/mcp/client/Start.java:5   net.minecraft.client.main
    build/tmp/.cache/expanded/zip_<hash>/mcp/client/Start.java:17

Start.java is ForgeGradle's own launcher source, extracted from the MCP config zip and compiled
into the run setup. It needs the Minecraft client jar, and that is exactly what is not arriving:
forge-1.20.1-47.4.28-inject_src.jar is 1KB, which is an empty jar, and no _mapped_official_ jar
exists at all.

So the shape is now clear and one level further in. ForgeGradle runs, registers its tasks, expands
the MCP config, and fails to produce the injected and mapped Minecraft artifacts -- the two steps
that turn the userdev artifact into something compilable. Our own source contributes zero errors,
which means the loader plumbing in build.forge.gradle.kts is probably right and the problem is
purely in that pipeline.

### The actual ForgeGradle failure, which was hiding under the empty artifacts

Resolving compileClasspath for a forge node prints the real error, and it has nothing to do with
the mapped artifact being empty:

    ObjectInstantiationException: Could not create an instance of type
      org.gradle.jvm.toolchain.internal.DefaultToolchainSpec
      at net.minecraftforge.gradle.mcp.function.ExecuteFunction.execute(ExecuteFunction.java:119)

ForgeGradle 6.0.54 was built against an older Gradle toolchain API, and Gradle 8.14.6 has moved it.
The MCP runtime calls javaToolchains.launcherFor while executing its steps -- downloadManifest,
downloadJson, downloadClient, downloadServer, extractServer all run, then this -- so the pipeline
dies before producing anything, which is why inject_src.jar is 1KB and no mapped jar exists.

That also explains why the task list has no genPatchedMinecraft: those tasks never get registered
because setup throws first.

Gradle 8.12 is present and complete locally; 8.7 and 8.8 are only partial downloads (.part files).
Trying 8.12 was abandoned after eight minutes -- a different Gradle means a new daemon and a full
re-resolution of everything, and it was not worth holding the session open for.

### The 26.x HUD pattern, from the Fabric docs

docs.fabricmc.net/develop/rendering/hud gives the whole shape, and it is simpler than the jar
signatures suggested:

    HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, MyMod::extract);

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        graphics.fill(0, 0, 10, 10, colour);
    }

So a HudElement is just (GuiGraphicsExtractor, DeltaTracker) -> void, the extractor carries the same
fill/blit surface the old GuiGraphics did, and the only real decision is WHERE to attach -- the API
offers attachElementBefore and attachElementAfter against a VanillaHudElements constant, plus
addFirst and addLast.

That means the port is smaller than the 13-file count implied: the type changes from GuiGraphics to
GuiGraphicsExtractor and the registration changes from HudRenderCallback.EVENT.register to a
HudElementRegistry call with a position. The drawing code inside should carry over.

Note also that HudElementRegistry takes a net.minecraft.resources.Identifier -- 26.x's name for
ResourceLocation -- so the id has to be built the new way.

### What actually changes in HudSubmit and HudBatchNewer

GuiGraphicsExtractor has no submit method of any kind and no guiRenderState field. Its own fields
are minecraft, pose, scissorStack, sprites and guiSprites -- nothing that holds pending elements.

So the 26.x model does not need HudSubmit at all. On 1.21.6 through 1.21.11 the batch code
accumulates GuiElementRenderState objects and hands them to GuiGraphics, which is why the Fabric arm
reaches through the private guiRenderState field with an access widener. On 26.x the extractor
writes as it is told and there is nothing to hand over.

That means the port is: HudBatchNewer calls graphics.fill(...) and friends directly on 26.x instead
of building states and submitting them, and HudSubmit has no 26.x arm. The drawing arguments carry
over; the batching layer does not.

This is the one place in the 26.x port where a design change is needed rather than a rename, and it
is confined to the batch layer. The thirteen files and 110 occurrences are still mostly type names.

### What HudBatchNewer actually needs on 26.x, now measured

HudBatchNewer is fenced whole on >=1.21.9 and ends by calling HudSubmit.submit(graphics, snapshot()),
where snapshot() returns a GuiElementRenderState that the GUI renderer plays back at the end of the
frame. HudBatchLegacy does the same for older versions.

GuiGraphicsExtractor offers three fills:

    fill(int, int, int, int, int)
    fill(RenderPipeline, int, int, int, int, int)
    fill(RenderPipeline, TextureSetup, int, int, int, int)

All three take ONE colour for the whole rectangle. A GuiElementRenderState built from a vertex batch
carries a colour per vertex, so the batch cannot be replayed as a single fill call. Whether it can be
replayed as several depends on what the batch actually draws -- if the HUD only ever emits flat
quads, a loop of fills is equivalent; if it emits gradients, it is not.

That is the question to answer before writing any of it, and it is the same kind of question as the
rest of this port: what does the existing code actually do, rather than what does the API offer.

### Why 26.x NeoForge builds and 26.x Fabric does not

NeoForge's 26.1 GuiGraphicsExtractor carries:

    submitGuiElementRenderState(net.minecraft.client.renderer.state.gui.GuiElementRenderState)
    submitPictureInPictureRenderState(...)

Vanilla's does not -- its fills take one colour per rectangle and there is no submit at all. So the
one line in HudSubmit works unchanged on the NeoForge nodes, which is why they build, and has no
counterpart on the Fabric ones.

Note also that the state type moved package: net.minecraft.client.renderer.state.gui.GuiElementRenderState
on 26.x, against net.minecraft.client.gui.render.state.GuiElementRenderState on 1.21.x.

So the Fabric 26.x path is genuinely a different route to the same result, not a rename: NeoForge
keeps the deferred model and vanilla does not, and the Fabric side has to draw through fills.
Whether a loop of fills is equivalent still depends on whether the batch emits per-vertex colour,
which is the next thing to check.

### The answer on HudBatchNewer: fills are not enough

VertexSink.vertex takes (x, y, r, g, b, a), so the sink can carry per-vertex colour in principle.
But every emitter passes one argb to all of a shape's vertices: emitQuad writes the same r,g,b,a to
four corners, emitTriangle writes the same to three and repeats the last, and emitDisc fans degenerate
quads with one colour each.

So colour is not the obstacle. Geometry is. GuiGraphicsExtractor's fills are axis-aligned
rectangles, and a triangle written as a quad with its last corner repeated is not a rectangle. Neither
is a disc. A loop of fill calls would draw the road markings wrongly rather than approximately.

Which means the Fabric 26.x HUD needs a real geometry submission path, and vanilla does not expose
one -- that is exactly the gap NeoForge's submitGuiElementRenderState fills. Whether vanilla 26.x has
some other route, or whether this mod's Fabric 26.x HUD has to give up the custom geometry, is the
next question and it is a documentation question, not an experiment.

### 26.3 Fabric: the last four errors, and what is known about them

RoadEditHandler lines 332 and 334, in the fabric && >=1.21.9 arm:

    ScreenKeyboardEvents.allowKeyPress(screen).register((open, event) ->
            !(isMapOpen(open) && handleKey(open, event.key(), event.scancode(), InputConstants.PRESS)));

The compiler names the whole line and not the member, so it is not yet established whether event.key()
or event.scancode() is the one that moved, or whether the event type changed shape entirely.

fabric-screen-api-v1 in 26.3 is 5.2.4, and its ScreenKeyboardEvents carries AfterKeyPress,
BeforeKeyRelease, AllowCharType and AfterCharType as nested types. There is no AllowKeyPress nested
class in that jar, so allowKeyPress must return Event<some shared type> -- most likely a KeyEvent
record that these several nested callbacks all use.

Next step is to javap that shared type and read its accessors, rather than to guess at which of the
two calls is wrong.

---

## Forge: removed from the repository

Forge was dropped rather than fixed, and this section records why so it is not attempted again
without reading this first.

Two facts, both verified:

  1. ForgeGradle 6 needs Gradle 8.1.x. MinecraftForge/ForgeGradle#933 says upgrading the wrapper to
     8.2 or above produces "Could not create an instance of type DefaultToolchainSpec" and "It builds
     properly on Gradle 8.1.x"; the FG 6.x porting guide says to use 8.1.1. Every earlier Forge
     failure in this file -- project services has been closed, Failed to get Minecraft Vanilla Base,
     zero-byte mapped artifacts, a 261-byte jar -- is downstream of that one mismatch.

  2. Stonecutter 0.7.11 does not load on Gradle 8.1.1. With 8.1.1 and Java 17 in a clean
     GRADLE_USER_HOME the build fails the moment the plugin applies:

         Running Stonecutter 0.7.11
         FAILURE: Protocol message contained an invalid tag (zero).

So the two requirements are mutually exclusive on this tree. Getting Forge would mean building those
nodes without Stonecutter -- a separate checkout with the //? markers pre-resolved, or an older
Stonecutter -- and both cost ongoing maintenance rather than a one-off change.

Everything Forge-specific has been deleted: build.forge.gradle.kts, forge-build/, versions/*-forge/,
the ForgeGradle plugin declaration and its version property. What remains above is the record of the
investigation.
