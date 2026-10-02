// The right-hand overlay: hover shows a preview, clicking an item pins it so the tabs and forms can be used.
// Tabs: Details (editable in edit mode), Children (component tree), Activity (history), Hull.

import { state, on, emit } from './state.js';
import { api } from './api.js';
import { h, clear, formatWhen, timeAgo, toast, parseSpecs, specsToText, NEUTRAL_OUTLINE } from './util.js';

const HIDE_DELAY_MS = 350;

let root;
let header;
let tabBar;
let body;
let current = null; // { id, item, tree, activity, tab }
let loadToken = 0;
let hideTimer = null;

export function initPanel(element) {
  root = element;
  header = h('div', { class: 'panel-head' });
  tabBar = h('div', { class: 'tabs', role: 'tablist' });
  body = h('div', { class: 'panel-body', role: 'tabpanel' });
  root.append(header, tabBar, body);

  root.addEventListener('pointerenter', cancelHide);
  root.addEventListener('pointerleave', scheduleHide);
  // Typing in a form pins the panel so it can't vanish mid-edit.
  root.addEventListener('focusin', () => {
    if (!state.pinned && current) {
      state.pinned = true;
      emit('panel-state');
      renderHeader();
    }
  });
  on('mode-changed', () => { if (current) renderBody(); });
}

// ------------------------------------------------------------- open / close

export function cancelHide() {
  clearTimeout(hideTimer);
  hideTimer = null;
}

export function scheduleHide() {
  cancelHide();
  hideTimer = setTimeout(() => { if (!state.pinned) hidePanel(); }, HIDE_DELAY_MS);
}

export function hidePanel() {
  cancelHide();
  loadToken += 1;
  state.pinned = false;
  state.panelItemId = null;
  current = null;
  root.classList.remove('open');
  root.setAttribute('aria-hidden', 'true');
  emit('panel-state');
}

function openPanel() {
  root.classList.add('open');
  root.setAttribute('aria-hidden', 'false');
}

/** Show an item in the panel. Pass { pin: true } when the user clicked it. */
export async function showItem(id, { pin = false } = {}) {
  cancelHide();
  const sameItem = current && current.id === id;
  if (pin) state.pinned = true;
  state.panelItemId = id;
  emit('panel-state');

  if (sameItem && current.tree) {
    openPanel();
    renderHeader();
    return;
  }

  const cached = state.items.find((i) => i.id === id) || null;
  current = { id, item: cached, tree: null, activity: null, tab: 'details' };
  const token = ++loadToken;
  openPanel();
  render();
  await load(id, token, cached);
}

/** Re-fetch the open item after an edit, move, or assembly, keeping the current tab. */
export async function refreshPanel() {
  if (!current) return;
  const { id } = current;
  const token = ++loadToken;
  await load(id, token, null);
}

async function load(id, token, cachedItem) {
  try {
    const [item, tree, activity] = await Promise.all([
      cachedItem ? Promise.resolve(cachedItem) : api.get(`/items/${id}`),
      api.get(`/items/${id}/tree`),
      api.get(`/items/${id}/activity`),
    ]);
    if (token !== loadToken || !current) return;
    current.item = item;
    current.tree = tree;
    current.activity = activity;
    render();
  } catch (err) {
    if (token !== loadToken || !current) return;
    body.replaceChildren(h('p', { class: 'msg msg-error', role: 'alert' }, err.message));
  }
}

// ------------------------------------------------------------------ render

function render() {
  renderHeader();
  renderTabs();
  renderBody();
}

