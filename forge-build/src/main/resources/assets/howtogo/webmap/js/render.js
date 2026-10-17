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
 *      on the road beneath it. Junction dots, then place markers, then text come after
 *      all road paint: a name is the last thing to be covered and the first thing to
 *      be read.
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
  // CASING_COLOR and MARKER_OUTLINE are deliberately different shades of near-black:
  // the harness tells the passes apart by stroke colour, and an outline sharing the
  // casing's colour would look like a road.
  const BACKGROUND_COLOR = '#101418';
  const GRID_COLOR = '#1b2430';
  const AXIS_COLOR = '#2c3a4a';
  const CASING_COLOR = '#0b0f14';
  const MARKER_OUTLINE = '#05070a';
  const ARROW_COLOR = '#0b0f14';
  const LABEL_ROAD_COLOR = '#E8F1FA';
  const LABEL_PLACE_COLOR = '#FFE7A8';
  const MESSAGE_COLOR = '#9FB0C0';
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
  const MARKER_COLOR = '#E8EDF2';

  const ARROW_SPACING_PX = 56;
  const ARROW_MIN_PATH_PX = 22;
  const ARROW_MAX_PER_SEGMENT = 24;
  const ARROW_TIP_PX = 5;
  const ARROW_BACK_PX = 2.5;
  const ARROW_HALF_WIDTH_PX = 3.4;

  /*
   * A road shorter than this does not get a name even if it has one. Expressed in
   * blocks rather than pixels on purpose: every threshold that decides a label
   * candidate is either world-space or viewport-relative, never an absolute pixel
   * count, so the 2x export reaches exactly the same decisions as the screen (see the
   * note on scale invariance above drawLabels).
   */
  const MIN_LABEL_ROAD_BLOCKS = 12;

  /*
   * Candidate order for a road label: positions along the road first, then either side
   * of it. The primary anchor is the middle of the road; the rest of the list walks
   * outwards from there, so a name that cannot sit on its road steps along the road
   * and then off it rather than jumping somewhere unrelated. 0.5, 0.4, 0.6, 0.3, 0.7
   * keeps the outward walk symmetric, which stops labels from all crowding one end.
   */
  const LABEL_TS = [0.5, 0.4, 0.6, 0.3, 0.7];

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
      background: typeof o.background === 'string' ? o.background : BACKGROUND_COLOR,
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
      markerColor: typeof o.markerColor === 'string' ? o.markerColor : MARKER_COLOR,
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

  function segmentScreenPoints(segment, view) {
    const G = deps();
    const out = [];
    if (!segment || !Array.isArray(segment.points)) return out;
    for (const p of segment.points) {
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
    for (const p of segment.points) {
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
    // Dark, like the casing: an arrowhead in the road's own colour would be invisible
    // on the road it is painted on.
    ctx.fillStyle = ARROW_COLOR;
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
      strokeRoad(ctx, seg, view, CASING_COLOR,
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
      ctx.strokeStyle = GRID_COLOR;
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
      ctx.strokeStyle = AXIS_COLOR;
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
    ctx.strokeStyle = MARKER_OUTLINE;
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
    ctx.strokeStyle = MARKER_OUTLINE;
    ctx.lineWidth = 1.2 * px;
    ctx.stroke();
  }

  /**
   * Junction dots, then place markers.
   *
   * Nodes carry no storey in the payload, so markers cannot join the per-storey
   * grouping; they are drawn after every storey's roads instead. A dot is a point the
   * map is read against, and burying one under a bridge would be worse than drawing an
   * underground junction over it. Their size is in reference pixels, so a 2x export
   * shows markers as large, relative to the roads, as the screen does.
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
        drawDot(ctx, s.x, s.y, ENDPOINT_RADIUS_PX * px, MARKER_OUTLINE, px);
      } else {
        drawDot(ctx, s.x, s.y, JUNCTION_RADIUS_PX * px, opts.markerColor, px);
      }
    }
    for (const node of nodes) {
      if (node.type !== 'POI') continue;
      const s = G.worldToScreen(view, node.x, node.z);
      if (!onScreen(s)) continue;
      drawDiamond(ctx, s.x, s.y, POI_RADIUS_PX * px, M.placeColor(node.placeKind), px);
    }
  }

  /**
   * Road label candidates, in the order they should be tried.
   *
   * The road's own direction is used as the text angle, straightened into (-90, 90] so
   * a name on a northbound road reads left-to-right like every other name. The two
   * non-zero offsets move the text perpendicular to the road, which is the "step off
   * the road rather than along it" half of the candidate list.
   */
  function roadCandidates(segment, view, fontPx) {
    const G = deps();
    const L = deps();
    const out = [];
    const textH = L.textHeight(fontPx);
    for (const t of LABEL_TS) {
      const p = G.pointAlong(segment.points, t);
      const s = G.worldToScreen(view, p.x, p.z);
      const angle = G.uprightAngle(p.angle);
      const rad = angle * Math.PI / 180;
      const nx = -Math.sin(rad);
      const ny = Math.cos(rad);
      for (const offset of [0, textH, -textH]) {
        out.push({x: s.x + nx * offset, y: s.y + ny * offset, angle});
      }
    }
    return out;
  }

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
   * The label candidates, in priority order, for one view.
   *
   * One label per road name: the server names every piece of a chain with the same
   * name, so without this a street drawn as twelve segments would print its name
   * twelve times. The longest piece carries the name, because that is where there is
   * room to read it. This is also the filter the harness leans on -- every test here is
   * world-space or viewport-relative, so it behaves identically at 2x.
   */
  function buildLabelItems(model, view, options) {
    const G = deps();
    const M = deps();
    const opts = normalizeOptions(options);
    const items = [];
    if (!model || !Array.isArray(model.segments)) return items;
    const visible = G.visibleBounds(view);

    if (opts.showRoadNames) {
      const best = new Map();
      for (const seg of model.segments) {
        if (!seg.drawable || !seg.hasName) continue;
        if (hiddenSegment(seg, opts)) continue;
        if (seg.lengthBlocks < MIN_LABEL_ROAD_BLOCKS) continue;
        const sb = segmentBounds(seg);
        if (!sb || !boundsHit(sb, visible.minX, visible.minZ, visible.maxX, visible.maxZ)) continue;
        const key = seg.name;
        const held = best.get(key);
        if (!held || seg.lengthBlocks > held.lengthBlocks) best.set(key, seg);
      }
      for (const seg of best.values()) {
        const candidates = roadCandidates(seg, view, opts.labelFontPx);
        if (!candidates.length) continue;
        items.push({
          id: 'seg:' + seg.id,
          kind: 'road',
          text: seg.name,
          x: candidates[0].x,
          y: candidates[0].y,
          angle: candidates[0].angle,
          priority: 1 + M.classRank(seg.roadClass),
          candidates,
        });
      }
    }

    if (opts.showPlaces) {
      const nodes = Array.isArray(model.nodes) ? model.nodes : [];
      for (const node of nodes) {
        if (node.type !== 'POI' || !node.hasName) continue;
        const candidates = placeCandidates(node, view, opts.labelFontPx);
        if (!candidates.length) continue;
        items.push({
          id: 'node:' + node.id,
          kind: 'place',
          text: node.name,
          x: candidates[0].x,
          y: candidates[0].y,
          angle: 0,
          priority: 0,
          candidates,
        });
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
    const font = fontString(opts.labelFontPx, true);
    ctx.font = font;
    const items = buildLabelItems(model, view, opts);
    const placement = L.placeLabels(items, {
      width: view.width,
      height: view.height,
      fontPx: opts.labelFontPx,
      padding: opts.labelPaddingPx,
      measure: (text) => ctx.measureText(text).width,
    });
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    for (const label of placement.placed) {
      ctx.save();
      ctx.translate(label.anchor.x, label.anchor.y);
      if (label.angle) ctx.rotate(label.angle * Math.PI / 180);
      ctx.font = font;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.lineWidth = opts.labelHaloPx;
      ctx.strokeStyle = opts.background;
      ctx.strokeText(label.text, 0, 0);
      ctx.fillStyle = label.kind === 'place' ? LABEL_PLACE_COLOR : LABEL_ROAD_COLOR;
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
    ctx.fillStyle = MESSAGE_COLOR;
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
    BACKGROUND_COLOR,
    GRID_COLOR,
    AXIS_COLOR,
    CASING_COLOR,
    MARKER_OUTLINE,
    ARROW_COLOR,
    LABEL_ROAD_COLOR,
    LABEL_PLACE_COLOR,
    MESSAGE_COLOR,
    BASE_LABEL_FONT_PX,
    LABEL_HALO_PX,
    LABEL_PADDING_PX,
    CASING_EXTRA_PX,
    MIN_ROAD_PX,
    MARKER_COLOR,
    POI_RADIUS_PX,
    MIN_LABEL_ROAD_BLOCKS,
    ARROW_SPACING_PX,
    ARROW_MIN_PATH_PX,
    ARROW_TIP_PX,
    ARROW_BACK_PX,
    ARROW_HALF_WIDTH_PX,
    LABEL_TS,
    PLACE_DIRECTIONS,
    GRID_STEPS,
    drawMap,
    buildLabelItems,
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
