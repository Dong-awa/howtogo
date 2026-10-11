/*
 * app.js -- everything that needs a browser: the canvas, the panel, the keyboard-free
 * wiring, the polling and the export.
 *
 * This is the only file in the map that touches the DOM. It owns no drawing maths: the
 * screen and the export both go through render.drawMap, and the panel only decides
 * which of the model's classes and storeys are handed to it. That is what keeps the
 * exported PNG an honest copy of the screen instead of a second implementation of it.
 *
 * Loaded as a classic script from /js/app.js, after the four modules it uses.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HowToGoMap = Object.assign(root.HowToGoMap || {}, api);
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const API_ROADS = '/api/roads';
  const AUTO_REFRESH_MS = 5000;
  const EXPORT_SCALE = 2;
  const FIT_PADDING_PX = 28;
  const ZOOM_STEP = 1.25;
  const ROAD_HOVER_PX = 12;
  const PLACE_HOVER_PX = 16;
  const TOAST_MS = 4500;
  const FONT_STACK = '"Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", sans-serif';

  const EMPTY_MESSAGE = '暂无道路数据';

  /*
   * The map modules.
   *
   * In the browser each module adds itself to the one shared namespace object, so the
   * three names below end up pointing at that same object -- writing geom.panBy,
   * model.normalize and render.drawMap is then a note about which module a function
   * came from, not three different objects. Under Node they are three real modules.
   * Nothing is resolved until boot(), which runs after the page has loaded every
   * script; leaving them null before that turns a wiring mistake into a clear failure
   * at startup instead of a ReferenceError in the middle of a drag.
   */
  let model = null;
  let geom = null;
  let render = null;

  function resolveModules() {
    if (typeof module === 'object' && module.exports && typeof require === 'function') {
      model = require('./model.js');
      geom = require('./geom.js');
      render = require('./render.js');
      return;
    }
    const api = (typeof globalThis !== 'undefined' ? globalThis : {}).HowToGoMap || {};
    model = api;
    geom = api;
    render = api;
  }

  function el(id) {
    return document.getElementById(id);
  }

  const state = {
    model: null,
    view: null,
    cssWidth: 1,
    cssHeight: 1,
    dpr: 1,
    hiddenClasses: new Set(),
    hiddenLayers: new Set(),
    showRoadNames: true,
    showPlaces: true,
    autoRefresh: false,
    timer: null,
    loading: false,
    exporting: false,
    hasLoaded: false,
    loadError: null,
    pointer: null,
    drag: null,
    toastTimer: null,
    frame: 0,
  };

  const refs = {};

  // -------------------------------------------------------------------------------
  // Rendering
  // -------------------------------------------------------------------------------

  /** Sizes the backing store to the CSS box times the device pixel ratio. */
  function resizeCanvas() {
    const host = refs.canvas.parentElement;
    const width = Math.max(1, Math.round(host.clientWidth));
    const height = Math.max(1, Math.round(host.clientHeight));
    const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    state.cssWidth = width;
    state.cssHeight = height;
    state.dpr = dpr;
    const pixelWidth = Math.round(width * dpr);
    const pixelHeight = Math.round(height * dpr);
    if (refs.canvas.width !== pixelWidth) refs.canvas.width = pixelWidth;
    if (refs.canvas.height !== pixelHeight) refs.canvas.height = pixelHeight;
    if (state.view) {
      state.view.width = width;
      state.view.height = height;
    }
  }

  /** Coalesces a burst of pointer or resize events into one paint. */
  function requestRender() {
    if (state.frame) return;
    state.frame = window.requestAnimationFrame(() => {
      state.frame = 0;
      paint();
    });
  }

  function paint() {
    if (!state.model) return;
    const ctx = refs.canvas.getContext('2d');
    if (!ctx) return;
    if (!state.view) fitToBounds(false);
    ctx.setTransform(state.dpr, 0, 0, state.dpr, 0, 0);
    render.drawMap(ctx, state.model, state.view, {
      showRoadNames: state.showRoadNames,
      showPlaces: state.showPlaces,
      hiddenClasses: state.hiddenClasses,
      hiddenLayers: state.hiddenLayers,
      message: state.model.empty ? EMPTY_MESSAGE : null,
    });
  }

  function fitToBounds(repaint) {
    state.view = geom.fitView(state.model ? state.model.bounds : null, state.cssWidth, state.cssHeight,
      {padding: FIT_PADDING_PX});
    updateStatus();
    if (repaint !== false) requestRender();
  }

  function zoomBy(factor) {
    if (!state.view) return;
    state.view = geom.zoomAt(state.view, factor, state.cssWidth / 2, state.cssHeight / 2);
    updateStatus();
    requestRender();
  }

  function keepView() {
    if (!state.view) {
      fitToBounds(false);
      return;
    }
    state.view = geom.makeView({
      centerX: state.view.centerX,
      centerZ: state.view.centerZ,
      scale: state.view.scale,
      width: state.cssWidth,
      height: state.cssHeight,
    });
  }

  // -------------------------------------------------------------------------------
  // Loading
  // -------------------------------------------------------------------------------

  async function load() {
    if (state.loading) return;
    state.loading = true;
    refs.refresh.disabled = true;
    try {
      const response = await fetch(API_ROADS, {cache: 'no-store'});
      let payload = null;
      try {
        payload = await response.json();
      } catch (err) {
        payload = null;
      }
      if (!response.ok) {
        // An error body is {"error": "..."}; anything else still has to be shown.
        const said = payload && typeof payload.error === 'string' ? payload.error : '';
        throw new Error(said || ('服务器返回 HTTP ' + response.status));
      }
      const said = model.errorOf(payload);
      if (said) throw new Error(said);
      applyNetwork(payload);
    } catch (err) {
      // The last good map stays on screen: a failed refresh must not blank the view.
      state.loadError = err && err.message ? err.message : String(err);
      setBanner('读取道路数据失败：' + state.loadError + '（请确认游戏正在运行，且本地服务已启动）', 'error');
      if (!state.model) {
        state.model = model.normalize(null);
        resizeCanvas();
        fitToBounds(false);
        requestRender();
        rebuildPanel();
      }
    } finally {
      state.loading = false;
      refs.refresh.disabled = false;
    }
  }

  function applyNetwork(payload) {
    const wasEmpty = !state.model || state.model.empty;
    state.loadError = null;
    state.model = model.normalize(payload);
    state.hasLoaded = true;
    resizeCanvas();
    if (!state.view || (wasEmpty && !state.model.empty)) fitToBounds(false);
    else keepView();
    rebuildPanel();
    if (state.model.empty) {
      setBanner('服务器返回了一个空网络：游戏里还没有画过道路。导出图片仍然可用。', 'info');
    } else {
      setBanner(null);
    }
    updateStatus();
    requestRender();
  }

  // -------------------------------------------------------------------------------
  // The side panel
  // -------------------------------------------------------------------------------

  function checkboxRow(options) {
    const row = document.createElement('label');
    row.className = 'row';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = options.checked;
    box.addEventListener('change', options.onChange);
    row.appendChild(box);
    if (options.color) {
      const swatch = document.createElement('span');
      swatch.className = 'swatch';
      // The colour comes from the server; model.js has already reduced it to hex or
      // rgb(), so it is safe to hand to the style property.
      swatch.style.background = options.color;
      row.appendChild(swatch);
    }
    const label = document.createElement('span');
    label.className = 'row-label';
    label.textContent = options.label;
    row.appendChild(label);
    const count = document.createElement('span');
    count.className = 'row-count';
    count.textContent = String(options.count);
    row.appendChild(count);
    return row;
  }

  function rebuildPanel() {
    const m = state.model;
    refs.classList.textContent = '';
    refs.layerList.textContent = '';
    if (!m) {
      updateStats();
      return;
    }

    // Classes: declared, plus any the server did not declare but the segments use.
    for (const klass of m.classes) {
      const count = m.classCounts[klass.id] || 0;
      refs.classList.appendChild(checkboxRow({
        label: model.classLabel(klass.id) + (klass.known ? '' : '（未声明）'),
        color: klass.color,
        count,
        checked: !state.hiddenClasses.has(klass.id),
        onChange: (event) => {
          if (event.target.checked) state.hiddenClasses.delete(klass.id);
          else state.hiddenClasses.add(klass.id);
          requestRender();
        },
      }));
    }

    // Storeys: highest first, because that is how the panel is read, even though the
    // map paints them the other way round.
    const layers = m.layers.slice().sort((a, b) => b - a);
    for (const layer of layers) {
      refs.layerList.appendChild(checkboxRow({
        label: model.layerLabel(layer),
        count: m.layerCounts[layer] || 0,
        checked: !state.hiddenLayers.has(layer),
        onChange: (event) => {
          if (event.target.checked) state.hiddenLayers.delete(layer);
          else state.hiddenLayers.add(layer);
          requestRender();
        },
      }));
    }
    if (!layers.length) {
      const none = document.createElement('p');
      none.className = 'muted';
      none.textContent = '没有道路层数据';
      refs.layerList.appendChild(none);
    }
    updateStats();
  }

  function statRow(term, value) {
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = value;
    return [dt, dd];
  }

  function updateStats() {
    const m = state.model;
    refs.statsList.textContent = '';
    if (!m) {
      refs.title.textContent = 'HowToGo 浏览器地图';
      refs.worldLine.textContent = '正在读取道路数据…';
      return;
    }
    const stats = m.stats;
    refs.title.textContent = 'HowToGo 浏览器地图' + (m.world ? ' · ' + m.world : '');
    const header = [
      m.world || '未知存档',
      m.dimension || '未知维度',
      model.formatThousands(stats.nodes) + ' 节点 / ' + model.formatThousands(stats.segments) + ' 路段',
      model.formatLength(stats.lengthBlocks),
      '生成于 ' + model.formatTime(m.generatedAt),
    ];
    refs.worldLine.textContent = header.join('  ·  ');

    const rows = [
      ['存档', m.world || '未知'],
      ['维度', m.dimension || '未知'],
      ['节点', model.formatThousands(stats.nodes) + (stats.nodes !== stats.computed.nodes ? '（实际 ' + stats.computed.nodes + '）' : '')],
      ['路段', model.formatThousands(stats.segments)],
      ['命名道路', model.formatThousands(m.namedSegmentCount)],
      ['命名地点', model.formatThousands(m.namedPlaceCount)],
      ['总长度', model.formatLength(stats.lengthBlocks)],
      ['道路层', stats.layers.length ? stats.layers.map(model.layerLabel).join('、') : '无'],
      ['生成时间', model.formatTime(m.generatedAt)],
      ['数据版本', 'v' + m.version],
    ];
    for (const [term, value] of rows) {
      for (const node of statRow(term, value)) refs.statsList.appendChild(node);
    }
  }

  // -------------------------------------------------------------------------------
  // Status bar and the hover readout
  // -------------------------------------------------------------------------------

  function updateStatus() {
    if (!state.view) {
      refs.zoom.textContent = '缩放 —';
      return;
    }
    refs.zoom.textContent = '缩放 ' + state.view.scale.toFixed(3) + ' px/格';
    if (state.pointer) {
      refs.cursor.textContent = '坐标 X ' + Math.round(state.pointer.x) + '  Z ' + Math.round(state.pointer.z);
    } else {
      refs.cursor.textContent = '坐标 —';
    }
  }

  /**
   * The nearest road or place to a world point.
   *
   * Segment bounding boxes are tested before the polylines themselves: on a network
   * with tens of thousands of pieces, the cheap test is what keeps the readout
   * responsive while the cursor moves.
   */
  function nearestRoad(x, z, radiusBlocks) {
    const m = state.model;
    let best = null;
    for (const seg of m.segments) {
      if (!seg.drawable) continue;
      if (state.hiddenClasses.has(seg.roadClass) || state.hiddenLayers.has(seg.layer)) continue;
      let minX = Infinity;
      let minZ = Infinity;
      let maxX = -Infinity;
      let maxZ = -Infinity;
      for (const p of seg.points) {
        if (p.x < minX) minX = p.x;
        if (p.x > maxX) maxX = p.x;
        if (p.z < minZ) minZ = p.z;
        if (p.z > maxZ) maxZ = p.z;
      }
      if (x < minX - radiusBlocks || x > maxX + radiusBlocks ||
          z < minZ - radiusBlocks || z > maxZ + radiusBlocks) continue;
      const near = geom.nearestOnPolyline(seg.points, x, z);
      if (near.distance <= radiusBlocks && (!best || near.distance < best.distance)) {
        best = {segment: seg, distance: near.distance};
      }
    }
    return best;
  }

  function nearestPlace(x, z, radiusBlocks) {
    const m = state.model;
    let best = null;
    for (const node of m.nodes) {
      const d = Math.hypot(node.x - x, node.z - z);
      if (d <= radiusBlocks && (!best || d < best.distance)) best = {node, distance: d};
    }
    return best;
  }

  function updateHover() {
    const m = state.model;
    if (!m || !state.view || !state.pointer) {
      refs.hover.textContent = '把光标移到地图上，这里会显示最近的道路或地点。';
      return;
    }
    const scale = state.view.scale || 1;
    const road = nearestRoad(state.pointer.x, state.pointer.z, ROAD_HOVER_PX / scale);
    const place = nearestPlace(state.pointer.x, state.pointer.z, PLACE_HOVER_PX / scale);
    if (!road && !place) {
      refs.hover.textContent = '附近没有道路或地点';
      return;
    }
    // A place wins when the cursor is within its marker, because that is the smaller
    // target and the one the user is aiming at.
    if (place && (!road || place.distance <= PLACE_HOVER_PX / scale)) {
      const node = place.node;
      const kind = node.placeKind ? model.placeKindLabel(node.placeKind) : model.nodeTypeLabel(node.type);
      refs.hover.textContent = '地点：' + (node.hasName ? node.name : '未命名') + '（' + kind + '）';
      return;
    }
    const seg = road.segment;
    // Class, then which way it runs, then where it is, then how long this piece is -- the same list the
    // in-game readout gives for a selected road, in the same order. Length is the piece's own: the page
    // has no idea which of a name's pieces are one road and which are two that were named alike, and a
    // segment's own length is a fact about the segment.
    const parts = [
      model.classLabel(seg.roadClass),
      model.directionLabel(seg.direction),
      model.layerLabel(seg.layer),
      '本段 ' + model.formatLength(seg.lengthBlocks),
    ];
    refs.hover.textContent = '道路：' + (seg.hasName ? seg.name : '未命名道路') + '（' + parts.join(' · ') + '）';
  }

  // -------------------------------------------------------------------------------
  // Messages
  // -------------------------------------------------------------------------------

  function setBanner(message, kind) {
    if (!refs.banner) return;
    if (!message) {
      refs.banner.hidden = true;
      refs.banner.textContent = '';
      return;
    }
    refs.banner.hidden = false;
    refs.banner.textContent = message;
    refs.banner.className = 'banner banner-' + (kind || 'info');
  }

  function showToast(message, kind) {
    if (!refs.toast) return;
    refs.toast.textContent = message;
    refs.toast.className = 'toast toast-' + (kind || 'info');
    refs.toast.hidden = false;
    if (state.toastTimer) window.clearTimeout(state.toastTimer);
    state.toastTimer = window.setTimeout(() => {
      refs.toast.hidden = true;
    }, TOAST_MS);
  }

  // -------------------------------------------------------------------------------
  // Export
  // -------------------------------------------------------------------------------

  /** A file name the filesystem will accept, whatever the dimension is called. */
  function exportName() {
    const dimension = (state.model && state.model.dimension ? state.model.dimension : 'map')
      .replace(/[^A-Za-z0-9._-]+/g, '-');
    return 'howtogo-' + dimension + '-' + model.formatStamp(Date.now()) + '.png';
  }

  function canvasToBlob(canvas) {
    return new Promise((resolve, reject) => {
      if (typeof canvas.toBlob === 'function') {
        canvas.toBlob((blob) => {
          if (blob) resolve(blob);
          else reject(new Error('画布导出为空'));
        }, 'image/png');
        return;
      }
      try {
        const url = canvas.toDataURL('image/png');
        const base64 = url.slice(url.indexOf(',') + 1);
        const binary = window.atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        resolve(new Blob([bytes], {type: 'image/png'}));
      } catch (err) {
        reject(err);
      }
    });
  }

  function download(blob, name) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 4000);
  }

  /**
   * The wrapper the caption and the legend are drawn into.
   *
   * Every style here is inline and in plain hex or rgba: html2canvas 1.4.1 parses the
   * computed styles of everything it renders, and oklch()/color-mix() make it throw
   * rather than degrade. The canvas is left at its natural size -- the point of the 2x
   * offscreen render is lost if CSS scales it back down.
   */
  function buildExportHost(canvas) {
    const m = state.model;
    const host = document.createElement('div');
    host.style.cssText = [
      'position:fixed',
      'left:-20000px',
      'top:0',
      'width:' + (canvas.width + 32) + 'px',
      'box-sizing:border-box',
      'padding:14px 16px 16px',
      'background:#101418',
      'color:#e8edf2',
      'font-family:' + FONT_STACK,
      'font-size:13px',
      'line-height:1.5',
    ].join(';');

    const title = document.createElement('div');
    title.style.cssText = 'font-size:17px;font-weight:600;color:#eaf2ff;margin:0 0 2px';
    title.textContent = 'HowToGo 浏览器地图' + (m.world ? ' · ' + m.world : '');

    const meta = document.createElement('div');
    meta.style.cssText = 'font-size:12px;color:#9fb0c0;margin:0 0 10px';
    meta.textContent = [
      m.dimension || '未知维度',
      model.formatTime(Date.now()),
      '节点 ' + model.formatThousands(m.stats.nodes),
      '路段 ' + model.formatThousands(m.stats.segments),
      '总长 ' + model.formatLength(m.stats.lengthBlocks),
      '生成于 ' + model.formatTime(m.generatedAt),
    ].join('  ·  ');

    canvas.style.cssText = 'display:block;border:1px solid #1b2430';

    const legend = document.createElement('div');
    legend.style.cssText = 'margin-top:10px;display:flex;flex-wrap:wrap;gap:6px 16px;font-size:12px;color:#c9d5e0';
    for (const klass of m.classes) {
      const count = m.classCounts[klass.id] || 0;
      if (!count && state.hiddenClasses.has(klass.id)) continue;
      const item = document.createElement('span');
      item.style.cssText = 'display:inline-flex;align-items:center;gap:6px';
      const chip = document.createElement('span');
      chip.style.cssText = 'display:inline-block;width:11px;height:11px;border:1px solid #0b0f14;background:' + klass.color;
      const text = document.createElement('span');
      text.textContent = model.classLabel(klass.id) + ' ' + count;
      if (state.hiddenClasses.has(klass.id)) {
        item.style.opacity = '0.45';
        text.textContent += '（已隐藏）';
      }
      item.appendChild(chip);
      item.appendChild(text);
      legend.appendChild(item);
    }
    const layers = document.createElement('span');
    layers.style.cssText = 'display:inline-flex;align-items:center;gap:6px;color:#9fb0c0';
    layers.textContent = '道路层 ' + (m.stats.layers.length ? m.stats.layers.map(model.layerLabel).join('、') : '无');
    legend.appendChild(layers);

    host.appendChild(title);
    host.appendChild(meta);
    host.appendChild(canvas);
    host.appendChild(legend);
    return host;
  }

  function nextFrame() {
    return new Promise((resolve) => window.requestAnimationFrame(() => resolve()));
  }

  /**
   * Renders the current view at 2x into an offscreen canvas, wraps it with a caption,
   * and asks html2canvas for the PNG -- falling back to the canvas itself when
   * html2canvas is missing or fails, so the button is never silently dead.
   */
  async function exportImage() {
    if (state.exporting) return;
    if (!state.model || !state.view) {
      showToast('还没有地图可以导出', 'warn');
      return;
    }
    state.exporting = true;
    setExportBusy(true);
    try {
      const offscreen = document.createElement('canvas');
      offscreen.width = Math.max(1, Math.round(state.cssWidth * EXPORT_SCALE));
      offscreen.height = Math.max(1, Math.round(state.cssHeight * EXPORT_SCALE));
      const ctx = offscreen.getContext('2d');
      const exportView = {
        centerX: state.view.centerX,
        centerZ: state.view.centerZ,
        scale: state.view.scale * EXPORT_SCALE,
        width: offscreen.width,
        height: offscreen.height,
      };
      // The same function, the same world extent, twice the pixels: `resolutionScale` multiplies
      // every pixel measurement inside the renderer -- road widths, casings, arrowheads and their
      // spacing, marker radii, the grid's minimum spacing, the label font -- so the PNG is the
      // screen's map at 2x rather than a different-looking map at a bigger size. It therefore has
      // exactly the occlusion and the label layout the screen has.
      render.drawMap(ctx, state.model, exportView, {
        showRoadNames: state.showRoadNames,
        showPlaces: state.showPlaces,
        hiddenClasses: state.hiddenClasses,
        hiddenLayers: state.hiddenLayers,
        message: state.model.empty ? EMPTY_MESSAGE : null,
        resolutionScale: EXPORT_SCALE,
      });

      let output = offscreen;
      let composed = false;
      let notice = null;
      const host = buildExportHost(offscreen);
      document.body.appendChild(host);
      try {
        await nextFrame();
        if (typeof window.html2canvas === 'function') {
          try {
            output = await window.html2canvas(host, {scale: 1, backgroundColor: '#101418', logging: false});
            composed = true;
          } catch (err) {
            // html2canvas is a nicety, not the export: the map is already drawn.
            output = offscreen;
            notice = 'html2canvas 渲染失败，已直接导出画布：' + (err && err.message ? err.message : err);
          }
        } else {
          notice = '未找到 html2canvas，已直接导出画布（不含标题与图例）';
        }
      } finally {
        host.remove();
      }

      const blob = await canvasToBlob(output);
      download(blob, exportName());
      // One message, and the honest one: a fallback says why the picture is plainer.
      showToast(notice || (composed ? '图片已导出：含标题、统计与图例' : '图片已导出'), notice ? 'warn' : 'ok');
    } catch (err) {
      showToast('导出失败：' + (err && err.message ? err.message : err), 'error');
    } finally {
      state.exporting = false;
      setExportBusy(false);
    }
  }

  function setExportBusy(busy) {
    if (!refs.export) return;
    refs.export.disabled = busy;
    refs.export.classList.toggle('busy', busy);
    refs.export.textContent = busy ? '导出中…' : '导出图片';
  }

  // -------------------------------------------------------------------------------
  // Input
  // -------------------------------------------------------------------------------

  function pointFromEvent(event) {
    const rect = refs.canvas.getBoundingClientRect();
    return {x: event.clientX - rect.left, y: event.clientY - rect.top};
  }

  function onPointerDown(event) {
    if (!state.view || event.button !== 0) return;
    refs.canvas.setPointerCapture(event.pointerId);
    refs.canvas.classList.add('dragging');
    state.drag = {id: event.pointerId, x: event.clientX, y: event.clientY};
  }

  function onPointerMove(event) {
    if (!state.view) return;
    const screen = pointFromEvent(event);
    state.pointer = geom.screenToWorld(state.view, screen.x, screen.y);
    if (state.drag && state.drag.id === event.pointerId) {
      const dx = event.clientX - state.drag.x;
      const dy = event.clientY - state.drag.y;
      state.drag.x = event.clientX;
      state.drag.y = event.clientY;
      state.view = geom.panBy(state.view, dx, dy);
      // The pan moved the map under the cursor, so the readout has to be recomputed.
      state.pointer = geom.screenToWorld(state.view, screen.x, screen.y);
      requestRender();
    }
    updateStatus();
    updateHover();
  }

  function onPointerUp(event) {
    if (state.drag && state.drag.id === event.pointerId) state.drag = null;
    refs.canvas.classList.remove('dragging');
  }

  function onPointerLeave() {
    state.pointer = null;
    updateStatus();
    updateHover();
  }

  function onWheel(event) {
    if (!state.view) return;
    event.preventDefault();
    const screen = pointFromEvent(event);
    const delta = event.deltaMode === 1 ? event.deltaY * 16 : event.deltaY;
    const factor = Math.pow(1.0015, -delta);
    state.view = geom.zoomAt(state.view, factor, screen.x, screen.y);
    state.pointer = geom.screenToWorld(state.view, screen.x, screen.y);
    updateStatus();
    updateHover();
    requestRender();
  }

  function setAutoRefresh(on) {
    state.autoRefresh = on;
    if (state.timer) {
      window.clearInterval(state.timer);
      state.timer = null;
    }
    if (on) {
      // A tick that lands during an export or an in-flight load is skipped rather than
      // queued: the next tick is five seconds away and will do just as well.
      state.timer = window.setInterval(() => {
        if (!state.exporting && !state.loading) load();
      }, AUTO_REFRESH_MS);
    }
  }

  // -------------------------------------------------------------------------------
  // Boot
  // -------------------------------------------------------------------------------

  function wire() {
    refs.fit.addEventListener('click', () => fitToBounds(true));
    refs.zoomIn.addEventListener('click', () => zoomBy(ZOOM_STEP));
    refs.zoomOut.addEventListener('click', () => zoomBy(1 / ZOOM_STEP));
    refs.refresh.addEventListener('click', () => {
      load().then(() => showToast('已刷新', 'ok'));
    });
    refs.autoRefresh.addEventListener('change', (event) => setAutoRefresh(event.target.checked));
    refs.export.addEventListener('click', () => exportImage());
    refs.showRoadNames.addEventListener('change', (event) => {
      state.showRoadNames = event.target.checked;
      requestRender();
    });
    refs.showPlaces.addEventListener('change', (event) => {
      state.showPlaces = event.target.checked;
      requestRender();
    });

    refs.canvas.addEventListener('pointerdown', onPointerDown);
    refs.canvas.addEventListener('pointermove', onPointerMove);
    refs.canvas.addEventListener('pointerup', onPointerUp);
    refs.canvas.addEventListener('pointercancel', onPointerUp);
    refs.canvas.addEventListener('pointerleave', onPointerLeave);
    refs.canvas.addEventListener('wheel', onWheel, {passive: false});
    window.addEventListener('resize', () => {
      resizeCanvas();
      requestRender();
    });
    window.addEventListener('keydown', (event) => {
      if (event.key === 'f' || event.key === 'F') fitToBounds(true);
    });
  }

  function boot() {
    resolveModules();
    refs.canvas = el('map');
    refs.title = el('map-title');
    refs.worldLine = el('world-line');
    refs.fit = el('btn-fit');
    refs.zoomIn = el('btn-zoom-in');
    refs.zoomOut = el('btn-zoom-out');
    refs.refresh = el('btn-refresh');
    refs.autoRefresh = el('auto-refresh');
    refs.export = el('btn-export');
    refs.showRoadNames = el('show-road-names');
    refs.showPlaces = el('show-places');
    refs.classList = el('class-list');
    refs.layerList = el('layer-list');
    refs.statsList = el('stats-list');
    refs.cursor = el('status-cursor');
    refs.zoom = el('status-zoom');
    refs.hover = el('status-hover');
    refs.banner = el('banner');
    refs.toast = el('toast');

    if (!refs.canvas) return;
    wire();
    rebuildPanel();
    resizeCanvas();
    fitToBounds(false);
    requestRender();
    load();
  }

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
  }

  return {
    boot,
    state,
    refs,
    EMPTY_MESSAGE,
    AUTO_REFRESH_MS,
    EXPORT_SCALE,
    exportImage,
  };
});
