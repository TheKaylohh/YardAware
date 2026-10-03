// The Data page: every tracked item in a spreadsheet-style grid.
// Rows are drawn on demand (only what is on screen), so thousands of rows stay fast. Edits stay in the browser until
// "Save changes"; the server checks them against the same rules as the map and saves them all or nothing.

import { ready } from './nav.js';
import { api } from './api.js';
import { h, clear, toast } from './util.js';
import { renderReport } from './report.js';

const ROW_H = 30;
const HEAD_H = 34;
const FILTER_H = 30;
const OVERSCAN = 6;

const STATUS_LABEL = { PLANNED: 'Planned', ACTIVE: 'On the yard', CONSUMED: 'Joined into parent' };

const COLS = [
  { key: '_flag', label: '', w: 30, flag: true, frozen: true },
  { key: 'name', label: 'Name', w: 220, frozen: true },
  { key: 'hull', label: 'Ship', w: 64 },
  { key: 'level', label: 'Level', w: 130 },
  { key: 'area', label: 'Area', w: 56 },
  { key: 'parent', label: 'Parent', w: 210 },
  { key: 'status', label: 'Status', w: 130 },
  { key: 'zone', label: 'Zone', w: 96, edit: true },
  { key: 'phase', label: 'Phase', w: 70, edit: true, num: true },
  { key: 'phaseName', label: 'Phase name', w: 200 },
  { key: 'route', label: 'Route', w: 110 },
  { key: 'specs', label: 'Specs (JSON)', w: 320, edit: true, mono: true },
];
const EDIT_KEYS = COLS.filter((c) => c.edit).map((c) => c.key);
const FIRST_EDIT_COL = COLS.findIndex((c) => c.edit);

// column left offsets, and the width of the frozen columns
const LEFTS = [];
let TOTAL_W = 0;
let FROZEN_W = 0;
for (const col of COLS) {
  LEFTS.push(TOTAL_W);
  TOTAL_W += col.w;
  if (col.frozen) FROZEN_W += col.w;
}

const $ = (id) => document.getElementById(id);

const S = {
  rows: [],
  zones: [],
  zoneByKey: new Map(),
  view: [],
  sort: { key: null, dir: 1 },
  colFilter: {},
  q: '',
  hull: '',
  status: '',
  changedOnly: false,
  sel: { r: 0, c: FIRST_EDIT_COL, r2: 0, c2: FIRST_EDIT_COL },
  editing: null,
  dragging: false,
  undo: [],
  redo: [],
  canEdit: false,
  busy: false,
};

let gridEl;
let rowsEl;
let editor;
let clip;

// ------------------------------------------------------------------- data

function prep(raw) {
  const cur = { zone: raw.zone || '', phase: raw.phase == null ? '' : String(raw.phase), specs: raw.specs || '' };
  return { ...raw, cur, orig: { ...cur }, check: null };
}

async function load() {
  const [rows, zones] = await Promise.all([api.get('/grid/items'), api.get('/zones')]);
  S.rows = rows.map(prep);
  S.zones = zones;
  S.zoneByKey = new Map();
  for (const z of zones) {
    S.zoneByKey.set(z.code.toLowerCase(), z);
    if (!S.zoneByKey.has(z.name.toLowerCase())) S.zoneByKey.set(z.name.toLowerCase(), z);
  }
  const list = $('zone-list');
  clear(list);
  for (const z of zones) list.append(h('option', { value: z.code }, z.name));
  const hulls = [...new Set(rows.map((r) => r.hull))].sort();
  const select = $('f-hull');
  const keep = select.value;
  while (select.options.length > 1) select.remove(1);
  for (const code of hulls) select.append(h('option', { value: code }, code));
  select.value = hulls.includes(keep) ? keep : '';
  S.hull = select.value;
  S.undo = [];
  S.redo = [];
}

function resolveZone(text) {
  return S.zoneByKey.get(String(text).trim().toLowerCase()) || null;
}

function isDirty(row) {
  return row.cur.zone !== row.orig.zone || row.cur.phase !== row.orig.phase || row.cur.specs.trim() !== row.orig.specs.trim();
}

function cellDirty(row, key) {
  return key === 'specs' ? row.cur.specs.trim() !== row.orig.specs.trim() : row.cur[key] !== row.orig[key];
}

