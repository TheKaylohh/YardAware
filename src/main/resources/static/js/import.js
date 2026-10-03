// Import page: pick an .xlsx, see exactly what it would do, then apply. The same file is uploaded twice
// (first as a check, then to apply) so the server re-validates against the data as it is at apply time.

import { ready } from './nav.js';
import { api } from './api.js';
import { h, clear, toast } from './util.js';
import { renderReport } from './report.js';

const $ = (id) => document.getElementById(id);

let file = null;      // the File chosen by the person
let report = null;    // last BulkReport from the server
let busy = false;
let runId = 0;        // ignores a slow answer for a file that has since been replaced

function sizeText(bytes) {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function show(id, on) { $(id).hidden = !on; }

function setBusy(on, text) {
  busy = on;
  show('busy', on);
  if (on) $('busy').textContent = text;
  refreshApply();
}

function refreshApply() {
  const apply = $('btn-apply');
  const skip = $('skip').checked;
  const canApply = !!report && !report.applied && !busy && report.changed > 0 && (report.errors === 0 || skip);
  apply.disabled = !canApply;
  const n = report ? report.changed : 0;
  apply.textContent = n > 0 ? `Apply ${n} change${n === 1 ? '' : 's'}` : 'Apply changes';
  show('skip-wrap', !!report && !report.applied && report.errors > 0);
  show('applybar', !!report && !report.applied);
}

function upload(apply) {
  const form = new FormData();
  form.append('file', file, file.name);
  const skip = $('skip').checked;
  return api.post(`/import/items?apply=${apply}&skipErrors=${apply && skip}`, form);
}

function failure(err) {
  const box = $('fail');
  box.hidden = false;
  // A file that changed on disk after it was chosen can't be read again by the browser.
  box.textContent = err && err.name === 'NotReadableError'
    ? 'The browser could not read the file again. If you changed or moved it, choose it again.'
    : err.message;
}

async function check() {
  const mine = ++runId;
  report = null;
  clear($('report'));
  show('fail', false);
  show('done', false);
  setBusy(true, 'Reading the file…');
  try {
    const result = await upload(false);
    if (mine !== runId) return;
    report = result;
    $('report').append(renderReport(result));
    if (result.total === 0) {
      failure(new Error('No item rows were found in the file. Use the export as your starting point so the columns match.'));
    }
  } catch (err) {
    if (mine === runId) failure(err);
  } finally {
    if (mine === runId) setBusy(false);
  }
}

async function applyNow() {
  if (!file || !report || busy) return;
  const mine = ++runId;
  show('fail', false);
  setBusy(true, 'Saving the changes…');
  try {
    const result = await upload(true);
    if (mine !== runId) return;
    report = result;
    clear($('report'));
    $('report').append(renderReport(result));
    const done = $('done');
    clear(done);
    if (result.applied) {
      done.append(h('strong', {}, `${result.changed} change${result.changed === 1 ? '' : 's'} saved. `),
        result.errors ? `${result.errors} row${result.errors === 1 ? ' was' : 's were'} skipped. ` : '',
        h('a', { href: '/data.html' }, 'See them in Data'), ' · ', h('a', { href: '/map.html' }, 'See them on the map'));
      done.hidden = false;
      toast('Import applied');
    } else {
      failure(new Error('Nothing was saved. See the problems listed below and try again.'));
    }
  } catch (err) {
    if (mine === runId) {
      failure(err);
      // The data may have changed under us, so show a fresh preview rather than a stale one.
      report = null;
    }
  } finally {
    if (mine === runId) setBusy(false);
  }
}

function choose(picked) {
  if (!picked) return;
  if (!/\.xlsx$/i.test(picked.name)) {
    toast('Only .xlsx files can be imported. In Excel use Save As → Excel Workbook (*.xlsx).', 'error');
    return;
  }
  file = picked;
  $('file-name').textContent = picked.name;
  $('file-size').textContent = sizeText(picked.size);
  $('skip').checked = false;
  show('step-pick', false);
  show('step-result', true);
  check();
}

function reset() {
  runId += 1;
  file = null;
  report = null;
  $('file').value = '';
  show('step-result', false);
  show('step-pick', true);
  setBusy(false);
}

function wireDrop() {
  const zone = $('step-pick');
  const over = (on) => zone.classList.toggle('over', on);
  for (const type of ['dragenter', 'dragover']) {
    zone.addEventListener(type, (e) => { e.preventDefault(); over(true); });
  }
  for (const type of ['dragleave', 'drop']) {
    zone.addEventListener(type, (e) => { e.preventDefault(); over(false); });
  }
  zone.addEventListener('drop', (e) => choose(e.dataTransfer && e.dataTransfer.files[0]));
  // A file dropped beside the drop zone should not make the browser open it and leave the page.
  for (const type of ['dragover', 'drop']) window.addEventListener(type, (e) => e.preventDefault());
}

async function boot() {
  const me = await ready;
  if (!me.canEdit) {
    show('denied', true);
    show('step-pick', false);
    return;
  }
  $('file').addEventListener('change', (e) => choose(e.target.files[0]));
  $('btn-other').addEventListener('click', reset);
  $('btn-apply').addEventListener('click', applyNow);
  $('skip').addEventListener('change', refreshApply);
  wireDrop();
}

boot();
