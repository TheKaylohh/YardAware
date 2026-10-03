// The yard map: a background image, clickable zone polygons, and item tiles laid out inside each zone.
// Pure SVG with a hand-rolled pan/zoom (viewBox), so there are no dependencies and it works offline.
// To use a real aerial photo or site plan later, replace /img/yard.svg and redraw the zone points.

import { state, emit } from './state.js';
import { s, clear, NEUTRAL_OUTLINE, LEVEL_CODES } from './util.js';

const MAP_W = 1800;
const MAP_H = 1500;
const CELL_W = 68;
const CELL_H = 72;
const TILE = 40;
const ZONE_PAD = 8;
const ZONE_HEAD = 34;
const ZONE_FOOT = 20;
const MIN_SCALE = 0.55;
const LABEL_SCALE = 0.8;
const MIN_VIEW_W = 300;
const DRAG_THRESHOLD_PX = 5;

let svg;
let zoneLayer;
let itemLayer;
let busy = false; // true while dragging an item or panning, so hover previews don't pop up
const view = { x: 0, y: 0, w: MAP_W, h: MAP_H };
const zoneGeometry = new Map(); // zoneId -> { zone, points: [[x, y], ...], bbox }

export function initMap(container) {
  svg = s('svg', { id: 'map', viewBox: `0 0 ${MAP_W} ${MAP_H}`, role: 'group', 'aria-label': 'Yard map' });
  svg.append(s('image', { href: '/img/yard.svg', x: 0, y: 0, width: MAP_W, height: MAP_H }));
  zoneLayer = s('g', { class: 'zones' });
  itemLayer = s('g', { class: 'items' });
  svg.append(zoneLayer, itemLayer);
  container.prepend(svg);

  svg.addEventListener('wheel', (e) => {
    e.preventDefault();
    zoomAt(toSvg(e), Math.exp(e.deltaY * 0.0015));
  }, { passive: false });
  svg.addEventListener('pointerdown', startPan);

  const byId = (id) => document.getElementById(id);
  byId('zoom-in')?.addEventListener('click', () => zoomAt(viewCenter(), 0.7));
  byId('zoom-out')?.addEventListener('click', () => zoomAt(viewCenter(), 1 / 0.7));
  byId('zoom-fit')?.addEventListener('click', fit);
  window.addEventListener('resize', fit);
  fit();
}

// ------------------------------------------------------------------ view

function aspect() {
  const w = svg.clientWidth;
  const h = svg.clientHeight;
  return w && h ? w / h : MAP_W / MAP_H;
}

export function fit() {
  const r = aspect();
  if (r > MAP_W / MAP_H) {
    view.h = MAP_H;
    view.w = MAP_H * r;
  } else {
    view.w = MAP_W;
    view.h = MAP_W / r;
  }
  view.x = (MAP_W - view.w) / 2;
  view.y = (MAP_H - view.h) / 2;
  applyView();
}

