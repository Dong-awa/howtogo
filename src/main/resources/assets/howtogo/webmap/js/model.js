/*
 * model.js -- turns the server's /api/roads payload into the shape the rest of the
 * map wants, and decides every fallback in one place.
 *
 * The contract promises a good payload; this file is what happens when it is not
 * quite one. A road with no name, a class the frontend has never heard of, a segment
 * with a single point, an empty network, a 503 body of {"error": "..."} -- all of
 * those arrive here and leave as something the renderer can draw without a single
 * `if` about the payload's mood. Nothing in this file touches the DOM, so the test
 * harness runs the same normalisation the page does.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HowToGoMap = Object.assign(root.HowToGoMap || {}, api);
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const LAYER_MIN = -32;
  const LAYER_MAX = 32;

  /** What the six class ids mean on screen, and the colours the mod already uses. */
  const DEFAULT_CLASSES = [
    {id: 'HIGHWAY', color: '#3FA9F5', width: 7.0},
    {id: 'ROAD', color: '#3FD07A', width: 5.0},
    {id: 'PATH', color: '#C8A24A', width: 3.0},
    {id: 'RAIL', color: '#FFA028', width: 4.0},
    {id: 'WATER', color: '#2F6FE0', width: 6.0},
    {id: 'ICE', color: '#9FE4FF', width: 5.0},
  ];

  const UNKNOWN_COLOR = '#888888';
  const UNKNOWN_WIDTH = 4;

  /*
   * Rank decides which label gets the good positions (higher first) and, because the
   * renderer paints thicker roads later, which class reads as being on top at a
   * crossing. Keep the two ideas in step: a highway should win both.
   */
  const ROAD_CLASS_RANK = {HIGHWAY: 0, ROAD: 1, RAIL: 2, WATER: 3, ICE: 4, PATH: 5};

  const CLASS_LABELS = {
    HIGHWAY: '高速公路',
    ROAD: '道路',
    PATH: '小径',
    RAIL: '铁路',
    WATER: '水路',
    ICE: '冰道',
  };

  const NODE_TYPES = ['JUNCTION', 'ENDPOINT', 'POI'];
  const NODE_TYPE_LABELS = {JUNCTION: '路口', ENDPOINT: '端点', POI: '地点'};

  const PLACE_KINDS = ['PLACE', 'RESOURCE', 'SHOP', 'STATION'];
  const PLACE_KIND_LABELS = {PLACE: '地点', RESOURCE: '资源', SHOP: '商店', STATION: '车站'};
  const PLACE_KIND_COLORS = {
    PLACE: '#F2C14E',
    RESOURCE: '#7ED957',
    SHOP: '#FF7AC8',
    STATION: '#6FD3FF',
  };
  const PLACE_COLOR_FALLBACK = '#DDDDDD';

  const DIRECTIONS = ['TWO_WAY', 'FORWARD', 'BACKWARD'];
  const DIRECTION_LABELS = {TWO_WAY: '双向', FORWARD: '单向 →', BACKWARD: '单向 ←'};

  function num(value, fallback) {
    return typeof value === 'number' && isFinite(value) ? value : fallback;
  }

  function clampInt(value, lo, hi, fallback) {
    const n = typeof value === 'number' && isFinite(value) ? Math.round(value) : fallback;
    return n < lo ? lo : (n > hi ? hi : n);
  }

  /**
   * A colour the legend's swatch and the export's inline styles can both use.
   *
   * Only hex and rgb()/rgba() get through: html2canvas 1.4.1 cannot parse oklch() or
   * color-mix(), and a swatch is exactly the sort of thing a themed stylesheet might
   * express in one of them, so anything else falls back to grey rather than throwing
   * halfway through an export.
   */
  function sanitiseColor(value, fallback) {
    if (typeof value !== 'string') return fallback;
    const text = value.trim();
    if (/^#[0-9a-f]{3}$/i.test(text) || /^#[0-9a-f]{6}$/i.test(text)) return text.toLowerCase();
    if (/^rgba?\(\s*[\d.\s,%]+\)$/i.test(text)) return text.toLowerCase();
    return fallback;
  }

  /** A name, or null when the server left it out. Never trimmed when present. */
  function cleanName(value) {
    if (typeof value !== 'string') return null;
    return value.trim() === '' ? null : value;
  }

  function upperIn(value, allowed, fallback) {
    if (typeof value !== 'string') return fallback;
    const text = value.trim().toUpperCase();
    return allowed.indexOf(text) >= 0 ? text : fallback;
  }

  const isKnownClass = (id) => Object.prototype.hasOwnProperty.call(ROAD_CLASS_RANK, id);

  function fallbackClass(id) {
    return {id: typeof id === 'string' ? id : '', color: UNKNOWN_COLOR, width: UNKNOWN_WIDTH, known: false};
  }

  /**
   * The class table, widened by whatever the segments actually use.
   *
   * A segment may name a class the server did not declare (an addon's custom road,
   * or a saved network from a newer version). Rather than let it vanish from the
   * legend, each undeclared id gets a grey entry of its own, so the filter list and
   * the counts still add up.
   */
  function normalizeClasses(raw, segments) {
    const declared = Array.isArray(raw) ? raw : [];
    const list = [];
    const seen = {};
    for (const entry of declared) {
      if (!entry || typeof entry !== 'object') continue;
      const id = typeof entry.id === 'string' ? entry.id : (entry.id === undefined || entry.id === null ? '' : String(entry.id));
      if (!id || seen[id]) continue;
      seen[id] = true;
      const width = num(entry.width, UNKNOWN_WIDTH);
      list.push({
        id,
        color: sanitiseColor(entry.color, UNKNOWN_COLOR),
        width: width > 0 ? width : UNKNOWN_WIDTH,
        known: isKnownClass(id),
      });
    }
    // No class table at all is survivable: the built-in six still give the usual map.
    if (!list.length && segments.length) {
      for (const c of DEFAULT_CLASSES) {
        seen[c.id] = true;
        list.push({id: c.id, color: c.color, width: c.width, known: true});
      }
    }
    for (const seg of segments) {
      if (seen[seg.roadClass]) continue;
      seen[seg.roadClass] = true;
      list.push(fallbackClass(seg.roadClass));
    }
    return list;
  }

  function normalizeNode(raw, index) {
    const n = raw && typeof raw === 'object' ? raw : {};
    const id = num(n.id, index + 1);
    const type = upperIn(n.type, NODE_TYPES, 'JUNCTION');
    return {
      id,
      x: num(n.x, 0),
      y: num(n.y, 0),
      z: num(n.z, 0),
      type,
      // Meaningful only for a POI, and often absent: null then, not a guess.
      placeKind: upperIn(n.placeKind, PLACE_KINDS, null),
      layer: typeof n.layer === 'number' && isFinite(n.layer) ? clampInt(n.layer, LAYER_MIN, LAYER_MAX, 0) : null,
      name: cleanName(n.name),
      hasName: cleanName(n.name) !== null,
    };
  }

  /**
   * One segment, with the two things the contract permits that would otherwise break
   * every consumer: fewer than two points, and a name that may be absent or null.
   *
   * `points` is normalised to {x, z} objects but a degenerate segment keeps whatever
   * points it had -- the hover readout can still say "there is a road end here", it
   * just cannot be drawn as a line.
   */
  function normalizeSegment(raw, index) {
    const s = raw && typeof raw === 'object' ? raw : {};
    const rawPoints = Array.isArray(s.points) ? s.points : [];
    const points = [];
    for (const p of rawPoints) {
      if (Array.isArray(p)) {
        if (p.length >= 2) points.push({x: num(p[0], 0), z: num(p[1], 0)});
      } else if (p && typeof p === 'object') {
        points.push({x: num(p.x, 0), z: num(p.z, 0)});
      }
    }
    const id = num(s.id, index + 1);
    const name = cleanName(s.name);
    const direction = upperIn(s.direction, DIRECTIONS, 'TWO_WAY');
    let length = 0;
    for (let i = 1; i < points.length; i++) {
      const dx = points[i].x - points[i - 1].x;
      const dz = points[i].z - points[i - 1].z;
      length += Math.sqrt(dx * dx + dz * dz);
    }
    return {
      id,
      roadClass: typeof s.roadClass === 'string' ? s.roadClass : '',
      klass: null, // filled in by normalize(), once the class table is complete
      y: num(s.y, 0),
      layer: clampInt(s.layer, LAYER_MIN, LAYER_MAX, 0),
      from: typeof s.from === 'number' && isFinite(s.from) ? Math.round(s.from) : -1,
      to: typeof s.to === 'number' && isFinite(s.to) ? Math.round(s.to) : -1,
      direction,
      name,
      hasName: name !== null,
      points,
      // Two points are the minimum a line can be drawn from; anything less is kept
      // for the readout but skipped by the renderer.
      drawable: points.length >= 2,
      lengthBlocks: length,
    };
  }

  /** Every world coordinate in the network, as one rectangle. Null when there is none. */
  function extentOf(nodes, segments) {
    let minX = Infinity;
    let minZ = Infinity;
    let maxX = -Infinity;
    let maxZ = -Infinity;
    let any = false;
    const visit = (x, z) => {
      if (!isFinite(x) || !isFinite(z)) return;
      any = true;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (z < minZ) minZ = z;
      if (z > maxZ) maxZ = z;
    };
    for (const n of nodes) visit(n.x, n.z);
    for (const s of segments) for (const p of s.points) visit(p.x, p.z);
    return any ? {minX, minZ, maxX, maxZ} : null;
  }

  function validBounds(raw) {
    if (!raw || typeof raw !== 'object') return null;
    const b = {
      minX: num(raw.minX, NaN),
      minZ: num(raw.minZ, NaN),
      maxX: num(raw.maxX, NaN),
      maxZ: num(raw.maxZ, NaN),
    };
    if (!isFinite(b.minX) || !isFinite(b.minZ) || !isFinite(b.maxX) || !isFinite(b.maxZ)) return null;
    return b;
  }

  /**
   * The bounds the view is fitted to.
   *
   * The payload's own bounds are trusted but not believed: if the geometry is
   * measurably bigger (a stale file, a rounding slip), the fit widens to cover it, so
   * "适应窗口" can never hide a road just off the edge of the rectangle it was given.
   */
  function resolveBounds(reported, extent) {
    if (!reported && !extent) return {minX: 0, minZ: 0, maxX: 0, maxZ: 0};
    if (!reported) return {minX: extent.minX, minZ: extent.minZ, maxX: extent.maxX, maxZ: extent.maxZ};
    if (!extent) return {minX: reported.minX, minZ: reported.minZ, maxX: reported.maxX, maxZ: reported.maxZ};
    return {
      minX: Math.min(reported.minX, extent.minX),
      minZ: Math.min(reported.minZ, extent.minZ),
      maxX: Math.max(reported.maxX, extent.maxX),
      maxZ: Math.max(reported.maxZ, extent.maxZ),
    };
  }

  /**
   * The payload's stats, with the frontend's own arithmetic alongside them.
   *
   * The reported numbers are shown when present -- the server knows its own network
   * better than a sum over the JSON does -- but `computed` is what the tests and the
   * status line fall back to, and the two disagreeing is a fact worth being able to
   * see rather than hide.
   */
  function normalizeStats(raw, nodes, segments, layers, totalLength) {
    const r = raw && typeof raw === 'object' ? raw : {};
    return {
      nodes: num(r.nodes, nodes.length),
      segments: num(r.segments, segments.length),
      lengthBlocks: num(r.lengthBlocks, totalLength),
      layers: Array.isArray(r.layers)
        ? r.layers.filter((v) => typeof v === 'number' && isFinite(v)).slice().sort((a, b) => a - b)
        : layers.slice(),
      computed: {
        nodes: nodes.length,
        segments: segments.length,
        lengthBlocks: totalLength,
        layers: layers.slice(),
      },
    };
  }

  function normalize(payload) {
    const p = payload && typeof payload === 'object' ? payload : {};
    const rawSegments = Array.isArray(p.segments) ? p.segments : [];
    const segments = rawSegments.map((raw, i) => normalizeSegment(raw, i));
    const classes = normalizeClasses(p.classes, segments);
    const classById = {};
    for (const c of classes) classById[c.id] = c;
    for (const seg of segments) seg.klass = classById[seg.roadClass] || fallbackClass(seg.roadClass);

    const rawNodes = Array.isArray(p.nodes) ? p.nodes : [];
    const nodes = rawNodes.map((raw, i) => normalizeNode(raw, i));

    const nodeById = {};
    for (const n of nodes) nodeById[String(n.id)] = n;

    const classCounts = {};
    const layerCounts = {};
    const segmentsByLayer = {};
    let totalLength = 0;
    for (const seg of segments) {
      classCounts[seg.roadClass] = (classCounts[seg.roadClass] || 0) + 1;
      layerCounts[seg.layer] = (layerCounts[seg.layer] || 0) + 1;
      if (!segmentsByLayer[seg.layer]) segmentsByLayer[seg.layer] = [];
      segmentsByLayer[seg.layer].push(seg);
      if (seg.drawable) totalLength += seg.lengthBlocks;
    }
    const layers = Object.keys(layerCounts).map(Number).sort((a, b) => a - b);

    const extent = extentOf(nodes, segments);
    const reported = validBounds(p.bounds);

    let namedPlaces = 0;
    let placeKinds = {};
    for (const n of nodes) {
      if (n.type !== 'POI') continue;
      if (n.hasName) namedPlaces++;
      const key = n.placeKind || 'UNKNOWN';
      placeKinds[key] = (placeKinds[key] || 0) + 1;
    }

    return {
      version: num(p.version, 1),
      generatedAt: num(p.generatedAt, null),
      world: typeof p.world === 'string' ? p.world : '',
      dimension: typeof p.dimension === 'string' ? p.dimension : '',
      empty: p.empty === true || (nodes.length === 0 && segments.length === 0),
      bounds: resolveBounds(reported, extent),
      reportedBounds: reported,
      extent,
      classes,
      classById,
      classCounts,
      layerCounts,
      layers,
      segmentsByLayer,
      nodes,
      nodeById,
      segments,
      stats: normalizeStats(p.stats, nodes, segments, layers, totalLength),
      totalLengthBlocks: totalLength,
      namedSegmentCount: segments.filter((s) => s.hasName).length,
      namedPlaceCount: namedPlaces,
      placeKindCounts: placeKinds,
    };
  }

  /** The class entry for a segment, tolerant of a model that was never normalised. */
  function classOf(model, segment) {
    if (segment && segment.klass) return segment.klass;
    if (segment && model && model.classById && model.classById[segment.roadClass]) {
      return model.classById[segment.roadClass];
    }
    return fallbackClass(segment ? segment.roadClass : '');
  }

  function classRank(id) {
    return isKnownClass(id) ? ROAD_CLASS_RANK[id] : ROAD_CLASS_RANK.PATH + 1;
  }

  function classLabel(id) {
    return CLASS_LABELS[id] || (id ? id : '未知');
  }

  function placeColor(kind) {
    return PLACE_KIND_COLORS[kind] || PLACE_COLOR_FALLBACK;
  }

  function nodeTypeLabel(type) {
    return NODE_TYPE_LABELS[type] || (type || '地点');
  }

  function placeKindLabel(kind) {
    return PLACE_KIND_LABELS[kind] || '地点';
  }

  function directionLabel(direction) {
    return DIRECTION_LABELS[direction] || DIRECTION_LABELS.TWO_WAY;
  }

  /** Storeys are named the way the map panel talks about them, height included. */
  function layerLabel(layer) {
    const n = num(layer, 0);
    if (n === 0) return '地面 0';
    return (n > 0 ? '高架 ' : '地下 ') + n;
  }

  function formatThousands(value) {
    const n = Math.round(num(value, 0));
    const sign = n < 0 ? '-' : '';
    const digits = String(Math.abs(n));
    let out = '';
    for (let i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 === 0) out += ',';
      out += digits[i];
    }
    return sign + out;
  }

  function formatLength(blocks) {
    return formatThousands(num(blocks, 0)) + ' 格';
  }

  const pad2 = (v) => (v < 10 ? '0' + v : String(v));

  function formatTime(ms) {
    if (typeof ms !== 'number' || !isFinite(ms) || ms <= 0) return '未知';
    const d = new Date(ms);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
      ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }

  /** The `yyyyMMdd-HHmmss` stamp the export file name is built from. */
  function formatStamp(ms) {
    const d = new Date(typeof ms === 'number' && isFinite(ms) ? ms : Date.now());
    return String(d.getFullYear()) + pad2(d.getMonth() + 1) + pad2(d.getDate()) +
      '-' + pad2(d.getHours()) + pad2(d.getMinutes()) + pad2(d.getSeconds());
  }

  /**
   * The server's error message, or null.
   *
   * A 503 or 500 body is `{"error": "..."}`, which is not a network at all -- the
   * caller must show it rather than draw an empty map, so the two are kept apart
   * here: an error is a string, an empty network is `empty: true` and no error.
   */
  function errorOf(payload) {
    if (payload === null || payload === undefined) return '响应为空';
    if (typeof payload !== 'object' || Array.isArray(payload)) return '响应不是有效的 JSON 对象';
    if (typeof payload.error === 'string' && payload.error.trim() !== '') return payload.error;
    return null;
  }

  return {
    LAYER_MIN,
    LAYER_MAX,
    DEFAULT_CLASSES,
    UNKNOWN_COLOR,
    UNKNOWN_WIDTH,
    ROAD_CLASS_RANK,
    CLASS_LABELS,
    NODE_TYPES,
    PLACE_KINDS,
    PLACE_KIND_COLORS,
    PLACE_COLOR_FALLBACK,
    DIRECTIONS,
    normalize,
    classOf,
    classRank,
    classLabel,
    placeColor,
    nodeTypeLabel,
    placeKindLabel,
    directionLabel,
    layerLabel,
    formatLength,
    formatThousands,
    formatTime,
    formatStamp,
    errorOf,
    sanitiseColor,
    cleanName,
  };
});
