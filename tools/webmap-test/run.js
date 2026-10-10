#!/usr/bin/env node
/*
 * run.js -- regression checks for the browser map's frontend.
 *
 * The browser map is four DOM-free modules plus one page that glues them together.
 * The four modules are what this file exercises, under Node, with no browser and no
 * dependencies: model.js normalisation, geom.js projection, labels.js placement, and
 * render.js drawing -- the last through a recording stub context, which is how the
 * occlusion order gets asserted rather than eyeballed.
 *
 * Every assertion here is one of two kinds: an invariant the map has to hold whatever
 * the server sends (no two labels overlap, nothing is ever drawn off its storey), or a
 * property of the bundled fixture that would catch a careless edit to it. The second
 * kind is skipped when a fixture path is passed in, because real server output is
 * allowed to have a different shape -- it is only required to survive.
 *
 *   node tools/webmap-test/run.js                  # the bundled fixture
 *   node tools/webmap-test/run.js C:\path\roads.json   # real /api/roads output
 *
 * Exits non-zero when anything fails.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const WEB_DIR = path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'assets', 'howtogo', 'webmap');
const JS_DIR = path.join(WEB_DIR, 'js');

const geom = require(path.join(JS_DIR, 'geom.js'));
const model = require(path.join(JS_DIR, 'model.js'));
const labels = require(path.join(JS_DIR, 'labels.js'));
const render = require(path.join(JS_DIR, 'render.js'));
// app.js only touches the DOM inside boot(), and it is not booted at require time when
// there is no document -- which is what lets the fake-DOM section near the end call it
// with a document of the test's own making.
const app = require(path.join(JS_DIR, 'app.js'));

let checks = 0;
let failures = 0;

function ok(name, condition, detail) {
  checks++;
  if (condition) {
    console.log('ok   ' + name);
  } else {
    failures++;
    console.log('FAIL ' + name + (detail === undefined ? '' : ' -- ' + detail));
  }
}

function group(title) {
  console.log('');
  console.log('== ' + title + ' ==');
}

/** Runs a group of checks; a throw inside is a failure, not the end of the run. */
function section(title, body) {
  group(title);
  try {
    body();
  } catch (err) {
    ok(title + ' ran to the end', false, err && err.stack ? err.stack.split('\n')[0] : String(err));
  }
}

function close(a, b, tolerance) {
  const t = tolerance === undefined ? 1e-9 : tolerance;
  return Math.abs(a - b) <= t;
}

function round6(value) {
  return Math.round(value * 1e6) / 1e6;
}

/**
 * A stylesheet with its comments removed.
 *
 * The colour-function checks below are about what the page actually declares; a comment
 * explaining why oklch() is avoided must not itself count as using it.
 */
function withoutCssComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, ' ');
}

// ---------------------------------------------------------------------------------
// The recording 2D context.
//
// It implements the handful of methods render.js uses, keeps a save/restore stack for
// the drawing state, and records every call with a snapshot of the state at the time.
// That is enough to answer "what did it draw, in what order, in what colour", which is
// all the checks below need -- no canvas, no pixels.
// ---------------------------------------------------------------------------------
function recordingContext() {
  const initial = {
    strokeStyle: '#000000',
    fillStyle: '#000000',
    lineWidth: 1,
    lineCap: 'butt',
    lineJoin: 'miter',
    miterLimit: 10,
    font: '10px sans-serif',
    textAlign: 'start',
    textBaseline: 'alphabetic',
    globalAlpha: 1,
  };
  const state = Object.assign({}, initial);
  const stack = [];
  const calls = [];
  const record = (name, args) => {
    calls.push({name, args: args || [], state: Object.assign({}, state)});
  };

  const methods = ['save', 'restore', 'beginPath', 'closePath', 'moveTo', 'lineTo',
    'quadraticCurveTo', 'bezierCurveTo', 'arc', 'arcTo', 'ellipse', 'rect', 'roundRect',
    'fill', 'stroke', 'fillRect', 'strokeRect', 'clearRect', 'fillText', 'strokeText',
    'translate', 'rotate', 'scale', 'setTransform', 'resetTransform', 'setLineDash',
    'clip', 'drawImage'];

  const ctx = {calls, state};
  for (const name of methods) {
    ctx[name] = function () {
      const args = Array.prototype.slice.call(arguments);
      if (name === 'save') stack.push(Object.assign({}, state));
      else if (name === 'restore' && stack.length) Object.assign(state, stack.pop());
      record(name, args);
    };
  }

  ctx.measureText = function (text) {
    record('measureText', [text]);
    const px = fontPixels(state.font);
    // Linear in the font size, which is what makes a 2x render reproduce the screen's
    // label layout exactly: every measured width simply doubles.
    return {width: String(text).length * px * 0.6, actualBoundingBoxAscent: px * 0.8};
  };

  // Property assignment has to be recorded too, or a stroke could not be attributed to
  // the pass that drew it.
  Object.keys(initial).forEach((key) => {
    Object.defineProperty(ctx, key, {
      get() {
        return state[key];
      },
      set(value) {
        state[key] = value;
        record(key + '=', [value]);
      },
    });
  });

  return ctx;
}

function fontPixels(font) {
  const m = /(\d+(?:\.\d+)?)px/.exec(String(font));
  return m ? parseFloat(m[1]) : 10;
}

/**
 * The stroked and filled paths in a recorded call stream, in order.
 *
 * render.js always begins a path before filling or stroking it, so a path run is
 * everything between a beginPath and the fill/stroke that finishes it. `points` is the
 * geometry, which is how a call is matched back to the segment that produced it.
 */
function paths(calls) {
  const out = [];
  let points = [];
  let open = false;
  for (let i = 0; i < calls.length; i++) {
    const call = calls[i];
    if (call.name === 'beginPath') {
      points = [];
      open = true;
    } else if (call.name === 'moveTo' || call.name === 'lineTo') {
      if (open) points.push([round6(call.args[0]), round6(call.args[1])]);
    } else if (call.name === 'arc') {
      if (open) points.push([round6(call.args[0]), round6(call.args[1]), round6(call.args[2])]);
    } else if (call.name === 'stroke' || call.name === 'fill') {
      out.push({
        kind: call.name,
        index: i,
        strokeStyle: call.state.strokeStyle,
        fillStyle: call.state.fillStyle,
        lineWidth: call.state.lineWidth,
        points,
      });
    }
  }
  return out;
}

function signature(points) {
  return JSON.stringify(points);
}

function indicesOf(calls, name) {
  const out = [];
  for (let i = 0; i < calls.length; i++) if (calls[i].name === name) out.push(i);
  return out;
}

/**
 * Where two world-space line pieces cross, or null when they do not.
 *
 * Used to prove the fixture really has a bridge crossing a surface road at a point
 * neither of them has a node at -- which is the case the two-pass casing/fill drawing
 * exists for, and which a distance test between vertices would miss entirely.
 */
function crossing(a1, a2, b1, b2) {
  const d1x = a2.x - a1.x;
  const d1z = a2.z - a1.z;
  const d2x = b2.x - b1.x;
  const d2z = b2.z - b1.z;
  const den = d1x * d2z - d1z * d2x;
  if (Math.abs(den) < 1e-12) return null;
  const wx = b1.x - a1.x;
  const wz = b1.z - a1.z;
  const t = (wx * d2z - wz * d2x) / den;
  const u = (wx * d1z - wz * d1x) / den;
  if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return null;
  return {x: a1.x + d1x * t, z: a1.z + d1z * t, dir1: {x: d1x, z: d1z}, dir2: {x: d2x, z: d2z}};
}