function renderHeader() {
  clear(header);
  const item = current && current.item;
  if (!item) {
    header.append(h('p', { class: 'panel-loading' }, 'Loading'));
    return;
  }
  root.style.setProperty('--hull', item.hullColor || NEUTRAL_OUTLINE);
  header.append(
    h('div', { class: 'panel-head-row' },
      h('div', { class: 'panel-title' },
        h('h2', {}, item.name),
        h('p', { class: 'panel-sub' }, [item.typeLabel, item.tagDescription].filter(Boolean).join(', '))),
      h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close panel', onClick: hidePanel }, '✕')),
    h('p', { class: `pin-state${state.pinned ? ' is-pinned' : ''}` },
      state.pinned ? 'Pinned. Close with ✕ or Esc.' : 'Preview. Click the item on the map to keep this open.'),
  );
}

function tabDefs() {
  const item = current.item;
  return [
    { id: 'details', label: 'Details' },
    { id: 'children', label: 'Children', count: item ? item.childCount : null },
    { id: 'activity', label: 'Activity', count: current.activity ? current.activity.length : null },
    { id: 'hull', label: 'Hull' },
  ];
}

function renderTabs() {
  clear(tabBar);
  if (!current || !current.item) return;
  for (const tab of tabDefs()) {
    const active = tab.id === current.tab;
    tabBar.append(h('button', {
      class: `tab${active ? ' is-active' : ''}`,
      type: 'button',
      role: 'tab',
      'aria-selected': active ? 'true' : 'false',
      onClick: () => { current.tab = tab.id; renderTabs(); renderBody(); },
    }, tab.label, tab.count ? h('span', { class: 'tab-count' }, String(tab.count)) : null));
  }
}

function renderBody() {
  clear(body);
  if (!current || !current.item) return;
  const item = current.item;
  switch (current.tab) {
    case 'children': body.append(childrenTab()); break;
    case 'activity': body.append(activityTab()); break;
    case 'hull': body.append(hullTab(item)); break;
    default: body.append(state.mode === 'edit' ? detailsEdit(item) : detailsView(item));
  }
}

// ----------------------------------------------------------------- details

function itemLink(id, name) {
  return h('button', { class: 'link', type: 'button', onClick: () => showItem(id, { pin: true }) }, name);
}

function hullValue(item) {
  if (!item.hullCode) return 'Not assigned to a hull';
  const hull = state.hulls.find((x) => x.id === item.hullId);
  return [
    h('span', { class: 'swatch', style: `--c:${item.hullColor}` }),
    hull && hull.name !== item.hullCode ? `${item.hullCode}, ${hull.name}` : item.hullCode,
  ];
}

function detailsView(item) {
  const consumed = item.status === 'CONSUMED';
  const facts = [
    ['Hull', hullValue(item)],
    [consumed ? 'Last seen in' : 'Location', item.zoneName || 'Not placed'],
    consumed ? ['Status', ['Assembled into ', item.parentId ? itemLink(item.parentId, item.parentName) : 'a larger item']] : null,
    item.tag ? ['Area tag', `${item.tag}, ${item.tagDescription}`] : null,
    item.batch ? ['Quantity', `${item.quantity} ${item.unit || ''}`.trim()] : null,
    ['Added', formatWhen(item.createdAt)],
    ['Last changed', `${timeAgo(item.updatedAt)}`],
  ].filter(Boolean);

  const specs = Object.entries(item.specs || {});
  return h('div', { class: 'details' },
    h('dl', { class: 'facts' }, facts.map(([label, value]) => [h('dt', {}, label), h('dd', {}, value)])),
    h('h3', {}, 'Specs'),
    specs.length
      ? h('table', { class: 'specs' }, h('tbody', {}, specs.map(([key, value]) =>
        h('tr', {}, h('th', { scope: 'row' }, key), h('td', {}, typeof value === 'object' ? JSON.stringify(value) : String(value))))))
      : h('p', { class: 'muted' }, 'No specs recorded.'));
}

function field(label, control, hint) {
  return h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), control, hint ? h('span', { class: 'field-hint' }, hint) : null);
}