/** Problems with a row's edited cells, as { zone?, phase?, specs? } messages. */
function problems(row) {
  const p = {};
  const zone = row.cur.zone.trim();
  const phase = row.cur.phase.trim();
  const specs = row.cur.specs.trim();
  if (zone) {
    if (!resolveZone(zone)) p.zone = `Unknown zone "${zone}".`;
  } else if (row.orig.zone) {
    p.zone = 'A blank zone means "no change". Type a zone to move the item, or press Delete to undo.';
  }
  if (phase) {
    if (!/^\d{1,3}$/.test(phase)) p.phase = 'Enter a phase number.';
    else if (!row.routePhases.includes(Number(phase))) p.phase = `Phase ${phase} isn't on this item's route (${row.route || 'none'}).`;
  } else if (row.orig.phase) {
    p.phase = 'A blank phase means "no change". Press Delete to undo.';
  }
  if (specs) {
    try {
      const v = JSON.parse(specs);
      if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('not an object');
    } catch (e) {
      p.specs = 'Specs must be a JSON object such as {"Weight (t)": 92.5}. Write {} to clear them.';
    }
  } else if (row.orig.specs) {
    p.specs = 'Blank specs mean "no change". Write {} to clear them, or press Delete to undo.';
  }
  const zoneChanged = !!zone && zone !== row.orig.zone;
  if (row.status === 'PLANNED' && !p.phase && phase && phase !== row.orig.phase && !zoneChanged) {
    p.phase = 'This item is only planned. Give it a zone too, so it is placed on the yard first.';
  }
  return p;
}

function isEditable(row, col) {
  return S.canEdit && !!row && !!col.edit && !(row.status === 'CONSUMED' && col.key !== 'specs');
}

function cellText(row, key) {
  if (key === 'status') return STATUS_LABEL[row.status] || row.status;
  if (EDIT_KEYS.includes(key)) return row.cur[key];
  return row[key] == null ? '' : String(row[key]);
}

// ------------------------------------------------------------------- view

function buildView() {
  const q = S.q.trim().toLowerCase();
  let list = S.rows.filter((r) => {
    if (S.hull && r.hull !== S.hull) return false;
    if (S.status && r.status !== S.status) return false;
    if (S.changedOnly && !isDirty(r)) return false;
    if (q) {
      const hay = [r.name, r.hull, r.level, r.area, r.parent, STATUS_LABEL[r.status], r.cur.zone, r.cur.phase, r.phaseName, r.cur.specs]
        .join('\n').toLowerCase();
      if (!hay.includes(q)) return false;
    }
    for (const [key, value] of Object.entries(S.colFilter)) {
      if (value && !cellText(r, key).toLowerCase().includes(value)) return false;
    }
    return true;
  });
  const { key, dir } = S.sort;
  if (key) {
    const numeric = COLS.find((c) => c.key === key).num;
    list = list.slice().sort((a, b) => {
      const x = cellText(a, key);
      const y = cellText(b, key);
      if (numeric) return dir * ((x === '' ? -1 : Number(x)) - (y === '' ? -1 : Number(y)));
      return dir * x.localeCompare(y, undefined, { numeric: true, sensitivity: 'base' });
    });
  }
  S.view = list;
  const max = Math.max(0, list.length - 1);
  S.sel.r = Math.min(S.sel.r, max);
  S.sel.r2 = Math.min(S.sel.r2, max);
}

function updateCount() {
  const dirty = S.rows.filter(isDirty).length;
  $('count').textContent = `${S.view.length.toLocaleString()} of ${S.rows.length.toLocaleString()} items`
    + (dirty ? ` · ${dirty} changed` : '');
}

// ------------------------------------------------------------------ paint

