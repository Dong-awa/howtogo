/*
 * app.js -- everything that needs a browser: the canvas, the panel, the keyboard-free
 * wiring, the polling and the export.
 *
 * This is the only file in the map that touches the DOM. It owns no drawing maths: the
 * screen and the export both go through render.drawMap, and the panel only decides
 * which of the model's classes and storeys are handed to it. That is what keeps the
 * exported PNG an honest copy of the screen instead of a second implementation of it.
 *
 * The interface theme is the one thing the page is told rather than working out: the
 * server resolves it on the game's thread and sends it with the roads, and this file
 * puts the chrome and the canvas into it together. See applyTheme() for what that costs
 * a page nobody is refreshing.
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
  /*
   * The full text of one place, fetched only when a card is open and the player asks for it.
   *
   * The map payload carries a one-line summary per place; this endpoint carries the rest for one
   * place at a time. It is read-only, and it reads the current world, so a place deleted since the
   * map was loaded answers 404 and the card says so rather than showing a record nothing owns.
   */
  const API_PLACE = '/api/place';
  const AUTO_REFRESH_MS = 5000;
  const EXPORT_SCALE = 2;
  const FIT_PADDING_PX = 28;
  const ZOOM_STEP = 1.25;
  const ROAD_HOVER_PX = 12;
  const PLACE_HOVER_PX = 16;
  /*
   * How close a click has to be to a place marker, in screen pixels. Markers are drawn about five
   * pixels across, so this is a forgiving click target rather than precision aiming -- and it is a
   * screen distance, not a block distance, because the player is aiming at a pixel on the screen.
   */
  const PLACE_CLICK_PX = 8;
  /*
   * How far the pointer may move between press and release and still count as a click rather than a
   * pan. A drag that ends on a marker was a drag: opening a card mid-pan is not what the player was
   * doing.
   */
  const CLICK_SLOP_PX = 4;
  /*
   * The card's size, used only to keep it inside the map area when it is put beside a click. The
   * real width and height are read back from the element when the browser will say; these are the
   * fallback for the first frame (and for the fake DOM the tests boot the app with).
   */
  const PLACE_CARD_WIDTH_PX = 290;
  const PLACE_CARD_HEIGHT_PX = 230;
  const TOAST_MS = 4500;
  const FONT_STACK = '"Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", sans-serif';

  const EMPTY_MESSAGE = '暂无道路数据';

  /*
   * The page's two palettes, as the payload and the stylesheet spell them, and the handful of
   * colours the PNG export has to repeat by hand.
   *
   * The chrome takes its colours from style.css, which keys the white one off
   * :root[data-theme="light"], and the map takes its own from render.setTheme. The export is the
   * odd one out: it builds its caption and legend as inline styles and hands the wrapper to
   * html2canvas, so its colours cannot come from either -- hence the table below. Plain hex,
   * because html2canvas 1.4.1 throws on oklch() and color-mix() rather than degrading, which is
   * exactly the failure a themed export would hit if this table used CSS variables or modern colour
   * functions.
   */
  const THEME_LIGHT = 'light';
  const THEME_DARK = 'dark';
  const THEME_ATTRIBUTE = 'data-theme';

  const EXPORT_THEME = {
    dark: {
      background: '#101418',
      text: '#e8edf2',
      title: '#eaf2ff',
      muted: '#9fb0c0',
      legend: '#c9d5e0',
      canvasBorder: '#1b2430',
      chipBorder: '#0b0f14',
    },
    light: {
      // The map's own light backdrop, so the caption sits on the same pale grey as the picture it
      // frames. Text is Google's #202124 with the two greys under it, and the hairlines are the
      // #DADCE0 the light map is drawn with.
      background: '#F1F3F4',
      text: '#202124',
      title: '#202124',
      muted: '#5F6368',
      legend: '#3C4043',
      canvasBorder: '#DADCE0',
      chipBorder: '#BDC1C6',
    },
  };

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
    theme: null,
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
    // The place whose card is open, the screen point it was opened from, and whether its full text
    // is being fetched. Null card means the card is hidden.
    placeCard: null,
    placeCardAnchor: null,
    cardLoading: false,
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
  // The interface theme
  // -------------------------------------------------------------------------------

  /**
   * Puts the page and the map into the palette the last payload named, and says whether that was a
   * change.
   *
   * <p>The payload is the only source, and it is enough. The game resolves the theme on its own
   * thread -- the player's setting, the dimension, and in auto mode the sky itself -- and sends the
   * answer with the roads, which this page already fetches on load, on refresh and, when the player
   * ticks the box, every five seconds. So a theme change arrives with a map change, and neither the
   * page nor the server needs a second endpoint or a second poll for it.
   *
   * <p>What that does not buy is a page nobody refreshes: with the poll off, a browser tab left open
   * across sunset keeps the palette it loaded with, because nothing here watches the clock or the
   * sky. The next load or refresh picks the new theme up, and that is the deliberate trade -- the
   * alternative is the page asking the game for its theme on a timer of its own, which is the second
   * poll this design exists to avoid.
   *
   * <p>The stylesheet is keyed off :root[data-theme=...] and the canvas palette is the renderer's
   * own, so both are set here: the chrome and the map are one picture and must not disagree.
   * Repainting is left to the caller, because paint() is the one place that decides when a frame is
   * due.
   */
  function applyTheme(theme) {
    const wanted = theme === THEME_LIGHT ? THEME_LIGHT : THEME_DARK;
    if (wanted === state.theme) return false;
    state.theme = wanted;
    if (typeof document !== 'undefined' && document.documentElement
      && typeof document.documentElement.setAttribute === 'function') {
      document.documentElement.setAttribute(THEME_ATTRIBUTE, wanted);
    }
    render.setTheme(wanted);
    return true;
  }

  /** The export's colours for the palette in force; the dark one when nothing has said otherwise. */
  function exportTheme() {
    return EXPORT_THEME[state.theme] || EXPORT_THEME.dark;
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
        // A payload that never arrived still has a palette: the page's own default, which is what
        // model.normalize(null) says and what the page has always looked like.
        applyTheme(state.model.theme);
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
    // Before the paint below, so the first frame of a changed theme is already the new one. The
    // theme travels with the roads, so this is also the only moment it can have changed.
    applyTheme(state.model.theme);
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
      model.formatThousands(m.places.length) + ' 地点',
      model.formatLength(stats.lengthBlocks),
      '生成于 ' + model.formatTime(m.generatedAt),
    ];
    refs.worldLine.textContent = header.join('  ·  ');

    const rows = [
      ['存档', m.world || '未知'],
      ['维度', m.dimension || '未知'],
      ['节点', model.formatThousands(stats.nodes) + (stats.nodes !== stats.computed.nodes ? '（实际 ' + stats.computed.nodes + '）' : '')],
      ['路段', model.formatThousands(stats.segments)],
      ['地点', model.formatThousands(m.places.length) + (m.favoritePlaceCount > 0
        ? '（收藏 ' + model.formatThousands(m.favoritePlaceCount) + '）' : '')],
      ['命名道路', model.formatThousands(m.namedSegmentCount)],
      ['命名地点', model.formatThousands(m.namedPlaceCount)],
      ['总长度', model.formatLength(stats.lengthBlocks)],
      ['道路层', stats.layers.length ? stats.layers.map(model.layerLabel).join('、') : '无'],
      ['生成时间', model.formatTime(m.generatedAt)],
      ['数据版本', 'v' + m.version],
    ];
    // The auto-recognised layers are part of the map now, so say how much of the map they are:
    // a player who wonders why the browser shows roads they never drew gets the answer here.
    if (m.readOnlySegments > 0) {
      const sources = Object.keys(m.sourceCounts).sort()
        .map((id) => model.sourceLabel(id) + ' ' + model.formatThousands(m.sourceCounts[id]))
        .join('、');
      rows.splice(4, 0, ['自动识别', model.formatThousands(m.readOnlySegments) + ' 段（' + sources + '）']);
    }
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
      // The painted shape, so the readout agrees with what the cursor is on: a recognised road is
      // drawn as its smoothed curve, not as the block staircase it is stored as.
      const points = seg.drawPoints || seg.points;
      let minX = Infinity;
      let minZ = Infinity;
      let maxX = -Infinity;
      let maxZ = -Infinity;
      for (const p of points) {
        if (p.x < minX) minX = p.x;
        if (p.x > maxX) maxX = p.x;
        if (p.z < minZ) minZ = p.z;
        if (p.z > maxZ) maxZ = p.z;
      }
      if (x < minX - radiusBlocks || x > maxX + radiusBlocks ||
          z < minZ - radiusBlocks || z > maxZ + radiusBlocks) continue;
      const near = geom.nearestOnPolyline(points, x, z);
      if (near.distance <= radiusBlocks && (!best || near.distance < best.distance)) {
        best = {segment: seg, distance: near.distance};
      }
    }
    return best;
  }

  function nearestPlace(x, z, radiusBlocks) {
    const m = state.model;
    let best = null;
    if (!m || !Array.isArray(m.places)) return best;
    for (const place of m.places) {
      const d = Math.hypot(place.x - x, place.z - z);
      if (d <= radiusBlocks && (!best || d < best.distance)) best = {place, distance: d};
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
      const found = place.place;
      const parts = [model.placeKindLabel(found.kind)];
      if (found.sourceLabel) parts.push(found.sourceLabel);
      const head = '地点：' + (found.hasName ? found.name : '未命名地点')
        + '（' + parts.join(' · ') + '）';
      // The payload's description keeps its line breaks for the card, but the status bar is one
      // line: collapse the whitespace here rather than making the payload carry two forms of it.
      const line = found.description.replace(/\s+/g, ' ').trim();
      refs.hover.textContent = line !== '' ? head + ' · ' + line : head;
      return;
    }
    const seg = road.segment;
    // Class, then which way it runs, then where it is, then how long this piece is -- the same list the
    // in-game readout gives for a selected road, in the same order. Length is the piece's own: the page
    // has no idea which of a name's pieces are one road and which are two that were named alike, and a
    // segment's own length is a fact about the segment. A recognised road says which layer it came
    // from and that it is read-only, so a line the game will not let the player edit is not read as
    // one of their own.
    const parts = [
      model.classLabel(seg.roadClass),
      model.directionLabel(seg.direction),
      model.layerLabel(seg.layer),
      '本段 ' + model.formatLength(seg.lengthBlocks),
    ];
    if (seg.source) {
      parts.unshift(seg.sourceLabel + ' · 只读');
    }
    refs.hover.textContent = '道路：' + (seg.hasName ? seg.name : '未命名道路') + '（' + parts.join(' · ') + '）';
  }

  // -------------------------------------------------------------------------------
  // The place card
  // -------------------------------------------------------------------------------

  /*
   * The card is the browser map's one piece of text the payload authored: a place's name, its
   * source, its tags and its description. It is therefore also the one place in this file where the
   * escaping rule matters, and the rule is absolute -- every string is written with textContent or a
   * text node, never with innerHTML and never with the DOM's markup-taking setters. A quote, a
   * backslash, a newline and Chinese are all ordinary characters here; the payload is JSON and the
   * card is a reader of it, not a document that trusts it.
   */

  /** Puts the card beside a click, inside the map area. */
  function positionPlaceCard(screen) {
    if (!refs.placeCard || !screen) return;
    const width = refs.placeCard.offsetWidth || PLACE_CARD_WIDTH_PX;
    const height = refs.placeCard.offsetHeight || PLACE_CARD_HEIGHT_PX;
    const left = Math.max(8, Math.min(screen.x + 14, state.cssWidth - width - 8));
    const top = Math.max(8, Math.min(screen.y + 14, state.cssHeight - height - 8));
    refs.placeCard.style.left = left + 'px';
    refs.placeCard.style.top = top + 'px';
  }

  /** A one-line status under the card's description, or clears it. */
  function setCardStatus(message) {
    if (!refs.placeCardStatus) return;
    refs.placeCardStatus.textContent = message || '';
    refs.placeCardStatus.hidden = !message;
  }

  /**
   * Shows the detail card for a place marker.
   *
   * <p>Everything on the card comes from the map payload -- the one-line summary, the tags, the
   * favourite flag -- so opening a card is instant and needs no request. Only the full description
   * costs one, and only if the player presses the button.
   *
   * @param place  a place from the normalised model (`model.places`)
   * @param screen the click position, so the card can be put beside it
   */
  function showPlaceCard(place, screen) {
    if (!place || !refs.placeCard) return;
    state.placeCard = place;
    state.placeCardAnchor = screen ? {x: screen.x, y: screen.y} : null;
    state.cardLoading = false;

    if (refs.placeCardName) {
      refs.placeCardName.textContent = place.hasName ? place.name : '未命名地点';
    }
    if (refs.placeCardKind) {
      refs.placeCardKind.textContent = model.placeKindLabel(place.kind);
    }
    if (refs.placeCardSource) {
      refs.placeCardSource.textContent = place.sourceShortLabel || '';
      refs.placeCardSource.hidden = !place.sourceShortLabel;
    }
    if (refs.placeCardFav) {
      refs.placeCardFav.hidden = !place.favorite;
    }
    if (refs.placeCardTags) {
      refs.placeCardTags.textContent = '';
      for (const tag of place.tags) {
        const pill = document.createElement('span');
        pill.className = 'place-tag';
        pill.textContent = tag;
        refs.placeCardTags.appendChild(pill);
      }
      refs.placeCardTags.hidden = !place.hasTags;
    }
    if (refs.placeCardDesc) {
      refs.placeCardDesc.textContent = place.hasDescription ? place.description : '暂无描述';
      refs.placeCardDesc.classList.toggle('muted', !place.hasDescription);
    }
    if (refs.placeCardFull) {
      // Only a place with a detail record behind it can have more text than the summary carries.
      // A marker derived from an older payload's POI node has nothing to fetch, so it gets no button.
      refs.placeCardFull.hidden = !place.descriptionTruncated || !place.source;
      refs.placeCardFull.disabled = false;
    }
    setCardStatus('');
    refs.placeCard.hidden = false;
    positionPlaceCard(screen);
  }

  /** Closes the card. The map, the selection and the view are untouched. */
  function hidePlaceCard() {
    state.placeCard = null;
    state.placeCardAnchor = null;
    state.cardLoading = false;
    if (refs.placeCard) refs.placeCard.hidden = true;
    if (refs.placeCardFull) refs.placeCardFull.disabled = false;
    setCardStatus('');
  }

  /**
   * Fetches the open place's full description and puts it in the card.
   *
   * <p>Fetched on demand rather than carried in the map payload: the payload is a map, and the one
   * player who opens a card is the only one who needs the whole text. The response is the current
   * world's record, so a place renamed or edited since the map was loaded updates here too.
   */
  async function loadFullPlaceDetail() {
    const place = state.placeCard;
    if (!place || state.cardLoading || !place.source) return;
    state.cardLoading = true;
    if (refs.placeCardFull) refs.placeCardFull.disabled = true;
    setCardStatus('正在读取完整详情…');
    try {
      const url = API_PLACE + '?source=' + encodeURIComponent(place.source)
        + '&id=' + encodeURIComponent(place.id);
      const response = await fetch(url, {cache: 'no-store'});
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
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
        throw new Error('响应不是有效的 JSON 对象');
      }
      const text = typeof payload.description === 'string' ? payload.description : '';
      if (typeof payload.name === 'string' && payload.name !== '' && refs.placeCardName) {
        refs.placeCardName.textContent = payload.name;
      }
      if (refs.placeCardDesc) {
        refs.placeCardDesc.textContent = text !== '' ? text : '暂无描述';
        refs.placeCardDesc.classList.toggle('muted', text === '');
      }
      setCardStatus('');
      if (refs.placeCardFull) refs.placeCardFull.hidden = true;
    } catch (err) {
      setCardStatus('读取完整详情失败：' + (err && err.message ? err.message : err));
      if (refs.placeCardFull) refs.placeCardFull.disabled = false;
    } finally {
      state.cardLoading = false;
    }
  }

  /**
   * The place marker nearest a screen point, within the click tolerance.
   *
   * <p>Measured on the screen rather than in blocks, unlike the hover readout next door: the
   * tolerance is how close the cursor is to the pixel the player sees, and that must not shrink as
   * the map is zoomed out.
   */
  function placeAt(screen) {
    const m = state.model;
    if (!m || !state.view || !Array.isArray(m.places)) return null;
    let best = null;
    for (const place of m.places) {
      const s = geom.worldToScreen(state.view, place.x, place.z);
      const distance = Math.hypot(s.x - screen.x, s.y - screen.y);
      if (distance <= PLACE_CLICK_PX && (!best || distance < best.distance)) {
        best = {place, distance};
      }
    }
    return best;
  }

  /** A click on the map: open the card of the place under the cursor, or close the open one. */
  function onMapClick(event) {
    if (!state.view) return;
    const screen = pointFromEvent(event);
    const found = placeAt(screen);
    if (found) {
      showPlaceCard(found.place, screen);
    } else {
      hidePlaceCard();
    }
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
   *
   * The colours come from EXPORT_THEME rather than from the stylesheet. The wrapper is built
   * detached from the page and handed to html2canvas, and whether a CSS variable resolves inside
   * html2canvas's clone of the document is not a property worth depending on: a light export whose
   * caption was styled through :root[data-theme] would be a dark caption around a light map. Inline
   * hex cannot fail that way.
   */
  function buildExportHost(canvas) {
    const m = state.model;
    const ink = exportTheme();
    const host = document.createElement('div');
    host.style.cssText = [
      'position:fixed',
      'left:-20000px',
      'top:0',
      'width:' + (canvas.width + 32) + 'px',
      'box-sizing:border-box',
      'padding:14px 16px 16px',
      'background:' + ink.background,
      'color:' + ink.text,
      'font-family:' + FONT_STACK,
      'font-size:13px',
      'line-height:1.5',
    ].join(';');

    const title = document.createElement('div');
    title.style.cssText = 'font-size:17px;font-weight:600;color:' + ink.title + ';margin:0 0 2px';
    title.textContent = 'HowToGo 浏览器地图' + (m.world ? ' · ' + m.world : '');

    const meta = document.createElement('div');
    meta.style.cssText = 'font-size:12px;color:' + ink.muted + ';margin:0 0 10px';
    meta.textContent = [
      m.dimension || '未知维度',
      model.formatTime(Date.now()),
      '节点 ' + model.formatThousands(m.stats.nodes),
      '路段 ' + model.formatThousands(m.stats.segments),
      '总长 ' + model.formatLength(m.stats.lengthBlocks),
      '生成于 ' + model.formatTime(m.generatedAt),
    ].join('  ·  ');

    canvas.style.cssText = 'display:block;border:1px solid ' + ink.canvasBorder;

    const legend = document.createElement('div');
    legend.style.cssText = 'margin-top:10px;display:flex;flex-wrap:wrap;gap:6px 16px;font-size:12px;color:'
      + ink.legend;
    for (const klass of m.classes) {
      const count = m.classCounts[klass.id] || 0;
      if (!count && state.hiddenClasses.has(klass.id)) continue;
      const item = document.createElement('span');
      item.style.cssText = 'display:inline-flex;align-items:center;gap:6px';
      const chip = document.createElement('span');
      chip.style.cssText = 'display:inline-block;width:11px;height:11px;border:1px solid '
        + ink.chipBorder + ';background:' + klass.color;
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
    layers.style.cssText = 'display:inline-flex;align-items:center;gap:6px;color:' + ink.muted;
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
            // The same background the map was painted on, so a light export is light all the way
            // to its edges and a dark one is unchanged.
            output = await window.html2canvas(host, {
              scale: 1,
              backgroundColor: exportTheme().background,
              logging: false,
            });
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
    // The press point is kept as well as the previous move point: a release close to where the press
    // happened is a click, and a release elsewhere is the end of a pan. Which one it was decides
    // whether a place card opens.
    state.drag = {
      id: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };
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
      if (Math.max(Math.abs(event.clientX - state.drag.startX),
        Math.abs(event.clientY - state.drag.startY)) > CLICK_SLOP_PX) {
        state.drag.moved = true;
      }
      state.view = geom.panBy(state.view, dx, dy);
      // The pan moved the map under the cursor, so the readout has to be recomputed.
      state.pointer = geom.screenToWorld(state.view, screen.x, screen.y);
      requestRender();
    }
    updateStatus();
    updateHover();
  }

  function onPointerUp(event) {
    const finished = state.drag && state.drag.id === event.pointerId ? state.drag : null;
    state.drag = null;
    refs.canvas.classList.remove('dragging');
    // A cancelled pointer is the browser taking the gesture away (a touch turning into a scroll, a
    // window losing focus): it is not a click, whatever distance it had travelled.
    if (finished && !finished.moved && event.type !== 'pointercancel') onMapClick(event);
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
    if (refs.placeCardClose) {
      refs.placeCardClose.addEventListener('click', () => hidePlaceCard());
    }
    if (refs.placeCardFull) {
      refs.placeCardFull.addEventListener('click', () => loadFullPlaceDetail());
    }
    window.addEventListener('resize', () => {
      resizeCanvas();
      requestRender();
    });
    window.addEventListener('keydown', (event) => {
      if (event.key === 'f' || event.key === 'F') fitToBounds(true);
      // Escape closes the open place card and nothing else: there is no modal state to leave, and a
      // card is the only thing on this page that can be dismissed.
      else if (event.key === 'Escape') hidePlaceCard();
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
    refs.placeCard = el('place-card');
    refs.placeCardName = el('place-card-name');
    refs.placeCardKind = el('place-card-kind');
    refs.placeCardSource = el('place-card-source');
    refs.placeCardFav = el('place-card-fav');
    refs.placeCardTags = el('place-card-tags');
    refs.placeCardDesc = el('place-card-desc');
    refs.placeCardStatus = el('place-card-status');
    refs.placeCardFull = el('place-card-full');
    refs.placeCardClose = el('place-card-close');

    if (!refs.canvas) return;
    // The card starts closed rather than relying on the markup's `hidden` alone: the page must not
    // depend on an attribute a future edit of index.html could drop.
    hidePlaceCard();
    wire();
    // The dark style until a payload says otherwise, made explicit rather than left to the
    // stylesheet's default so that data-theme always describes the frame being drawn. The first
    // successful fetch replaces it, which is what makes a light-theme page light.
    applyTheme(THEME_DARK);
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
    API_ROADS,
    API_PLACE,
    PLACE_CLICK_PX,
    EXPORT_SCALE,
    exportImage,
  };
});
