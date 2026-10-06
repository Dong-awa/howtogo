/*
 * geom.js -- the world <-> screen transform, and the polyline maths the rest of the
 * map is built on.
 *
 * Conventions, matching the in-game map: world X runs east and becomes screen X,
 * world Z runs south and becomes screen Y (so north is up), and the scale is
 * isotropic -- `scale` is pixels per block on both axes. A "view" is therefore just
 * a centre, a scale and a viewport size, and every other file can stay ignorant of
 * how the map is panned or zoomed.
 *
 * Nothing here touches the DOM, so the same code runs under Node in the test
 * harness (tools/webmap-test).
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HowToGoMap = Object.assign(root.HowToGoMap || {}, api);
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /*
   * MIN_SCALE puts a 500-block world across a phone screen; MAX_SCALE is 16 pixels
   * per block. Both ends are wider than anything the buttons can reach in practice,
   * so in normal use `fitView` decides the scale and the clamp only rescues the
   * degenerate cases (an empty network, or a wheel event with a huge delta).
   */
  const MIN_SCALE = 0.002;
  const MAX_SCALE = 16;
  const DEFAULT_SCALE = 1;

  function num(value, fallback) {
    return typeof value === 'number' && isFinite(value) ? value : fallback;
  }

  function clamp(value, lo, hi) {
    return value < lo ? lo : (value > hi ? hi : value);
  }

  /** Reads a world point given either `[x, z]` or `{x, z}`. */
  function px(point) {
    return Array.isArray(point) ? num(point[0], 0) : num(point && point.x, 0);
  }

  /*
   * The second coordinate, under either name.
   *
   * These helpers are used on world polylines and on screen polylines: in world space
   * the second component is Z, on screen it is Y, and the projection is the identity
   * between them. Reading either name means a caller holding screen points does not
   * have to re-label them -- and, more to the point, cannot silently get 0 for every
   * one of them by forgetting to.
   */
  function pz(point) {
    if (Array.isArray(point)) return num(point[1], 0);
    if (!point) return 0;
    if (typeof point.z === 'number' && isFinite(point.z)) return point.z;
    return num(point.y, 0);
  }

  /** A bounds object with finite, ordered edges; an empty network reads as all zeros. */
  function saneBounds(bounds) {
    const b = bounds || {};
    let minX = num(b.minX, 0);
    let maxX = num(b.maxX, 0);
    let minZ = num(b.minZ, 0);
    let maxZ = num(b.maxZ, 0);
    if (minX > maxX) {
      const t = minX;
      minX = maxX;
      maxX = t;
    }
    if (minZ > maxZ) {
      const t = minZ;
      minZ = maxZ;
      maxZ = t;
    }
    return {minX, minZ, maxX, maxZ};
  }

  function clampScale(scale, min, max) {
    const lo = num(min, MIN_SCALE);
    const hi = num(max, MAX_SCALE);
    const low = Math.min(lo, hi);
    const high = Math.max(lo, hi);
    return clamp(num(scale, DEFAULT_SCALE), low, high);
  }

  /** Fills in anything a half-built view is missing, and clamps its scale. */
  function makeView(view) {
    const v = view || {};
    return {
      centerX: num(v.centerX, 0),
      centerZ: num(v.centerZ, 0),
      scale: clampScale(v.scale),
      width: Math.max(0, num(v.width, 0)),
      height: Math.max(0, num(v.height, 0)),
    };
  }

  function worldToScreen(view, x, z) {
    const v = makeView(view);
    return {
      x: (num(x, 0) - v.centerX) * v.scale + v.width / 2,
      y: (num(z, 0) - v.centerZ) * v.scale + v.height / 2,
    };
  }

  function screenToWorld(view, sx, sy) {
    const v = makeView(view);
    return {
      x: (num(sx, 0) - v.width / 2) / v.scale + v.centerX,
      z: (num(sy, 0) - v.height / 2) / v.scale + v.centerZ,
    };
  }

  /** The world rectangle currently on screen. */
  function visibleBounds(view) {
    const a = screenToWorld(view, 0, 0);
    const b = screenToWorld(view, num(view && view.width, 0), num(view && view.height, 0));
    return {minX: a.x, minZ: a.z, maxX: b.x, maxZ: b.z};
  }

  /**
   * The view that shows `bounds` whole: centred on it, as large as fits inside the
   * viewport once `padding` pixels are kept clear on every side.
   *
   * A single-point network (or an empty one, whose bounds are all zeros) has no span
   * to divide by, so it gets `defaultScale` instead -- the "divide by zero" case the
   * contract calls out. The two axes are fitted independently and the smaller scale
   * wins, because both spans have to fit.
   */
  function fitView(bounds, width, height, options) {
    const opts = options || {};
    const padding = Math.max(0, num(opts.padding, 0));
    const w = Math.max(0, num(width, 0));
    const h = Math.max(0, num(height, 0));
    const b = saneBounds(bounds);
    const spanX = b.maxX - b.minX;
    const spanZ = b.maxZ - b.minZ;
    const availW = Math.max(1, w - 2 * padding);
    const availH = Math.max(1, h - 2 * padding);
    const sx = spanX > 0 ? availW / spanX : Infinity;
    const sz = spanZ > 0 ? availH / spanZ : Infinity;
    let scale = Math.min(sx, sz);
    if (!isFinite(scale) || scale <= 0) scale = num(opts.defaultScale, DEFAULT_SCALE);
    return makeView({
      centerX: (b.minX + b.maxX) / 2,
      centerZ: (b.minZ + b.maxZ) / 2,
      scale: clampScale(scale, opts.minScale, opts.maxScale),
      width: w,
      height: h,
    });
  }

  /** The same view, panned by a drag of `dx`/`dy` pixels. */
  function panBy(view, dxPx, dyPx) {
    const v = makeView(view);
    return makeView({
      centerX: v.centerX - num(dxPx, 0) / v.scale,
      centerZ: v.centerZ - num(dyPx, 0) / v.scale,
      scale: v.scale,
      width: v.width,
      height: v.height,
    });
  }

  /**
   * Zoom by `factor` while keeping the world point under (sx, sy) under the cursor,
   * which is what makes wheel zoom feel anchored rather than centre-anchored. The
   * correction is computed against the *clamped* scale, so a wheel turn at the
   * clamp is a no-op instead of drifting the map sideways.
   */
  function zoomAt(view, factor, sx, sy) {
    const v = makeView(view);
    const before = screenToWorld(v, sx, sy);
    const zoomed = makeView({
      centerX: v.centerX,
      centerZ: v.centerZ,
      scale: v.scale * num(factor, 1),
      width: v.width,
      height: v.height,
    });
    const after = screenToWorld(zoomed, sx, sy);
    return makeView({
      centerX: zoomed.centerX + (before.x - after.x),
      centerZ: zoomed.centerZ + (before.z - after.z),
      scale: zoomed.scale,
      width: zoomed.width,
      height: zoomed.height,
    });
  }

  /** Total length of a polyline, in blocks (the points are world coordinates). */
  function polylineLength(points) {
    const list = Array.isArray(points) ? points : [];
    let total = 0;
    for (let i = 1; i < list.length; i++) {
      const dx = px(list[i]) - px(list[i - 1]);
      const dz = pz(list[i]) - pz(list[i - 1]);
      total += Math.sqrt(dx * dx + dz * dz);
    }
    return total;
  }

  function uprightAngle(angle) {
    let a = num(angle, 0) % 360;
    if (a > 180) a -= 360;
    if (a <= -180) a += 360;
    if (a > 90) a -= 180;
    if (a <= -90) a += 180;
    return a;
  }

  /**
   * The point `t` of the way along a polyline by arc length, with the local
   * direction as a screen-space angle in degrees.
   *
   * The angle is atan2(dz, dx): world Z already grows south, which is screen-down, so
   * a world direction and its screen direction are the same number and no flip is
   * needed anywhere in this file.
   */
  function pointAlong(points, t) {
    const list = Array.isArray(points) ? points : [];
    const first = list.length ? {x: px(list[0]), z: pz(list[0])} : {x: 0, z: 0};
    if (list.length < 2) return {x: first.x, z: first.z, angle: 0, t: 0, segmentIndex: 0};
    const total = polylineLength(list);
    if (!(total > 0)) return {x: first.x, z: first.z, angle: 0, t: 0, segmentIndex: 0};
    const want = clamp(num(t, 0), 0, 1) * total;
    let walked = 0;
    for (let i = 1; i < list.length; i++) {
      const ax = px(list[i - 1]);
      const az = pz(list[i - 1]);
      const bx = px(list[i]);
      const bz = pz(list[i]);
      const dx = bx - ax;
      const dz = bz - az;
      const len = Math.sqrt(dx * dx + dz * dz);
      if (len <= 0) continue;
      if (walked + len >= want || i === list.length - 1) {
        const local = clamp((want - walked) / len, 0, 1);
        return {
          x: ax + dx * local,
          z: az + dz * local,
          angle: Math.atan2(dz, dx) * 180 / Math.PI,
          t: clamp(want / total, 0, 1),
          segmentIndex: i - 1,
        };
      }
      walked += len;
    }
    const last = list[list.length - 1];
    return {x: px(last), z: pz(last), angle: 0, t: 1, segmentIndex: list.length - 2};
  }

  /**
   * The closest point on a polyline to (x, z), in world units.
   *
   * Used for the hover readout, where "which road is the cursor over" has to be
   * answered in blocks (the caller turns a pixel radius into a block radius with the
   * current scale) rather than in pixels.
   */
  function nearestOnPolyline(points, x, z) {
    const list = Array.isArray(points) ? points : [];
    const target = {x: num(x, 0), z: num(z, 0)};
    if (!list.length) {
      return {distance: Infinity, x: target.x, z: target.z, angle: 0, segmentIndex: 0, t: 0};
    }
    if (list.length === 1) {
      const only = {x: px(list[0]), z: pz(list[0])};
      return {
        distance: Math.sqrt((target.x - only.x) * (target.x - only.x) + (target.z - only.z) * (target.z - only.z)),
        x: only.x,
        z: only.z,
        angle: 0,
        segmentIndex: 0,
        t: 0,
      };
    }
    const total = polylineLength(list);
    let best = null;
    let bestDist = Infinity;
    let walked = 0;
    for (let i = 1; i < list.length; i++) {
      const ax = px(list[i - 1]);
      const az = pz(list[i - 1]);
      const bx = px(list[i]);
      const bz = pz(list[i]);
      const dx = bx - ax;
      const dz = bz - az;
      const lenSq = dx * dx + dz * dz;
      const len = Math.sqrt(lenSq);
      let local = 0;
      if (lenSq > 0) local = clamp(((target.x - ax) * dx + (target.z - az) * dz) / lenSq, 0, 1);
      const cx = ax + dx * local;
      const cz = az + dz * local;
      const d = Math.sqrt((target.x - cx) * (target.x - cx) + (target.z - cz) * (target.z - cz));
      if (d < bestDist) {
        bestDist = d;
        best = {
          distance: d,
          x: cx,
          z: cz,
          angle: len > 0 ? Math.atan2(dz, dx) * 180 / Math.PI : 0,
          segmentIndex: i - 1,
          t: total > 0 ? clamp((walked + len * local) / total, 0, 1) : 0,
        };
      }
      walked += len;
    }
    return best;
  }

  /** Axis-aligned overlap test, edges touching counting as clear. */
  function rectsOverlap(a, b) {
    return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
  }

  return {
    MIN_SCALE,
    MAX_SCALE,
    DEFAULT_SCALE,
    num,
    clamp,
    clampScale,
    makeView,
    worldToScreen,
    screenToWorld,
    visibleBounds,
    fitView,
    panBy,
    zoomAt,
    polylineLength,
    pointAlong,
    nearestOnPolyline,
    uprightAngle,
    rectsOverlap,
    saneBounds,
  };
});
