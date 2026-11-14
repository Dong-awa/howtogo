# Road categories and signs

A road in this mod has always had a **class** (`RoadClass`: highway, road, path, rail, water,
ice). That answers how wide it is drawn and how fast one may travel on it, and it is shared by every
road of that kind in the world.

A **category** is the level below the class: which kind of highway or street this particular road is.
Every class has exactly one built-in category, **未分类 / Not classified**, which is what a road is
until someone classifies it; everything else is the player's to define. A road stores which category
it is plus its **number**, which is free text (`G318`, `S12`, `一环`, `3`).

## The built-in category, and the player's own

Each class starts with only the built-in category. It cannot be renamed, recoloured or deleted: it is
the answer every unclassified road gives, so there is nothing to make it mean anything else.

Everything else is player-made, per class, on the category page (see below): **add** a category,
**delete** one, **rename** it, and **pick its colour** with a colour picker that also takes HEX.

The category's colour is **not** the road's display colour. Road lines are drawn in the class colour
(`road_colors` in the client config); the category colour only paints the sign card. Changing one
never changes the other.

## Editing a road

The road editor is the panel opened with **N** on a road (see `RoadNameScreen`). It edits five things
in one visit:

- **name** -- as before;
- **storey** -- as before;
- **category** -- a stepper under the storey box: click its left half to step back through the road's
  class, its right half to step forward; the mouse wheel over it steps; the Up/Down keys step. It
  cycles the class's categories (built-in first, then the player's, wrapping);
- **number** -- a free-text field (24 characters at most), **enabled only under a real category**.
  While the road is 未分类 the field is simply disabled (no note under it): an unclassified road wears
  no plate, so there is nothing for a number to go on. Committing an unclassified edit clears the
  stored number.
- **direction** -- a stepper at the foot of the panel: 双向 / 单行道（顺向）/ 单行道（逆向）, stepped by
  its two halves or by the mouse wheel. It is the same three-state cycle the map's **O** key walks, and
  what it writes is the direction of the **whole street**, not of the piece under the cursor: the pieces
  of a bent road may be stored pointing opposite ways, and the editor orients each piece along the
  street's own walk (see `RoadEditor#setChainDirection`).

`Done` (or Enter) writes all five values onto every piece of the selected road, as **one undo step**.
The storey is also remembered as the one the next road is drawn on, as it always was.

## The sign card on the map

When the display mode asks for a sign and a road has both a real category and a number, each label
position along it gets a small card **centred on the road** (where its name would sit, not above it):

- the card's fill is the category colour, with a one-pixel border in the same contrasting ink as the
  text; the ink itself (white or near-black) is chosen from the fill's luminance, because a player may
  pick any colour;
- the number is centred in it; in mixed mode the road's own name is drawn under the number. The name
  has a **minimum size**: when it would have to be shrunk past that floor to fit a card sized by a
  short number, the **card is widened** instead and the number stays centred with the padding either
  side -- a name is never made unreadable to keep the plate narrow;
- the card is **upright**, not rotated with the road: a name belongs to the line and is turned along
  it, a sign is an object on the road;
- cards take their boxes on the same collision board/placed list as names, so a card and a name never
  cover each other, and cards are dropped where they would overlap;
- a road with no number, no category, or a category that has been deleted gets **no card** -- it falls
  back to its name (or nothing) like any other label.

The card is drawn on every map in the mod that labels roads: **Xaero's World Map** (through
`RoadElementRenderer`), the **destination picker's own map** (`DestinationScreen`), the **destination
page on MCphone** (`compat/mcphone/DestinationPickerPage`) and the **main screen's map** (K,
`MainScreen`). The navigation HUD's mini-map draws no road labels and therefore no cards. The browser
map is not covered yet: it has its own renderer, separately from every in-game surface.

The world map's card scales with the map's zoom (0.7 of the label zoom, clamped to 0.5..0.8); the
three screens whose maps draw labels at a fixed size -- the destination picker, the phone page and the
main screen -- draw the same card at about 0.72 of the font size, so it is the same small object on
all of them. In mixed mode a name that would need shrinking below 0.7 widens the card instead.

