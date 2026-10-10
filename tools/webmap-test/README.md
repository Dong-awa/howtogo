# Browser map frontend checks

Runs the browser map's JavaScript outside a browser. Nothing here is part of the mod:
`tools/` is outside the source set, so the jar is unaffected and nothing in `src/` depends
on it. The tests need no packages -- Node's own `require` and `assert`-by-hand -- because
the map's four modules are deliberately DOM-free and load under Node unchanged.

The point of the file layout is this: `model.js`, `geom.js`, `labels.js` and `render.js`
never touch `document` or `window`, so the projection, the payload normalisation, the
label placement and the whole occlusion order can be asserted here, headlessly. Only
`app.js` touches the DOM, and it is booted here against a fake document -- fake `fetch`,
fake `html2canvas`, fake canvas -- so the export path is exercised too.

## Running it

```powershell
node tools\webmap-test\run.js
```

Prints one `ok`/`FAIL` line per check, then `ALL OK (n checks)` or
`FAILED: n of m checks`, and exits non-zero when anything failed.

### Against a real network

The server's own payload can be fed in as the fixture. Everything structural still has to
hold; the checks that assert the *bundled* fixture's shape (a bridge over a tunnel, all six
classes, both one-way directions, its exact stats) are skipped, because a real world is
allowed to look different.

Two ways to get one, neither of which needs the page to be open:

```powershell
# 1. from the game's own server, with HowToGo running and the map started
curl.exe -s http://127.0.0.1:7573/api/roads -o roads.json
node tools\webmap-test\run.js roads.json

# 2. without a game at all: the regression harness writes the payload its own fixture network
#    serialises to, which is the same serialiser the server uses
powershell -ExecutionPolicy Bypass -File tools\routing-harness\run-fast.ps1 -WriteWebMapFixture roads.json
node tools\webmap-test\run.js roads.json
```

The second is the one to run when the *contract* changes: it is the only check that the
Java serialiser and the page's parser still agree, and it fails loudly (rather than at
runtime, as a blank map) if a field is renamed on one side.

A path that cannot be read is reported as a failed check and the run continues against a
small built-in network, so a typo in the path does not hide everything else.

## What is covered

| Area | What it pins |
|---|---|
| `geom.js` | Fit-to-bounds centres the content and picks the scale that fits both axes; world -> screen -> world round-trips within 1e-6; zoom clamps at both ends and keeps the world point under the cursor; visible bounds match the viewport corners; zero-size and empty bounds do not divide by zero; arc-length walking, nearest-point-on-polyline and upright text angles |
| `model.js` | An absent, null or whitespace name is unnamed rather than the string `"null"`; an unknown road class becomes grey at width 4 and still reaches the legend; a missing class table falls back to the built-in six; empty networks, missing `points`, one-point segments and layers outside -32..32 are all survivable; Chinese names and names containing `<b>"&` pass through untouched; a `{"error": "..."}` body is an error, not an empty map |
| `labels.js` | The crowded case places labels with zero overlapping padded boxes, every box wholly inside the viewport, the majority still placed, and byte-identical results across two runs; a lone label takes its primary anchor; a place beats a road and a highway beats a path for a contested position; an upside-down angle is flipped into (-90, 90]; a label with nowhere to go is dropped rather than overlapped; 5000 labels finish quickly |
| `render.js` | Road paint is grouped by storey, ascending, with no lower storey painting over a higher one; inside a storey every casing precedes every fill, and all of a storey's casings precede all of its fills; arrowheads exist only for one-way roads, are drawn after their own storey's roads, and point the way travel goes; junction dots, then place markers, then labels -- every label haloed before it is filled; the class and storey filters remove exactly their own roads; a 2x export through the same `drawMap` sees the same world extent, paints the same segments in the same order and places exactly the same labels at exactly twice the position and size -- and, because every pixel measurement scales with `resolutionScale`, draws the same arrowheads at twice their pixels, the same road widths, the same marker radii and the same grid step over the same ground |
| `index.html` / `style.css` | Assets are referenced by the absolute paths the server serves, in dependency order; a data-URI favicon so no icon request is made; no inline event handler attributes; classic scripts only, no ES module syntax; no `oklch()`/`color-mix()` anywhere, because html2canvas 1.4.1 throws on them instead of degrading |
| `app.js` | Boots, fetches `/api/roads` once and draws the network; the canvas is sized to the CSS box times the device pixel ratio; one filter row per class and per storey, with counts; a class filter repaints without that class; the hover readout names the road under the cursor with its class, direction, storey and the piece's length, in that order; the auto-refresh toggle polls every 5000 ms, a tick re-fetches, and switching it off clears the timer; the export renders through `html2canvas(host, {scale: 1, backgroundColor: '#101418', logging: false})`, wraps the map with a caption and a legend, removes the temporary container again, and downloads `howtogo-<dimension>-<stamp>.png`; a throwing or missing html2canvas falls back to the offscreen canvas and says so; a 503 shows the server's message and leaves the last good map on screen; an empty network is explained and can still be exported |