function detailsEdit(item) {
  const refs = {};
  refs.name = h('input', { type: 'text', value: item.name, maxlength: 120, required: true });
  refs.hull = h('select', {},
    h('option', { value: '' }, 'No hull'),
    state.hulls.map((hl) => h('option', { value: hl.id, selected: hl.id === item.hullId }, hl.code)));
  if (item.type === 'GRAND_BLOCK' || item.tag) {
    refs.tag = h('input', { type: 'text', value: item.tag || '', maxlength: 5, placeholder: 'EA500', required: item.type === 'GRAND_BLOCK' });
  }
  if (item.batch) {
    refs.quantity = h('input', { type: 'number', min: 1, step: 1, value: item.quantity ?? '', required: true });
    refs.unit = h('input', { type: 'text', value: item.unit || 'pcs', maxlength: 12 });
  }
  refs.specs = h('textarea', { rows: 6, spellcheck: 'false', value: specsToText(item.specs) });
  refs.note = h('input', { type: 'text', maxlength: 300, placeholder: 'Why the change (optional)' });
  const error = h('p', { class: 'msg msg-error', role: 'alert', hidden: true });

  const showError = (text) => { error.textContent = text; error.hidden = false; };
  const save = async (e) => {
    e.preventDefault();
    error.hidden = true;
    try {
      const payload = {
        name: refs.name.value,
        hullId: refs.hull.value ? Number(refs.hull.value) : null,
        tag: refs.tag ? refs.tag.value : item.tag,
        quantity: refs.quantity ? Number(refs.quantity.value) : null,
        unit: refs.unit ? refs.unit.value : null,
        specs: parseSpecs(refs.specs.value),
        note: refs.note.value,
      };
      await api.put(`/items/${item.id}`, payload);
      toast('Saved changes');
      emit('items-mutated', { id: item.id });
    } catch (err) {
      showError(err.message);
    }
  };

  const form = h('form', { class: 'form', onSubmit: save },
    field('Name', refs.name),
    field('Hull', refs.hull),
    refs.tag ? field('Area tag', refs.tag, 'Area code plus three digits, like EA500') : null,
    refs.quantity ? h('div', { class: 'field-row' }, field('Quantity', refs.quantity), field('Unit', refs.unit)) : null,
    field('Specs', refs.specs, 'One per line, like Weight (t): 92.5'),
    field('Note', refs.note),
    error,
    h('button', { class: 'btn btn-primary', type: 'submit' }, 'Save changes'));

  return h('div', { class: 'details' }, form, item.status === 'ACTIVE' ? moveForm(item) : null);
}

function moveForm(item) {
  const select = h('select', { required: true },
    h('option', { value: '' }, 'Choose a zone'),
    state.zones.filter((z) => z.id !== item.zoneId).map((z) => h('option', { value: z.id }, z.name)));
  const note = h('input', { type: 'text', maxlength: 300, placeholder: 'Reason or job (optional)' });
  const error = h('p', { class: 'msg msg-error', role: 'alert', hidden: true });

  const submit = async (e) => {
    e.preventDefault();
    error.hidden = true;
    try {
      const zone = state.zones.find((z) => z.id === Number(select.value));
      await api.post(`/items/${item.id}/move`, { zoneId: Number(select.value), note: note.value });
      toast(`Moved ${item.name} to ${zone ? zone.name : 'the new zone'}`);
      emit('items-mutated', { id: item.id });
    } catch (err) {
      error.textContent = err.message;
      error.hidden = false;
    }
  };
  return h('form', { class: 'form form-move', onSubmit: submit },
    h('h3', {}, 'Move to another zone'),
    field('Zone', select),
    field('Note', note),
    error,
    h('button', { class: 'btn', type: 'submit' }, 'Move item'));
}

// ---------------------------------------------------------------- children

function treeRow(node) {
  return h('span', { class: 'node-row' },
    itemLink(node.id, node.name),
    h('span', { class: 'chip' }, node.typeLabel),
    node.quantity != null ? h('span', { class: 'qty' }, `${node.quantity} ${node.unit || ''}`.trim()) : null);
}