### Spacing and zoom on the fixed-size maps

A map whose labels are all drawn at one size has no zoom of its own to tell it when a label is too
crowded or too big, so two rules are constants (`RoadSigns`) rather than a function of the scale:

- **Clearance.** Every label on the picker and on the K screen reserves its painted rectangle grown by
  `LABEL_CLEARANCE_PX` (3 px; the world map keeps 2), so two plates -- or a plate and a name -- stand a
  visible gap apart instead of touching and reading as one band of colour;
- **Zoom gate.** Past `LABEL_MIN_SCALE` (0.22 px per block, the zoom at which ordinary roads stop being
  drawn) a classified road shows its **name** instead of its plate: a card keeps its pixel size however
  far out the map is, so beyond that zoom the plates would cover the roads the player zoomed out to
  see. The road is not left unlabelled -- it falls back to the name, the same fallback a road with no
  number already gets;
- **Name size.** On the K screen a road's name is drawn no taller than the road under it
  (`RoadSigns.nameFactor`): a word taller than its street reads as a word standing beside it. Since
  that map's strokes are a fixed two to six pixels wide, the cap has a floor of 0.55 so the narrow
  classes keep a readable name at every zoom.

### What a road's name has to fit

A name is written along its road, so it may not be wider than the road it names -- otherwise it hangs
past both ends and reads as a label for whatever is beside it. Both the K screen and the picker
therefore measure the **chain's drawn length in pixels** (walked along its vertices, not the straight
line between its ends) and write the name only when it fits with `ROAD_NAME_END_MARGIN` (4 px) of road
showing at each end. Zooming out takes the names away in the order the roads become too short to hold
them, instead of leaving words sticking out of the strokes.

And a name near the map's edge is **cut by the frame** rather than dropped: the label is kept as long
as its anchor -- the point on the road -- is inside the map, and the drawing is clipped to the map's
rectangle for the pass. Before that, a label whose last letter met the border vanished whole and came
back one zoom step later, which read as flicker rather than as a rule.

The picker keeps one further rule of its own: it writes **nothing** when the map is showing more than
`NAME_MAX_VIEW_BLOCKS` (900 blocks) across -- the whole trip seen end to end, which is the reading the
picker is for. The limit is in blocks rather than in pixels per block because that map is a fraction of
the K screen's size, so one number for pixels per block would mean a village on one and a region on the
other. The phone page needs neither the gate nor the clearance: it already suppresses all of its labels
below 0.5 px per block, and its map area is small enough that the gate is the crowding rule.

## The display-mode setting

**Settings -> Navigation HUD -> Map display mode** (`map_display_mode`) decides where the navigation
map is drawn; the road-label choice next to it is **Settings -> Appearance -> Road labels**
(`road_label_style` in `config/howtogo-client.toml`, `howtogo-client.json` on Fabric; also in the
NeoForge config screen):

| Value | What a road shows |
| --- | --- |
| `name` (default) | its name, as the mod always drew it |
| `sign` | the sign card when the road has a category and a number, otherwise its name |
| `mixed` | one card with the number and the name inside it, otherwise its name |

`name` is the default so an existing install and an existing world look exactly as they did: no road
has a category or a number until the player adds both, and an unclassified road is drawn the same way
in all three modes.

## The category editor

**Settings -> Appearance -> Road categories** opens `RoadCategoryScreen`, a band per class:

- the built-in 未分类 row is read-only: a fixed grey swatch and its name, no field, no delete;
- each player-made category has a colour swatch, a name field and a delete button ("×", which asks for
  a second press to confirm). Deleting a category also puts every road carrying it back to 未分类, so
  no road keeps a reference to something that no longer exists;
- **Add category** at the end of each class group creates one and rebuilds the rows;
- names are saved by `Done`, and by leaving the page; `Cancel` is the one ending that drops them.