function buildHeader() {
  const head = $('g-head');
  const filters = $('g-filters');
  clear(head);
  clear(filters);
  head.style.width = `${TOTAL_W}px`;
  filters.style.width = `${TOTAL_W}px`;
  COLS.forEach((col, c) => {
    const frozen = col.frozen ? `left:${LEFTS[c]}px;` : '';
    const arrow = h('span', { class: 'arrow' });
    const cell = h('div', {
      class: `g-h${col.edit ? ' edit' : ''}${col.frozen ? ' frozen' : ''}`,
      style: `width:${col.w}px;${frozen}`,
      role: 'columnheader',
      title: col.flag ? 'Changed (●) or problem (!)' : (col.edit ? `${col.label}: editable. Click to sort.` : `${col.label}. Click to sort.`),
      onClick: () => {
        if (col.flag) return;
        if (S.sort.key !== col.key) S.sort = { key: col.key, dir: 1 };
        else if (S.sort.dir === 1) S.sort.dir = -1;
        else S.sort = { key: null, dir: 1 };
        refresh();
      },
    }, col.label, arrow);
    cell.dataset.key = col.key;
    head.append(cell);

    const f = h('div', { class: `g-f${col.frozen ? ' frozen' : ''}`, style: `width:${col.w}px;${frozen}` });
    if (!col.flag) {
      const input = h('input', { type: 'text', placeholder: 'Filter', 'aria-label': `Filter ${col.label}` });
      let timer = null;
      input.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
          S.colFilter[col.key] = input.value.trim().toLowerCase();
          refresh();
        }, 120);
      });
      f.append(input);
    }
    filters.append(f);
  });
}

function paintHeaderArrows() {
  for (const cell of $('g-head').children) {
    const arrow = cell.querySelector('.arrow');
    arrow.textContent = S.sort.key === cell.dataset.key ? (S.sort.dir === 1 ? '▲' : '▼') : '';
  }
}

function inSelection(r, c) {
  const { sel } = S;
  return r >= Math.min(sel.r, sel.r2) && r <= Math.max(sel.r, sel.r2) && c >= Math.min(sel.c, sel.c2) && c <= Math.max(sel.c, sel.c2);
}

function cellEl(row, r, col, c) {
  const editable = isEditable(row, col);
  const classes = ['g-cell'];
  if (!editable && !col.flag) classes.push('ro');
  if (col.frozen) classes.push('frozen');
  if (col.mono) classes.push('mono');
  if (col.num) classes.push('num');
  if (col.flag) classes.push('flag');
  let text = col.flag ? '' : cellText(row, col.key);
  let title = '';
  if (col.flag) {
    const bad = row.check && row.check.outcome === 'ERROR';
    if (bad) {
      classes.push('err');
      text = '!';
      title = row.check.message;
    } else if (isDirty(row)) {
      classes.push('dirty');
      text = '●';
      title = 'Changed';
    }
  } else if (col.edit) {
    if (cellDirty(row, col.key)) classes.push('dirty');
    const p = problems(row)[col.key];
    if (p) {
      classes.push('invalid');
      title = p;
    }
  }
  if (inSelection(r, c)) classes.push('sel');
  if (r === S.sel.r && c === S.sel.c) classes.push('anchor');
  const style = `width:${col.w}px;${col.frozen ? `left:${LEFTS[c]}px;` : ''}`;
  const el = h('div', { class: classes.join(' '), style, role: 'gridcell' }, text);
  el.dataset.r = String(r);
  el.dataset.c = String(c);
  if (title) el.title = title;
  else if (col.key === 'specs' && text.length > 40) el.title = text;
  return el;
}

function paint() {
  const body = $('g-body');
  body.style.height = `${S.view.length * ROW_H}px`;
  body.style.width = `${TOTAL_W}px`;
  const first = Math.max(0, Math.floor(gridEl.scrollTop / ROW_H) - OVERSCAN);
  const visible = Math.ceil(Math.max(0, gridEl.clientHeight - HEAD_H - FILTER_H) / ROW_H) + OVERSCAN * 2;
  const last = Math.min(S.view.length, first + visible);
  clear(rowsEl);
  for (let r = first; r < last; r++) {
    const row = S.view[r];
    const el = h('div', { class: 'g-row', style: `top:${r * ROW_H}px;height:${ROW_H}px;width:${TOTAL_W}px`, role: 'row' });
    COLS.forEach((col, c) => el.append(cellEl(row, r, col, c)));
    rowsEl.append(el);
  }
}

function refresh() {
  buildView();
  paintHeaderArrows();
  paint();
  updateCount();
  updateSaveBar();
}

function afterEdit() {
  paint();
  updateCount();
  updateSaveBar();
}