function crossingsBetween(a, b) {
  const out = [];
  for (let i = 1; i < a.points.length; i++) {
    for (let j = 1; j < b.points.length; j++) {
      const hit = crossing(a.points[i - 1], a.points[i], b.points[j - 1], b.points[j]);
      if (hit) out.push(hit);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------
// The fixture.
// ---------------------------------------------------------------------------------
const fixtureArg = process.argv[2];
const fixturePath = fixtureArg ? path.resolve(fixtureArg) : path.join(__dirname, 'fixture.json');
const usingBundled = !fixtureArg;

let fixture = null;
let fixtureLoadError = null;
try {
  fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
} catch (err) {
  fixtureLoadError = err;
}

console.log('HowToGo browser map -- frontend checks');
console.log('fixture: ' + fixturePath + (usingBundled ? ' (bundled)' : ' (supplied)'));
if (fixtureLoadError) console.log('fixture could not be read: ' + fixtureLoadError.message);

// A payload that can never fail to parse, so a broken fixture still leaves the
// structural checks running rather than turning the whole run into one line of stack.
// It is a complete little network on purpose -- three storeys, both one-way
// directions, places, and a bridge over a road -- so the failures that come back are
// about the fixture that could not be read rather than about the fallback.
const modelPayload = fixture || {
  version: 1,
  generatedAt: 1760000000000,
  world: 'sp_FALLBACK',
  dimension: 'minecraft:overworld',
  empty: false,
  bounds: {minX: -100, minZ: -100, maxX: 100, maxZ: 100},
  classes: model.DEFAULT_CLASSES,
  nodes: [
    {id: 1, x: -100, y: 64, z: 0, type: 'JUNCTION', name: '甲'},
    {id: 2, x: 100, y: 64, z: 0, type: 'ENDPOINT', name: '乙'},
    {id: 3, x: 0, y: 64, z: 0, type: 'POI', placeKind: 'STATION', name: '车站'},
    {id: 4, x: 50, y: 64, z: 50, type: 'POI', placeKind: 'SHOP', name: '商店'},
  ],
  segments: [
    {id: 1, roadClass: 'ROAD', y: 64, layer: 0, from: 1, to: 2, direction: 'FORWARD',
      name: '甲到乙', points: [[-100, 0], [0, 0], [100, 0]]},
    {id: 2, roadClass: 'PATH', y: 64, layer: 0, from: 2, to: 1, direction: 'BACKWARD',
      name: '乙到甲', points: [[100, 0], [60, -60], [0, -60], [-100, 0]]},
    {id: 3, roadClass: 'HIGHWAY', y: 72, layer: 1, from: -1, to: -1, direction: 'TWO_WAY',
      name: '高架', points: [[-50, -100], [-50, 100]]},
    {id: 4, roadClass: 'ROAD', y: 52, layer: -1, from: -1, to: -1, direction: 'TWO_WAY',
      name: '隧道', points: [[-60, 60], [-20, 90], [40, 90]]},
  ],
  stats: {nodes: 4, segments: 4, lengthBlocks: 758.7, layers: [-1, 0, 1]},
};

const m = model.normalize(modelPayload);

// ---------------------------------------------------------------------------------
// 1. geom
// ---------------------------------------------------------------------------------
section('geom: the view transform', () => {
  const bounds = {minX: -100, minZ: -50, maxX: 100, maxZ: 50};
  const view = geom.fitView(bounds, 400, 300, {padding: 0});

  // 200 blocks across 400 pixels and 100 down 300: the width is the binding constraint.
  ok('fit-to-bounds picks the scale that fits both axes', close(view.scale, 2, 1e-12), 'scale=' + view.scale);
  ok('fit-to-bounds centres the content',
    close(view.centerX, 0, 1e-12) && close(view.centerZ, 0, 1e-12),
    JSON.stringify({centerX: view.centerX, centerZ: view.centerZ}));

  const middle = geom.worldToScreen(view, view.centerX, view.centerZ);
  ok('the centre of the bounds lands in the middle of the viewport',
    close(middle.x, 200, 1e-9) && close(middle.y, 150, 1e-9), JSON.stringify(middle));

  const topLeft = geom.worldToScreen(view, bounds.minX, bounds.minZ);
  const bottomRight = geom.worldToScreen(view, bounds.maxX, bounds.maxZ);
  ok('the fitted content is inside the viewport',
    topLeft.x >= -1e-9 && topLeft.y >= -1e-9 && bottomRight.x <= 400 + 1e-9 && bottomRight.y <= 300 + 1e-9,
    JSON.stringify({topLeft, bottomRight}));
  ok('and is not smaller than it had to be',
    close(topLeft.x, 0, 1e-9) || close(bottomRight.x, 400, 1e-9),
    JSON.stringify({topLeft, bottomRight}));

  const padded = geom.fitView(bounds, 400, 300, {padding: 20});
  ok('padding keeps the requested margin clear',
    close(geom.worldToScreen(padded, bounds.minX, 0).x, 20, 1e-9) ||
    close(geom.worldToScreen(padded, bounds.minX, 0).x, 400 - 20 - 200 * padded.scale, 1e-9),
    'scale=' + padded.scale);

  let worst = 0;
  const samples = [[0, 0], [37.5, -12.25], [-99.5, 49.5], [1000, -1000]];
  for (const [x, z] of samples) {
    for (const v of [view, geom.makeView({centerX: 12, centerZ: -30, scale: 0.37, width: 800, height: 600})]) {
      const s = geom.worldToScreen(v, x, z);
      const back = geom.screenToWorld(v, s.x, s.y);
      worst = Math.max(worst, Math.abs(back.x - x), Math.abs(back.z - z));
    }
  }
  ok('world -> screen -> world round-trips within 1e-6', worst <= 1e-6, 'worst error ' + worst);

  // Zoom clamping.
  ok('scale is clamped at the maximum',
    close(geom.clampScale(1e9), geom.MAX_SCALE, 1e-12));
  ok('scale is clamped at the minimum',
    close(geom.clampScale(1e-9), geom.MIN_SCALE, 1e-12));
  ok('a nonsense scale falls back to a usable one',
    isFinite(geom.clampScale(undefined)) && geom.clampScale(undefined) > 0);
  const zoomedIn = geom.zoomAt(view, 1e9, 100, 100);
  ok('zooming past the maximum stops at it', close(zoomedIn.scale, geom.MAX_SCALE, 1e-12), 'scale=' + zoomedIn.scale);
  const zoomedOut = geom.zoomAt(view, 1e-9, 100, 100);
  ok('zooming past the minimum stops at it', close(zoomedOut.scale, geom.MIN_SCALE, 1e-12), 'scale=' + zoomedOut.scale);

  // Wheel zoom is anchored under the cursor.
  const anchor = geom.screenToWorld(view, 260, 90);
  const zoomed = geom.zoomAt(view, 1.75, 260, 90);
  const anchorAfter = geom.worldToScreen(zoomed, anchor.x, anchor.z);
  ok('wheel zoom keeps the world point under the cursor',
    close(anchorAfter.x, 260, 1e-9) && close(anchorAfter.y, 90, 1e-9),
    JSON.stringify(anchorAfter));

  const panned = geom.panBy(view, 40, -20);
  ok('panning moves by the drag in world units',
    close(panned.centerX, view.centerX - 20, 1e-9) && close(panned.centerZ, view.centerZ + 10, 1e-9),
    JSON.stringify({centerX: panned.centerX, centerZ: panned.centerZ}));

  const visible = geom.visibleBounds(view);
  const atCorner = geom.screenToWorld(view, 0, 0);
  ok('visible bounds match the viewport corners',
    close(visible.minX, atCorner.x, 1e-9) && close(visible.minZ, atCorner.z, 1e-9),
    JSON.stringify({visible, atCorner}));
  ok('visible bounds span the viewport at the current scale',
    close(visible.maxX - visible.minX, view.width / view.scale, 1e-9) &&
    close(visible.maxZ - visible.minZ, view.height / view.scale, 1e-9));

  // The degenerate cases the contract calls out: an empty network (all-zero bounds)
  // and a one-point network (zero span on both axes).
  const zero = geom.fitView({minX: 0, minZ: 0, maxX: 0, maxZ: 0}, 400, 300, {});
  ok('zero-size bounds give a finite, positive scale',
    isFinite(zero.scale) && zero.scale > 0, 'scale=' + zero.scale);
  const empty = geom.fitView(null, 0, 0, {});
  ok('a missing bounds object and a zero-size viewport stay finite',
    isFinite(empty.scale) && empty.scale > 0 && isFinite(empty.centerX) && isFinite(empty.centerZ),
    JSON.stringify(empty));
  const single = geom.fitView({minX: 12, minZ: -8, maxX: 12, maxZ: -8}, 640, 480, {padding: 16});
  ok('a single-point network centres on the point without dividing by zero',
    close(single.centerX, 12, 1e-12) && close(single.centerZ, -8, 1e-12) && isFinite(single.scale),
    JSON.stringify(single));

  ok('polyline length is the sum of the pieces',
    close(geom.polylineLength([[0, 0], [3, 4], [3, 14]]), 15, 1e-12),
    String(geom.polylineLength([[0, 0], [3, 4], [3, 14]])));
  // Halfway along this polyline is 15 blocks in: 10 along the first leg and 5 up the
  // second, which is exactly where an index-based midpoint would not land.
  const mid = geom.pointAlong([[0, 0], [10, 0], [10, 20]], 0.5);
  ok('pointAlong walks by arc length, not by index',
    close(mid.x, 10, 1e-9) && close(mid.z, 5, 1e-9), JSON.stringify(mid));
  ok('pointAlong reports the local direction',
    close(Math.abs(mid.angle), 90, 1e-9), 'angle=' + mid.angle);
  const near = geom.nearestOnPolyline([[0, 0], [10, 0], [10, 20]], 8, 5);
  ok('nearest point on a polyline lands on the line',
    close(near.distance, 2, 1e-9) && close(near.x, 10, 1e-9) && close(near.z, 5, 1e-9),
    JSON.stringify(near));

  const angles = [0, 90, -90, 91, -91, 180, -180, 270, 359];
  ok('text angles are kept upright, inside (-90, 90]',
    angles.every((a) => geom.uprightAngle(a) > -90 - 1e-9 && geom.uprightAngle(a) <= 90 + 1e-9),
    JSON.stringify(angles.map(geom.uprightAngle)));
});

// ---------------------------------------------------------------------------------
// 2. model
// ---------------------------------------------------------------------------------
section('model: normalising the payload', () => {
  ok('the payload parses as JSON', !fixtureLoadError, fixtureLoadError && fixtureLoadError.message);
  ok('nodes and segments are arrays', Array.isArray(m.nodes) && Array.isArray(m.segments));
  ok('counts add up to the segments',
    m.segments.length === Object.keys(m.classCounts).reduce((n, k) => n + m.classCounts[k], 0) &&
    m.segments.length === Object.keys(m.layerCounts).reduce((n, k) => n + m.layerCounts[k], 0),
    JSON.stringify({segments: m.segments.length, classCounts: m.classCounts, layerCounts: m.layerCounts}));
  ok('every segment carries its class entry', m.segments.every((s) => s.klass && s.klass.color));
  ok('storeys are listed ascending',
    m.layers.every((layer, i) => i === 0 || m.layers[i - 1] < layer), JSON.stringify(m.layers));
  ok('each storey is bucketed under itself',
    Object.keys(m.segmentsByLayer).every((k) => m.segmentsByLayer[k].every((s) => String(s.layer) === String(k))));

  const missingName = {segments: [{id: 1, roadClass: 'ROAD', points: [[0, 0], [10, 0]]}]};
  ok('a road with no name is unnamed, not "undefined"',
    model.normalize(missingName).segments[0].name === null &&
    model.normalize(missingName).segments[0].hasName === false);
  const nullName = {segments: [{id: 1, roadClass: 'ROAD', name: null, points: [[0, 0], [10, 0]]}]};
  ok('an explicit null name is unnamed too', model.normalize(nullName).segments[0].name === null);
  const blankName = {segments: [{id: 1, roadClass: 'ROAD', name: '   ', points: [[0, 0], [10, 0]]}]};
  ok('a whitespace-only name is unnamed', model.normalize(blankName).segments[0].hasName === false);

  const unknown = model.normalize({
    classes: [{id: 'ROAD', color: '#3FD07A', width: 5}],
    segments: [{id: 9, roadClass: 'MYSTERY', points: [[0, 0], [5, 0]]}],
  });
  const fallback = model.classOf(unknown, unknown.segments[0]);
  ok('an unknown road class falls back to grey at width 4',
    fallback.color === model.UNKNOWN_COLOR && fallback.width === model.UNKNOWN_WIDTH,
    JSON.stringify(fallback));
  ok('and the fallback class still reaches the legend',
    unknown.classes.some((c) => c.id === 'MYSTERY' && c.color === model.UNKNOWN_COLOR),
    JSON.stringify(unknown.classes.map((c) => c.id)));
  ok('a class table with one entry does not make the other five disappear',
    model.normalize({
      classes: [{id: 'ROAD', color: '#3FD07A', width: 5}],
      segments: [{id: 1, roadClass: 'HIGHWAY', points: [[0, 0], [5, 0]]}],
    }).classes.some((c) => c.id === 'HIGHWAY'));

  const emptyModel = model.normalize({
    version: 1, empty: true, bounds: {minX: 0, minZ: 0, maxX: 0, maxZ: 0},
    classes: model.DEFAULT_CLASSES, nodes: [], segments: [],
    stats: {nodes: 0, segments: 0, lengthBlocks: 0, layers: []},
  });
  ok('an empty network is empty and keeps all-zero bounds',
    emptyModel.empty === true && emptyModel.layers.length === 0 &&
    emptyModel.bounds.maxX === 0 && emptyModel.bounds.minZ === 0,
    JSON.stringify(emptyModel.bounds));
  ok('an empty network survives being drawn', (() => {
    const ctx = recordingContext();
    const view = geom.fitView(emptyModel.bounds, 400, 300, {});
    const placement = render.drawMap(ctx, emptyModel, view, {message: '暂无道路数据'});
    return placement.placed.length === 0 && ctx.calls.length > 0;
  })());
  ok('a null payload normalises to an empty model rather than throwing',
    model.normalize(null).segments.length === 0 && model.normalize(undefined).empty === true);

  const degenerate = model.normalize({
    segments: [
      {id: 1, roadClass: 'ROAD', points: [[0, 0]]},
      {id: 2, roadClass: 'ROAD'},
      {id: 3, roadClass: 'ROAD', points: [[0, 0], [0, 0]]},
    ],
  });
  ok('segments with too few points are kept but not drawable',
    degenerate.segments[0].drawable === false && degenerate.segments[1].drawable === false &&
    degenerate.segments[0].points.length === 1 && degenerate.segments[1].points.length === 0,
    JSON.stringify(degenerate.segments.map((s) => ({id: s.id, points: s.points.length, drawable: s.drawable}))));
  ok('a two-point segment of zero length is drawable but contributes no length',
    degenerate.segments[2].drawable === true &&
    close(degenerate.segments[2].lengthBlocks, 0, 1e-9) &&
    close(degenerate.stats.computed.lengthBlocks, 0, 1e-9));

  const clamped = model.normalize({
    segments: [
      {id: 1, roadClass: 'ROAD', layer: 99, points: [[0, 0], [1, 0]]},
      {id: 2, roadClass: 'ROAD', layer: -99, points: [[0, 0], [1, 0]]},
      {id: 3, roadClass: 'ROAD', points: [[0, 0], [1, 0]]},
    ],
  });
  ok('storeys are clamped to -32..32 and default to the surface',
    clamped.segments[0].layer === 32 && clamped.segments[1].layer === -32 && clamped.segments[2].layer === 0,
    JSON.stringify(clamped.segments.map((s) => s.layer)));

  const unicode = model.normalize({
    world: '存档 世界',
    segments: [{id: 7, roadClass: 'ROAD', name: '中央大道 <b>"& 东段', points: [[0, 0], [1, 0]]}],
    nodes: [{id: 3, x: 0, y: 0, z: 0, type: 'POI', name: '人民广场 <b>"&'}],
  });
  ok('a Chinese name survives untouched',
    unicode.segments[0].name === '中央大道 <b>"& 东段' && unicode.nodes[0].name === '人民广场 <b>"&',
    JSON.stringify(unicode.segments[0].name));
  ok('and survives a JSON round trip unchanged',
    JSON.parse(JSON.stringify(unicode)).segments[0].name === '中央大道 <b>"& 东段');

  const boundsOk = m.nodes.every((n) => n.x >= m.bounds.minX - 1e-9 && n.x <= m.bounds.maxX + 1e-9 &&
    n.z >= m.bounds.minZ - 1e-9 && n.z <= m.bounds.maxZ + 1e-9) &&
    m.segments.every((s) => s.points.every((p) => p.x >= m.bounds.minX - 1e-9 && p.x <= m.bounds.maxX + 1e-9 &&
      p.z >= m.bounds.minZ - 1e-9 && p.z <= m.bounds.maxZ + 1e-9));
  ok('bounds contain every node and every segment point', boundsOk, JSON.stringify(m.bounds));

  const computed = m.stats.computed;
  const lengthTolerance = Math.max(0.5, computed.lengthBlocks * 0.001);
  ok('the reported stats agree with the geometry',
    computed.nodes === m.stats.nodes && computed.segments === m.stats.segments &&
    Math.abs(computed.lengthBlocks - m.stats.lengthBlocks) <= lengthTolerance,
    JSON.stringify({reported: m.stats.lengthBlocks, computed: computed.lengthBlocks}));
  ok('the reported storey list is the one in the data',
    JSON.stringify(m.stats.layers) === JSON.stringify(computed.layers),
    JSON.stringify({reported: m.stats.layers, computed: computed.layers}));

  ok('a 503 body is reported as an error, not as an empty map',
    model.errorOf({error: '道路数据尚未生成'}) === '道路数据尚未生成');
  ok('a payload with no error field is not an error',
    model.errorOf(modelPayload) === null);
  ok('an empty response body is an error', model.errorOf(null) === '响应为空');
  ok('a non-object body is an error', typeof model.errorOf('nope') === 'string');

  if (usingBundled) {
    ok('the fixture is a real network, not a stub',
      m.nodes.length >= 20 && m.segments.length >= 40,
      m.nodes.length + ' nodes, ' + m.segments.length + ' segments');
    ok('the fixture covers all six road classes',
      model.DEFAULT_CLASSES.every((c) => (m.classCounts[c.id] || 0) > 0),
      JSON.stringify(m.classCounts));
    ok('the fixture has at least 40 named roads',
      m.namedSegmentCount >= 40, String(m.namedSegmentCount));
    ok('the fixture has a bridge over the surface and a tunnel under it',
      m.layers.indexOf(1) >= 0 && m.layers.indexOf(-1) >= 0, JSON.stringify(m.layers));
    ok('the fixture has both one-way directions',
      m.segments.some((s) => s.direction === 'FORWARD') && m.segments.some((s) => s.direction === 'BACKWARD'));
    ok('the fixture has all four place kinds',
      model.PLACE_KINDS.every((k) => m.nodes.some((n) => n.placeKind === k)),
      JSON.stringify(m.nodes.map((n) => n.placeKind)));
    ok('the fixture has all three node types',
      model.NODE_TYPES.every((t) => m.nodes.some((n) => n.type === t)));
    ok('the fixture has unnamed roads and an unnamed place',
      m.segments.some((s) => !s.hasName) && m.nodes.some((n) => !n.hasName));

    // The bridge is a layer 1 line crossing a layer 0 line at right angles, with no
    // node where they meet -- the case the occlusion rules exist for.
    const bridge = m.segments.find((s) => s.layer === 1);
    let hits = [];
    if (bridge) {
      for (const other of m.segments) {
        if (other.layer !== 0) continue;
        hits = hits.concat(crossingsBetween(bridge, other).map((hit) => ({hit, other})));
      }
    }
    ok('the fixture has a bridge crossing a surface road at a shared-looking point',
      !!bridge && hits.length >= 1,
      bridge ? 'bridge ' + bridge.id + ', crossings ' + hits.length : 'no layer 1 segment');
    ok('that crossing is at a right angle',
      hits.length >= 1 && hits.every(({hit}) => {
        const dot = hit.dir1.x * hit.dir2.x + hit.dir1.z * hit.dir2.z;
        const scale = Math.sqrt(hit.dir1.x * hit.dir1.x + hit.dir1.z * hit.dir1.z) *
          Math.sqrt(hit.dir2.x * hit.dir2.x + hit.dir2.z * hit.dir2.z);
        return Math.abs(dot / scale) < 0.02;
      }),
      JSON.stringify(hits.map(({hit}) => [hit.x, hit.z])));
    ok('and there is no node at the crossing, so it really is two roads and not a junction',
      hits.length >= 1 && hits.every(({hit}) => m.nodes.every((n) =>
        Math.abs(n.x - hit.x) > 1e-6 || Math.abs(n.z - hit.z) > 1e-6)),
      JSON.stringify(hits.map(({hit}) => [hit.x, hit.z])));
  } else {
    console.log('note: fixture-specific shape checks skipped for a supplied fixture');
  }
});

// ---------------------------------------------------------------------------------
// 3. labels
// ---------------------------------------------------------------------------------
section('labels: greedy placement', () => {
  const fontPx = 12;
  const measure = (text) => String(text).length * fontPx * 0.6;

  /*
   * The crowded case: 140 labels anchored on a 16-column grid inside 520x360, each
   * about 39x18 pixels. That is more than half again the area the boxes need, and the
   * anchors are closer together than a box is wide, so neighbour fights neighbour and
   * a good third of them cannot be placed anywhere. It is meant to be a case where
   * dropping is the right answer -- what is asserted is that nothing overlaps, nothing
   * leaves the viewport, and the majority still finds a home.
   */
  function crowd(count, width, height) {
    const items = [];
    const columns = 16;
    const fontPx = 12;
    for (let i = 0; i < count; i++) {
      const col = i % columns;
      const row = Math.floor(i / columns);
      const x = 24 + col * 31;
      const y = 24 + row * 34;
      const candidates = [];
      // Five offsets each way, so a label can dodge a neighbour that got there first.
      for (let k = 0; k < 25; k++) {
        const ox = k % 5;
        const oy = Math.floor(k / 5);
        candidates.push({x: x + (ox - 2) * 6, y: y + (oy - 2) * 8, angle: 0});
      }
      items.push({
        id: i,
        kind: i % 5 === 0 ? 'place' : 'road',
        text: '路段名' + i,
        x,
        y,
        angle: 0,
        priority: i % 7,
        candidates,
      });
    }
    return {items, options: {width, height, fontPx, padding: 1.5, measure}};
  }

  const crowded = crowd(140, 520, 360);
  const first = labels.placeLabels(crowded.items, crowded.options);
  const second = labels.placeLabels(JSON.parse(JSON.stringify(crowded.items)), crowded.options);

  const boxList = first.placed.map((p) => p.box);
  let overlapping = 0;
  for (let i = 0; i < boxList.length; i++) {
    for (let j = i + 1; j < boxList.length; j++) {
      const a = boxList[i];
      const b = boxList[j];
      if (a.x < b.x + b.w - 1e-9 && b.x < a.x + a.w - 1e-9 &&
          a.y < b.y + b.h - 1e-9 && b.y < a.y + a.h - 1e-9) overlapping++;
    }
  }
  ok('a crowded map places labels without a single overlap', overlapping === 0, overlapping + ' overlapping pairs');
  ok('every placed label is fully inside the viewport',
    first.placed.every((p) => p.box.x >= -1e-9 && p.box.y >= -1e-9 &&
      p.box.x + p.box.w <= crowded.options.width + 1e-9 &&
      p.box.y + p.box.h <= crowded.options.height + 1e-9),
    JSON.stringify(first.placed.filter((p) => p.box.x < 0 || p.box.y < 0).slice(0, 2)));
  ok('a crowded map still places most of its labels',
    first.placed.length >= crowded.items.length * 0.6,
    first.placed.length + ' of ' + crowded.items.length + ' placed');
  ok('placement is deterministic: the same input gives the same boxes',
    JSON.stringify(first.placed.map((p) => [p.id, p.box, p.angle])) ===
    JSON.stringify(second.placed.map((p) => [p.id, p.box, p.angle])));
  ok('the same input also gives the same drops',
    JSON.stringify(first.dropped.map((p) => p.id)) === JSON.stringify(second.dropped.map((p) => p.id)));

  const alone = labels.placeLabels(
    [{id: 5, kind: 'place', text: '人民广场', x: 200, y: 150, priority: 0,
      candidates: [{x: 200, y: 150, angle: 0}, {x: 200, y: 200, angle: 0}]}],
    {width: 400, height: 300, fontPx: 13, padding: 1.5, measure: (t) => t.length * 13 * 0.6});
  ok('a single label takes its primary anchor',
    alone.placed.length === 1 && alone.placed[0].candidateIndex === 0 &&
    alone.placed[0].anchor.x === 200 && alone.placed[0].anchor.y === 150,
    JSON.stringify(alone.placed[0] && alone.placed[0].anchor));

  const noRoom = labels.placeLabels(
    [{id: 1, kind: 'road', text: '很长很长的一条路名', x: 5, y: 5,
      candidates: [{x: 5, y: 5, angle: 0}]}],
    {width: 40, height: 10, fontPx: 13, padding: 1.5, measure: (t) => t.length * 13 * 0.6});
  ok('a label with nowhere to go is dropped, not drawn over something',
    noRoom.placed.length === 0 && noRoom.dropped.length === 1 && noRoom.dropped[0].id === 1,
    JSON.stringify({placed: noRoom.placed.length, dropped: noRoom.dropped.map((d) => d.reason)}));

  const nothing = labels.placeLabels([], {width: 400, height: 300, fontPx: 12});
  ok('an empty input is not a crash',
    nothing.placed.length === 0 && nothing.dropped.length === 0);
  const blank = labels.placeLabels(
    [{id: 1, text: '', candidates: [{x: 10, y: 10, angle: 0}]}, {id: 2, text: '有名字'}],
    {width: 400, height: 300, fontPx: 12, measure});
  ok('a label with no text and one with no candidates are dropped cleanly',
    blank.placed.length === 0 && blank.dropped.length === 2,
    JSON.stringify(blank.dropped.map((d) => d.reason)));

  const contested = labels.placeLabels(
    [{id: 1, kind: 'road', text: '小路', x: 100, y: 100, priority: 1 + model.ROAD_CLASS_RANK.PATH,
      candidates: [{x: 100, y: 100, angle: 0}]},
      {id: 2, kind: 'road', text: '高速', x: 100, y: 100, priority: 1 + model.ROAD_CLASS_RANK.HIGHWAY,
        candidates: [{x: 100, y: 100, angle: 0}]}],
    {width: 400, height: 300, fontPx: 12, measure});
  ok('a higher class wins a contested position',
    contested.placed.length === 1 && contested.placed[0].id === 2,
    JSON.stringify(contested.placed.map((p) => p.id)));

  const placeFirst = labels.placeLabels(
    [{id: 1, kind: 'road', text: '道路', x: 100, y: 100, priority: 1,
      candidates: [{x: 100, y: 100, angle: 0}]},
      {id: 2, kind: 'place', text: '地点', x: 100, y: 100, priority: 0,
        candidates: [{x: 100, y: 100, angle: 0}]}],
    {width: 400, height: 300, fontPx: 12, measure});
  ok('a place beats a road for the same position',
    placeFirst.placed.length === 1 && placeFirst.placed[0].id === 2,
    JSON.stringify(placeFirst.placed.map((p) => p.id)));

  const rotated = labels.placeLabels(
    [{id: 1, kind: 'road', text: '北向道路', x: 200, y: 150, candidates: [{x: 200, y: 150, angle: 110}]}],
    {width: 400, height: 300, fontPx: 12, measure});
  ok('an upside-down angle is flipped into range, and the box follows the flip',
    rotated.placed.length === 1 && rotated.placed[0].angle === -70 &&
    rotated.placed[0].box.w > 0 && rotated.placed[0].box.h > 0,
    JSON.stringify(rotated.placed[0] && {angle: rotated.placed[0].angle, box: rotated.placed[0].box}));
  const wrapped = labels.placeLabels(
    [{id: 1, kind: 'road', text: '南向道路', x: 200, y: 150, candidates: [{x: 200, y: 150, angle: -115}]}],
    {width: 400, height: 300, fontPx: 12, measure});
  ok('an angle past -90 wraps the other way too',
    wrapped.placed.length === 1 && close(wrapped.placed[0].angle, 65, 1e-9),
    String(wrapped.placed[0] && wrapped.placed[0].angle));

  // "Never hangs" is a real requirement, so it is timed rather than assumed.
  const many = crowd(5000, 4000, 3000);
  const started = Date.now();
  const huge = labels.placeLabels(many.items, many.options);
  const elapsed = Date.now() - started;
  ok('placing 5000 labels finishes, and finishes quickly',
    huge.placed.length + huge.dropped.length === 5000 && elapsed < 5000,
    elapsed + ' ms');

  // The same invariant, but with the real names and geometry of the fixture: the
  // dense grid is drawn into a small window so every label is competing for room.
  const gridView = geom.fitView({minX: 0, minZ: 0, maxX: 660, maxZ: 660}, 420, 300, {padding: 8});
  const items = render.buildLabelItems(m, gridView, {});
  const placedHere = labels.placeLabels(items, {
    width: gridView.width, height: gridView.height, fontPx: 13, padding: 1.5,
    measure: (t) => String(t).length * 13 * 0.6,
  });
  let realOverlaps = 0;
  for (let i = 0; i < placedHere.placed.length; i++) {
    for (let j = i + 1; j < placedHere.placed.length; j++) {
      const a = placedHere.placed[i].box;
      const b = placedHere.placed[j].box;
      if (a.x < b.x + b.w - 1e-9 && b.x < a.x + a.w - 1e-9 &&
          a.y < b.y + b.h - 1e-9 && b.y < a.y + a.h - 1e-9) realOverlaps++;
    }
  }
  ok('the fixture\'s dense grid places labels with no overlaps on a small map',
    realOverlaps === 0 && items.length > 0,
    realOverlaps + ' overlaps over ' + items.length + ' candidates');
  if (usingBundled && items.length >= 10) {
    // A share of the labels placed is a fact about how crowded *this* window is, which is a fact
    // about the bundled fixture's grid. No overlap is the rule and is asserted for any payload; how
    // many fit is not, so it is asserted only where the input was designed to be tight.
    ok('and still places most of them',
      placedHere.placed.length >= items.length * 0.6,
      placedHere.placed.length + ' of ' + items.length);
  }
  ok('and drops the rest rather than overlapping them',
    placedHere.placed.length + placedHere.dropped.length === items.length &&
    (placedHere.dropped.length === 0 || placedHere.dropped.every((d) => d.reason === 'no-fit')),
    JSON.stringify({placed: placedHere.placed.length, dropped: placedHere.dropped.length}));
});

// ---------------------------------------------------------------------------------
// 4. render
// ---------------------------------------------------------------------------------
section('render: the occlusion order', () => {
  const view = geom.fitView(m.bounds, 900, 620, {padding: 24});
  const ctx = recordingContext();
  render.drawMap(ctx, m, view, {});
  const ops = paths(ctx.calls);

  // Every segment that is drawn, with the screen path it is drawn as.
  const classColors = new Set(m.classes.map((c) => c.color));
  const drawable = m.segments.filter((s) => s.drawable);
  const expected = new Map();
  for (const seg of drawable) {
    const sig = signature(render.segmentScreenPoints(seg, view).map((p) => [round6(p.x), round6(p.y)]));
    if (!expected.has(sig)) expected.set(sig, {seg, layers: new Set()});
    expected.get(sig).layers.add(seg.layer);
  }

  const roadStrokes = ops.filter((op) => op.kind === 'stroke' &&
    (op.strokeStyle === render.CASING_COLOR || classColors.has(op.strokeStyle)) &&
    op.points.length >= 2);
  const casings = roadStrokes.filter((op) => op.strokeStyle === render.CASING_COLOR);
  const fills = roadStrokes.filter((op) => classColors.has(op.strokeStyle));

  ok('every drawable segment gets a casing and a fill',
    casings.length === drawable.length && fills.length === drawable.length,
    casings.length + ' casings, ' + fills.length + ' fills, ' + drawable.length + ' segments');
  ok('every road stroke matches a segment in the model',
    roadStrokes.every((op) => expected.has(signature(op.points))),
    String(roadStrokes.filter((op) => !expected.has(signature(op.points))).length) + ' unmatched');

  // (a) storeys are painted bottom-up, and each storey occupies one contiguous run.
  const layerOfOp = (op) => {
    const entry = expected.get(signature(op.points));
    return entry ? Math.min.apply(null, Array.from(entry.layers)) : null;
  };
  const runs = [];
  const rangeByLayer = new Map();
  for (const op of roadStrokes) {
    const layer = layerOfOp(op);
    if (layer === null) continue;
    if (!runs.length || runs[runs.length - 1].layer !== layer) {
      runs.push({layer, count: 0, firstIndex: op.index, lastIndex: op.index});
    }
    const run = runs[runs.length - 1];
    run.count++;
    run.lastIndex = op.index;
    const range = rangeByLayer.get(layer) || {min: op.index, max: op.index};
    range.min = Math.min(range.min, op.index);
    range.max = Math.max(range.max, op.index);
    rangeByLayer.set(layer, range);
  }
  ok('road paint is grouped by storey, ascending',
    runs.every((run, i) => i === 0 || runs[i - 1].layer < run.layer),
    JSON.stringify(runs));
  ok('and every storey with a drawable segment is painted exactly once',
    runs.length === m.layers.filter((l) => (m.segmentsByLayer[l] || []).some((s) => s.drawable)).length,
    runs.length + ' runs for ' + JSON.stringify(m.layers));

  const tunnelRun = runs.find((r) => r.layer === -1);
  const surfaceRun = runs.find((r) => r.layer === 0);
  const bridgeRun = runs.find((r) => r.layer === 1);
  if (usingBundled) {
    ok('the fixture really does exercise all three storeys',
      !!tunnelRun && !!surfaceRun && !!bridgeRun,
      JSON.stringify(runs));
    ok('the tunnel is painted under the surface and the bridge over it',
      !!tunnelRun && !!surfaceRun && !!bridgeRun &&
      runs.indexOf(tunnelRun) < runs.indexOf(surfaceRun) &&
      runs.indexOf(surfaceRun) < runs.indexOf(bridgeRun));
  } else {
    console.log('note: the bridge/tunnel shape checks are fixture-specific and were skipped');
  }
  const layerOrder = Array.from(rangeByLayer.keys()).sort((a, b) => a - b);
  ok('no lower storey paints over a higher one: every op of a storey precedes every op of the next',
    layerOrder.every((layer, i) => i === 0 ||
      rangeByLayer.get(layerOrder[i - 1]).max < rangeByLayer.get(layer).min),
    JSON.stringify(layerOrder.map((l) => [l, rangeByLayer.get(l)])));

  // (b) all casings in a storey precede all its fills, and each segment's own casing
  // precedes its own fill.
  const casingIndex = new Map();
  for (const op of casings) casingIndex.set(signature(op.points), op.index);
  const fillIndex = new Map();
  for (const op of fills) {
    const sig = signature(op.points);
    if (!fillIndex.has(sig)) fillIndex.set(sig, op.index);
    else fillIndex.set(sig, Math.min(fillIndex.get(sig), op.index));
  }
  const wrongOrder = [];
  for (const [sig, i] of casingIndex) {
    if (!fillIndex.has(sig)) continue;
    if (!(i < fillIndex.get(sig))) wrongOrder.push(sig);
  }
  ok('inside a storey every segment\'s casing is drawn before its fill',
    wrongOrder.length === 0, wrongOrder.length + ' out of order');

  let perStoreyInterleaved = 0;
  for (const layer of m.layers) {
    const sigs = new Set();
    for (const seg of m.segmentsByLayer[layer] || []) {
      sigs.add(signature(render.segmentScreenPoints(seg, view).map((p) => [round6(p.x), round6(p.y)])));
    }
    const cs = [...casingIndex].filter(([sig]) => sigs.has(sig)).map(([, i]) => i);
    const fs = [...fillIndex].filter(([sig]) => sigs.has(sig)).map(([, i]) => i);
    if (cs.length && fs.length && Math.max.apply(null, cs) > Math.min.apply(null, fs)) perStoreyInterleaved++;
  }
  ok('all the casings of a storey come before all of its fills',
    perStoreyInterleaved === 0, perStoreyInterleaved + ' storeys interleaved');

  // (c) markers and text come after the roads, and text comes last of all.
  const fillTexts = indicesOf(ctx.calls, 'fillText');
  const haloTexts = indicesOf(ctx.calls, 'strokeText');
  const lastRoadOp = roadStrokes.length ? roadStrokes[roadStrokes.length - 1].index : -1;
  const named = m.segments.filter((s) => s.drawable && s.hasName).length +
    m.nodes.filter((n) => n.type === 'POI' && n.hasName).length;
  if (named > 0) {
    ok('something was labelled', fillTexts.length > 0, fillTexts.length + ' labels for ' + named + ' names');
  }
  ok('every label is drawn after the last road stroke',
    fillTexts.length === 0 || Math.min.apply(null, fillTexts) > lastRoadOp,
    JSON.stringify({firstLabel: fillTexts.length ? Math.min.apply(null, fillTexts) : null, lastRoad: lastRoadOp}));
  ok('every label is haloed before it is filled',
    fillTexts.length === haloTexts.length &&
    fillTexts.every((index, i) => haloTexts[i] < index),
    JSON.stringify({fillTexts, haloTexts}));

  const placeColours = new Set(model.PLACE_KINDS.map((k) => model.PLACE_KIND_COLORS[k])
    .concat([model.PLACE_COLOR_FALLBACK]));

  // The marker order is checked on a model built here rather than on the loaded payload. A network is
  // free to contain no junction at all, or no place -- a real one often has neither -- and what is
  // being asserted is a rule of the renderer: dots, then place markers, then text. Reading it off
  // whichever fixture happened to be supplied would make the rule depend on the payload's shape.
  const markerModel = model.normalize({
    version: 1,
    generatedAt: 1,
    world: 'test',
    dimension: 'test:test',
    empty: false,
    bounds: {minX: 0, minZ: 0, maxX: 100, maxZ: 100},
    classes: [{id: 'ROAD', color: '#3FD07A', width: 5}],
    nodes: [
      {id: 1, x: 10, y: 64, z: 10, type: 'JUNCTION'},
      {id: 2, x: 50, y: 64, z: 50, type: 'ENDPOINT'},
      {id: 3, x: 90, y: 64, z: 90, type: 'POI', placeKind: 'SHOP', name: '店铺'},
    ],
    segments: [],
    stats: {nodes: 3, segments: 0, lengthBlocks: 0, layers: []},
  });
  const markerCtx = recordingContext();
  const markerView = geom.fitView(markerModel.bounds, 400, 300, {padding: 20});
  render.drawMap(markerCtx, markerModel, markerView, {});
  const markerOps = paths(markerCtx.calls);
  const junctionFills = markerOps.filter((op) => op.kind === 'fill' && op.fillStyle === render.MARKER_COLOR);
  const markerFills = markerOps.filter((op) => op.kind === 'fill' && placeColours.has(op.fillStyle));
  const markerTexts = indicesOf(markerCtx.calls, 'fillText');
  ok('a model with one junction in it draws exactly one junction dot',
    junctionFills.length === 1, String(junctionFills.length));
  ok('place markers are drawn, and are drawn before the text',
    markerFills.length === 1 && markerTexts.length === 1 &&
    markerFills[0].index < markerTexts[0],
    JSON.stringify({markers: markerFills.length, texts: markerTexts.length}));
  ok('junction dots come before place markers',
    junctionFills.length === 1 && markerFills.length === 1 &&
    junctionFills[0].index < markerFills[0].index,
    JSON.stringify({dot: junctionFills[0] && junctionFills[0].index,
      marker: markerFills[0] && markerFills[0].index}));

  // The sign of travel, independently of any drawing and of any fixture: FORWARD runs
  // from `from` to `to` however the polyline happens to be stored.
  const unitModel = {nodeById: {'5': {x: 0, z: 0}, '6': {x: 100, z: 0}}};
  const unitPoints = [{x: 0, z: 0}, {x: 100, z: 0}];
  ok('travel direction is read off the endpoint nodes when it can be',
    render.arrowTravelSign({direction: 'FORWARD', from: 5, to: 6, points: unitPoints}, unitModel) === 1 &&
    render.arrowTravelSign({direction: 'BACKWARD', from: 5, to: 6, points: unitPoints}, unitModel) === -1);
  const nodeLess = m.segments.find((s) => s.direction !== 'TWO_WAY' && s.from < 0 && s.to < 0) ||
    {direction: 'FORWARD', from: -1, to: -1, points: unitPoints};
  ok('a one-way road with no nodes still gets an arrow direction',
    render.arrowTravelSign(nodeLess, m) === 1, String(render.arrowTravelSign(nodeLess, m)));
  // A polyline stored the wrong way round: travel is from the node at x=100 back to
  // the node at x=0, but the points run 0 -> 20.
  const flipped = {direction: 'FORWARD', from: 5, to: 6, points: [{x: 0, z: 0}, {x: 20, z: 0}]};
  const flippedModel = {nodeById: {'5': {x: 100, z: 0}, '6': {x: 0, z: 0}}};
  ok('a polyline stored against the travel direction is detected',
    render.arrowTravelSign(flipped, flippedModel) === -1,
    String(render.arrowTravelSign(flipped, flippedModel)));

  /*
   * Arrows belong to the fill pass, and only to one-way roads, and they point the way
   * travel goes.
   *
   * Attribution is exact rather than nearest-neighbour: two roads can share an
   * alignment (a highway leaving town along a ring road, say), so "which segment is
   * this arrowhead on" has no unique answer in general. Instead the placements the
   * spacing rule predicts for the one-way segments are computed first, and every drawn
   * arrowhead has to account for exactly one of them -- and then the triangle's
   * direction is compared with the road's own tangent, oriented by the travel sign,
   * which is the FORWARD/BACKWARD rule checked independently of the arrow code.
   */
  const arrowFills = ops.filter((op) => op.kind === 'fill' && op.fillStyle === render.ARROW_COLOR &&
    op.points.length === 3);
  const screenPaths = new Map();
  for (const seg of drawable) screenPaths.set(seg.id, render.segmentScreenPoints(seg, view));

  const predicted = [];
  for (const seg of drawable) {
    if (seg.direction === 'TWO_WAY') continue;
    for (const mark of render.arrowPlacements(seg, view, m)) predicted.push({seg, mark});
  }
  const matched = new Set();
  const unmatched = [];
  const misdirected = [];
  let worstAngle = 0;
  // An arrowhead is a triangle: the anchor it was placed at sits between the tip
  // (ARROW_TIP ahead of it) and the midpoint of the two barbs (ARROW_BACK behind it),
  // so the anchor can be recovered from the drawn points alone.
  const anchorRatio = render.ARROW_BACK_PX / (render.ARROW_TIP_PX + render.ARROW_BACK_PX);
  for (const op of arrowFills) {
    const tip = {x: op.points[0][0], y: op.points[0][1]};
    const base = {
      x: (op.points[1][0] + op.points[2][0]) / 2,
      y: (op.points[1][1] + op.points[2][1]) / 2,
    };
    const mark = {
      x: base.x + (tip.x - base.x) * anchorRatio,
      y: base.y + (tip.y - base.y) * anchorRatio,
    };
    let index = -1;
    for (let i = 0; i < predicted.length; i++) {
      if (matched.has(i)) continue;
      if (close(predicted[i].mark.x, mark.x, 1e-6) && close(predicted[i].mark.y, mark.y, 1e-6)) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      unmatched.push(mark);
      continue;
    }
    matched.add(index);
    const seg = predicted[index].seg;
    const drawn = Math.atan2(tip.y - base.y, tip.x - base.x) * 180 / Math.PI;
    // The tangent is read at the anchor, not at the tip: five pixels along at a bend can
    // land on the next leg, whose direction is legitimately a different angle.
    const local = geom.nearestOnPolyline(screenPaths.get(seg.id), mark.x, mark.y).angle;
    const travel = local + (render.arrowTravelSign(seg, m) < 0 ? 180 : 0);
    const raw = Math.abs(drawn - travel) % 360;
    const diff = raw > 180 ? 360 - raw : raw;
    worstAngle = Math.max(worstAngle, diff);
    if (diff > 2) misdirected.push({id: seg.id, drawn: round6(drawn), travel: round6(travel)});
  }

  const oneWay = drawable.filter((s) => s.direction !== 'TWO_WAY');
  if (oneWay.length) {
    ok('one-way roads get arrowheads', arrowFills.length > 0, String(arrowFills.length));
  } else {
    console.log('note: this network has no one-way roads, so the arrow checks were skipped');
  }
  ok('every arrowhead is one the spacing rule asked for, and every one of those is drawn',
    unmatched.length === 0 && matched.size === predicted.length && predicted.length === arrowFills.length,
    JSON.stringify({drawn: arrowFills.length, predicted: predicted.length, unmatched: unmatched.slice(0, 3)}));
  ok('every arrowhead points the way travel goes',
    misdirected.length === 0, JSON.stringify({worst: round6(worstAngle), bad: misdirected.slice(0, 3)}));
  if (usingBundled) {
    ok('both one-way directions are drawn', (() => {
      const seen = new Set(predicted.map((p) => p.seg.direction));
      return seen.has('FORWARD') && seen.has('BACKWARD');
    })(), JSON.stringify(Array.from(new Set(predicted.map((p) => p.seg.direction)))));
  }

  // The other half of the rule: a network with nothing one-way gets no arrows at all.
  const twoWayOnly = model.normalize(Object.assign({}, modelPayload, {
    segments: (Array.isArray(modelPayload.segments) ? modelPayload.segments : [])
      .map((s) => Object.assign({}, s, {direction: 'TWO_WAY'})),
  }));
  const twoWayCtx = recordingContext();
  render.drawMap(twoWayCtx, twoWayOnly, view, {});
  const twoWayArrows = paths(twoWayCtx.calls).filter((op) => op.kind === 'fill' &&
    op.fillStyle === render.ARROW_COLOR && op.points.length === 3);
  ok('a two-way network gets no arrowheads at all', twoWayArrows.length === 0,
    String(twoWayArrows.length) + ' drawn');

  // An arrow belongs to its own storey: after that storey's fills, and not over the
  // storey above it.
  const arrowOwner = (op) => {
    const tip = {x: op.points[0][0], y: op.points[0][1]};
    const base = {
      x: (op.points[1][0] + op.points[2][0]) / 2,
      y: (op.points[1][1] + op.points[2][1]) / 2,
    };
    const mark = {
      x: base.x + (tip.x - base.x) * anchorRatio,
      y: base.y + (tip.y - base.y) * anchorRatio,
    };
    const found = predicted.find((p) => close(p.mark.x, mark.x, 1e-6) && close(p.mark.y, mark.y, 1e-6));
    return found ? found.seg : null;
  };
  ok('every arrowhead is drawn after its own storey\'s roads',
    arrowFills.every((op) => {
      const seg = arrowOwner(op);
      const range = seg && rangeByLayer.get(seg.layer);
      return !!range && op.index > range.max;
    }));
  ok('and none is drawn over a higher storey',
    arrowFills.every((op) => {
      const seg = arrowOwner(op);
      if (!seg) return false;
      return layerOrder.every((layer) => {
        if (layer <= seg.layer) return true;
        const range = rangeByLayer.get(layer);
        return !range || op.index < range.min;
      });
    }));

  // Filters.
  const hiddenClass = m.classes[0].id;
  const hiddenCtx = recordingContext();
  render.drawMap(hiddenCtx, m, view, {hiddenClasses: [hiddenClass]});
  const hiddenOps = paths(hiddenCtx.calls).filter((op) => op.kind === 'stroke' &&
    op.strokeStyle === m.classById[hiddenClass].color);
  ok('hiding a class removes its roads', hiddenOps.length === 0, String(hiddenOps.length) + ' left');

  const hiddenLayerCtx = recordingContext();
  render.drawMap(hiddenLayerCtx, m, view, {hiddenLayers: [1]});
  const bridgeSigs = new Set((m.segmentsByLayer[1] || []).map((s) =>
    signature(render.segmentScreenPoints(s, view).map((p) => [round6(p.x), round6(p.y)]))));
  const bridgeLeft = paths(hiddenLayerCtx.calls).filter((op) => op.kind === 'stroke' && bridgeSigs.has(signature(op.points)));
  ok('hiding a storey removes that storey', bridgeLeft.length === 0, String(bridgeLeft.length) + ' left');

  // (d) the export is the same drawMap, the same world, twice the pixels.
  const exportView = {
    centerX: view.centerX,
    centerZ: view.centerZ,
    scale: view.scale * 2,
    width: view.width * 2,
    height: view.height * 2,
  };
  const screenPlacement = render.drawMap(recordingContext(), m, view, {});
  const exportCtx = recordingContext();
  const exportPlacement = render.drawMap(exportCtx, m, exportView, {resolutionScale: 2});
  const exportOps = paths(exportCtx.calls);
  const exportRoads = exportOps.filter((op) => op.kind === 'stroke' &&
    (op.strokeStyle === render.CASING_COLOR || classColors.has(op.strokeStyle)) && op.points.length >= 2);

  const sb = geom.visibleBounds(view);
  const eb = geom.visibleBounds(exportView);
  ok('the export shows exactly the same world extent',
    close(sb.minX, eb.minX, 1e-6) && close(sb.minZ, eb.minZ, 1e-6) &&
    close(sb.maxX, eb.maxX, 1e-6) && close(sb.maxZ, eb.maxZ, 1e-6),
    JSON.stringify({screen: sb, export: eb}));

  // The two runs cannot be compared point for point -- the export's coordinates are
  // twice the screen's -- so each op is matched back to the segment that produced it
  // and the two sequences of segment ids are compared. Same ids in the same order
  // means the same occlusion.
  const screenOrder = roadStrokes.map((op) => {
    const sig = signature(op.points);
    for (const seg of drawable) {
      const other = signature(render.segmentScreenPoints(seg, view).map((p) => [round6(p.x), round6(p.y)]));
      if (other === sig) return seg.id;
    }
    return null;
  });
  const exportOrder = exportRoads.map((op) => {
    const sig = signature(op.points);
    for (const seg of drawable) {
      const other = signature(render.segmentScreenPoints(seg, exportView).map((p) => [round6(p.x), round6(p.y)]));
      if (other === sig) return seg.id;
    }
    return null;
  });
  ok('the export paints every road', exportOrder.every((id) => id !== null) && exportOrder.length === screenOrder.length,
    JSON.stringify({screen: screenOrder.length, export: exportOrder.length}));
  ok('the export paints the roads in exactly the same order',
    JSON.stringify(screenOrder) === JSON.stringify(exportOrder),
    JSON.stringify({screen: screenOrder.slice(0, 8), export: exportOrder.slice(0, 8)}));
  ok('the export places exactly the same labels',
    screenPlacement.placed.length === exportPlacement.placed.length &&
    screenPlacement.dropped.length === exportPlacement.dropped.length &&
    screenPlacement.placed.every((p, i) => p.text === exportPlacement.placed[i].text),
    JSON.stringify({
      screen: screenPlacement.placed.length, export: exportPlacement.placed.length,
      screenDrops: screenPlacement.dropped.length, exportDrops: exportPlacement.dropped.length,
    }));
  ok('and places them at exactly twice the position and twice the size',
    screenPlacement.placed.every((p, i) => {
      const q = exportPlacement.placed[i].box;
      return close(q.x, p.box.x * 2, 1e-6) && close(q.y, p.box.y * 2, 1e-6) &&
        close(q.w, p.box.w * 2, 1e-6) && close(q.h, p.box.h * 2, 1e-6);
    }),
    JSON.stringify(screenPlacement.placed.slice(0, 1).map((p) => p.box)));
  ok('the export draws the same number of labels into its context',
    indicesOf(exportCtx.calls, 'fillText').length === indicesOf(ctx.calls, 'fillText').length,
    indicesOf(exportCtx.calls, 'fillText').length + ' vs ' + indicesOf(ctx.calls, 'fillText').length);

  // Resolution fidelity: the export must be the *same* map at twice the size, not a map that
  // happens to use the same function. Every pixel-space constant in the renderer is multiplied by
  // resolutionScale, so anything that is not twice the screen's value here is a constant that was
  // forgotten -- which is exactly how the export came out with twice as many arrowheads per block,
  // half-size markers and a grid twice as dense before `resolutionScale` existed.
  const arrowFillsOf = (ops) => ops.filter((op) => op.kind === 'fill' && op.fillStyle === render.ARROW_COLOR);
  const screenArrows = arrowFillsOf(paths(ctx.calls));
  const exportArrows = arrowFillsOf(exportOps);
  ok('the export draws exactly the same arrowheads, one for one',
    screenArrows.length === exportArrows.length && screenArrows.length > 0,
    screenArrows.length + ' vs ' + exportArrows.length);
  ok('and each is at twice the position and twice the size',
    screenArrows.every((op, i) => {
      const q = exportArrows[i];
      // Points are [x, y] pairs, rounded to six decimals when they were recorded, so the doubled
      // comparison carries that rounding twice over.
      return q.points.length === op.points.length && q.points.every((p, j) =>
        close(p[0], op.points[j][0] * 2, 1e-4) && close(p[1], op.points[j][1] * 2, 1e-4));
    }),
    JSON.stringify({screen: screenArrows[0] && screenArrows[0].points[0],
      export: exportArrows[0] && exportArrows[0].points[0]}));

  const screenRoadWidths = roadStrokes.map((op) => op.lineWidth);
  const exportRoadWidths = exportRoads.map((op) => op.lineWidth);
  ok('and every road is stroked at exactly twice the width',
    screenRoadWidths.length === exportRoadWidths.length &&
    screenRoadWidths.every((w, i) => close(exportRoadWidths[i], w * 2, 1e-6)),
    JSON.stringify({screen: screenRoadWidths.slice(0, 4), export: exportRoadWidths.slice(0, 4)}));

  const screenMarkerRadii = indicesOf(ctx.calls, 'arc').map((i) => ctx.calls[i].args[2]);
  const exportMarkerRadii = indicesOf(exportCtx.calls, 'arc').map((i) => exportCtx.calls[i].args[2]);
  ok('and every junction dot keeps its size relative to the map, at twice the pixels',
    screenMarkerRadii.length > 0 && screenMarkerRadii.length === exportMarkerRadii.length &&
    screenMarkerRadii.every((r, i) => close(exportMarkerRadii[i], r * 2, 1e-6)),
    JSON.stringify({screen: screenMarkerRadii.slice(0, 4), export: exportMarkerRadii.slice(0, 4)}));

  // The grid is one path made of many lines, so the step is read out of that path's vertical runs.
  const gridStepOf = (ops) => {
    for (const op of ops) {
      if (op.kind !== 'stroke' || op.strokeStyle !== render.GRID_COLOR) continue;
      const xs = [];
      for (let i = 0; i + 1 < op.points.length; i += 2) {
        const a = op.points[i];
        const b = op.points[i + 1];
        if (Math.abs(a[0] - b[0]) < 1e-9) xs.push(a[0]);
      }
      if (xs.length >= 2) return Math.abs(xs[1] - xs[0]);
    }
    return null;
  };
  const screenStep = gridStepOf(paths(ctx.calls));
  const exportStep = gridStepOf(exportOps);
  ok('and the grid is the same grid over the same ground, at twice the pixels',
    screenStep !== null && exportStep !== null && close(exportStep, screenStep * 2, 1e-6),
    JSON.stringify({screen: screenStep, export: exportStep}));

  // The export must never be handed a colour html2canvas cannot parse.
  const rawCss = (() => {
    try {
      return fs.readFileSync(path.join(WEB_DIR, 'style.css'), 'utf8');
    } catch (err) {
      return null;
    }
  })();
  ok('the stylesheet avoids oklch()/color-mix(), which html2canvas 1.4.1 cannot parse',
    rawCss !== null && !/oklch\(|color-mix\(|\blab\(|\blch\(/i.test(withoutCssComments(rawCss)),
    rawCss === null ? 'style.css is missing' : 'modern colour function found');
});

// ---------------------------------------------------------------------------------
// 5. the page contract
// ---------------------------------------------------------------------------------
section('page: the HTTP contract and the classic-script rules', () => {
  function read(name) {
    try {
      return fs.readFileSync(path.join(WEB_DIR, name), 'utf8');
    } catch (err) {
      return null;
    }
  }
  function readJs(name) {
    try {
      return fs.readFileSync(path.join(JS_DIR, name), 'utf8');
    } catch (err) {
      return null;
    }
  }

  const html = read('index.html');
  ok('index.html exists', html !== null);
  if (html) {
    const order = ['/js/model.js', '/js/geom.js', '/js/labels.js', '/js/render.js', '/js/app.js'];
    const positions = order.map((p) => html.indexOf('src="' + p + '"'));
    ok('every asset is referenced by the absolute path the server serves',
      positions.every((p) => p >= 0), JSON.stringify(order.map((p, i) => [p, positions[i]])));
    ok('scripts load in dependency order',
      positions.every((p, i) => i === 0 || (positions[i - 1] >= 0 && p > positions[i - 1])), JSON.stringify(positions));
    ok('the stylesheet is referenced from the root', html.indexOf('href="/style.css"') >= 0);
    ok('html2canvas is loaded from the vendored copy', html.indexOf('/vendor/html2canvas.min.js') >= 0);
    ok('the favicon is a data URI, so no /favicon.ico request is made',
      /rel="icon"[^>]*href="data:/i.test(html) && html.indexOf('favicon.ico') < 0);
    ok('there are no inline event handler attributes',
      !/\son(click|load|change|input|submit|mouseover|keydown|keyup)\s*=/i.test(html));
    ok('the page is declared as UTF-8', /charset="?utf-8/i.test(html));
  }

  const classicFiles = ['model.js', 'geom.js', 'labels.js', 'render.js', 'app.js'];
  const sources = {};
  let modulesClean = true;
  let importers = [];
  for (const name of classicFiles) {
    const src = readJs(name);
    sources[name] = src;
    if (src === null) {
      modulesClean = false;
      continue;
    }
    if (/^\s*(import|export)\s/m.test(src)) importers.push(name);
  }
  ok('all five scripts exist', classicFiles.every((n) => sources[n] !== null),
    classicFiles.filter((n) => sources[n] === null).join(','));
  ok('no script uses ES module syntax', importers.length === 0, importers.join(','));

  /** Line and block comments removed, so prose about the DOM cannot fail these. */
  function stripComments(src) {
    return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
  }
  const domFree = ['model.js', 'geom.js', 'labels.js', 'render.js'];
  const offenders = domFree.filter((name) => {
    const src = sources[name];
    if (src === null) return true;
    return /\bdocument\b|\bwindow\b/.test(stripComments(src));
  });
  ok('the four model/geometry modules never touch the DOM', offenders.length === 0, offenders.join(','));

  const app = sources['app.js'];
  if (app) {
    ok('the app draws the screen and the export through the same entry point',
      (app.match(/drawMap\s*\(/g) || []).length >= 2,
      String((app.match(/drawMap\s*\(/g) || []).length) + ' call sites');
    ok('the export asks html2canvas for a deliberately un-scaled render of an already 2x canvas',
      /html2canvas\s*\(/.test(app) && /scale\s*:\s*1/.test(app));
    // The renderer scales every pixel measurement by this, so a 2x export is the screen's map at
    // twice the size rather than a map with a denser grid and half-size markers. Dropping it here
    // would silently undo that while every other check stayed green.
    ok('and tells the renderer it is drawing at twice the resolution',
      /resolutionScale\s*:\s*EXPORT_SCALE\b/.test(app));
    ok('the export falls back to toBlob when html2canvas is missing or throws',
      /toBlob/.test(app) && /html2canvas/.test(app));
    ok('no DOM is built from a road or place name with innerHTML',
      !/innerHTML\s*=\s*[^;]*(name|text)/i.test(app));
  }

  const css = read('style.css');
  ok('style.css exists', css !== null);
  if (css) {
    ok('the stylesheet uses no modern colour functions',
      !/oklch\(|color-mix\(|\blab\(|\blch\(/i.test(withoutCssComments(css)));
  }
});

// ---------------------------------------------------------------------------------
// 6. app.js, end to end, against a fake DOM
//
// The page is the one file the recording stub cannot reach, and it is where the export
// lives -- the part of the contract with the most ways to fail quietly. So it is booted
// here for real: a fake document, a fake fetch, a fake html2canvas, and assertions on
// what the app did with them. Nothing is written to disk and no browser is involved.
// ---------------------------------------------------------------------------------
function fakeNode(tag) {
  const classes = new Set();
  const node = {
    tagName: String(tag || 'div').toUpperCase(),
    children: [],
    parentElement: null,
    style: {},
    attributes: {},
    listeners: {},
    hidden: false,
    disabled: false,
    checked: false,
    textContent: '',
    clientWidth: 900,
    clientHeight: 600,
    removed: false,
  };
  node.classList = {
    add: (name) => classes.add(name),
    remove: (name) => classes.delete(name),
    contains: (name) => classes.has(name),
    toggle: (name, on) => {
      const want = on === undefined ? !classes.has(name) : !!on;
      if (want) classes.add(name);
      else classes.delete(name);
      return want;
    },
  };
  node.setAttribute = (key, value) => {
    node.attributes[key] = value;
  };
  node.getAttribute = (key) => node.attributes[key];
  node.appendChild = (child) => {
    child.parentElement = node;
    node.children.push(child);
    return child;
  };
  node.removeChild = (child) => {
    const at = node.children.indexOf(child);
    if (at >= 0) node.children.splice(at, 1);
    child.parentElement = null;
    return child;
  };
  node.remove = () => {
    node.removed = true;
    if (node.parentElement) node.parentElement.removeChild(node);
  };
  node.addEventListener = (name, fn) => {
    (node.listeners[name] = node.listeners[name] || []).push(fn);
  };
  node.dispatch = (name, event) => {
    for (const fn of node.listeners[name] || []) fn(event || {});
  };
  node.click = () => {
    node.clicked = (node.clicked || 0) + 1;
  };
  node.setPointerCapture = () => {};
  node.releasePointerCapture = () => {};
  node.getBoundingClientRect = () => ({left: 0, top: 0, width: 900, height: 600});
  node.focus = () => {};
  // Anything that builds markup from data would blow up here rather than pass silently.
  Object.defineProperty(node, 'innerHTML', {
    get() {
      return '';
    },
    set() {
      throw new Error('app.js assigned innerHTML');
    },
  });
  return node;
}

function fakeCanvas() {
  const node = fakeNode('canvas');
  node.width = 300;
  node.height = 150;
  node.context = recordingContext();
  node.getContext = () => node.context;
  node.toBlob = (callback) => callback(new Blob(['png'], {type: 'image/png'}));
  node.toDataURL = () => 'data:image/png;base64,cG5n';
  return node;
}

function sectionAsync(title, body) {
  group(title);
  return Promise.resolve()
    .then(body)
    .catch((err) => {
      ok(title + ' ran to the end', false, err && err.stack ? err.stack.split('\n')[0] : String(err));
    });
}

(async () => {
  await sectionAsync('app: boots, draws, filters and exports against a fake DOM', async () => {
    // --- the fake document --------------------------------------------------------
    const ids = ['map', 'map-title', 'world-line', 'btn-fit', 'btn-zoom-in', 'btn-zoom-out',
      'btn-refresh', 'auto-refresh', 'btn-export', 'show-road-names', 'show-places',
      'class-list', 'layer-list', 'stats-list', 'status-cursor', 'status-zoom',
      'status-hover', 'banner', 'toast'];
    const byId = {};
    const mapArea = fakeNode('section');
    const canvas = fakeCanvas();
    mapArea.appendChild(canvas);
    for (const id of ids) {
      byId[id] = id === 'map' ? canvas : fakeNode('div');
    }
    byId['auto-refresh'] = fakeNode('input');
    byId['show-road-names'] = fakeNode('input');
    byId['show-places'] = fakeNode('input');
    byId['show-road-names'].checked = true;
    byId['show-places'].checked = true;

    const created = [];
    const document = {
      readyState: 'complete',
      body: fakeNode('body'),
      documentElement: fakeNode('html'),
      getElementById: (id) => byId[id] || null,
      createElement: (tag) => {
        const node = tag === 'canvas' ? fakeCanvas() : fakeNode(tag);
        created.push(node);
        return node;
      },
      addEventListener: () => {},
    };

    // --- the fake window ---------------------------------------------------------
    const frames = [];
    const intervals = [];
    let frameId = 0;
    const window = {
      devicePixelRatio: 2,
      requestAnimationFrame: (callback) => {
        // Queued and run on a microtask: an id is returned before the callback runs, as
        // a browser does, and awaiting anything drains it.
        const id = ++frameId;
        frames.push(callback);
        Promise.resolve().then(() => {
          const at = frames.indexOf(callback);
          if (at >= 0) frames.splice(at, 1);
          callback(id);
        });
        return id;
      },
      cancelAnimationFrame: () => {},
      setTimeout: () => 0,
      clearTimeout: () => {},
      setInterval: (fn, ms) => {
        intervals.push({fn, ms, cleared: false});
        return intervals.length;
      },
      clearInterval: (id) => {
        if (intervals[id - 1]) intervals[id - 1].cleared = true;
      },
      addEventListener: () => {},
      atob: (text) => Buffer.from(text, 'base64').toString('binary'),
      html2canvas: null,
    };

    // --- the fake network ---------------------------------------------------------
    const fetchLog = [];
    let response = {ok: true, status: 200, body: modelPayload};
    let networkDown = false;
    const fakeFetch = (url) => {
      fetchLog.push(url);
      if (networkDown) return Promise.reject(new Error('Failed to fetch'));
      return Promise.resolve({
        ok: response.ok,
        status: response.status,
        json: () => Promise.resolve(response.body),
      });
    };

    const anchors = [];
    const originalCreate = document.createElement;
    document.createElement = (tag) => {
      const node = originalCreate(tag);
      if (String(tag).toLowerCase() === 'a') anchors.push(node);
      return node;
    };

    global.document = document;
    global.window = window;
    global.fetch = fakeFetch;
    global.URL = {createObjectURL: () => 'blob:fake', revokeObjectURL: () => {}};

    const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

    app.boot();
    await settle();
    await settle();

    const live = byId['map'].context.calls;
    ok('the app boots, fetches the roads and draws them',
      fetchLog.length === 1 && fetchLog[0] === '/api/roads' &&
      app.state.model && app.state.model.segments.length === m.segments.length,
      JSON.stringify({fetches: fetchLog, segments: app.state.model && app.state.model.segments.length}));
    ok('the canvas is sized to the CSS box times the device pixel ratio',
      byId['map'].width === 1800 && byId['map'].height === 1200,
      byId['map'].width + 'x' + byId['map'].height);
    ok('the map really was painted',
      live.some((c) => c.name === 'stroke') && live.some((c) => c.name === 'fillText'),
      live.length + ' context calls');
    ok('the header names the world, the counts and the length',
      byId['world-line'].textContent.indexOf(app.state.model.world) >= 0 &&
      byId['world-line'].textContent.indexOf('路段') >= 0,
      byId['world-line'].textContent);
    ok('one filter row per class, each with its count',
      byId['class-list'].children.length === app.state.model.classes.length &&
      byId['class-list'].children[0].children.length >= 4,
      byId['class-list'].children.length + ' rows for ' + app.state.model.classes.length + ' classes');
    ok('one filter row per storey',
      byId['layer-list'].children.length === Math.max(1, app.state.model.layers.length),
      String(byId['layer-list'].children.length));
    ok('the stats block is filled in',
      byId['stats-list'].children.length >= 8 && byId['stats-list'].children[1].textContent !== '',
      byId['stats-list'].children.length + ' nodes');
    ok('a class row is labelled with the class name and not with markup',
      byId['class-list'].children[0].children[2].textContent.length > 0 &&
      byId['class-list'].children[0].children[3].textContent.length > 0,
      JSON.stringify(byId['class-list'].children[0].children.map((c) => c.textContent)));

    // --- a class filter ------------------------------------------------------------
    const hiddenId = app.state.model.classes[0].id;
    const hiddenColour = app.state.model.classById[hiddenId].color;
    const before = live.length;
    const firstRow = byId['class-list'].children[0];
    firstRow.children[0].checked = false;
    firstRow.children[0].dispatch('change', {target: {checked: false}});
    await settle();
    const after = live.slice(before);
    ok('unchecking a class hides its roads and repaints',
      app.state.hiddenClasses.has(hiddenId) &&
      after.some((c) => c.name === 'stroke') &&
      !after.some((c) => c.name === 'stroke' && c.state.strokeStyle === hiddenColour),
      JSON.stringify({hidden: hiddenId, newCalls: after.length}));

    // --- the toggles --------------------------------------------------------------
    byId['show-road-names'].checked = false;
    byId['show-road-names'].dispatch('change', {target: {checked: false}});
    await settle();
    ok('the road-name toggle is remembered', app.state.showRoadNames === false);
    byId['show-road-names'].checked = true;
    byId['show-road-names'].dispatch('change', {target: {checked: true}});
    await settle();

    // --- auto refresh -------------------------------------------------------------
    byId['auto-refresh'].checked = true;
    byId['auto-refresh'].dispatch('change', {target: {checked: true}});
    ok('the auto-refresh toggle polls every five seconds',
      intervals.length === 1 && intervals[0].ms === app.AUTO_REFRESH_MS && intervals[0].ms === 5000,
      JSON.stringify(intervals.map((i) => i.ms)));
    const fetchesBeforeTick = fetchLog.length;
    intervals[0].fn();
    await settle();
    await settle();
    ok('a poll tick re-fetches the network', fetchLog.length === fetchesBeforeTick + 1,
      fetchLog.length + ' fetches');
    byId['auto-refresh'].checked = false;
    byId['auto-refresh'].dispatch('change', {target: {checked: false}});
    ok('turning it off stops the timer', intervals[0].cleared === true);

    // --- export, with html2canvas working -----------------------------------------
    let composed = null;
    window.html2canvas = (host, options) => {
      composed = {host, options, attached: host.parentElement === document.body};
      return Promise.resolve(fakeCanvas());
    };
    const anchorsBefore = anchors.length;
    const bodyBefore = document.body.children.length;
    await app.exportImage();
    ok('the export renders through html2canvas with the documented options',
      !!composed && composed.options.scale === 1 && composed.options.backgroundColor === '#101418' &&
      composed.options.logging === false && composed.attached,
      JSON.stringify(composed && composed.options));
    ok('the export container carries a caption and a legend',
      !!composed && composed.host.children.length === 4 &&
      composed.host.children[0].textContent.indexOf('HowToGo') === 0 &&
      composed.host.children[3].children.length >= 1,
      composed ? String(composed.host.children.length) + ' parts' : 'no host');
    ok('the temporary container is taken out of the document again',
      document.body.children.length === bodyBefore, String(document.body.children.length));
    const link = anchors[anchors.length - 1];
    ok('an export with html2canvas is announced as complete',
      byId['toast'].textContent.indexOf('图例') >= 0, byId['toast'].textContent);
    ok('the file is named howtogo-<dimension>-<stamp>.png',
      anchors.length === anchorsBefore + 1 && /^howtogo-.+-\d{8}-\d{6}\.png$/.test(link.download) &&
      link.download.indexOf(app.state.model.dimension.replace(/[^A-Za-z0-9._-]+/g, '-')) >= 0 &&
      link.clicked === 1,
      link.download);

    // --- export, with html2canvas throwing ---------------------------------------
    window.html2canvas = () => Promise.reject(new Error('渲染失败'));
    const anchorsThrowing = anchors.length;
    await app.exportImage();
    ok('a throwing html2canvas falls back to the offscreen canvas and still downloads',
      anchors.length === anchorsThrowing + 1 &&
      /^howtogo-.+\.png$/.test(anchors[anchors.length - 1].download) &&
      byId['toast'].textContent.indexOf('直接导出画布') >= 0,
      byId['toast'].textContent);

    // --- export, with html2canvas missing entirely -------------------------------
    window.html2canvas = null;
    const anchorsMissing = anchors.length;
    await app.exportImage();
    ok('a missing html2canvas falls back the same way',
      anchors.length === anchorsMissing + 1 &&
      byId['toast'].textContent.indexOf('未找到 html2canvas') >= 0,
      byId['toast'].textContent);

    // --- the server refusing, with a good map already on screen -------------------
    response = {ok: false, status: 503, body: {error: '道路数据尚未生成'}};
    byId['btn-refresh'].dispatch('click', {});
    await settle();
    await settle();
    ok('a 503 shows the server\'s own message instead of an empty map',
      byId['banner'].hidden === false &&
      byId['banner'].textContent.indexOf('道路数据尚未生成') >= 0 &&
      byId['banner'].className.indexOf('error') >= 0,
      byId['banner'].textContent);
    ok('and the last good map is left on screen, not blanked',
      app.state.model.segments.length === m.segments.length,
      String(app.state.model.segments.length));

    networkDown = true;
    byId['btn-refresh'].dispatch('click', {});
    await settle();
    await settle();
    ok('a dead connection is reported too',
      byId['banner'].textContent.indexOf('读取道路数据失败') >= 0, byId['banner'].textContent);

    // --- an empty network ---------------------------------------------------------
    networkDown = false;
    response = {
      ok: true,
      status: 200,
      body: {
        version: 1, generatedAt: 1760000000000, world: 'sp_TEST', dimension: 'minecraft:overworld',
        empty: true, bounds: {minX: 0, minZ: 0, maxX: 0, maxZ: 0}, classes: model.DEFAULT_CLASSES,
        nodes: [], segments: [], stats: {nodes: 0, segments: 0, lengthBlocks: 0, layers: []},
      },
    };
    byId['btn-refresh'].dispatch('click', {});
    await settle();
    await settle();
    ok('an empty network is explained rather than drawn as a blank map',
      app.state.model.empty === true && byId['banner'].hidden === false &&
      byId['banner'].textContent.indexOf('空网络') >= 0,
      byId['banner'].textContent);
    const anchorsEmpty = anchors.length;
    await app.exportImage();
    ok('an empty network can still be exported',
      anchors.length === anchorsEmpty + 1 &&
      /^howtogo-.+\.png$/.test(anchors[anchors.length - 1].download),
      anchors.length ? anchors[anchors.length - 1].download : 'nothing downloaded');

    // --- the hover readout ---------------------------------------------------------
    // A model of its own with one road in it, so which road the cursor is over is not a question: what
    // is being checked is what the readout says about a road, not how it picks one. It goes last
    // because it replaces the model the app is holding, and the checks above read that one; the filters
    // are cleared first because the class-filter check above turned one off and the hidden set outlives
    // the model it was made for.
    app.state.hiddenClasses.clear();
    app.state.hiddenLayers.clear();
    app.state.model = model.normalize({
      version: 1, generatedAt: 1, world: 'sp_HOVER', dimension: 'minecraft:overworld', empty: false,
      bounds: {minX: 0, minZ: 0, maxX: 100, maxZ: 0},
      classes: [{id: 'HIGHWAY', color: '#3FA9F5', width: 7}],
      nodes: [{id: 1, x: 0, y: 64, z: 0, type: 'ENDPOINT'}, {id: 2, x: 100, y: 64, z: 0, type: 'ENDPOINT'}],
      segments: [{id: 1, roadClass: 'HIGHWAY', y: 64, layer: 1, from: 1, to: 2, direction: 'BACKWARD',
        name: '高架路', points: [[0, 0], [100, 0]]}],
      stats: {nodes: 2, segments: 1, lengthBlocks: 100, layers: [1]},
    });
    app.state.view = geom.makeView({centerX: 50, centerZ: 0, scale: 4, width: 900, height: 600});
    byId['map'].dispatch('pointermove', {clientX: 450, clientY: 300, pointerId: 1});
    const hoverText = byId['status-hover'].textContent;
    ok('the hover readout names the road and gives its class, direction, storey and length',
      hoverText.indexOf('道路：高架路') === 0 &&
      hoverText.indexOf(model.classLabel('HIGHWAY')) >= 0 &&
      hoverText.indexOf(model.directionLabel('BACKWARD')) >= 0 &&
      hoverText.indexOf(model.layerLabel(1)) >= 0 &&
      hoverText.indexOf('本段 ' + model.formatLength(100)) >= 0,
      hoverText);
    ok('and the storey comes after the direction, with the length after the storey',
      hoverText.indexOf(model.directionLabel('BACKWARD')) < hoverText.indexOf(model.layerLabel(1)) &&
      hoverText.indexOf(model.layerLabel(1)) < hoverText.indexOf('本段'),
      hoverText);

    // --- tidy up ------------------------------------------------------------------
    for (const key of ['document', 'window', 'fetch', 'URL']) delete global[key];
  });

  console.log('');
  if (failures === 0) {
    console.log('ALL OK (' + checks + ' checks)');
    process.exit(0);
  } else {
    console.log('FAILED: ' + failures + ' of ' + checks + ' checks');
    process.exit(1);
  }
})();
