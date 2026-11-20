# In-game settings

The mod's client config has always been a file. That is right for the values a player tunes once --
the colour of each road class, the distance at which a road stops counting as the one underfoot --
but wrong for the handful a player changes because they are playing: the interface theme, where the
navigation map is drawn, whether walking is offered when the car route fails.

This screen is that handful. It is reached from the mod's own main screen (default key **K**) with
the **Settings** button on the title line, and it is drawn in the mod's own style in whichever
palette is current.

## What it shows, and what it deliberately does not

The filter is "would a player look for this while playing, and can they understand the answer without
reading the config file's comments". That leaves six groups:

| Group | Setting | Config key | Default | Effect |
| --- | --- | --- | --- | --- |
| Appearance | Interface theme | `ui_theme` | `auto` | dark / light / follow the sky |
| Appearance | Road labels | `road_label_style` | `name` | a road's name, its classification sign, or both (see [road-categories.md](road-categories.md)) |
| Appearance | Road categories | -- | -- | opens the category editor (`RoadCategoryScreen`): add/delete/rename categories, pick their colours |
| Navigation HUD | Map display mode | `map_display_mode` | `hud` | own HUD map, or Xaero's Minimap (first row: it decides what this group is about) |
| Navigation HUD | Compact HUD corner | `compact_hud_anchor` | `bottom_left` | where the readout-only card hangs |
| Navigation HUD | Compact HUD horizontal offset | `compact_hud_offset_x` | `0` | pixels further in from that corner |
| Navigation HUD | Compact HUD vertical offset | `compact_hud_offset_y` | `0` | the same, vertically |
| Routing and travel | Default travel mode | `default_travel_mode` | `walk` | mode a session starts in |
| Routing and travel | Walk when chosen mode is slower | `fall_back_to_walking_when_slower` | `true` | foot route as fallback |
| Routing and travel | Mix walking into public transport | `transit_mixed` | `true` | transit may walk between lines (walks follow the roads); a journey that rides a line is kept even if walking is quicker |
| Routing and travel | Transit wait per boarding | `transit_wait_seconds` | `60` | average wait used in estimates |
| Routing and travel | Walk off-road distance | `walk_offroad_blocks` | `32` | how far a walk may cross between two different roads' ends |
| Routing and travel | Station snap distance | `station_snap_blocks` | `32` | how close counts as at the stop (and how near a road or track a place may be marked as a station); the planner places a stop on its line by the editor's 256-block station reach instead, so this no longer decides whether a line can be ridden |
| Routing and travel | Straight-through junction calls | `straight_announcements` | `true` | call a real junction the route carries straight through: distance on the approach, "go straight" as you reach it |
| Map data sources | Read Create train tracks | `create_train_tracks` | `true` | Create's tracks/stations layer |
| Map data sources | Read MTR stations and lines | `mtr_transit` | `true` | MTR as transit data |
| Map data sources | Read MTR's whole railway | `mtr_full_map` | `true` | while hosting, instead of the sent window |
| Map data sources | Read RoadWeaver's roads | `roadweaver_roads` | `true` | generated-road layer |
| Browser map | Start browser map with a world | `webmap_auto_start` | `false` | opens the local socket on world load |
| Logging | Info log output | `log_info` | `true` | the mod's ordinary progress lines (autosaves, re-reads, route reports); off leaves only warnings and errors -- the debug switch's diagnostics are separate |

Everything else stays in the file. In particular:

- **Road class colours, per-class tolerances and block ids** need a text field or a colour picker;
  NeoForge already offers its own config screen for them, and a second editor here would be a second
  copy of the values. On Fabric they remain file-only. The **road categories** are the one exception:
  the player defines them (add, delete, rename, recolour), so they get a page of their own behind the
  appearance group (`RoadCategoryScreen`, with the colour picker in `RoadCategoryColorScreen`) rather
  than rows on this screen.
- **Route preference, avoided classes, prefer-major-roads, voice, transit-board-only and
  straight-through calls** are shadowed by `config/howtogo/route_preferences.json`, which the
  destination picker writes: editing the config value would be a visible no-op on any install where
  the picker has been used. The picker is the right place for the first five. Straight-through calls
  are the exception that proves the rule: the switch **is** on this screen (Routing and travel), and
  its row writes the preference store directly rather than the config, which is why it is the one row
  here whose save does not go through `RoadConfig`'s setters. The config value is the declared
  default it falls back to when the store has no saved answer.
- **Create track ids, scan radius and chunk budget**, **MTR Map Overlay / server sync / station merge
  / auto route marks**, **RoadWeaver road class / full map / structures / routing** and
  **on-road tolerance** are tuning values for a world's data and its cost, not daily switches.
- **`webmap_port`** is read only when the browser-map server starts, and needs a numeric field; it
  stays a file value. **`debug_log`** is a diagnostics switch.