function treeNode(node, depth) {
  if (!node.children.length) return h('li', { class: 'leaf' }, treeRow(node));
  return h('li', {},
    h('details', { open: depth < 1 },
      h('summary', {}, treeRow(node)),
      h('ul', { class: 'tree' }, node.children.map((child) => treeNode(child, depth + 1)))));
}

function childrenTab() {
  if (!current.tree) return h('p', { class: 'muted' }, 'Loading');
  const kids = current.tree.children;
  if (!kids.length) {
    return h('div', {},
      h('p', { class: 'muted' }, 'Nothing has been assembled into this item.'),
      state.mode === 'edit' ? h('p', { class: 'muted' }, 'To build an item from pieces, choose Assemble in the top bar.') : null);
  }
  const list = h('ul', { class: 'tree tree-root' }, kids.map((kid) => treeNode(kid, 0)));
  const setAll = (open) => list.querySelectorAll('details').forEach((d) => { d.open = open; });
  return h('div', {},
    h('div', { class: 'tree-tools' },
      h('button', { class: 'link', type: 'button', onClick: () => setAll(true) }, 'Expand all'),
      h('button', { class: 'link', type: 'button', onClick: () => setAll(false) }, 'Collapse all')),
    list);
}

// ---------------------------------------------------------------- activity

function describeActivity(a) {
  switch (a.type) {
    case 'MOVED': return `Moved from ${a.fromZone || 'unknown'} to ${a.toZone || 'unknown'}`;
    case 'CREATED': return a.toZone ? `Added in ${a.toZone}` : 'Added to the yard log';
    case 'ASSEMBLED': return a.toZone ? `Assembled in ${a.toZone}` : 'Assembled';
    case 'CONSUMED': return 'Joined into a larger item';
    case 'EDITED': return 'Details edited';
    default: return a.type;
  }
}

function activityTab() {
  if (!current.activity) return h('p', { class: 'muted' }, 'Loading');
  if (!current.activity.length) return h('p', { class: 'muted' }, 'No activity yet.');
  return h('ol', { class: 'timeline' }, current.activity.map((a) =>
    h('li', { class: `event event-${a.type.toLowerCase()}` },
      h('p', { class: 'event-title' }, describeActivity(a)),
      a.note && a.type !== 'CREATED' ? h('p', { class: 'event-note' }, a.note) : null,
      h('p', { class: 'event-meta', title: formatWhen(a.timestamp) }, `${a.actor || 'Unknown'}, ${timeAgo(a.timestamp)}`))));
}

// -------------------------------------------------------------------- hull

function tally(list, keyOf) {
  const counts = new Map();
  for (const entry of list) counts.set(keyOf(entry), (counts.get(keyOf(entry)) || 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function hullTab(item) {
  if (!item.hullId) {
    return h('div', {},
      h('p', { class: 'muted' }, 'This item isn\'t assigned to a hull, so its map tile has a dashed grey outline.'),
      state.mode === 'edit' ? h('p', { class: 'muted' }, 'Pick a hull on the Details tab to assign one.') : null);
  }
  const hull = state.hulls.find((x) => x.id === item.hullId);
  const onYard = state.items.filter((i) => i.hullId === item.hullId);
  const rows = (entries) => h('ul', { class: 'tally' }, entries.map(([label, n]) =>
    h('li', {}, h('span', {}, label), h('span', { class: 'tally-n' }, String(n)))));
  return h('div', { class: 'hull-tab' },
    h('p', { class: 'hull-card' },
      h('span', { class: 'swatch swatch-lg', style: `--c:${item.hullColor}` }),
      h('span', { class: 'hull-code' }, item.hullCode),
      hull ? h('span', { class: 'hull-name' }, hull.name) : null),
    h('p', {}, `${onYard.length} item${onYard.length === 1 ? '' : 's'} from this hull on the yard.`),
    h('h3', {}, 'By type'),
    rows(tally(onYard, (i) => i.typeLabel)),
    h('h3', {}, 'By zone'),
    rows(tally(onYard, (i) => i.zoneName)));
}
