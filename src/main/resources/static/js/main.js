// Boots the app, loads data, and connects the top bar, the map, the panel and the forms.

import { state, on, emit } from './state.js';
import { api, csrfToken, SIGN_IN_HOME } from './api.js';
import { h, toast } from './util.js';
import { initMap, renderZones, renderItems, applySearch, focusItem, syncPinned, syncPicks } from './map.js';
import { initPanel, showItem, hidePanel, scheduleHide, refreshPanel } from './panel.js';
import { openPlaceItem, openAssemble } from './forms.js';

const $ = (id) => document.getElementById(id);

async function boot() {
  initMap($('map-wrap'));
  initPanel($('panel'));
  wireTopBar();
  wireMapEvents();
  wireKeyboard();
  applyUser(await loadMe());
  try {
    const [meta, hulls, zones, items] = await Promise.all([
      api.get('/meta'), api.get('/hulls'), api.get('/zones'), api.get('/items'),
    ]);
    Object.assign(state, { meta, hulls, zones, items });
  } catch (err) {
    $('map-wrap').append(h('div', { class: 'fatal', role: 'alert' },
      h('p', {}, 'The yard data didn\'t load. Check that the server is running, then try again.'),
      h('p', { class: 'muted' }, err.message),
      h('button', { class: 'btn', type: 'button', onClick: () => window.location.reload() }, 'Try again')));
    return;
  }
  buildFilters();
  renderZones();
  renderItems();
  openFromLink();
}

/** /map.html?q=S041-BA-U01 (links from the home and data pages) searches for that item and jumps to it. */
function openFromLink() {
  const q = new URLSearchParams(window.location.search).get('q');
  if (!q) return;
  $('search').value = q;
  state.query = q;
  const hits = applySearch();
  if (hits.length) focusItem(hits[0]);
}

async function reload() {
  try {
    state.items = await api.get('/items');
    renderItems();
  } catch (err) {
    toast(err.message, 'error');
  }
}

// --------------------------------------------------------------- signed-in user

/** Who is signed in. If the call fails (older server, auth off) behave like the original app. */
async function loadMe() {
  try {
    return await api.get('/me');
  } catch (err) {
    return { authenticated: false, canEdit: true, roles: [], authMode: 'unknown' };
  }
}

function roleLabel(me) {
  if (me.roles.includes('ADMIN')) return 'Admin';
  return me.canEdit ? 'Editor' : 'Viewer';
}

/** Show who is signed in, and only offer Edit to people who can use it. The server enforces this either way. */
function applyUser(me) {
  state.user = me;
  if (me.authenticated && $('user')) {
    $('user-name').textContent = me.name || me.username;
    $('user-name').title = me.email || '';
    $('user-role').textContent = roleLabel(me);
    $('user').hidden = false;
  }
  document.querySelector('.mode').hidden = !me.canEdit;
}

async function signOut() {
  try {
    const token = csrfToken();
    await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: token ? { 'X-XSRF-TOKEN': token } : {} });
  } finally {
    window.location.assign(SIGN_IN_HOME);
  }
}

// ---------------------------------------------------------------- top bar

function buildFilters() {
  const host = $('filters');
  const chips = state.hulls.map((hl) => ({ key: hl.id, label: hl.code, color: hl.color }));
  for (const chip of chips) {
    const button = h('button', {
      class: 'hull-chip', type: 'button', 'aria-pressed': 'true',
      title: `Show or hide hull ${chip.label}`,
      onClick: () => {
        if (state.hiddenHulls.has(chip.key)) state.hiddenHulls.delete(chip.key);
        else state.hiddenHulls.add(chip.key);
        button.setAttribute('aria-pressed', String(!state.hiddenHulls.has(chip.key)));
        renderItems();
      },
    },
    h('span', { class: 'swatch', style: `--c:${chip.color}` }),
    chip.label);
    host.append(button);
  }
}

function wireTopBar() {
  document.querySelectorAll('[data-mode-btn]').forEach((button) => {
    button.addEventListener('click', () => setMode(button.getAttribute('data-mode-btn')));
  });

  let searchCursor = 0;
  const search = $('search');
  search.addEventListener('input', () => {
    state.query = search.value;
    searchCursor = 0;
    applySearch();
  });
  search.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const hits = applySearch();
    if (!hits.length) return;
    focusItem(hits[searchCursor % hits.length]);
    searchCursor += 1;
  });

  if ($('btn-signout')) $('btn-signout').addEventListener('click', signOut);
  $('btn-add').addEventListener('click', openPlaceItem);
  $('btn-assemble').addEventListener('click', () => (state.assembling ? endAssemble() : startAssemble()));
  $('assemble-cancel').addEventListener('click', endAssemble);
  $('assemble-go').addEventListener('click', () => openAssemble([...state.picked]));
}

function setMode(mode) {
  if (mode === 'edit' && state.user && !state.user.canEdit) return;
  state.mode = mode;
  document.body.dataset.mode = mode;
  document.querySelectorAll('[data-mode-btn]').forEach((button) => {
    button.setAttribute('aria-pressed', String(button.getAttribute('data-mode-btn') === mode));
  });
  $('edit-actions').hidden = mode !== 'edit';
  $('map-hint').hidden = mode !== 'edit';
  if (mode !== 'edit') endAssemble();
  emit('mode-changed', mode);
}

function startAssemble() {
  state.assembling = true;
  state.picked.clear();
  document.body.classList.add('assembling');
  $('assemble-bar').hidden = false;
  $('btn-assemble').setAttribute('aria-pressed', 'true');
  updateAssembleBar();
  syncPicks();
}

function endAssemble() {
  state.assembling = false;
  state.picked.clear();
  document.body.classList.remove('assembling');
  $('assemble-bar').hidden = true;
  $('btn-assemble').setAttribute('aria-pressed', 'false');
  syncPicks();
}

function updateAssembleBar() {
  const n = state.picked.size;
  $('assemble-count').textContent = n ? `${n} selected` : 'Select all pieces of one block or unit';
  $('assemble-go').disabled = n < 2;
}

// ------------------------------------------------------------- map events

function wireMapEvents() {
  on('item-hover', (id) => { if (!state.pinned) showItem(id); });
  on('item-leave', () => { if (!state.pinned) scheduleHide(); });
  on('background-click', () => { if (state.pinned) hidePanel(); });
  on('panel-state', syncPinned);

  on('item-click', (id) => {
    if (!state.assembling) {
      showItem(id, { pin: true });
      return;
    }
    if (state.picked.has(id)) state.picked.delete(id);
    else state.picked.add(id);
    syncPicks();
    updateAssembleBar();
  });

  on('item-move', async ({ item, zone }) => {
    try {
      await api.post(`/items/${item.id}/move`, { zoneId: zone.id });
      toast(`Moved ${item.name} to ${zone.name}`);
      await reload();
      if (state.panelItemId === item.id) refreshPanel();
    } catch (err) {
      toast(err.message, 'error');
      renderItems(); // snap the tile back
    }
  });

  on('items-mutated', async (info) => {
    await reload();
    if (info && info.endAssemble) endAssemble();
    if (info && info.open) {
      await showItem(info.id, { pin: true });
      focusItem(info.id);
    } else {
      refreshPanel();
    }
  });
}

function wireKeyboard() {
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || $('modal').open) return;
    if (state.assembling) endAssemble();
    else if (state.pinned) hidePanel();
  });
}

boot();
