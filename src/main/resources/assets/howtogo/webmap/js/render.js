/*
 * render.js -- one function draws the whole map, and everything that decides what
 * covers what lives in it.
 *
 * The occlusion rules are the point of this file, so they are worth stating once, in
 * the order the code applies them:
 *
 *   1. Storeys are painted bottom-up: every storey's roads are drawn before the next
 *      one's, so a bridge at layer 1 covers the road at layer 0 it crosses, and a
 *      tunnel at layer -1 is covered by it. Nothing from a lower storey is ever
 *      allowed to be painted after a higher one.
 *   2. Inside one storey there are two passes, not one: every segment's *casing* (a
 *      dark outline two pixels wider than the road) first, then every segment's fill.
 *      That is what makes a crossing read as one road passing over another instead of
 *      two colours smeared into each other -- the upper road's fill cuts the lower
 *      road's casing cleanly in two, the way a real junction looks. Doing it per
 *      segment instead would leave the lower road's casing drawn across the upper
 *      road wherever the two happened to be in the wrong order.
 *   3. Within a pass the order is by nominal class width, then by id. Nominal width,
 *      not pixels, so the paint order is identical at every zoom -- including the 2x
 *      export, which must produce the same picture as the screen. Thicker roads are
 *      painted later and therefore win a crossing, which is the same instinct as
 *      giving a highway the first pick of label positions.
 *   4. One-way arrows follow their storey's fills, so a bridge still covers the arrows
 *      on the road beneath it. Junction dots, then place markers, then the corner badge
 *      on a favourite, then text come after all road paint: a name is the last thing to
 *      be covered and the first thing to be read.
 *
 * The export is the same picture, bigger. Everything here that is measured in pixels
 * rather than in blocks is multiplied by `resolutionScale` (the road-width floor, the
 * casing, arrowheads and their spacing, marker radii, the cull margin, the grid's
 * minimum spacing, the banner's font), and the label font is scaled by it too -- so a
 * 2x export draws the screen's map at twice the size instead of drawing a map with a
 * denser grid, half-size markers and twice the arrowheads per block. The harness
 * asserts that: the same roads in the same order, the same arrowheads, the same labels
 * at exactly twice the box.
 *
 * Everything is drawn through the 2D context the caller supplies -- no DOM, no
 * globals, nothing that needs a browser -- so the Node harness can pass in a recording
 * stub and assert the order above, and the export path can pass in the offscreen
 * canvas and get exactly the same picture.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HowToGoMap = Object.assign(root.HowToGoMap || {}, api);
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // --- palette -------------------------------------------------------------------
  //
  // Two palettes, one set of roles. Everything here is a colour the map uses to be *read* -- the
  // backdrop, the grid, the casing that separates crossing roads, the ink of a label, an arrow or
  // a message, the fill and ring of a marker -- and those are exactly the things whose right value
  // depends on what they sit on. Road class colours are deliberately not here: they are the
  // player's configured colours, carried in the payload, and a theme that recoloured them would be
  // overwriting data.
  //
  // The dark palette is the one the map has always drawn, literal for literal, because the white
  // style is an addition to it rather than a reason to restyle it. The light palette is Google
  // Maps' light map -- land #F1F3F4, hairline #DADCE0, road names #5F6368, place names #3C4043 --
  // which is where the game's own white theme comes from, so a browser map in the light style is
  // the same map the game draws on its own screens.
  //
  // Plain hex on both sides, because of the export: html2canvas 1.4.1, which the PNG download
  // runs on, parses these into its own colour parser and throws on oklch()/color-mix() rather than
  // degrading.
  //
  // CASING_COLOR and MARKER_OUTLINE are deliberately different shades in the dark palette: the
  // harness tells the drawing passes apart by stroke colour, and an outline sharing the casing's
  // colour would look like a road. The light palette keeps them apart for the same reason -- a
  // casing is a hairline against the pale land, while an outline is the ring that separates a
  // marker from whatever is under it, which on a light map is white.
  const PALETTES = {
    dark: {
      background: '#101418',
      grid: '#1b2430',
      axis: '#2c3a4a',
      casing: '#0b0f14',
      markerOutline: '#05070a',
      // The fill of a road end's dot. Near-black in both palettes, but not the same role's
      // near-black: the dark map has always filled it with the outline colour, and the light map
      // cannot, because a white dot on #F1F3F4 land is not a dot. There it takes the text grey.
      endpoint: '#05070a',
      arrow: '#0b0f14',
      labelRoad: '#E8F1FA',
      labelPlace: '#FFE7A8',
      message: '#9FB0C0',
      marker: '#E8EDF2',
      // The corner badge on a favourite place. Amber, because the favourite star is amber in the
      // game's own interfaces and a marker is read before its label.
      favorite: '#FFC857',
    },
    light: {
      background: '#F1F3F4',
      grid: '#DADCE0',
      // One step darker than the grid: the grid is a hint where a coordinate falls, the world
      // origin is a line the eye has to find.
      axis: '#BDC1C6',
      casing: '#DADCE0',
      markerOutline: '#FFFFFF',
      endpoint: '#3C4043',
      // Darker than the light palette's casing on purpose: an arrow sits on a road's own
      // saturated data colour, where a pale hairline would vanish, not on the pale land.
      arrow: '#3C4043',
      labelRoad: '#5F6368',
      labelPlace: '#3C4043',
      message: '#5F6368',
      marker: '#3C4043',
      // Google's dark amber, the one that still reads as a star on a white sheet.
      favorite: '#F9AB00',
    },
  };

  /*
   * The palette in force.
   *
   * A module-level variable rather than a drawing option, because the theme is not a property of
   * one drawing: it is what the map is currently drawing in, and drawMap is called again on every
   * pan, zoom, resize and export with options that have nothing to say about it. setTheme swaps the
   * variable and the next drawing picks it up, which is the whole of the mechanism.
   */
  let palette = PALETTES.dark;

  const MESSAGE_FONT_PX = 15;

  const BASE_LABEL_FONT_PX = 13;
  const LABEL_HALO_PX = 3;
  const LABEL_PADDING_PX = 1.5;

  const CASING_EXTRA_PX = 2;
  const MIN_ROAD_PX = 1.75;
  const UNKNOWN_WIDTH = 4;

  const JUNCTION_RADIUS_PX = 2.6;
  const ENDPOINT_RADIUS_PX = 1.5;
  const POI_RADIUS_PX = 5.5;

  const ARROW_SPACING_PX = 56;
  const ARROW_MIN_PATH_PX = 22;
  const ARROW_MAX_PER_SEGMENT = 24;
  const ARROW_TIP_PX = 5;
  const ARROW_BACK_PX = 2.5;
  const ARROW_HALF_WIDTH_PX = 3.4;

  /*
   * Candidate order for a place label: below the marker first (a name under a pin is
   * what every map does), then above, right, left, then the four diagonals, and each
   * direction at two increasing distances so a name whose first slot is taken can step
   * out past the label that took it. Below-before-above matters: it keeps the text
   * clear of the road on the far side of the marker.
   */
  const PLACE_DIRECTIONS = [
    [0, 1], [0, -1], [1, 0], [-1, 0],
    [1, 1], [-1, 1], [1, -1], [-1, -1],
  ];

  /*
   * A long road gets more than one name: one per this many pixels of visible length, up
   * to a ceiling. A screen distance rather than a block distance, because it is the
   * screen the names are read from -- zoomed out, a fixed block spacing would put the
   * names on top of each other; zoomed in, it would leave the far half of a visible road
   * nameless. Both are multiplied by the render's pixel ratio, so the export names the
   * same places the screen did.
   */
  const LABEL_SPACING_PX = 190;
  const MAX_LABELS_PER_ROAD = 8;

  /*
   * The smallest font at which a road's name is still worth drawing, in CSS pixels. A
   * road's name may not be taller than the road itself, so a road too thin for a legible
   * word gets no name until the player zooms in far enough for it to be wide enough --
   * which is what makes names appear class by class, the highway first and the footpath
   * last. A road too short to hold the name at all is dropped by the group length check
   * in {@link buildRoadLabelItems}, in pixels like this one.
   */
  const MIN_ROAD_LABEL_FONT_PX = 6;

  /*
   * The zoom at which each kind of place's *name* appears, in CSS pixels per block.
   * Markers come earlier and are a separate question: a dot says "something is here" when
   * the map is far out, while a name has to be readable. A shop, the commonest and least
   * important, waits longest; a resource point is named first.
   */
  const PLACE_LABEL_MIN_SCALE = {
    SHOP: 1.0,
    STATION: 0.7,
    PLACE: 0.55,
    RESOURCE: 0.4,
  };

  const GRID_STEPS = [16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192];
  const GRID_MIN_PX = 48;
  const GRID_MAX_LINES = 400;

  const FONT_STACK = '"Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", sans-serif';

  /*
   * The renderer needs the projection, the label placer and the class table. In the
   * browser they are already on the shared namespace (index.html loads the files in
   * dependency order); under Node they come from require(). Resolving lazily keeps the
   * file loadable in either host with no build step, and keeps every module's load
   * free of side effects.
   */
  let cachedDeps = null;
  function deps() {
    if (cachedDeps) return cachedDeps;
    if (typeof module === 'object' && module.exports && typeof require === 'function') {
      cachedDeps = Object.assign({}, require('./geom.js'), require('./labels.js'), require('./model.js'));
    } else {
      cachedDeps = (typeof globalThis !== 'undefined' ? globalThis : {}).HowToGoMap || {};
    }
    return cachedDeps;
  }

  function num(value, fallback) {
    return typeof value === 'number' && isFinite(value) ? value : fallback;
  }

  function clamp(value, lo, hi) {
    return value < lo ? lo : (value > hi ? hi : value);
  }

  function toSet(value) {
    if (value instanceof Set) return value;
    if (Array.isArray(value)) return new Set(value);
    if (value && typeof value === 'object') {
      return new Set(Object.keys(value).filter((k) => value[k]));
    }
    return new Set();
  }

  /**
   * Switches the palette, and says whether it changed.
   *
   * Called by app.js with the theme the last `/api/roads` payload named. Nothing is painted here:
   * the renderer is handed a context and owns no canvas, so the caller schedules the repaint -- which
   * is also what keeps a theme change from painting over an export in progress. A word the palette
   * table does not know means the dark style, so a payload the page cannot interpret can never leave
   * it with no palette at all.
   */
  function setTheme(theme) {
    const next = theme === 'light' ? PALETTES.light : PALETTES.dark;
    if (next === palette) return false;
    palette = next;
    return true;
  }

  /** The palette in force, spelled as the payload and the page's data-theme attribute spell it. */
  function activeTheme() {
    return palette === PALETTES.light ? 'light' : 'dark';
  }

  function fontString(px, bold) {
    return (bold ? '600 ' : '') + px + 'px ' + FONT_STACK;
  }

  function normalizeOptions(options) {
    const o = options || {};
    const fontScale = num(o.fontScale, 1) || 1;
    // How many device pixels this render puts where the reference render puts one. The screen
    // draws at 1; the 2x export draws at 2, and every constant in this file that is measured in
    // *pixels* (the road-width floor, the casing, arrowheads and their spacing, marker radii, the
    // cull margin, the grid's minimum spacing) is multiplied by it. Without that, a 2x export
    // would draw the same map with a denser grid, half-size markers and twice as many arrowheads
    // per block, because those constants would stay the size they are on a 1x screen.
    const resolutionScale = num(o.resolutionScale, 1) > 0 ? num(o.resolutionScale, 1) : 1;
    return {
      background: typeof o.background === 'string' ? o.background : palette.background,
      grid: o.grid !== false,
      showRoadNames: o.showRoadNames !== false,
      showPlaces: o.showPlaces !== false,
      showArrows: o.showArrows !== false,
      hiddenClasses: toSet(o.hiddenClasses),
      hiddenLayers: toSet(o.hiddenLayers),
      fontScale,
      resolutionScale,
      px: resolutionScale,
      labelFontPx: BASE_LABEL_FONT_PX * fontScale * resolutionScale,
      labelHaloPx: LABEL_HALO_PX * fontScale * resolutionScale,
      labelPaddingPx: LABEL_PADDING_PX * fontScale * resolutionScale,
      markerColor: typeof o.markerColor === 'string' ? o.markerColor : palette.marker,
      message: typeof o.message === 'string' && o.message ? o.message : null,
      // Set by the harness to prove the two entry points really are one function.
      onPass: typeof o.onPass === 'function' ? o.onPass : null,
    };
  }

  /**
   * How wide a road is painted, in the current pixels.
   *
   * `width` in the payload is a nominal block width, so it becomes pixels by the
   * current scale -- and is then floored, because at a whole-world zoom a three-block
   * path would otherwise be a fifth of a pixel and the map would show a highway next
   * to nothing at all. The floor is in device pixels and therefore scales with the
   * render: a road that is floored on screen is floored at twice the width in the 2x
   * export rather than coming out half as thick as the roads beside it.
   */
  function roadStrokePx(nominalWidth, scale, resolutionScale) {
    const px = num(resolutionScale, 1) > 0 ? num(resolutionScale, 1) : 1;
    const w = num(nominalWidth, UNKNOWN_WIDTH) * num(scale, 1);
    return Math.max(MIN_ROAD_PX * px, w);
  }

  /**
   * The points a segment is painted from.
   *
   * The server sends the block vertices and, for a line it recognised out of another mod, a smoothed
   * display curve. The curve is what the in-game map draws; drawing the block vertices here instead
   * is what made a machine-read road a per-block staircase in the browser while it was smooth in the
   * game. Measurement and the hover readout stay on the block points, in model.js.
   */
  function drawPointsOf(segment) {
    if (!segment) return [];
    if (Array.isArray(segment.drawPoints) && segment.drawPoints.length >= 2) return segment.drawPoints;
    return Array.isArray(segment.points) ? segment.points : [];
  }

  function segmentScreenPoints(segment, view) {
    const G = deps();
    const out = [];
    const points = drawPointsOf(segment);
    if (!points.length) return out;
    for (const p of points) {
      const s = G.worldToScreen(view, p.x, p.z);
      out.push({x: s.x, y: s.y});
    }
    return out;
  }

  /** World-space bounding box of a segment, or null when it has no points. */
  function segmentBounds(segment) {
    let minX = Infinity;
    let minZ = Infinity;
    let maxX = -Infinity;
    let maxZ = -Infinity;
    for (const p of drawPointsOf(segment)) {
      if (p.x < minX) minX = p.x;
      if (p.x > maxX) maxX = p.x;
      if (p.z < minZ) minZ = p.z;
      if (p.z > maxZ) maxZ = p.z;
    }
    if (!isFinite(minX)) return null;
    return {minX, minZ, maxX, maxZ};
  }

  function boundsHit(b, minX, minZ, maxX, maxZ) {
    return b.maxX >= minX && b.minX <= maxX && b.maxZ >= minZ && b.minZ <= maxZ;
  }

  function hiddenSegment(seg, opts) {
    return !seg.drawable || opts.hiddenClasses.has(seg.roadClass) || opts.hiddenLayers.has(seg.layer);
  }

  /**
   * The segments worth drawing, bucketed by storey.
   *
   * The cull margin is 64 screen pixels' worth of blocks, so a thick road whose
   * centreline is just off screen still has its edge drawn -- and it is applied to
   * drawing only. Label candidates are filtered against the exact viewport instead, so
   * the export cannot lose a label the screen kept.
   */
  function visibleByLayer(model, view, opts) {
    const G = deps();
    const b = G.visibleBounds(view);
    // A margin of 64 reference pixels, so the same band around the viewport is kept whatever the
    // render's resolution: a thick road whose centreline is just off screen still has its edge drawn.
    const margin = (64 * opts.px) / (view.scale || 1);
    const byLayer = new Map();
    const segments = Array.isArray(model.segments) ? model.segments : [];
    for (const seg of segments) {
      if (hiddenSegment(seg, opts)) continue;
      const sb = segmentBounds(seg);
      if (!sb || !boundsHit(sb, b.minX - margin, b.minZ - margin, b.maxX + margin, b.maxZ + margin)) continue;
      let list = byLayer.get(seg.layer);
      if (!list) {
        list = [];
        byLayer.set(seg.layer, list);
      }
      list.push(seg);
    }
    return byLayer;
  }

  function pathThrough(ctx, points) {
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length; i++) ctx.lineTo(points[i].x, points[i].y);
  }

  function strokeRoad(ctx, segment, view, colour, width) {
    const pts = segmentScreenPoints(segment, view);
    if (pts.length < 2) return;
    pathThrough(ctx, pts);
    ctx.strokeStyle = colour;
    ctx.lineWidth = width;
    ctx.stroke();
  }

  /**
   * Which way travel runs along `points`.
   *
   * FORWARD means from the `from` node to the `to` node, but the contract does not
   * promise that `points` is stored in that order -- so the polyline is oriented
   * against the two endpoint nodes when they exist, and only falls back to trusting
   * the stored order when one of them is -1 and there is nothing to compare against.
   */
  function arrowTravelSign(segment, model) {
    const nodes = model && model.nodeById ? model.nodeById : {};
    const from = nodes[String(segment.from)];
    const to = nodes[String(segment.to)];
    let order = 1;
    if (from && to && segment.points.length >= 2) {
      const G = deps();
      const first = segment.points[0];
      const last = segment.points[segment.points.length - 1];
      const dFrom = Math.sqrt((first.x - from.x) * (first.x - from.x) + (first.z - from.z) * (first.z - from.z));
      const dTo = Math.sqrt((last.x - to.x) * (last.x - to.x) + (last.z - to.z) * (last.z - to.z));
      if (dTo < dFrom) order = -1;
    }
    const travel = segment.direction === 'BACKWARD' ? -1 : 1;
    return order * travel;
  }

  /**
   * Where the arrowheads on one one-way road go.
   *
   * The spacing and the minimum useful path are in reference pixels and scale with the render, so
   * the 2x export puts the same arrowheads in the same places as the screen rather than twice as
   * many of them half the size.
   */
  function arrowPlacements(segment, view, model, resolutionScale) {
    const G = deps();
    const px = num(resolutionScale, 1) > 0 ? num(resolutionScale, 1) : 1;
    const out = [];
    const pts = segmentScreenPoints(segment, view);
    if (pts.length < 2) return out;
    const total = G.polylineLength(pts);
    if (total < ARROW_MIN_PATH_PX * px) return out;
    const sign = arrowTravelSign(segment, model);
    const count = clamp(Math.floor(total / (ARROW_SPACING_PX * px)), 1, ARROW_MAX_PER_SEGMENT);
    for (let i = 0; i < count; i++) {
      // Between the ends, not on them: an arrowhead on a junction dot is unreadable.
      const t = (i + 0.5) / count;
      const p = G.pointAlong(pts, t);
      // pointAlong names the second component `z`; on screen points that is the screen
      // Y, which is what the projection makes of the world Z.
      out.push({x: p.x, y: p.z, angle: p.angle + (sign < 0 ? 180 : 0)});
    }
    return out;
  }

  function drawArrows(ctx, segment, view, model, resolutionScale) {
    if (segment.direction === 'TWO_WAY') return;
    const px = num(resolutionScale, 1) > 0 ? num(resolutionScale, 1) : 1;
    const marks = arrowPlacements(segment, view, model, px);
    if (!marks.length) return;
    // Dark ink, like the casing in the dark palette: an arrowhead in the road's own colour would be
    // invisible on the road it is painted on. The light palette's casing is a pale hairline that
    // would vanish on a saturated road fill too, so there the arrow takes the near-black text grey
    // instead -- it sits on the road's data colour, not on the pale land.
    ctx.fillStyle = palette.arrow;
    for (const mark of marks) {
      const rad = mark.angle * Math.PI / 180;
      const dx = Math.cos(rad);
      const dy = Math.sin(rad);
      const nx = -dy;
      const ny = dx;
      ctx.beginPath();
      ctx.moveTo(mark.x + dx * ARROW_TIP_PX * px, mark.y + dy * ARROW_TIP_PX * px);
      ctx.lineTo(mark.x - dx * ARROW_BACK_PX * px + nx * ARROW_HALF_WIDTH_PX * px,
        mark.y - dy * ARROW_BACK_PX * px + ny * ARROW_HALF_WIDTH_PX * px);
      ctx.lineTo(mark.x - dx * ARROW_BACK_PX * px - nx * ARROW_HALF_WIDTH_PX * px,
        mark.y - dy * ARROW_BACK_PX * px - ny * ARROW_HALF_WIDTH_PX * px);
      ctx.closePath();
      ctx.fill();
    }
  }

  /**
   * One storey: casings, then fills, then the arrows that go with the fills.
   *
   * The arrows stay inside the storey rather than being deferred to the end of the
   * map, so the bridge over them still covers them. Within a pass the sort is nominal
   * width then id -- see the note at the top of the file.
   */
  function drawStorey(ctx, segments, view, model, opts) {
    const sorted = segments.slice().sort((a, b) => {
      const aw = num(a.klass && a.klass.width, UNKNOWN_WIDTH);
      const bw = num(b.klass && b.klass.width, UNKNOWN_WIDTH);
      if (aw !== bw) return aw - bw;
      return (num(a.id, 0) - num(b.id, 0));
    });
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const seg of sorted) {
      strokeRoad(ctx, seg, view, palette.casing,
        roadStrokePx(seg.klass.width, view.scale, opts.px) + CASING_EXTRA_PX * opts.px);
    }
    for (const seg of sorted) {
      strokeRoad(ctx, seg, view, seg.klass.color,
        roadStrokePx(seg.klass.width, view.scale, opts.px));
    }
    if (opts.showArrows) {
      for (const seg of sorted) drawArrows(ctx, seg, view, model, opts.px);
    }
  }

  function drawGrid(ctx, view, opts) {
    const G = deps();
    const b = G.visibleBounds(view);
    let step = GRID_STEPS[GRID_STEPS.length - 1];
    // The minimum spacing is in reference pixels, so the export gets the same grid in world terms
    // rather than one twice as dense.
    for (const s of GRID_STEPS) {
      if (s * view.scale >= GRID_MIN_PX * opts.px) {
        step = s;
        break;
      }
    }
    const xs = [];
    for (let x = Math.ceil(b.minX / step) * step; x <= b.maxX && xs.length < GRID_MAX_LINES; x += step) xs.push(x);
    const zs = [];
    for (let z = Math.ceil(b.minZ / step) * step; z <= b.maxZ && zs.length < GRID_MAX_LINES; z += step) zs.push(z);

    if (xs.length + zs.length <= GRID_MAX_LINES) {
      ctx.beginPath();
      for (const x of xs) {
        const sx = G.worldToScreen(view, x, 0).x;
        ctx.moveTo(sx, 0);
        ctx.lineTo(sx, view.height);
      }
      for (const z of zs) {
        const sy = G.worldToScreen(view, 0, z).y;
        ctx.moveTo(0, sy);
        ctx.lineTo(view.width, sy);
      }
      ctx.strokeStyle = palette.grid;
      ctx.lineWidth = 1 * opts.px;
      ctx.stroke();
    }

    // The world origin, so an empty map still has something to orient against.
    const origin = G.worldToScreen(view, 0, 0);
    const hasV = origin.x >= 0 && origin.x <= view.width;
    const hasH = origin.y >= 0 && origin.y <= view.height;
    if (hasV || hasH) {
      ctx.beginPath();
      if (hasV) {
        ctx.moveTo(origin.x, 0);
        ctx.lineTo(origin.x, view.height);
      }
      if (hasH) {
        ctx.moveTo(0, origin.y);
        ctx.lineTo(view.width, origin.y);
      }
      ctx.strokeStyle = palette.axis;
      ctx.lineWidth = 1 * opts.px;
      ctx.stroke();
    }
  }

  function drawDot(ctx, x, y, radius, colour, resolutionScale) {
    const px = num(resolutionScale, 1) > 0 ? num(resolutionScale, 1) : 1;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.closePath();
    ctx.fillStyle = colour;
    ctx.fill();
    ctx.strokeStyle = palette.markerOutline;
    ctx.lineWidth = 1 * px;
    ctx.stroke();
  }

  function drawDiamond(ctx, x, y, radius, colour, resolutionScale) {
    const px = num(resolutionScale, 1) > 0 ? num(resolutionScale, 1) : 1;
    ctx.beginPath();
    ctx.moveTo(x, y - radius);
    ctx.lineTo(x + radius, y);
    ctx.lineTo(x, y + radius);
    ctx.lineTo(x - radius, y);
    ctx.closePath();
    ctx.fillStyle = colour;
    ctx.fill();
    ctx.strokeStyle = palette.markerOutline;
    ctx.lineWidth = 1.2 * px;
    ctx.stroke();
  }

  /**
   * A five-pointed star, for the corner badge on a favourite place.
   *
   * <p>Drawn as a filled path rather than with a glyph: the canvas font is the map's own, and a
   * Unicode star would depend on that font having it -- the export and the screen would then be able
   * to disagree about whether a favourite is marked. Ten points, alternating outer and inner radius,
   * in the palette's own plain hex, so html2canvas has nothing to parse.
   */
  function drawStar(ctx, x, y, outer, inner, colour, resolutionScale) {
    const px = num(resolutionScale, 1) > 0 ? num(resolutionScale, 1) : 1;
    ctx.beginPath();
    for (let i = 0; i < 10; i++) {
      const radius = i % 2 === 0 ? outer : inner;
      const angle = -Math.PI / 2 + (i * Math.PI) / 5;
      const sx = x + Math.cos(angle) * radius;
      const sy = y + Math.sin(angle) * radius;
      if (i === 0) ctx.moveTo(sx, sy);
      else ctx.lineTo(sx, sy);
    }
    ctx.closePath();
    ctx.fillStyle = colour;
    ctx.fill();
    ctx.strokeStyle = palette.markerOutline;
    ctx.lineWidth = 1 * px;
    ctx.stroke();
  }

  /**
   * Junction dots, then place markers, then the corner badge on a favourite.
   *
   * Nodes carry no storey in the payload, so markers cannot join the per-storey
   * grouping; they are drawn after every storey's roads instead. A dot is a point the
   * map is read against, and burying one under a bridge would be worse than drawing an
   * underground junction over it. Their size is in reference pixels, so a 2x export
   * shows markers as large, relative to the roads, as the screen does.
   *
   * <p>Place markers come from `model.places` and not from the POI nodes: a place is a
   * destination -- a station, a waypoint, a landmark with a description -- and the version-3
   * payload says which ones there are. The badge pass runs after every diamond, so a badge is
   * never covered by a neighbouring marker; it is the last mark before the names.
   */
  function drawMarkers(ctx, model, view, opts) {
    const G = deps();
    const M = deps();
    const px = opts.px;
    const pad = POI_RADIUS_PX * px + 2;
    const nodes = Array.isArray(model.nodes) ? model.nodes : [];
    const onScreen = (s) => s.x >= -pad && s.x <= view.width + pad && s.y >= -pad && s.y <= view.height + pad;
    for (const node of nodes) {
      if (node.type === 'POI') continue;
      const s = G.worldToScreen(view, node.x, node.z);
      if (!onScreen(s)) continue;
      if (node.type === 'ENDPOINT') {
        drawDot(ctx, s.x, s.y, ENDPOINT_RADIUS_PX * px, palette.endpoint, px);
      } else {
        drawDot(ctx, s.x, s.y, JUNCTION_RADIUS_PX * px, opts.markerColor, px);
      }
    }
    const places = Array.isArray(model.places) ? model.places : [];
    for (const place of places) {
      const s = G.worldToScreen(view, place.x, place.z);
      if (!onScreen(s)) continue;
      drawDiamond(ctx, s.x, s.y, POI_RADIUS_PX * px, M.placeColor(place.kind), px);
    }
    for (const place of places) {
      if (!place.favorite) continue;
      const s = G.worldToScreen(view, place.x, place.z);
      if (!onScreen(s)) continue;
      // Up and to the right: clear of the diamond, and the direction a corner badge is read from.
      const bx = s.x + POI_RADIUS_PX * px * 0.78;
      const by = s.y - POI_RADIUS_PX * px * 0.78;
      drawStar(ctx, bx, by, POI_RADIUS_PX * px * 0.62, POI_RADIUS_PX * px * 0.28,
        palette.favorite, px);
    }
  }

  /**
   * How large one road's name may be drawn, in pixels.
   *
   * <p>The name lies on the road, so its height is capped by the width the road is painted
   * at this zoom: a word taller than the street it names reads as a word standing beside the
   * street. A road too thin to hold a legible word gets no name at all -- the map is zoomed
   * out too far for names of that class to be read, and the player sees it when they zoom in.
   *
   * <p>Both quantities here are measured in the render's own pixels (the road width already
   * carries `opts.px`), so the threshold means the same thing on screen and in the 2x export.
   */
  function roadLabelFontPx(segment, view, opts) {
    const L = deps();
    const stroke = roadStrokePx(segment.klass.width, view.scale, opts.px);
    const cap = stroke / L.textHeight(1);
    return Math.min(opts.labelFontPx, cap);
  }

  /**
   * Candidates for one label sitting at a point on a line: on the line, then either side.
   *
   * <p>The side offsets move the text perpendicular to the road, which is how a name that
   * cannot sit on its road steps off it instead of jumping somewhere unrelated. They are
   * multiples of the text height, so the export doubles them along with the font.
   */
  function roadLabelCandidates(x, y, angle, fontPx) {
    const L = deps();
    const textH = L.textHeight(fontPx);
    const rad = angle * Math.PI / 180;
    const nx = -Math.sin(rad);
    const ny = Math.cos(rad);
    const out = [];
    for (const offset of [0, textH, -textH]) {
      out.push({x: x + nx * offset, y: y + ny * offset, angle});
    }
    return out;
  }

  /** Where a place's name may sit: below first, then round the marker, at two distances. */
  function placeCandidates(node, view, fontPx) {
    const G = deps();
    const L = deps();
    const s = G.worldToScreen(view, node.x, node.z);
    const textH = L.textHeight(fontPx);
    // The first distance puts the near edge of the box `gap` from the marker centre;
    // the second steps out by one more line so a name can clear the one that got there
    // first.
    const gap = fontPx * 0.55;
    const near = gap + textH / 2;
    const far = near + textH;
    const out = [];
    for (const dir of PLACE_DIRECTIONS) {
      const len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]) || 1;
      for (const distance of [near, far]) {
        out.push({
          x: s.x + (dir[0] / len) * distance,
          y: s.y + (dir[1] / len) * distance,
          angle: 0,
        });
      }
    }
    return out;
  }

  /**
   * Whether a place's name is drawn at this zoom.
   *
   * <p>Markers and names are two questions: a marker is a dot that says "something is
   * here" and is worth drawing while the map is far out, while a name has to be readable.
   * The thresholds are in CSS pixels per block (the view's scale divided by the render's
   * pixel ratio), so the 2x export names exactly the places the screen named.
   *
   * <p>Reads a normalised place's `kind`, and also the `placeKind` of a raw POI node, so the
   * function works on either the payload and on the model.
   */
  function placeLabelVisible(place, pixelsPerBlock) {
    const kind = place && (place.kind || place.placeKind);
    const from = Object.prototype.hasOwnProperty.call(PLACE_LABEL_MIN_SCALE, kind)
      ? PLACE_LABEL_MIN_SCALE[kind] : PLACE_LABEL_MIN_SCALE.PLACE;
    return pixelsPerBlock >= from;
  }

  /** The on-screen length of a run of screen points. */
  function screenPolylineLength(points) {
    let total = 0;
    for (let i = 1; i < points.length; i++) {
      const dx = points[i].x - points[i - 1].x;
      const dy = points[i].y - points[i - 1].y;
      total += Math.sqrt(dx * dx + dy * dy);
    }
    return total;
  }

  /**
   * A point a given distance along a run of screen points, with the direction there.
   *
   * <p>Screen space, unlike the world-space walk in geom: what is being measured here is
   * the part of the road the player can see, and a name is placed on that part rather
   * than on the kilometres of the road that are off the edge of the map.
   */
  function screenPointAt(points, distance) {
    if (!points || points.length < 2) return null;
    let travelled = 0;
    for (let i = 1; i < points.length; i++) {
      const dx = points[i].x - points[i - 1].x;
      const dy = points[i].y - points[i - 1].y;
      const length = Math.sqrt(dx * dx + dy * dy);
      if (length < 1.0e-9) continue;
      if (distance <= travelled + length || i === points.length - 1) {
        const along = Math.max(0, Math.min(1, (distance - travelled) / length));
        return {
          x: points[i - 1].x + dx * along,
          y: points[i - 1].y + dy * along,
          angle: Math.atan2(dy, dx) * 180 / Math.PI,
        };
      }
      travelled += length;
    }
    return null;
  }

  /**
   * The stretches of a segment that touch the viewport, as runs of screen points.
   *
   * <p>A road may cross the screen, leave it and come back. Naming it by distance along
   * its whole length would put names on the ground nobody can see; naming each visible
   * stretch separately puts them where they can be read.
   */
  function screenRuns(segment, view) {
    const points = segmentScreenPoints(segment, view);
    const runs = [];
    let current = null;
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1];
      const b = points[i];
      const visible = Math.max(a.x, b.x) >= 0 && Math.min(a.x, b.x) <= view.width
        && Math.max(a.y, b.y) >= 0 && Math.min(a.y, b.y) <= view.height;
      if (visible) {
        if (!current) current = [a];
        current.push(b);
      } else if (current) {
        runs.push(current);
        current = null;
      }
    }
    if (current) runs.push(current);
    const out = [];
    for (const run of runs) {
      const length = screenPolylineLength(run);
      if (length > 0) out.push({points: run, length});
    }
    return out;
  }

  /**
   * Names for the roads: one per stretch of roughly LABEL_SPACING_PX, not one per road.
   *
   * <p>A road long enough to cross the screen is a road whose name is useful at more than
   * its middle, so a group of same-named same-class segments is named at as many places
   * along its visible length as the spacing calls for. Only the stretches on screen count
   * -- the off-screen middle of a road is not a place to spend a name on -- and the
   * candidates are then offered to the label placer, which drops the ones that would
   * overlap something, so two names still never sit on top of each other. A name's size is
   * its road's own (see {@link roadLabelFontPx}).
   */
  function buildRoadLabelItems(model, view, opts, visible, measure) {
    const G = deps();
    const M = deps();
    const groups = new Map();
    for (const seg of model.segments) {
      if (!seg.drawable || !seg.hasName) continue;
      if (hiddenSegment(seg, opts)) continue;
      const sb = segmentBounds(seg);
      if (!sb || !boundsHit(sb, visible.minX, visible.minZ, visible.maxX, visible.maxZ)) continue;
      const runs = screenRuns(seg, view);
      if (!runs.length) continue;
      const key = seg.name + '\u0000' + seg.roadClass;
      let group = groups.get(key);
      if (!group) {
        group = {name: seg.name, representative: seg, runs: [], total: 0};
        groups.set(key, group);
      }
      for (const run of runs) {
        group.runs.push({points: run.points, length: run.length, start: group.total});
        group.total += run.length;
      }
    }

    const items = [];
    for (const group of groups.values()) {
      const fontPx = roadLabelFontPx(group.representative, view, opts);
      if (!(fontPx >= MIN_ROAD_LABEL_FONT_PX * opts.px)) continue;
      const textWidth = measure(group.name, fontPx);
      if (group.total < textWidth + opts.labelPaddingPx * 2) continue;
      const spacing = Math.max(LABEL_SPACING_PX * opts.px, textWidth + 48 * opts.px);
      const count = Math.max(1, Math.min(MAX_LABELS_PER_ROAD,
        Math.floor(group.total / spacing)));
      for (let i = 0; i < count; i++) {
        const target = group.total * (i + 0.5) / count;
        let run = null;
        for (const candidate of group.runs) {
          if (target <= candidate.start + candidate.length) {
            run = candidate;
            break;
          }
        }
        if (!run) run = group.runs[group.runs.length - 1];
        if (!run) continue;
        const onLine = screenPointAt(run.points, target - run.start);
        if (!onLine) continue;
        const angle = G.uprightAngle(onLine.angle);
        const candidates = roadLabelCandidates(onLine.x, onLine.y, angle, fontPx);
        if (!candidates.length) continue;
        items.push({
          id: 'road:' + group.representative.id + ':' + i,
          kind: 'road',
          text: group.name,
          fontPx,
          x: candidates[0].x,
          y: candidates[0].y,
          angle: candidates[0].angle,
          priority: 1 + M.classRank(group.representative.roadClass),
          candidates,
        });
      }
    }
    return items;
  }

  /**
   * The painted roads as screen-space bars, so a place's name can be kept off them.
   *
   * <p>One box per polyline edge, widened by the road's own half-width and the label
   * padding -- the same estimate the label boxes use. Built only when a place name is
   * actually being placed; a road further from every visible place than the place's
   * name can step cannot affect it, so the boxes are culled against the visible world
   * rectangle first.
   */
  function buildRoadInk(model, view, opts, visible) {
    const G = deps();
    const bars = [];
    for (const seg of model.segments) {
      if (!seg.drawable || hiddenSegment(seg, opts)) continue;
      const sb = segmentBounds(seg);
      if (!sb || !boundsHit(sb, visible.minX, visible.minZ, visible.maxX, visible.maxZ)) continue;
      const points = segmentScreenPoints(seg, view);
      if (points.length < 2) continue;
      const pad = roadStrokePx(seg.klass.width, view.scale, opts.px) / 2
        + opts.labelPaddingPx;
      for (let i = 1; i < points.length; i++) {
        const a = points[i - 1];
        const b = points[i];
        bars.push({
          minX: Math.min(a.x, b.x) - pad,
          maxX: Math.max(a.x, b.x) + pad,
          minY: Math.min(a.y, b.y) - pad,
          maxY: Math.max(a.y, b.y) + pad,
        });
      }
    }
    return {
      overlaps(box) {
        for (const bar of bars) {
          if (box.x < bar.maxX && bar.minX < box.x + box.w
            && box.y < bar.maxY && bar.minY < box.y + box.h) {
            return true;
          }
        }
        return false;
      },
    };
  }

  /** Marks the candidates of every place label that would lie over a painted road. */
  function markRoadBlocked(items, ink, opts, measure) {
    const L = deps();
    if (!ink) return;
    for (const item of items) {
      if (item.kind !== 'place') continue;
      const pad = opts.labelPaddingPx;
      const w = measure(item.text, item.fontPx) + pad * 2;
      const h = L.textHeight(item.fontPx) + pad * 2;
      for (const candidate of item.candidates) {
        const box = {x: candidate.x - w / 2, y: candidate.y - h / 2, w, h};
        if (ink.overlaps(box)) candidate.blocked = true;
      }
    }
  }

  /**
   * The label candidates, in priority order, for one view.
   *
   * <p>Places come first: a marker with a name is what you navigate by, while a road can
   * be identified by its colour and its place in the network. Within roads, class order
   * first (a highway's name is worth more than a footpath's), and the placer breaks every
   * remaining tie deterministically. One road group gets as many labels as its on-screen
   * length calls for -- see {@link buildRoadLabelItems} -- so a road crossing the screen
   * is named more than once, and a road that leaves and re-enters the view names each
   * visible stretch rather than spending a name on the off-screen middle.
   */
  function buildLabelItems(model, view, options) {
    const G = deps();
    const M = deps();
    const opts = normalizeOptions(options);
    const source = options || {};
    const pixelsPerBlock = Math.abs(opts.px) > 0
      ? Math.abs(G.num(view.scale, 1)) / opts.px : 0;
    const measure = typeof source.measure === 'function' ? source.measure
      : (text, fontPx) => String(text).length * G.num(fontPx, opts.labelFontPx) * 0.6;
    const items = [];
    if (!model || !Array.isArray(model.segments)) return items;
    const visible = G.visibleBounds(view);

    if (opts.showRoadNames) {
      for (const item of buildRoadLabelItems(model, view, opts, visible, measure)) {
        items.push(item);
      }
    }

    if (opts.showPlaces) {
      // Names come from the places, the same list the markers are drawn from: a marker without a
      // name and a name without a marker are the two ways this can mismatch, and one list makes
      // both impossible. An older payload's places are the POI nodes, derived in model.js.
      const places = Array.isArray(model.places) ? model.places : [];
      const placeFontPx = opts.labelFontPx;
      const placeItems = [];
      for (const place of places) {
        if (!place.hasName) continue;
        if (!placeLabelVisible(place, pixelsPerBlock)) continue;
        const candidates = placeCandidates(place, view, placeFontPx);
        if (!candidates.length) continue;
        placeItems.push({
          id: 'place:' + place.id,
          kind: 'place',
          text: place.name,
          fontPx: placeFontPx,
          x: candidates[0].x,
          y: candidates[0].y,
          angle: 0,
          priority: 0,
          candidates,
        });
      }
      if (placeItems.length) {
        // A place name is a point label, and the roads under it are the one thing it can
        // still be mistaken for. The painted roads are read once into boxes and the
        // candidates over one are marked: the placer tries the clear sides first and only
        // falls back to a road-covered side if no clear side is free.
        markRoadBlocked(placeItems, buildRoadInk(model, view, opts, visible), opts, measure);
        for (const item of placeItems) {
          items.push(item);
        }
      }
    }
    return items;
  }

  /**
   * Text last, above everything, each label haloed in the map background so it stays
   * readable over a road of any colour.
   *
   * Scale invariance, which the export depends on: the font, the padding and every
   * candidate offset are multiples of `labelFontPx`, the measurement comes from the
   * context being drawn into (so it doubles with the font), and no decision anywhere in
   * here mentions a pixel count that was not derived that way. A 2x render therefore
   * places exactly the same labels in exactly the same relative positions, which is
   * what "the PNG has the same label layout as the screen" has to mean.
   */
  function drawLabels(ctx, model, view, opts) {
    const L = deps();
    const baseFont = opts.labelFontPx;
    // Measurement is asked with the font the label will be drawn in: a road's name is
    // capped by the width of its road, so not every label on the map is the same size any
    // more, and measuring them all at the base font would place boxes that are not the
    // boxes painted.
    const measure = (text, fontPx) => {
      ctx.font = fontString(num(fontPx, baseFont), true);
      return ctx.measureText(text).width;
    };
    const items = buildLabelItems(model, view, Object.assign({}, opts, {measure}));
    const placement = L.placeLabels(items, {
      width: view.width,
      height: view.height,
      fontPx: baseFont,
      padding: opts.labelPaddingPx,
      measure,
    });
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    for (const label of placement.placed) {
      const fontPx = num(label.fontPx, baseFont);
      ctx.save();
      ctx.translate(label.anchor.x, label.anchor.y);
      if (label.angle) ctx.rotate(label.angle * Math.PI / 180);
      ctx.font = fontString(fontPx, true);
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      // The halo is a fraction of the text, so a small road name keeps a small halo
      // rather than being swallowed by the base label's outline.
      ctx.lineWidth = opts.labelHaloPx * (fontPx / baseFont);
      ctx.strokeStyle = opts.background;
      ctx.strokeText(label.text, 0, 0);
      ctx.fillStyle = label.kind === 'place' ? palette.labelPlace : palette.labelRoad;
      ctx.fillText(label.text, 0, 0);
      ctx.restore();
    }
    return placement;
  }

  function drawMessage(ctx, view, opts) {
    if (!opts.message) return;
    // Scaled with the render, like every other pixel measurement here: the "no roads yet" banner is
    // the same size relative to the map in the export as it is on screen.
    const fontPx = MESSAGE_FONT_PX * opts.fontScale * opts.px;
    ctx.save();
    ctx.font = fontString(fontPx, false);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.lineJoin = 'round';
    ctx.lineWidth = opts.labelHaloPx * 1.6;
    ctx.strokeStyle = opts.background;
    ctx.strokeText(opts.message, view.width / 2, view.height / 2);
    ctx.fillStyle = palette.message;
    ctx.fillText(opts.message, view.width / 2, view.height / 2);
    ctx.restore();
  }

  /**
   * Draws the map. The one entry point for the screen and for the export.
   *
   * @param ctx      a 2D context; nothing else is touched
   * @param model    a model from model.normalize()
   * @param view     {centerX, centerZ, scale, width, height} in CSS pixels
   * @param options  {background, grid, showRoadNames, showPlaces, showArrows,
   *                 hiddenClasses, hiddenLayers, fontScale, message, markerColor}
   * @returns the label placement, {placed, dropped}, for callers that report on it
   */
  function drawMap(ctx, model, view, options) {
    const opts = normalizeOptions(options);
    const empty = {placed: [], dropped: []};
    if (!ctx || !model || !view) return empty;

    ctx.save();
    try {
      ctx.fillStyle = opts.background;
      ctx.fillRect(0, 0, view.width, view.height);
      if (opts.grid) drawGrid(ctx, view, opts);

      const byLayer = visibleByLayer(model, view, opts);
      const layers = Array.isArray(model.layers) ? model.layers : [];
      for (const layer of layers) {
        const segments = byLayer.get(layer);
        if (!segments || !segments.length) continue;
        if (opts.onPass) opts.onPass('storey', layer);
        drawStorey(ctx, segments, view, model, opts);
      }

      if (opts.showPlaces) drawMarkers(ctx, model, view, opts);

      const placement = drawLabels(ctx, model, view, opts);
      drawMessage(ctx, view, opts);
      return placement;
    } finally {
      ctx.restore();
    }
  }

  return {
    /*
     * The palette in force, under the names the harness has always used. Getters rather than copied
     * values so a caller that reads after setTheme() -- which is how the Node harness reads them --
     * gets the palette in force rather than a snapshot of the dark one. The page itself takes its
     * colours through drawMap and never through these exports, which is why the browser's shared
     * namespace flattening them at load (Object.assign reads the getters once) changes nothing.
     */
    get BACKGROUND_COLOR() { return palette.background; },
    get GRID_COLOR() { return palette.grid; },
    get AXIS_COLOR() { return palette.axis; },
    get CASING_COLOR() { return palette.casing; },
    get MARKER_OUTLINE() { return palette.markerOutline; },
    get ARROW_COLOR() { return palette.arrow; },
    get LABEL_ROAD_COLOR() { return palette.labelRoad; },
    get LABEL_PLACE_COLOR() { return palette.labelPlace; },
    get MESSAGE_COLOR() { return palette.message; },
    get MARKER_COLOR() { return palette.marker; },
    get FAVORITE_COLOR() { return palette.favorite; },
    BASE_LABEL_FONT_PX,
    LABEL_HALO_PX,
    LABEL_PADDING_PX,
    CASING_EXTRA_PX,
    MIN_ROAD_PX,
    POI_RADIUS_PX,
    LABEL_SPACING_PX,
    MAX_LABELS_PER_ROAD,
    MIN_ROAD_LABEL_FONT_PX,
    PLACE_LABEL_MIN_SCALE,
    ARROW_SPACING_PX,
    ARROW_MIN_PATH_PX,
    ARROW_TIP_PX,
    ARROW_BACK_PX,
    ARROW_HALF_WIDTH_PX,
    PLACE_DIRECTIONS,
    GRID_STEPS,
    setTheme,
    activeTheme,
    drawMap,
    buildLabelItems,
    roadLabelFontPx,
    placeLabelVisible,
    roadStrokePx,
    segmentScreenPoints,
    segmentBounds,
    arrowTravelSign,
    arrowPlacements,
    drawGrid,
    drawMarkers,
    fontString,
  };
});
