// Admin page: people and access, ships, the admin activity log and basic system facts.
// The server enforces everything (ADMIN only, no self-changes); this page only makes it comfortable.

import { ready, isAdmin } from './nav.js';
import { api } from './api.js';
import { h, clear, formatWhen, timeAgo, toast } from './util.js';

const $ = (id) => document.getElementById(id);

const ROLE_OPTIONS = [['', 'No access'], ['VIEWER', 'Viewer'], ['EDITOR', 'Editor'], ['ADMIN', 'Admin']];
const ROLE_NAME = { VIEWER: 'Viewer', EDITOR: 'Editor', ADMIN: 'Admin' };
const ACTION_TEXT = {
  ROLE_CHANGED: 'Changed access of',
  USER_ADDED: 'Added',
  USER_REMOVED: 'Removed',
  USER_DISABLED: 'Disabled',
  USER_ENABLED: 'Enabled',
  HULL_CREATED: 'Added ship',
};

let me = null;
let people = [];
let busy = false;

function same(a, b) { return !!a && !!b && a.toLowerCase() === b.toLowerCase(); }

function isMe(u) {
  return same(u.username, me.username) || same(u.email, me.email);
}

function showError(id, message) {
  const box = $(id);
  box.textContent = message || '';
  box.hidden = !message;
}

// ------------------------------------------------------------------ people

function nameCell(u) {
  const main = u.displayName || u.username || u.email;
  const subs = [u.username && u.username !== main ? u.username : null, u.email && u.email !== main ? u.email : null]
    .filter(Boolean).join(' · ');
  return h('td', {},
    h('div', {}, h('strong', {}, main), isMe(u) ? h('span', { class: 'chip chip-you' }, 'you') : false),
    subs ? h('div', { class: 'sub' }, subs) : false);
}

function accessCell(u, locked) {
  const select = h('select', { 'aria-label': `Access for ${u.displayName || u.username || u.email}`, disabled: locked }, ...ROLE_OPTIONS.map(
    ([value, label]) => h('option', { value, selected: (u.role || '') === value }, label)));
  select.addEventListener('change', () => changeRole(u, select));
  const fromConfig = (u.configRoles || []).map((r) => ROLE_NAME[r] || r);
  return h('td', {}, select,
    fromConfig.length
      ? h('div', { class: 'sub', title: 'Set on the server; cannot be removed here' }, `Also from configuration: ${fromConfig.join(', ')}`)
      : false);
}

function statusCell(u) {
  if (!u.enabled) return h('td', {}, h('span', { class: 'pill pill-bad', title: 'Disabled people cannot use the app' }, 'Disabled'));
  if (!u.role && !(u.configRoles || []).length) {
    return h('td', {}, h('span', { class: 'pill pill-warn', title: 'Can sign in but sees nothing until given access' }, 'No access'));
  }
  if (u.pending) return h('td', {}, h('span', { class: 'pill pill-warn', title: 'Has access as soon as they sign in' }, 'Not signed in yet'));
  return h('td', {}, h('span', { class: 'pill pill-ok' }, 'Active'));
}

function renderPeople() {
  const needle = $('people-filter').value.trim().toLowerCase();
  $('people-filter').hidden = people.length < 8;
  const shown = people.filter((u) => !needle
    || [u.displayName, u.username, u.email].some((v) => v && v.toLowerCase().includes(needle)));
  const body = $('people-body');
  clear(body);
  if (!shown.length) {
    body.append(h('tr', {}, h('td', { colspan: '5', class: 'empty' },
      people.length ? 'Nobody matches.' : 'Nobody has signed in yet. Add people below, or ask them to sign in.')));
    return;
  }
  for (const u of shown) {
    const locked = isMe(u);
    const lockTitle = locked ? 'You can\'t change your own access. Ask another administrator.' : '';
    body.append(h('tr', {},
      nameCell(u),
      accessCell(u, locked),
      statusCell(u),
      h('td', { title: u.lastLoginAt ? formatWhen(u.lastLoginAt) : '' }, u.lastLoginAt ? timeAgo(u.lastLoginAt) : h('span', { class: 'sub' }, 'never')),
      h('td', { class: 'actions' },
        h('button', { class: 'btn btn-small', type: 'button', disabled: locked, title: lockTitle, onClick: () => toggle(u) },
          u.enabled ? 'Disable' : 'Enable'),
        ' ',
        h('button', { class: 'btn btn-small btn-danger', type: 'button', disabled: locked, title: lockTitle, onClick: () => remove(u) },
          'Remove'))));
  }
}

async function guarded(errorBox, work) {
  if (busy) return false;
  busy = true;
  showError(errorBox, '');
  try {
    await work();
    return true;
  } catch (err) {
    showError(errorBox, err.message);
    return false;
  } finally {
    busy = false;
  }
}

function replace(updated) {
  const i = people.findIndex((p) => p.id === updated.id);
  if (i >= 0) people[i] = updated; else people.push(updated);
}

async function changeRole(u, select) {
  const previous = u.role || '';
  const ok = await guarded('people-error', async () => {
    replace(await api.put(`/admin/users/${u.id}`, { role: select.value || 'NONE' }));
    toast('Access updated');
  });
  if (!ok) select.value = previous;
  else { renderPeople(); loadLog(); }
}

