// Draws the result of a bulk check / import: counts, notes, and a table of changes and problems.
// Used by the Data page (in a dialog) and the Import page. Built with createElement only.

import { h, clear } from './util.js';

const FIELD_LABEL = { zone: 'Zone', phase: 'Phase', specs: 'Specs', status: 'Status' };
const MAX_ROWS_DRAWN = 600;

function tile(n, label, kind) {
  return h('div', { class: `rtile ${kind || ''}` }, h('b', {}, String(n)), label);
}

function changeLine(c) {
  return h('span', { class: 'change' },
    `${FIELD_LABEL[c.field] || c.field}: `,
    c.from ? h('span', { class: 'from' }, c.from) : h('span', { class: 'from' }, '(none)'),
    h('span', { class: 'arrow' }, ' → '),
    h('strong', {}, c.to || '(none)'));
}

/** Returns an element showing the report. {@code report} is a BulkReport from the server. */
export function renderReport(report) {
  const root = h('div', { class: 'report' });

  root.append(h('div', { class: 'report-tiles' },
    tile(report.total, 'rows read'),
    tile(report.changed, report.applied ? 'changed' : 'would change', report.changed ? 'good' : ''),
    tile(report.unchanged, 'already as wanted'),
    tile(report.errors, report.errors === 1 ? 'problem' : 'problems', report.errors ? 'bad' : '')));

  if (report.notes && report.notes.length) {
    root.append(h('ul', { class: 'notes' }, ...report.notes.map((n) => h('li', {}, n))));
  }

  const interesting = report.rows.filter((r) => r.outcome !== 'UNCHANGED');
  if (!interesting.length) {
    root.append(h('p', { class: 'empty' }, 'No row needs a change.'));
    return root;
  }

  let filter = report.errors ? 'ERROR' : 'ALL';
  const tabs = h('div', { class: 'tabs', role: 'group', 'aria-label': 'Show' });
  const tableHost = h('div', { class: 'report-table-wrap' });

  function draw() {
    const rows = interesting.filter((r) => filter === 'ALL' || r.outcome === filter);
    clear(tableHost);
    const body = h('tbody', {});
    for (const r of rows.slice(0, MAX_ROWS_DRAWN)) {
      body.append(h('tr', {},
        h('td', { class: 'num' }, r.row == null ? '' : String(r.row)),
        h('td', {}, r.name || ''),
        h('td', {}, r.outcome === 'ERROR'
          ? h('span', { class: 'pill pill-bad' }, 'Problem')
          : h('span', { class: 'pill pill-ok' }, report.applied ? 'Saved' : 'Will change')),
        h('td', {}, r.outcome === 'ERROR'
          ? h('span', { class: 'err-text' }, r.message)
          : (r.changes || []).map(changeLine))));
    }
    tableHost.append(h('table', { class: 'tbl' },
      h('thead', {}, h('tr', {}, h('th', {}, 'Row'), h('th', {}, 'Item'), h('th', {}, 'Result'), h('th', {}, 'Details'))),
      body));
    if (rows.length > MAX_ROWS_DRAWN) {
      tableHost.append(h('p', { class: 'sub', style: 'padding:8px 10px' },
        `Showing the first ${MAX_ROWS_DRAWN} of ${rows.length} rows.`));
    }
    for (const b of tabs.querySelectorAll('button')) {
      b.setAttribute('aria-pressed', String(b.dataset.filter === filter));
    }
  }

  const options = [['ALL', `All (${interesting.length})`]];
  if (report.changed) options.push(['CHANGE', `Changes (${report.changed})`]);
  if (report.errors) options.push(['ERROR', `Problems (${report.errors})`]);
  for (const [key, label] of options) {
    tabs.append(h('button', { class: 'tab', type: 'button', dataset: { filter: key }, onClick: () => { filter = key; draw(); } }, label));
  }
  root.append(tabs, tableHost);
  draw();
  return root;
}