function updateSaveBar() {
  const bar = $('savebar');
  bar.hidden = !S.canEdit;
  if (!S.canEdit) return;
  const dirty = S.rows.filter(isDirty);
  const bad = dirty.filter((r) => Object.keys(problems(r)).length > 0).length;
  const msg = $('dirty-msg');
  if (!dirty.length) {
    msg.textContent = 'No unsaved changes.';
  } else {
    msg.textContent = `${dirty.length} item${dirty.length === 1 ? '' : 's'} changed`
      + (bad ? ` · ${bad} with problems (red cells)` : '') + '. Nothing is saved until you press Save changes.';
  }
  $('btn-discard').disabled = !dirty.length || S.busy;
  $('btn-check').disabled = !dirty.length || bad > 0 || S.busy;
  $('btn-save').disabled = !dirty.length || bad > 0 || S.busy;
  $('btn-save').textContent = dirty.length ? `Save ${dirty.length} change${dirty.length === 1 ? '' : 's'}` : 'Save changes';
}

// -------------------------------------------------------------- selection

function clampSel() {
  const maxR = Math.max(0, S.view.length - 1);
  const maxC = COLS.length - 1;
  S.sel.r = Math.max(0, Math.min(maxR, S.sel.r));
  S.sel.r2 = Math.max(0, Math.min(maxR, S.sel.r2));
  S.sel.c = Math.max(1, Math.min(maxC, S.sel.c));
  S.sel.c2 = Math.max(1, Math.min(maxC, S.sel.c2));
}

function ensureVisible(r, c) {
  const viewH = gridEl.clientHeight - HEAD_H - FILTER_H;
  const top = r * ROW_H;
  if (top < gridEl.scrollTop) gridEl.scrollTop = top;
  else if (top + ROW_H > gridEl.scrollTop + viewH) gridEl.scrollTop = top + ROW_H - viewH;
  const left = LEFTS[c];
  const right = left + COLS[c].w;
  if (!COLS[c].frozen) {
    if (left < gridEl.scrollLeft + FROZEN_W) gridEl.scrollLeft = Math.max(0, left - FROZEN_W);
    else if (right > gridEl.scrollLeft + gridEl.clientWidth) gridEl.scrollLeft = right - gridEl.clientWidth;
  }
}

function moveBy(dr, dc, extend) {
  if (extend) {
    S.sel.r2 += dr;
    S.sel.c2 += dc;
  } else {
    S.sel.r += dr;
    S.sel.c += dc;
    S.sel.r2 = S.sel.r;
    S.sel.c2 = S.sel.c;
  }
  clampSel();
  ensureVisible(extend ? S.sel.r2 : S.sel.r, extend ? S.sel.c2 : S.sel.c);
  paint();
}

function selectedCells() {
  const cells = [];
  const r1 = Math.min(S.sel.r, S.sel.r2);
  const r2 = Math.max(S.sel.r, S.sel.r2);
  const c1 = Math.min(S.sel.c, S.sel.c2);
  const c2 = Math.max(S.sel.c, S.sel.c2);
  for (let r = r1; r <= r2; r++) for (let c = c1; c <= c2; c++) cells.push([r, c]);
  return cells;
}

// ------------------------------------------------------------------ edits

/** Sets one cell. Returns a change record, or null if nothing changed or the cell isn't editable. */
function setCell(r, c, value) {
  const row = S.view[r];
  const col = COLS[c];
  if (!row || !col || !isEditable(row, col)) return null;
  let v = String(value == null ? '' : value).replace(/[\r\n\t]+/g, ' ').trim();
  if (col.key === 'zone') {
    const z = resolveZone(v);
    if (z) v = z.code;
  } else if (col.key === 'phase') {
    v = v.replace(/\.0+$/, '');
  }
  const prev = row.cur[col.key];
  if (prev === v) return null;
  row.cur[col.key] = v;
  row.check = null;
  return { row, key: col.key, prev, next: v };
}

function commit(changes) {
  const done = changes.filter(Boolean);
  if (!done.length) return 0;
  S.undo.push(done);
  if (S.undo.length > 200) S.undo.shift();
  S.redo = [];
  afterEdit();
  return done.length;
}

function startEdit(initial) {
  const { r, c } = S.sel;
  const row = S.view[r];
  const col = COLS[c];
  if (!row || !isEditable(row, col)) return;
  ensureVisible(r, c);
  paint();
  editor.style.top = `${r * ROW_H}px`;
  editor.style.left = `${LEFTS[c]}px`;
  editor.style.width = `${col.w}px`;
  editor.style.height = `${ROW_H}px`;
  if (col.key === 'zone') editor.setAttribute('list', 'zone-list');
  else editor.removeAttribute('list');
  editor.value = initial !== undefined ? initial : row.cur[col.key];
  editor.hidden = false;
  S.editing = { r, c };
  editor.focus();
  if (initial === undefined) editor.select();
  else editor.setSelectionRange(editor.value.length, editor.value.length);
}