Clicking a colour swatch opens `RoadCategoryColorScreen`: a large preview with its hex printed in
contrast ink, a **palette grid** of clickable colour swatches (a hue wheel against a light-to-dark
ladder, a grey row, and the categories' default colours as a "common colours" row), and a HEX field
that accepts `#RRGGBB` / `RRGGBB` and stays in sync with the grid. `Done` applies the colour to that
category immediately.

The definitions live in `config/howtogo/road-categories.json`:

```json
{
  "version": 2,
  "categories": {
    "ROAD": [ { "id": "c1", "name": "国道", "color": "#FFC0392B" } ]
  },
  "nextId": { "ROAD": 2 }
}
```

Only player-made categories are written; the built-in one is implicit, so the file is empty until the
first category is made. `id` is opaque and never reused (`nextId` keeps the counter), which is what
stops a deleted category from coming back to life on an old road.

## Storage and compatibility

In the road network file, each segment has two optional fields beside `roadClass`:

```json
{ "id": 12, "roadClass": "ROAD", "category": "c1", "number": "G318", ... }
```

- `category` is the category's opaque id (`none` for the built-in one);
- `number` is the text as typed, written only when there is one.

Both are read tolerantly, like every other optional field in that format: a file written before they
existed loads every road as unclassified and unnumbered. An id that the category store does not
define -- its category was deleted, or the file was hand-edited -- keeps its reference in the save but
is **drawn and edited as 未分类**, so a missing category can never paint a sign nothing describes.
The format version is unchanged, because an older build reading such a file ignores the two fields
and loses nothing else.

The class is still what a re-class means: changing a road's class moves its category to the new
class's built-in one (a category is defined per class), and keeps its number.

## Where the code is

| Class | Responsibility |
| --- | --- |
| `road/RoadCategory` | the reference: (class, opaque id), the built-in `none` per class, equality |
| `road/RoadSegment` | `category()` / `setCategory()` (clamped to the segment's class), `number()` / `setNumber()`, `hasSign()` (a number under a real category); both copied by `copy()` (undo) |
| `road/RoadStorage` | the two fields in `SegmentDto`, written always and read tolerantly |
| `road/RoadEditor` | `setRoadDetails(segmentId, name, layer, category, number)`: the whole panel's edit over the whole selected road, one undo step |
| `client/RoadCategoryStore` | the player's categories per class (add / remove / rename / recolour), the built-in one, its JSON file |
| `client/RoadLabelStyle` | the three display modes |
| `client/RoadSigns` | the card's shared proportions and its contrast rule (the ink is chosen from the colour it paints) |
| `client/RoadNameScreen` | the road edit panel's category stepper and number field |
| `client/RoadCategoryScreen` | the category editor: rows, add/delete, scrolling |
| `client/RoadCategoryColorScreen` | the colour picker: palette grid, HEX field, preview |
| `client/MapLabels` | `fillOnMap`, the one rectangle fill both graphics eras accept |
| `client/RoadElementRenderer` | the sign card on Xaero's World Map (label pass, collision board, card scaled with the zoom) |
| `client/DestinationScreen` | the same card on the destination picker's map (fixed-size map: scaled card) |
| `client/MainScreen` | road names and sign cards on the K screen's map, in the same text pass as the place names |
| `compat/mcphone/DestinationPickerPage` | the same card on the phone's destination page (NeoForge builds with the phone mod present) |
| `RoadConfig` (both arms) | `road_label_style` and its setter |
| `assets/howtogo/lang/*.json` | `howtogo.road_category.*` and the settings/chrome text |

## Verifying it

```
# type-check the mod and run the headless checks (includes the road-category check)
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\routing-harness\run-fast.ps1

# build the active node
$env:JAVA_HOME='C:\Users\hzz12\.jdks\ms-25.0.3'
.\gradlew.bat :1.21.1-neoforge:build --offline
```

In game: press N on a road, step the category, type a number, press Done (and check that 未分类 simply
disables the number field); then check the three display modes in Settings -> Appearance -> Road
labels on Xaero's World Map, the destination picker's map, the main screen's map and the phone page;
add, rename, recolour (palette grid or HEX) and delete a category in Settings -> Appearance -> Road
categories; and `/howtogo selftest` reports the storage round trip including each road's category and
number.
