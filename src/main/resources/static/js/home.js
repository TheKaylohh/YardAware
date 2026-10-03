// Home page: welcome, shortcuts and the live numbers from /api/summary.

import { ready, roleLabel, isAdmin } from './nav.js';
import { api } from './api.js';
import { h, clear, timeAgo, toast } from './util.js';

const $ = (id) => document.getElementById(id);
const nf = new Intl.NumberFormat();

const EVENT_LABEL = {
  CREATED: 'Planned', PLACED: 'Placed on the yard', MOVED: 'Moved', PHASE_CHANGED: 'Phase changed',
  ASSEMBLED: 'Assembled', CONSUMED: 'Joined into parent', EDITED: 'Edited',
};

function greeting(me) {
  const first = (me.name || me.username || '').split(/\s+/)[0];
  return first ? `Welcome, ${first}` : 'Welcome';
}

function shortcuts(me) {
  const cards = [
    { href: '/map.html', title: 'Map', text: 'See every item on the yard map. Search, pin, and in Edit mode drag items between zones.', go: 'Open the map' },
    { href: '/data.html', title: 'Data', text: 'All items in a spreadsheet. Edit zones, phases and specs in bulk, then save in one go.', go: 'Open the data sheet' },
  ];
  if (me.canEdit) {
    cards.push({ href: '/import.html', title: 'Import', text: 'Upload an Excel file, review every change and problem, then apply it.', go: 'Import a sheet' });
  }
  if (isAdmin(me)) {
    cards.push({ href: '/admin.html', title: 'Admin', text: 'Decide who can view, edit or administer. Add ships. See what administrators did.', go: 'Open admin' });
  }
  const host = $('shortcuts');
  clear(host);
  for (const c of cards) {
    host.append(h('a', { class: 'card', href: c.href },
      h('span', { class: 'card-title' }, c.title),
      h('span', { class: 'card-text' }, c.text),
      h('span', { class: 'card-go' }, `${c.go} →`)));
  }
}

function stat(n, label) {
  return h('div', { class: 'stat' }, h('div', { class: 'stat-n' }, nf.format(n)), h('div', { class: 'stat-l' }, label));
}

function renderStats(s) {
  const host = $('stats');
  clear(host);
  host.append(stat(s.hulls, s.hulls === 1 ? 'ship' : 'ships'), stat(s.items, 'tracked items'),
    stat(s.active, 'on the yard now'), stat(s.planned, 'still planned'), stat(s.consumed, 'joined into a parent'));
  if (s.hulls === 0) {
    const banner = $('empty');
    banner.hidden = false;
    clear(banner);
    banner.append('No ships have been added yet. ');
    banner.append(window.__isAdmin
      ? h('a', { href: '/admin.html' }, 'Add the first ship in Admin.')
      : 'Ask an administrator to add the first ship.');
  }
}

function renderShips(list) {
  const host = $('ships');
  clear(host);
  if (!list.length) {
    host.append(h('p', { class: 'empty' }, 'No ships yet.'));
    return;
  }
  for (const s of list) {
    const total = s.planned + s.active + s.consumed;
    const pct = (n) => (total ? `${(n / total) * 100}%` : '0%');
    const built = total ? Math.round(((s.active + s.consumed) / total) * 100) : 0;
    host.append(h('div', { class: 'ship' },
      h('div', { class: 'ship-head' },
        h('span', { class: 'swatch', style: `--c:${s.color}` }),
        h('span', { class: 'ship-code' }, s.code),
        h('span', { class: 'ship-name' }, s.name),
        h('span', { class: 'ship-pct', title: 'Share of items that have reached the yard' }, `${built}% started`)),
      h('div', { class: 'stack', role: 'img', 'aria-label': `${s.code}: ${s.planned} planned, ${s.active} on the yard, ${s.consumed} joined` },
        h('span', { class: 'seg-consumed', style: `width:${pct(s.consumed)}` }),
        h('span', { class: 'seg-active', style: `width:${pct(s.active)};--c:${s.color}` }),
        h('span', { class: 'seg-planned', style: `width:${pct(s.planned)}` })),
      h('div', { class: 'stack-legend' },
        h('span', {}, h('i', { class: 'key seg-consumed' }), `${nf.format(s.consumed)} joined`),
        h('span', {}, h('i', { class: 'key', style: `background:${s.color}` }), `${nf.format(s.active)} on the yard`),
        h('span', {}, h('i', { class: 'key seg-planned' }), `${nf.format(s.planned)} planned`))));
  }
}

function renderBars(host, rows, labelOf, countOf, emptyText) {
  clear(host);
  const max = Math.max(1, ...rows.map(countOf));
  if (!rows.length || rows.every((r) => countOf(r) === 0)) {
    host.append(h('li', { class: 'empty' }, emptyText));
    return;
  }
  for (const r of rows) {
    const n = countOf(r);
    host.append(h('li', {},
      h('span', { class: 'bar-label', title: labelOf(r) }, labelOf(r)),
      h('span', { class: 'bar-track' }, h('i', { class: 'bar-fill', style: `width:${(n / max) * 100}%` })),
      h('span', { class: 'bar-n' }, nf.format(n))));
  }
}

function renderRecent(list) {
  const host = $('recent');
  clear(host);
  if (!list.length) {
    host.append(h('li', { class: 'empty' }, 'Nothing has happened yet.'));
    return;
  }
  for (const a of list) {
    const where = a.toZone ? ` → ${a.toZone}` : '';
    host.append(h('li', {},
      h('div', { class: 'feed-top' },
        h('a', { class: 'feed-what', href: `/map.html?q=${encodeURIComponent(a.item)}` }, a.item),
        h('span', { class: 'chip' }, EVENT_LABEL[a.type] || a.type),
        where ? h('span', {}, where) : false),
      h('div', { class: 'feed-meta' }, `${a.actor || 'unknown'} · ${timeAgo(a.timestamp)}`
        + (a.note ? ` · ${a.note}` : ''))));
  }
}

async function boot() {
  const me = await ready;
  window.__isAdmin = isAdmin(me);
  $('hello').textContent = greeting(me);
  if (me.authenticated) {
    $('hello-role').textContent = roleLabel(me);
    $('hello-role').hidden = false;
  }
  shortcuts(me);

  try {
    const s = await api.get('/summary');
    renderStats(s);
    renderShips(s.hullProgress);
    renderBars($('phases'), s.activeByPhase, (p) => `${p.number}. ${p.name}`, (p) => p.count, 'Nothing is on the yard yet.');
    renderBars($('zones'), s.zoneLoad.slice(0, 8), (z) => z.name, (z) => z.count, 'No zone has anything in it yet.');
    renderRecent(s.recent);
  } catch (err) {
    toast(err.message, 'error');
    $('stats').append(h('p', { class: 'empty' }, 'The numbers could not be loaded. Try reloading the page.'));
  }
}

boot();