function endEdit(save) {
  if (!S.editing) return;
  const { r, c } = S.editing;
  S.editing = null;
  const value = editor.value;
  editor.hidden = true;
  clip.focus();
  if (save) commit([setCell(r, c, value)]);
  else paint();
}

function resetCells() {
  const changes = [];
  for (const [r, c] of selectedCells()) {
    const row = S.view[r];
    const col = COLS[c];
    if (row && col.edit && isEditable(row, col)) changes.push(setCell(r, c, row.orig[col.key]));
  }
  commit(changes);
}

function fillDown() {
  const r1 = Math.min(S.sel.r, S.sel.r2);
  const r2 = Math.max(S.sel.r, S.sel.r2);
  const c1 = Math.min(S.sel.c, S.sel.c2);
  const c2 = Math.max(S.sel.c, S.sel.c2);
  if (r1 === r2) return;
  const changes = [];
  for (let c = c1; c <= c2; c++) {
    if (!COLS[c].edit) continue;
    const source = S.view[r1].cur[COLS[c].key];
    for (let r = r1 + 1; r <= r2; r++) changes.push(setCell(r, c, source));
  }
  commit(changes);
}

function undo() {
  const batch = S.undo.pop();
  if (!batch) return;
  for (const ch of batch) {
    ch.row.cur[ch.key] = ch.prev;
    ch.row.check = null;
  }
  S.redo.push(batch);
  afterEdit();
}

function redo() {
  const batch = S.redo.pop();
  if (!batch) return;
  for (const ch of batch) {
    ch.row.cur[ch.key] = ch.next;
    ch.row.check = null;
  }
  S.undo.push(batch);
  afterEdit();
}

// -------------------------------------------------------- copy and paste