## Dependencies between rows

Two rows are only meaningful when another one is on. They are shown **greyed with the reason in the
description strip**, not hidden: a control that disappears when the row above it changes looks like a
bug, while a greyed one with a reason explains itself.

- the three compact-HUD rows follow `NavMapMode.effective()`, not merely the chosen mode: Xaero's
  Minimap must be installed, supported by this build, and registered; if it falls back to the HUD
  panel, the compact card does not exist and the rows are disabled;
- `mtr_full_map` is disabled while `mtr_transit` is off.

## Controls, theme and scrolling

All controls are the mod's own, over `UiTheme` colours: a rounded toggle (On/Off), a chip that cycles
a short list (theme, map mode, road labels, corner, travel mode), a `- value +` stepper for the numeric
settings, and a button row for a setting that opens a page of its own (road categories). There is no
vanilla `Button`/`Slider` and no literal colour in the screen; both palettes resolve per call, so
selecting a theme recolours the screen on the next frame. The list is scissored and scrolled with the
wheel, with the mod's two-pixel scrollbar (`UiTheme.scrollTrack/scrollThumb`).

The strip under the list shows the hovered setting's description (wrapped and elided), the reset
warning, or a short "Saved." acknowledgement. Footer buttons are **Reset to defaults** (two presses:
the first arms, the second restores; only the value rows on this screen -- the button row opens a page
and has no value to reset) and **Back**.

## Saving and effect timing

**Every change is saved as soon as it is made** -- one `RoadConfig.save()` per edit -- so closing the
screen or the game after an edit loses nothing. There is no Apply button.

| Kind of change | When it takes effect |
| --- | --- |
| theme, map display mode, road labels, compact corner/offsets | next frame |
| road category names/colours (the other page; its own store) | next frame, and every map redrawn after it |
| Create / MTR / RoadWeaver read switches | next client tick (the layers are read per tick) |
| fallback, transit mixed, walk off-road, station snap, transit wait | next route plan or guidance decision |
| straight-through junction calls | next frame: the manoeuvre list is rebuilt when the switch differs from the one it was built for, with no re-plan (the road under the player has not changed) |
| default travel mode | immediately if the player has not switched mode this session; otherwise it is the default next session (the session's choice is not taken away) |
| browser map auto-start | next world load; turning it off does not stop a server that is already running |

`default_travel_mode` and RoadWeaver's road class are the only latched values; `save()` and the
existing config-reload path re-read them. On NeoForge `RoadConfig.save()` goes through
`ModConfigSpec#save()`, which writes the TOML and fires `ModConfigEvent.Reloading`; the listener in
`HowToGo` does the re-seeding. On Fabric there is no loader event, so `RoadConfig.applyRuntimeChanges()`
re-reads the latched travel mode after a save.

## Known limits

- The screen is client-side only and is opened over a world (the main screen itself has no title-screen
  entry); nothing it writes is sent to a server.
- Reset restores the value rows above, not the whole config file, and not the categories on the other
  page (those are the player's own definitions with their own store, and have no reset-all).
- The category page's colour change is immediate and is not undone by its Cancel; only the typed names
  are a draft.
- On Fabric the config JSON is normally loaded at startup, so a hand edit of the file still needs a
  restart; changes made **through this screen** update the in-memory values immediately. RoadWeaver's
  layer is not ticked on the Fabric arm at all yet (a pre-existing gap), so its read switch does
  nothing there.
- The browser map's port still needs the file (or NeoForge's config screen).
- The description strip is three lines; longer text is elided, the whole text is in the config
  screen's tooltip for the keys that have one.

## Where the code is

| Class | Responsibility |
| --- | --- |
| `client/MainScreen` | the K-key screen; the title-line Settings button opens this screen |
| `client/SettingsScreen` | the whole editor: grouped rows, controls, scrolling, reset, theme |
| `client/RoadCategoryScreen` | the page behind the Road categories row |
| `client/RoadCategoryColorScreen` | the colour picker it opens (palette grid, HEX field, preview) |
| `client/RoadCategoryStore` | the categories it edits, and their own JSON file |
| `client/PanelButton`, `client/HudDraw`, `client/PlaceText`, `client/UiTheme` | the style it reuses |
| `RoadConfig` (both arms) | the typed setters, `save()`, `applyRuntimeChanges()`, range clamps |
| `assets/howtogo/lang/en_us.json`, `zh_cn.json` | names, descriptions, group titles, chrome |

## Verifying it

```
$env:JAVA_HOME='C:\Users\hzz12\.jdks\ms-25.0.3'
.\gradlew.bat :1.21.1-neoforge:build --offline
```

In game: press K, click Settings, then check the list scrolls, a toggle and a stepper edit persist in
`config/howtogo-client.toml`, Reset restores the defaults, and switching Interface theme between
**Dark** and **Light** recolours the screen without a restart.
