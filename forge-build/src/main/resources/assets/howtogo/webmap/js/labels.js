/*
 * labels.js -- greedy, deterministic label placement.
 *
 * Given candidate anchors for a set of labels and a viewport, this decides where each
 * label actually goes, moving it down its list of candidate positions until one is
 * both wholly on screen and clear of everything placed before it. A label with no such
 * position is dropped rather than drawn over its neighbour: a road name you cannot
 * read is worse than no road name, and the caller can count what it lost.
 *
 * The function is pure and DOM-free -- measurement arrives as a callback, because only
 * a real 2D context knows how wide a string is -- so the Node harness can assert the
 * invariants directly: no two placed boxes overlap, every placed box is inside the
 * viewport, the same input gives the same output, and nothing here can loop forever
 * (every loop is a `for` over a finite list, and each candidate is tried once).
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HowToGoMap = Object.assign(root.HowToGoMap || {}, api);
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const DEFAULT_FONT_PX = 13;
  const DEFAULT_PADDING = 1.5;

  /*
   * A line box is taller than the glyphs. 1.25 is the multiplier every renderer's own
   * text metrics use for a single line, and keeping it here means a caller that only
   * knows the font size still gets boxes of about the right height.
   */
  const LINE_HEIGHT = 1.25;

  const DEFAULT_PRIORITY = 1e9;

  // Guards against a float error refusing a box that mathematically fits exactly, and
  // against float error calling two exactly-touching boxes an overlap.
  const EPS = 1e-9;

  function num(value, fallback) {
    return typeof value === 'number' && isFinite(value) ? value : fallback;
  }

  function clamp(value, lo, hi) {
    return value < lo ? lo : (value > hi ? hi : value);
  }

  /**
   * Text is never rendered upside down: an angle outside (-90, 90] is turned through
   * half a turn, because a label leaning 100 degrees reads as a label leaning -80.
   *
   * Kept local rather than imported from geom so that placeLabels stays a
   * self-contained pure function with no module resolution of its own -- it is the one
   * piece of this map that is worth being able to test on its own.
   */
  function upright(angle) {
    let a = num(angle, 0) % 360;
    if (a > 180) a -= 360;
    if (a <= -180) a += 360;
    if (a > 90) a -= 180;
    if (a <= -90) a += 180;
    return a;
  }

  /** How tall one line of `fontPx` text is, in the same pixels as the font. */
  function textHeight(fontPx, lineHeight) {
    const mult = num(lineHeight, LINE_HEIGHT);
    return num(fontPx, DEFAULT_FONT_PX) * (mult > 0 ? mult : LINE_HEIGHT);
  }

  /**
   * The axis-aligned box a rotated `w` x `h` rectangle occupies.
   *
   * Placement works in axis-aligned boxes, so a label lying along a diagonal road has
   * to be reserved as the rectangle it really covers; using the unrotated size would
   * let two labels on crossing roads be placed straight through one another.
   */
  function rotatedSize(w, h, angle) {
    const rad = Math.abs(upright(angle)) * Math.PI / 180;
    const c = Math.cos(rad);
    const s = Math.sin(rad);
    return {w: w * c + h * s, h: w * s + h * c};
  }

  function textOf(item) {
    return item && typeof item.text === 'string' ? item.text : '';
  }

  function priorityOf(item) {
    return num(item && item.priority, DEFAULT_PRIORITY);
  }

  function kindRank(item) {
    return item && item.kind === 'place' ? 0 : 1;
  }

  function idOf(item) {
    return item ? item.id : undefined;
  }

  function compareIds(a, b) {
    const ia = idOf(a);
    const ib = idOf(b);
    const na = typeof ia === 'number' && isFinite(ia);
    const nb = typeof ib === 'number' && isFinite(ib);
    if (na && nb) return ia - ib;
    if (na !== nb) return na ? -1 : 1; // numeric ids first, so the order never depends on type
    const sa = String(ia);
    const sb = String(ib);
    return sa < sb ? -1 : (sa > sb ? 1 : 0);
  }

  /**
   * Who gets the good positions.
   *
   * Places before roads, because a marker with a name is what you navigate by and a
   * road can be identified by its colour and its place in the network. Among roads,
   * class order first (a highway's name is worth more than a footpath's), then the
   * longer text, because a long name has fewer positions it can fit into and so should
   * take its pick while the map is still empty. Ids break every remaining tie, so two
   * runs over the same input cannot disagree.
   */
  function compareItems(a, b) {
    const pa = priorityOf(a);
    const pb = priorityOf(b);
    if (pa !== pb) return pa - pb;
    const ka = kindRank(a);
    const kb = kindRank(b);
    if (ka !== kb) return ka - kb;
    const la = textOf(a).length;
    const lb = textOf(b).length;
    if (la !== lb) return lb - la;
    return compareIds(a, b);
  }

  /** An approximation used only when the caller has no canvas to measure with. */
  function estimateMeasure(fontPx) {
    const size = num(fontPx, DEFAULT_FONT_PX);
    return function (text) {
      return String(text).length * size * 0.6;
    };
  }

  function candidatesOf(item) {
    const list = item && Array.isArray(item.candidates) ? item.candidates : [];
    const out = [];
    for (const c of list) {
      if (!c || typeof c !== 'object') continue;
      if (!isFinite(c.x) || !isFinite(c.y)) continue;
      out.push({x: c.x, y: c.y, angle: num(c.angle, 0)});
    }
    if (out.length) return out;
    // Tolerant of an item handed over with just its anchor: one candidate, tried once.
    if (item && isFinite(item.x) && isFinite(item.y)) {
      return [{x: item.x, y: item.y, angle: num(item.angle, 0)}];
    }
    return out;
  }

  function insideViewport(box, width, height) {
    return box.x >= -EPS && box.y >= -EPS &&
      box.x + box.w <= width + EPS && box.y + box.h <= height + EPS;
  }

  /** True when two boxes share any area at all. Boxes that merely touch do not. */
  function overlaps(a, b) {
    return a.x < b.x + b.w - EPS && b.x < a.x + a.w - EPS &&
      a.y < b.y + b.h - EPS && b.y < a.y + a.h - EPS;
  }

  /**
   * Places as many labels as will fit.
   *
   * @param items    candidate labels: {id, kind: 'road'|'place', text, x, y, angle,
   *                 priority, candidates: [{x, y, angle}, ...]}
   * @param options  {width, height, measure(text)->px, fontPx, padding, lineHeight}
   * @returns {{placed: Array, dropped: Array}} placed entries carry the winning
   *          `box` (top-left based, padded, CSS px), the `angle` actually used and the
   *          `anchor` the text is centred on.
   */
  function placeLabels(items, options) {
    const opts = options || {};
    const width = Math.max(0, num(opts.width, 0));
    const height = Math.max(0, num(opts.height, 0));
    const fontPx = num(opts.fontPx, DEFAULT_FONT_PX);
    const padding = Math.max(0, num(opts.padding, DEFAULT_PADDING));
    const measure = typeof opts.measure === 'function' ? opts.measure : estimateMeasure(fontPx);
    const lineH = textHeight(fontPx, opts.lineHeight);

    const list = Array.isArray(items) ? items.slice() : [];
    list.sort(compareItems);

    const placed = [];
    const dropped = [];
    const taken = [];

    for (const item of list) {
      const text = textOf(item);
      if (!text) {
        dropped.push(Object.assign({}, item, {reason: 'no-text'}));
        continue;
      }
      const w = Math.max(0, num(measure(text), 0)) + padding * 2;
      const h = lineH + padding * 2;
      let hit = null;
      const candidates = candidatesOf(item);
      for (let i = 0; i < candidates.length && !hit; i++) {
        const c = candidates[i];
        const angle = upright(c.angle);
        const size = rotatedSize(w, h, angle);
        const box = {x: c.x - size.w / 2, y: c.y - size.h / 2, w: size.w, h: size.h};
        if (!insideViewport(box, width, height)) continue;
        let clash = false;
        for (let j = 0; j < taken.length && !clash; j++) clash = overlaps(box, taken[j]);
        if (clash) continue;
        hit = {box, angle, anchor: {x: c.x, y: c.y}, candidateIndex: i};
      }
      if (hit) {
        taken.push(hit.box);
        placed.push(Object.assign({}, item, {
          box: hit.box,
          angle: hit.angle,
          anchor: hit.anchor,
          candidateIndex: hit.candidateIndex,
        }));
      } else {
        // Nothing fitted: record the loss instead of drawing over a neighbour.
        dropped.push(Object.assign({}, item, {reason: candidates.length ? 'no-fit' : 'no-candidate'}));
      }
    }

    return {placed, dropped};
  }

  /** Kept so a caller can lay labels out without re-deriving the line box. */
  function centreBox(cx, cy, w, h) {
    return {x: cx - w / 2, y: cy - h / 2, w, h};
  }

  return {
    DEFAULT_FONT_PX,
    DEFAULT_PADDING,
    LINE_HEIGHT,
    DEFAULT_PRIORITY,
    placeLabels,
    compareItems,
    textHeight,
    rotatedSize,
    upright,
    overlaps,
    centreBox,
    clamp,
  };
});