/** Tab-separated text, quoted the way Excel does, so it pastes into a spreadsheet unchanged. */
function toTsv() {
  const lines = [];
  const r1 = Math.min(S.sel.r, S.sel.r2);
  const r2 = Math.max(S.sel.r, S.sel.r2);
  const c1 = Math.min(S.sel.c, S.sel.c2);
  const c2 = Math.max(S.sel.c, S.sel.c2);
  for (let r = r1; r <= r2; r++) {
    const parts = [];
    for (let c = c1; c <= c2; c++) {
      const key = COLS[c].key;
      let text = key === '_flag' ? '' : cellText(S.view[r], key);
      if (/["\t\n\r]/.test(text)) text = `"${text.replace(/"/g, '""')}"`;
      parts.push(text);
    }
    lines.push(parts.join('\t'));
  }
  return lines.join('\n');
}

/** Parses tab-separated text with Excel-style quoting into rows of cells. */
function parseTsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  let i = 0;
  const s = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  while (i < s.length) {
    const ch = s[i];
    if (quoted) {
      if (ch === '"') {
        if (s[i + 1] === '"') { cell += '"'; i += 2; continue; }
        quoted = false;
      } else {
        cell += ch;
      }
    } else if (ch === '"' && cell === '') {
      quoted = true;
    } else if (ch === '\t') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += ch;
    }
    i += 1;
  }
  if (cell !== '' || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

function pasteText(text) {
  if (!S.canEdit || !text) return;
  const grid = parseTsv(text);
  if (!grid.length) return;
  const changes = [];
  const multi = selectedCells().length > 1;
  if (grid.length === 1 && grid[0].length === 1 && multi) {
    for (const [r, c] of selectedCells()) changes.push(setCell(r, c, grid[0][0]));
  } else {
    const startR = Math.min(S.sel.r, S.sel.r2);
    const startC = Math.min(S.sel.c, S.sel.c2);
    grid.forEach((cells, i) => {
      cells.forEach((value, j) => {
        const r = startR + i;
        const c = startC + j;
        if (r < S.view.length && c < COLS.length) changes.push(setCell(r, c, value));
      });
    });
  }
  const n = commit(changes);
  if (!n) toast('Nothing was pasted. Only the yellow columns (Zone, Phase, Specs) can be changed.');
}

// ------------------------------------------------------------ keyboard

function onKey(e) {
  if (S.editing) return;
  if (!S.view.length) return;      // nothing to move around in (an empty filter result)
  const mod = e.ctrlKey || e.metaKey;
  const pageRows = Math.max(1, Math.floor((gridEl.clientHeight - HEAD_H - FILTER_H) / ROW_H) - 1);
  switch (e.key) {
    case 'ArrowDown': moveBy(1, 0, e.shiftKey); break;
    case 'ArrowUp': moveBy(-1, 0, e.shiftKey); break;
    case 'ArrowLeft': moveBy(0, -1, e.shiftKey); break;
    case 'ArrowRight': moveBy(0, 1, e.shiftKey); break;
    case 'PageDown': moveBy(pageRows, 0, e.shiftKey); break;
    case 'PageUp': moveBy(-pageRows, 0, e.shiftKey); break;
    case 'Tab': moveBy(0, e.shiftKey ? -1 : 1, false); break;
    case 'Home': if (mod) { S.sel.r = 0; S.sel.r2 = 0; gridEl.scrollTop = 0; paint(); } else moveBy(0, -COLS.length, false); break;
    case 'End': moveBy(0, COLS.length, false); break;
    case 'Enter':
      if (isEditable(S.view[S.sel.r], COLS[S.sel.c])) startEdit(); else moveBy(1, 0, false);
      break;
    case 'F2': startEdit(); break;
    case 'Delete':
    case 'Backspace': resetCells(); break;
    case 'Escape': S.sel.r2 = S.sel.r; S.sel.c2 = S.sel.c; paint(); break;
    default:
      if (mod && e.key.toLowerCase() === 'c') { clip.value = toTsv(); clip.select(); return; }
      if (mod && e.key.toLowerCase() === 'x') { clip.value = toTsv(); clip.select(); setTimeout(resetCells, 0); return; }
      if (mod && e.key.toLowerCase() === 'v') return;                       // the paste event does the work
      if (mod && e.key.toLowerCase() === 'z') { e.shiftKey ? redo() : undo(); break; }
      if (mod && e.key.toLowerCase() === 'y') { redo(); break; }
      if (mod && e.key.toLowerCase() === 'd') { fillDown(); break; }
      if (mod && e.key.toLowerCase() === 'a') { S.sel.r = 0; S.sel.r2 = S.view.length - 1; S.sel.c = 1; S.sel.c2 = COLS.length - 1; paint(); break; }
      if (!mod && !e.altKey && e.key.length === 1) { startEdit(e.key); break; }
      return;
  }
  e.preventDefault();
}

function onEditorKey(e) {
  if (e.key === 'Enter') {
    e.preventDefault();
    endEdit(true);
    moveBy(e.shiftKey ? -1 : 1, 0, false);
  } else if (e.key === 'Tab') {
    e.preventDefault();
    endEdit(true);
    moveBy(0, e.shiftKey ? -1 : 1, false);
  } else if (e.key === 'Escape') {
    e.preventDefault();
    endEdit(false);
  }
}

// ------------------------------------------------------------------ mouse

function cellAt(target) {
  const el = target && target.closest ? target.closest('.g-cell') : null;
  if (!el) return null;
  return { r: Number(el.dataset.r), c: Number(el.dataset.c) };
}

function onMouseDown(e) {
  if (e.button !== 0) return;
  const at = cellAt(e.target);
  if (!at) return;
  if (S.editing) endEdit(true);
  if (e.shiftKey) {
    S.sel.r2 = at.r;
    S.sel.c2 = Math.max(1, at.c);
  } else {
    S.sel = { r: at.r, c: Math.max(1, at.c), r2: at.r, c2: Math.max(1, at.c) };
  }
  S.dragging = true;
  paint();
  e.preventDefault();      // keep the click from moving focus away from the keyboard catcher
  clip.focus();
}

function onMouseMove(e) {
  if (!S.dragging) return;
  const at = cellAt(document.elementFromPoint(e.clientX, e.clientY));
  if (!at || (at.r === S.sel.r2 && at.c === S.sel.c2)) return;
  S.sel.r2 = at.r;
  S.sel.c2 = Math.max(1, at.c);
  paint();
}

function onDoubleClick(e) {
  const at = cellAt(e.target);
  if (!at) return;
  S.sel = { r: at.r, c: at.c, r2: at.r, c2: at.c };
  startEdit();
}

// ------------------------------------------------------- check and save

function payload() {
  const rows = S.rows.filter(isDirty).map((r, i) => ({
    row: i + 1, name: r.name, version: String(r.version), zone: r.cur.zone, phase: r.cur.phase, specs: r.cur.specs,
  }));
  return { columns: EDIT_KEYS, rows, skipErrors: false, source: 'Bulk edit in the data grid' };
}

function annotate(report) {
  const byName = new Map(report.rows.map((r) => [(r.name || '').toLowerCase(), r]));
  for (const row of S.rows) {
    const res = byName.get(row.name.toLowerCase());
    row.check = res && res.outcome === 'ERROR' ? res : null;
  }
}

function showReport(title, report) {
  const dialog = $('report-dialog');
  clear(dialog);
  dialog.append(h('div', { class: 'modal-form' },
    h('div', { class: 'modal-head' }, h('h2', {}, title),
      h('button', { class: 'btn btn-small', type: 'button', onClick: () => dialog.close() }, 'Close')),
    h('div', { class: 'modal-body' }, renderReport(report))));
  dialog.showModal();
}

async function run(kind) {
  if (S.busy) return;
  S.busy = true;
  updateSaveBar();
  try {
    const report = await api.post(kind === 'save' ? '/grid/items/save' : '/grid/items/check', payload());
    annotate(report);
    if (kind === 'save' && report.applied) {
      toast(`Saved ${report.changed} item${report.changed === 1 ? '' : 's'}.`);
      await load();
      refresh();
    } else if (kind === 'save') {
      toast('Nothing was saved. Check the problems listed.', 'error');
      showReport('Nothing was saved', report);
      paint();
    } else {
      showReport(report.errors ? 'Problems found' : 'Check passed', report);
      paint();
    }
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    S.busy = false;
    updateSaveBar();
  }
}

function discard() {
  const n = S.rows.filter(isDirty).length;
  if (!n) return;
  if (!window.confirm(`Discard the changes to ${n} item${n === 1 ? '' : 's'}?`)) return;
  for (const row of S.rows) {
    row.cur = { ...row.orig };
    row.check = null;
  }
  S.undo = [];
  S.redo = [];
  refresh();
}

// ------------------------------------------------------------------- boot

async function boot() {
  const me = await ready;
  S.canEdit = !!me.canEdit;
  gridEl = $('grid');
  rowsEl = $('g-rows');
  editor = $('g-editor');
  clip = $('g-clip');

  $('btn-import').hidden = !S.canEdit;
  $('ro-note').hidden = S.canEdit;

  buildHeader();
  try {
    await load();
  } catch (err) {
    toast(err.message, 'error');
    gridEl.append(h('p', { class: 'empty', style: 'padding:20px' }, 'The items could not be loaded. Reload the page to try again.'));
    return;
  }

  gridEl.addEventListener('scroll', () => requestAnimationFrame(paint));
  window.addEventListener('resize', paint);
  rowsEl.addEventListener('mousedown', onMouseDown);
  rowsEl.addEventListener('dblclick', onDoubleClick);
  window.addEventListener('mousemove', onMouseMove);
  window.addEventListener('mouseup', () => { S.dragging = false; });
  gridEl.addEventListener('focus', () => clip.focus());
  gridEl.tabIndex = 0;
  clip.addEventListener('keydown', onKey);
  clip.addEventListener('keyup', () => { clip.value = ''; });
  clip.addEventListener('paste', (e) => {
    e.preventDefault();
    pasteText(e.clipboardData ? e.clipboardData.getData('text/plain') : '');
  });
  editor.addEventListener('keydown', onEditorKey);
  editor.addEventListener('blur', () => endEdit(true));

  $('q').addEventListener('input', (e) => { S.q = e.target.value; refresh(); });
  $('f-hull').addEventListener('change', (e) => { S.hull = e.target.value; refresh(); });
  $('f-status').addEventListener('change', (e) => { S.status = e.target.value; refresh(); });
  $('f-changed').addEventListener('change', (e) => { S.changedOnly = e.target.checked; refresh(); });
  $('btn-check').addEventListener('click', () => run('check'));
  $('btn-save').addEventListener('click', () => run('save'));
  $('btn-discard').addEventListener('click', discard);
  window.addEventListener('beforeunload', (e) => {
    if (S.rows.some(isDirty)) { e.preventDefault(); e.returnValue = ''; }
  });

  refresh();
  clip.focus();
}

boot();