function applyView() {
  view.x = Math.min(Math.max(view.x, -view.w * 0.6), MAP_W - view.w * 0.4);
  view.y = Math.min(Math.max(view.y, -view.h * 0.6), MAP_H - view.h * 0.4);
  svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.w} ${view.h}`);
}

function viewCenter() {
  return { x: view.x + view.w / 2, y: view.y + view.h / 2 };
}

function zoomAt(point, factor) {
  const maxW = MAP_W * 1.3;
  const newW = Math.min(Math.max(view.w * factor, MIN_VIEW_W), maxW);
  const ratio = newW / view.w;
  view.x = point.x - (point.x - view.x) * ratio;
  view.y = point.y - (point.y - view.y) * ratio;
  view.w = newW;
  view.h *= ratio;
  applyView();
}

function toSvg(e) {
  const matrix = svg.getScreenCTM();
  if (!matrix) return { x: 0, y: 0 };
  const pt = svg.createSVGPoint();
  pt.x = e.clientX;
  pt.y = e.clientY;
  return pt.matrixTransform(matrix.inverse());
}

function startPan(e) {
  if (e.button !== 0 || e.target.closest('.item')) return;
  const matrix = svg.getScreenCTM();
  const scale = matrix ? matrix.a : 1;
  const start = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y };
  let moved = false;
  svg.setPointerCapture(e.pointerId);

  const move = (ev) => {
    const dx = ev.clientX - start.x;
    const dy = ev.clientY - start.y;
    if (!moved && Math.hypot(dx, dy) > 4) {
      moved = true;
      busy = true;
      svg.classList.add('panning');
    }
    if (moved) {
      view.x = start.vx - dx / scale;
      view.y = start.vy - dy / scale;
      applyView();
    }
  };
  const up = (ev) => {
    svg.releasePointerCapture(ev.pointerId);
    svg.removeEventListener('pointermove', move);
    svg.removeEventListener('pointerup', up);
    svg.removeEventListener('pointercancel', up);
    busy = false;
    svg.classList.remove('panning');
    if (!moved) emit('background-click');
  };
  svg.addEventListener('pointermove', move);
  svg.addEventListener('pointerup', up);
  svg.addEventListener('pointercancel', up);
}

/** Centre the view on an item and flash it. */
export function focusItem(itemId) {
  const g = itemLayer.querySelector(`.item[data-id="${itemId}"]`);
  if (!g || !g.__pos) return;
  const ratio = view.h / view.w;
  view.w = Math.min(view.w, 640);
  view.h = view.w * ratio;
  view.x = g.__pos.cx - view.w / 2;
  view.y = g.__pos.cy - view.h / 2;
  applyView();
  g.classList.add('flash');
  setTimeout(() => g.classList.remove('flash'), 1800);
}

// ----------------------------------------------------------------- zones

export function renderZones() {
  clear(zoneLayer);
  zoneGeometry.clear();
  for (const zone of state.zones) {
    const points = zone.points.trim().split(/\s+/).map((pair) => pair.split(',').map(Number));
    const xs = points.map((p) => p[0]);
    const ys = points.map((p) => p[1]);
    const bbox = { minX: Math.min(...xs), minY: Math.min(...ys), maxX: Math.max(...xs), maxY: Math.max(...ys) };
    zoneGeometry.set(zone.id, { zone, points, bbox });

    zoneLayer.append(s('g', { class: `zone zone-${zone.kind || 'other'}`, 'data-zone': zone.id },
      s('polygon', { class: 'zone-shape', points: zone.points }),
      s('text', { class: 'zone-name', x: bbox.minX + 10, y: bbox.minY + 22 }, zone.name),
      s('text', { class: 'zone-count', x: bbox.maxX - 10, y: bbox.maxY - 9, 'text-anchor': 'end', 'data-count': zone.id }),
    ));
  }
}

function insidePolygon(p, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const [xi, yi] = points[i];
    const [xj, yj] = points[j];
    if ((yi > p.y) !== (yj > p.y) && p.x < ((xj - xi) * (p.y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

function zoneAt(p) {
  for (const { zone, points } of zoneGeometry.values()) {
    if (insidePolygon(p, points)) return zone;
  }
  return null;
}

function highlightDropZone(p) {
  const zone = zoneAt(p);
  zoneLayer.querySelectorAll('.zone').forEach((g) => {
    g.classList.toggle('drop', !!zone && String(zone.id) === g.getAttribute('data-zone'));
  });
}

function clearDropZone() {
  zoneLayer.querySelectorAll('.zone.drop').forEach((g) => g.classList.remove('drop'));
}

// ----------------------------------------------------------------- items

function isHidden(item) {
  return state.hiddenHulls.has(item.hullId);
}

export function renderItems() {
  clear(itemLayer);
  const byZone = new Map();
  for (const item of state.items) {
    if (isHidden(item) || !zoneGeometry.has(item.zoneId)) continue;
    if (!byZone.has(item.zoneId)) byZone.set(item.zoneId, []);
    byZone.get(item.zoneId).push(item);
  }
  for (const [zoneId, list] of byZone) {
    const { bbox } = zoneGeometry.get(zoneId);
    list.sort((a, b) => a.name.localeCompare(b.name));
    const layout = fitTiles(list.length, bbox.maxX - bbox.minX, bbox.maxY - bbox.minY);
    list.forEach((item, index) => {
      const cx = bbox.minX + ZONE_PAD + (index % layout.cols) * layout.cellW + layout.cellW / 2;
      const cy = bbox.minY + ZONE_HEAD + Math.floor(index / layout.cols) * layout.cellH + 20 * layout.scale;
      itemLayer.append(buildItem(item, cx, cy, layout.scale, layout.labelled));
    });
  }
  updateZoneCounts();
  applySearch();
  syncPinned();
}

/**
 * Busy zones shrink their tiles so everything stays inside the outline: the biggest scale (1 down to MIN_SCALE) at
 * which all n tiles fit. Small tiles drop the label under them; the tooltip and the panel still name the item.
 */
function fitTiles(n, width, height) {
  let layout;
  for (let scale = 1; scale >= MIN_SCALE - 0.001; scale -= 0.05) {
    const labelled = scale >= LABEL_SCALE;
    const cellW = labelled ? CELL_W * scale : (TILE + 8) * scale;
    const cellH = labelled ? CELL_H * scale : (TILE + 8) * scale;
    const cols = Math.max(1, Math.floor((width - 2 * ZONE_PAD) / cellW));
    const rows = Math.max(1, Math.floor((height - ZONE_HEAD - ZONE_FOOT) / cellH));
    layout = { scale, labelled, cellW, cellH, cols };
    if (cols * rows >= n) break;
  }
  return layout;
}

function updateZoneCounts() {
  const counts = new Map();
  for (const item of state.items) counts.set(item.zoneId, (counts.get(item.zoneId) || 0) + 1);
  zoneLayer.querySelectorAll('[data-count]').forEach((node) => {
    const n = counts.get(Number(node.getAttribute('data-count'))) || 0;
    node.textContent = n ? `${n} item${n === 1 ? '' : 's'}` : '';
  });
}

/** The plan code without the ship prefix (the outline colour says which ship); long codes keep their specific end. */
function shortLabel(item) {
  const label = item.nodeCode;
  return label.length > 13 ? `…${label.slice(-12)}` : label;
}

function buildItem(item, cx, cy, scale = 1, labelled = true) {
  const outline = item.hullColor || NEUTRAL_OUTLINE;
  const g = s('g', {
    class: 'item',
    tabindex: 0,
    role: 'button',
    transform: `translate(${cx} ${cy}) scale(${scale})`,
    'data-id': item.id,
    'aria-label': `${item.name}, ${item.levelLabel}, phase ${item.phaseNumber} ${item.phaseName}, in ${item.zoneName}`,
  },
  s('title', {}, `${item.name}, ${item.levelLabel}. Phase ${item.phaseNumber}: ${item.phaseName}. ${item.zoneName}`),
  s('rect', { class: 'item-halo', x: -27, y: -27, width: 54, height: 54, rx: 10 }),
  s('rect', {
    class: 'item-tile', x: -20, y: -20, width: TILE, height: TILE, rx: 6,
    stroke: outline,
  }),
  s('text', { class: 'item-code', 'text-anchor': 'middle', dy: '.35em' }, LEVEL_CODES[item.level] || '?'),
  labelled ? s('text', { class: 'item-label', y: 37, 'text-anchor': 'middle' }, shortLabel(item)) : null);
  g.__pos = { cx, cy, scale };

  // The badge is the production phase (1-11) the item is in now.
  const phaseText = String(item.phaseNumber);
  const badgeWidth = 14 + phaseText.length * 7;
  g.append(s('g', { class: 'item-badge', transform: 'translate(17 -21)' },
    s('rect', { x: -badgeWidth / 2, y: -8, width: badgeWidth, height: 16, rx: 8 }),
    s('text', { 'text-anchor': 'middle', dy: '.35em' }, phaseText)));
  g.append(s('g', { class: 'pick-mark', transform: 'translate(-17 -17)' },
    s('circle', { r: 9 }),
    s('path', { d: 'M-4,0 L-1,3.5 L4.5,-3', fill: 'none' })));

  g.classList.toggle('picked', state.picked.has(item.id));

  g.addEventListener('pointerenter', () => { if (!busy) emit('item-hover', item.id); });
  g.addEventListener('pointerleave', () => { if (!busy) emit('item-leave', item.id); });
  g.addEventListener('focus', () => emit('item-hover', item.id));
  g.addEventListener('blur', () => emit('item-leave', item.id));
  g.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      emit('item-click', item.id);
    }
  });
  g.addEventListener('pointerdown', (e) => startItemPointer(e, item, g));
  return g;
}

/** Click opens the panel; in edit mode, dragging onto another zone moves the item. */
function startItemPointer(e, item, g) {
  if (e.button !== 0) return;
  e.stopPropagation();
  const canDrag = state.mode === 'edit' && !state.assembling;
  const start = { x: e.clientX, y: e.clientY };
  let dragging = false;
  g.setPointerCapture(e.pointerId);

  const move = (ev) => {
    if (!dragging && canDrag && Math.hypot(ev.clientX - start.x, ev.clientY - start.y) > DRAG_THRESHOLD_PX) {
      dragging = true;
      busy = true;
      g.classList.add('dragging');
      itemLayer.append(g); // bring to front
      emit('drag-start', item.id);
    }
    if (dragging) {
      const p = toSvg(ev);
      g.setAttribute('transform', `translate(${p.x} ${p.y}) scale(${g.__pos.scale})`);
      highlightDropZone(p);
    }
  };
  const up = (ev) => {
    g.releasePointerCapture(ev.pointerId);
    g.removeEventListener('pointermove', move);
    g.removeEventListener('pointerup', up);
    g.removeEventListener('pointercancel', up);
    if (!dragging) {
      emit('item-click', item.id);
      return;
    }
    busy = false;
    g.classList.remove('dragging');
    clearDropZone();
    const zone = zoneAt(toSvg(ev));
    if (zone && zone.id !== item.zoneId) {
      emit('item-move', { item, zone });
    } else {
      g.setAttribute('transform', `translate(${g.__pos.cx} ${g.__pos.cy}) scale(${g.__pos.scale})`); // snap back
    }
  };
  g.addEventListener('pointermove', move);
  g.addEventListener('pointerup', up);
  g.addEventListener('pointercancel', up);
}

// ------------------------------------------------- highlight / search / pins

export function syncPinned() {
  itemLayer.querySelectorAll('.item').forEach((g) => {
    const id = Number(g.getAttribute('data-id'));
    g.classList.toggle('pinned', state.pinned && id === state.panelItemId);
  });
}

export function syncPicks() {
  itemLayer.querySelectorAll('.item').forEach((g) => {
    g.classList.toggle('picked', state.picked.has(Number(g.getAttribute('data-id'))));
  });
}

function matches(item, q) {
  return [item.name, item.nodeCode, item.areaCode, item.areaName, item.levelLabel, item.hullCode, item.zoneName,
    item.phaseName, item.function, item.side]
    .filter(Boolean)
    .some((value) => value.toLowerCase().includes(q));
}

/** Dim items that don't match the search box. Returns the matching ids. */
export function applySearch() {
  const q = state.query.trim().toLowerCase();
  const hits = [];
  itemLayer.querySelectorAll('.item').forEach((g) => {
    const id = Number(g.getAttribute('data-id'));
    const item = state.items.find((i) => i.id === id);
    const hit = !q || (item && matches(item, q));
    g.classList.toggle('dim', !hit);
    if (hit && q) hits.push(id);
  });
  return hits;
}
