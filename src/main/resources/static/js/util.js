// Small DOM helpers. Everything is built with textContent/createElement, never innerHTML,
// so item names and notes can't inject markup.

const SVG_NS = 'http://www.w3.org/2000/svg';

function append(node, kids) {
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue;
    node.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
}

function applyProps(node, props) {
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.setAttribute('class', value);
    else if (key === 'value') node.value = value;
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2).toLowerCase(), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
}

/** HTML element builder: h('button', { class: 'x', onClick: fn }, 'Label') */
export function h(tag, props, ...kids) {
  const node = document.createElement(tag);
  applyProps(node, props);
  append(node, kids);
  return node;
}

/** SVG element builder. */
export function s(tag, props, ...kids) {
  const node = document.createElementNS(SVG_NS, tag);
  applyProps(node, props);
  append(node, kids);
  return node;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

export function formatWhen(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

export function timeAgo(iso) {
  if (!iso) return '';
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} hr ago`;
  const days = Math.round(hours / 24);
  if (days < 60) return `${days} day${days === 1 ? '' : 's'} ago`;
  const months = Math.round(days / 30);
  return `${months} months ago`;
}

export function toast(message, kind = 'info') {
  const host = document.getElementById('toasts');
  if (!host) return;
  const node = h('div', { class: `toast toast-${kind}`, role: kind === 'error' ? 'alert' : 'status' }, message);
  host.append(node);
  setTimeout(() => node.remove(), kind === 'error' ? 7000 : 3500);
}

/** "Weight (t): 92.5" lines -> { "Weight (t)": 92.5 }. Throws Error with a readable message. */
export function parseSpecs(text) {
  const specs = {};
  const lines = (text || '').split('\n').map((l) => l.trim()).filter(Boolean);
  for (const line of lines) {
    const idx = line.indexOf(':');
    if (idx < 1 || idx === line.length - 1) {
      throw new Error(`Each spec needs a name and a value, like "Weight (t): 92.5". Check: ${line}`);
    }
    const key = line.slice(0, idx).trim();
    const raw = line.slice(idx + 1).trim();
    specs[key] = /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : raw;
  }
  return specs;
}

export function specsToText(specs) {
  return Object.entries(specs || {})
    .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : value}`)
    .join('\n');
}

/** Short code drawn on each map tile. */
export const TYPE_CODES = {
  SUB_ASSEMBLY: 'SA',
  SECTION: 'SC',
  BLOCK: 'BK',
  GRAND_BLOCK: 'GB',
  UNIT: 'UN',
  PIPE_OUTFITTING: 'PO',
  TOOL: 'TL',
};

export const NEUTRAL_OUTLINE = '#7C8B92';