async function toggle(u) {
  const ok = await guarded('people-error', async () => {
    replace(await api.put(`/admin/users/${u.id}`, { enabled: !u.enabled }));
    toast(u.enabled ? 'Disabled' : 'Enabled');
  });
  if (ok) { renderPeople(); loadLog(); }
}

async function remove(u) {
  const name = u.displayName || u.username || u.email;
  if (!window.confirm(`Remove ${name} from the list?\n\nThey lose any access you gave them here. If they sign in again they start with no access.`)) return;
  const ok = await guarded('people-error', async () => {
    await api.del(`/admin/users/${u.id}`);
    people = people.filter((p) => p.id !== u.id);
    toast('Removed');
  });
  if (ok) { renderPeople(); loadLog(); }
}

async function addPerson(event) {
  event.preventDefault();
  const identifier = $('add-id').value.trim();
  if (!identifier) return;
  $('add-btn').disabled = true;
  const ok = await guarded('people-error', async () => {
    replace(await api.post('/admin/users', { identifier, role: $('add-role').value }));
    $('add-id').value = '';
    toast(`Added ${identifier}`);
  });
  $('add-btn').disabled = false;
  if (ok) { renderPeople(); loadLog(); }
}

async function loadPeople() {
  const data = await api.get('/admin/users');
  people = data.users;
  const note = $('mode-note');
  if (!data.signInManaged) {
    note.hidden = false;
    note.textContent = `This server is using "${data.authMode}" sign-in. Roles come from its configuration, so the access `
      + 'you set here only applies when people sign in through your identity provider (OAuth2).';
  }
  renderPeople();
}

// ------------------------------------------------------------------ ships

async function loadShips() {
  const summary = await api.get('/summary');
  const body = $('ships-body');
  clear(body);
  if (!summary.hullProgress.length) {
    body.append(h('tr', {}, h('td', { colspan: '3', class: 'empty' }, 'No ships yet. Add the first one below.')));
    return;
  }
  for (const s of summary.hullProgress) {
    body.append(h('tr', {},
      h('td', {}, h('span', { class: 'swatch', style: `--c:${s.color}` }), ' ', h('strong', {}, s.code)),
      h('td', {}, s.name),
      h('td', { class: 'num' }, String(s.planned + s.active + s.consumed))));
  }
}

async function addShip(event) {
  event.preventDefault();
  const code = $('ship-code').value.trim();
  const name = $('ship-name').value.trim();
  const color = $('ship-color').value;
  $('ship-btn').disabled = true;
  const ok = await guarded('ship-error', async () => {
    await api.post('/hulls', { code, name, color });
    $('ship-code').value = '';
    $('ship-name').value = '';
    toast(`Ship ${code.toUpperCase()} added`);
  });
  $('ship-btn').disabled = false;
  if (ok) { await loadShips().catch((e) => toast(e.message, 'error')); loadLog(); }
}

// ------------------------------------------------------------------ log, system

function eventText(e) {
  const verb = ACTION_TEXT[e.action] || e.action;
  return `${verb} ${e.target || ''}`.trim();
}

async function loadLog() {
  try {
    const events = await api.get('/admin/events');
    const host = $('log');
    clear(host);
    if (!events.length) {
      host.append(h('li', { class: 'empty' }, 'Nothing yet. Changes made on this page are listed here and cannot be edited.'));
      return;
    }
    for (const e of events.slice(0, 30)) {
      host.append(h('li', {},
        h('div', { class: 'feed-top' }, h('span', { class: 'feed-what' }, eventText(e)), e.detail ? h('span', { class: 'chip' }, e.detail) : false),
        h('div', { class: 'feed-meta', title: formatWhen(e.timestamp) }, `${e.actor || 'unknown'} · ${timeAgo(e.timestamp)}`)));
    }
  } catch (err) {
    toast(err.message, 'error');
  }
}

function uptimeText(seconds) {
  if (seconds < 90) return `${seconds} seconds`;
  const m = Math.round(seconds / 60);
  if (m < 90) return `${m} minutes`;
  const hrs = Math.round(m / 60);
  if (hrs < 48) return `${hrs} hours`;
  return `${Math.round(hrs / 24)} days`;
}

async function loadSystem() {
  try {
    const info = await api.get('/admin/system');
    const host = $('system');
    clear(host);
    const rows = [
      ['Sign-in', info.authMode],
      ['Profiles', info.profiles.length ? info.profiles.join(', ') : 'default'],
      ['Java', info.java],
      ['Running for', uptimeText(info.uptimeSeconds)],
    ];
    for (const [k, v] of rows) host.append(h('dt', {}, k), h('dd', {}, v));
  } catch (err) {
    toast(err.message, 'error');
  }
}

async function boot() {
  me = await ready;
  if (!isAdmin(me)) {
    $('denied').hidden = false;
    return;
  }
  $('content').hidden = false;
  $('add-person').addEventListener('submit', addPerson);
  $('add-ship').addEventListener('submit', addShip);
  $('people-filter').addEventListener('input', renderPeople);
  const results = await Promise.allSettled([loadPeople(), loadShips(), loadLog(), loadSystem()]);
  for (const r of results) {
    if (r.status === 'rejected') toast(r.reason.message, 'error');
  }
}

boot();
