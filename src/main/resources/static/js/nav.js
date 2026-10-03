// The top menu bar shared by every page. Builds itself from <body data-page="home|map|data|import|admin">,
// shows only the links the signed-in person can use (the server enforces the same rules), and exports the
// person's details so a page doesn't have to ask twice.

import { api, csrfToken, SIGN_IN_HOME } from './api.js';
import { h, s, toast } from './util.js';

const LINKS = [
  { key: 'home', href: '/', label: 'Home' },
  { key: 'map', href: '/map.html', label: 'Map' },
  { key: 'data', href: '/data.html', label: 'Data' },
  { key: 'import', href: '/import.html', label: 'Import', needs: 'edit' },
  { key: 'admin', href: '/admin.html', label: 'Admin', needs: 'admin' },
];

/** Who is signed in. If the call fails (older server, auth off) behave like the original app. */
async function loadMe() {
  try {
    return await api.get('/me');
  } catch (err) {
    return { authenticated: false, canEdit: true, roles: [], authMode: 'unknown' };
  }
}

export function roleLabel(me) {
  if (me.roles.includes('ADMIN')) return 'Admin';
  if (me.canEdit) return 'Editor';
  return me.roles.includes('VIEWER') ? 'Viewer' : 'No access';
}

export function isAdmin(me) {
  // With authentication off nobody has roles, and everybody may do everything (as in the original app).
  return !me.authenticated || me.roles.includes('ADMIN');
}

export async function signOut() {
  try {
    const token = csrfToken();
    await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: token ? { 'X-XSRF-TOKEN': token } : {} });
  } finally {
    window.location.assign(SIGN_IN_HOME);
  }
}

function allowed(link, me) {
  if (link.needs === 'edit') return me.canEdit;
  if (link.needs === 'admin') return isAdmin(me);
  return true;
}

function build(me, active) {
  const links = LINKS.filter((l) => allowed(l, me)).map((l) => h('a', {
    class: 'navlink', href: l.href, 'aria-current': l.key === active ? 'page' : false,
  }, l.label));

  const nav = h('nav', { class: 'topnav', 'aria-label': 'Main' },
    h('a', { class: 'nav-brand', href: '/', title: 'Home' },
      s('svg', { class: 'brand-mark', viewBox: '0 0 28 28', width: '26', height: '26', 'aria-hidden': 'true' },
        s('path', { d: 'M2 16h24l-4 8H6z', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.4', 'stroke-linejoin': 'round' }),
        s('path', { d: 'M7 16v-6h8v6M20 16V5h4', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.4', 'stroke-linejoin': 'round' })),
      h('span', { class: 'brand-name' }, 'Yard Tracker')),
    h('div', { class: 'nav-links' }, ...links),
    h('div', { class: 'spacer' }),
    me.authenticated
      ? h('div', { class: 'user', id: 'nav-user' },
        h('span', { class: 'user-name', title: me.email || '' }, me.name || me.username),
        h('span', { class: 'user-role' }, roleLabel(me)),
        h('button', { class: 'user-out', type: 'button', onClick: signOut }, 'Sign out'))
      : false);

  document.body.prepend(nav);
}

/** Resolves with the person once the bar is on the page. */
export const ready = (async () => {
  const me = await loadMe();
  build(me, document.body.dataset.page);
  return me;
})();

export { toast };