The fake document throws if anything assigns `innerHTML`, so the rule that user-authored
text (road and place names) only ever reaches the page through `textContent` is enforced by
the harness rather than by review.

## What it cannot check

A real browser. The checks above run against a recording 2D context and a fake DOM, so they
pin *what is drawn and in what order* and *what the app does*, but not what the pixels look
like: fonts, `measureText` in a real browser, the composited PNG, device-pixel-ratio
scaling, and whether html2canvas 1.4.1 renders the caption container as intended all need a
page load. The `render.js` checks are written so that a real browser's metrics cannot change
their outcome -- every label decision is a multiple of the font size and no threshold is an
absolute pixel count -- which is what also makes the 2x export match the screen.

### Seeing it in a browser

`tools\webmap\run.ps1` serves the real page, the real scripts and the real vendored
html2canvas out of a real HTTP server, against a made-up city and without starting a game:

```powershell
powershell -ExecutionPolicy Bypass -File tools\webmap\run.ps1 -Port 7599 -Seconds 600
```

That is how the page and the export button were checked while this was written: the map
rendered with haloed rotated labels and no overlaps, the storey and class lists matched the
network, and 导出图片 downloaded a PNG through html2canvas (the toast said "含标题、统计与
图例", not the fallback's message). A browser driven over the DevTools protocol --
`--headless=new --remote-debugging-port`, `Page.setDownloadBehavior`, then
`document.getElementById('btn-export').click()` -- is enough to make that a scripted check
that leaves a PNG in a directory to be looked at.

## fixture.json

`fixture.json` mirrors the `/api/roads` payload exactly, field for field, and is built to
exercise everything the renderer has a rule about:

* all six road classes, with the contract's colours and widths;
* 52 segments, 49 of them named, in Chinese and English, several of them long;
* a bridge at `layer: 1` crossing a surface road at a right angle with **no node** where
  they meet -- the case the casing/fill two-pass exists for -- and a tunnel at `layer: -1`
  under two surface roads;
* `FORWARD` and `BACKWARD` one-way roads, including one with `from`/`to` of `-1` so the
  fallback orientation is exercised, and one whose polyline is stored against its travel
  direction;
* a 12-by-12 named street grid, plus diagonals, as the dense area the crowded-label check
  needs;
* POIs covering all four `placeKind` values, an unnamed POI, and `JUNCTION`/`ENDPOINT`/`POI`
  nodes;
* unnamed roads (one with no `name` key at all, one with an explicit `null`), and bounds and
  stats that the checks recompute from the geometry -- so a careless edit to either is a
  failing check rather than a quietly wrong map.

## Adding a case

`run.js` is plain JavaScript. Add an `ok('what it pins', condition, detail)` inside the
relevant `section(...)`, or a new `section('name', () => { ... })` for a new area. A throw
inside a section is reported as a failed check and the run continues, which is what makes
`ok` calls safe to write without defensive wrapping.
