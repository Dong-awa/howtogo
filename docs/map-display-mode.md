# Map display mode

Navigation has two halves: a map (roads, the route, the turn markers, the destination) and a readout
(the next turn, the distance to it, what is left). The map half has an alternative: on an install that
has **Xaero's Minimap**, the same content can be drawn on Xaero's map instead of inside this mod's
panel, leaving the HUD with a small card that carries only what a map cannot show.

`map_display_mode` chooses which. The default is the panel this mod has always drawn.

## The two modes

| Mode id | What draws the map | What the HUD shows |
| --- | --- | --- |
| `hud` (default) | this mod's navigation panel: a 120 px rotating mini-map with roads, rails, the route, road names, place markers, the origin, the destination and north | the panel's own readout under the map |
| `xaero_minimap` | Xaero's Minimap, through its minimap element-render API: the whole road network and rail layers as real polylines, the route, and the destination/origin markers | a compact card: turn arrow, distance to the turn, the instruction, a progress bar, the remaining distance/time, the destination name and travel mode |

The two modes read the **same** readout: `NavHudReadout` computes every string and number once from
`Navigation`, and both renderers draw that one value. Only the map is routed differently, so the panel
and the compact card cannot disagree about the instruction.

## Where the code is

| Class | Responsibility |
| --- | --- |
| `RoadConfig` (both arms) | the keys `map_display_mode`, `compact_hud_anchor`, `compact_hud_offset_x`, `compact_hud_offset_y`; NeoForge's spec and Fabric's JSON share the same accessor names |
| `MapDisplayMode` | the two values, their on-disk ids and the declared default |
| `HudAnchor` | the four corners the compact card can hang from, and the default |
| `NavMapMode` | the one place that decides which mode is *possible*: chosen + registered → `xaero_minimap`, anything else → `hud`; writes the single fallback log line |
| `NavHudReadout` | the readout values, computed once, consumed by both renderers |
| `NavHudShapes` | the turn arrow, the U-turn arrow and the progress bar, drawn once for both layouts |
| `NavHudRenderer` | the full panel in `hud` mode; dispatches to the compact card otherwise |
| `NavCompactHud` | the small readout-only card |
| `client/xaero/XaeroMinimapBridge` | the always-compiled seam: finds the optional overlay by name, polls registration, says why it is unavailable |
| `client/xaero/minimap/*` | the overlay itself, compiled only on nodes whose `libs/` holds a Xaero's Minimap jar |

## The fallback

`xaero_minimap` needs three things: Xaero's Minimap installed, a build compiled against it, and a
successful registration. Any of them missing means the in-HUD map is used instead, and the log gets one
line naming the missing piece:

```
[HowToGo] map display mode xaero_minimap is unavailable (Xaero's Minimap is not installed); falling back to the in-HUD navigation map
[HowToGo] map display mode xaero_minimap is unavailable (this build was compiled without Xaero's Minimap support (no minimap jar in libs/ for this node)); falling back to the in-HUD navigation map
[HowToGo] map display mode xaero_minimap is unavailable (registering with Xaero's Minimap failed: ...); falling back to the in-HUD navigation map
```

While Xaero is still initialising (its minimap handler is built after this mod's client init) the panel
keeps its map, but no fallback is logged: the question is not settled yet, and a warning printed a
moment before the overlay comes up would be worse than none. Once the overlay registers, the next tick
logs `map display mode: navigation map content is drawn on Xaero's Minimap`.

A change made in NeoForge's config screen takes effect on the next frame — the resolution is per frame
and the overlay only draws while the resolved mode is `xaero_minimap`. On Fabric the JSON file is read
at startup like every other key in it, so there the switch is a restart.

## The overlay, and how it projects

Xaero's Minimap exposes an element-render pipeline of its own
(`xaero.hud.minimap.element.render.*`), reached through `HudMod.INSTANCE.getMinimap()
.getOverMapRendererHandler().add(renderer)`. It is the minimap's counterpart of the world map's
pipeline this mod already compiles against, and the overlay is written as its three abstract
participants: a `MinimapElementReader` (world positions and hit boxes), a `MinimapElementRenderProvider`
(the elements for one pass) and a `MinimapElementRenderer` (drawing).

The one thing that extension point does **not** give a renderer is the viewport transform: the
over-map handler projects each element itself — it pushes a translation so that pose-local (0,0) is
that element's screen position — and hands the renderer no zoom, rotation or minimap origin. Rather
than guess at those (the first version guessed at a marker scale and drew no network at all), the
overlay **measures** the frame's transform from Xaero's own projection:

- the provider emits a handful of invisible probe elements first: one at the player (the frame
  anchor), two each at +64 / +16 / +4 blocks along world X and Z, and four at ±100000 blocks;
- the renderer reads each probe's pose matrix, which gives that element's screen position
  (`m30`, `m31`) and the parent transform's linear part, and derives the frame's screen pixels per
  block (rotation and zoom included) from the largest probe pair that was not clamped;
- the four huge probes are clamped by Xaero to the edge of its map, so their positions measure the
  minimap's screen rectangle; that becomes the clip rectangle, converted into the same local units,
  so no stroke can be painted outside the map;
- the road and rail layers, the route and the markers then go through the same
  `HudDraw.emitNetwork` / `emitNetworkInBox` / `emitPath` emitters the navigation panel uses for its
  own mini-map, with the measured world-to-local matrix.

Nothing is drawn on a frame whose transform could not be measured: drawing nothing is invisible,
drawing with a guessed transform paints the HUD. The first frame with a usable transform logs
`[HowToGo] minimap overlay active: <px>/block, map rect <w>x<h>` at INFO, which is what tells a
working projection from a failed one in the log.

Other limits, for the record:

- no text labels on the minimap (the panel's road names stay in the panel);
- nothing is written to Xaero's data — no waypoints are added, no settings touched, no jar modified;
- the road network draws whenever the minimap does (it is a property of the world); the route and the
  destination/origin markers need a trip;
- the next turn has no marker of its own: the route already draws attention there, and a solid dot on
  the corner read as an obstruction; the compact card's arrow is the next-turn reading;
- a circular minimap is clipped by the rectangle that bounds it, so a stroke may reach a few pixels
  into the frame. That is Xaero's own clamp bound rather than the projection's.

## Why the jar is a build input and not a declared dependency

Xaero's Minimap stays optional in both directions:

- `neoforge.mods.toml` and `fabric.mod.json` do not mention it, so an install without it is normal and
  loses nothing but this mode;
- the build scripts look for `libs/xaerominimap-<loader>-<mc>-*.jar`, add it as `compileOnly` /
  `modCompileOnly` when present, and otherwise **exclude the overlay package from the source set** — the
  node compiles without it, and the display-mode router reports that this build has no support for the
  minimap mode. That is the same escape hatch the mcphone integration uses.

See [`libs/README.md`](../libs/README.md) for which file to drop in, and why a jar from another
Minecraft generation needs its own node.

## Verifying it

```
./gradlew :1.21.1-neoforge:build          # the active node, which is the one with the jar in libs/
```

In game, with a destination set:

1. `map_display_mode = hud` → the navigation panel has its mini-map and its readout (unchanged).
2. `map_display_mode = xaero_minimap`, Xaero's Minimap installed → the panel is gone; a compact card
   sits in the configured corner; the road network is drawn on Xaero's minimap with the route and the
   destination on top of it;
   the log says the content is drawn there.
3. `map_display_mode = xaero_minimap`, Xaero's Minimap removed → the full panel comes back and the log
   carries the fallback line above.
