// Modal forms: place a planned item on the yard, and assemble the pieces of a block or unit.

import { state, emit } from './state.js';
import { api } from './api.js';
import { h, clear, toast } from './util.js';

function openDialog({ title, content, submitLabel, onSubmit }) {
  const dialog = document.getElementById('modal');
  clear(dialog);
  const error = h('p', { class: 'msg msg-error', role: 'alert', hidden: true });
  const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, submitLabel);
  const close = () => dialog.close();

  const form = h('form', {
    class: 'modal-form',
    onSubmit: async (e) => {
      e.preventDefault();
      error.hidden = true;
      submit.disabled = true;
      try {
        await onSubmit();
        close();
      } catch (err) {
        error.textContent = err.message;
        error.hidden = false;
      } finally {
        submit.disabled = false;
      }
    },
  },
  h('div', { class: 'modal-head' },
    h('h2', {}, title),
    h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', onClick: close }, '✕')),
  h('div', { class: 'modal-body' }, content),
  error,
  h('div', { class: 'modal-foot' },
    h('button', { class: 'btn', type: 'button', onClick: close }, 'Cancel'),
    submit));
  dialog.append(form);
  dialog.showModal();
}

function field(label, control, hint) {
  const hintNode = hint ? h('span', { class: 'field-hint' }, hint) : null;
  return { node: h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), control, hintNode), hint: hintNode };
}

/** The zone where the item's current phase happens (matched on the workbook facility code), if any zone maps to it. */
export function defaultZoneId(item) {
  const zone = state.zones.find((z) => z.facility && z.facility === item.phaseFacility);
  return zone ? zone.id : null;
}

export function zoneSelect(selectedId, excludeId = null) {
  return h('select', { required: true },
    h('option', { value: '' }, 'Choose a zone'),
    state.zones.filter((z) => z.id !== excludeId)
      .map((z) => h('option', { value: z.id, selected: z.id === selectedId }, z.name)));
}

// ------------------------------------------------------------- place item

/** Pick a planned item (one of the hull's units, blocks or sections) and put it on the yard. */
export function openPlaceItem() {
  const refs = {
    hull: h('select', {}, state.hulls.map((hl) => h('option', { value: hl.id }, hl.code))),
    item: h('input', { type: 'text', list: 'planned-items', placeholder: 'Type to search, like BA-U01-B02', autocomplete: 'off', required: true }),
    zone: zoneSelect(null),
    note: h('input', { type: 'text', maxlength: 300, placeholder: 'Optional' }),
  };
  const datalist = h('datalist', { id: 'planned-items' });
  const itemField = field('Planned item', refs.item, 'Loading the plan');
  let planned = new Map(); // item name -> item

  const selected = () => planned.get(refs.item.value.trim().toUpperCase()) || null;

  const loadPlanned = async () => {
    refs.item.disabled = true;
    itemField.hint.textContent = 'Loading the plan';
    try {
      const list = await api.get(`/items?status=PLANNED&hullId=${refs.hull.value}`);
      planned = new Map(list.map((i) => [i.name.toUpperCase(), i]));
      datalist.replaceChildren(...list.map((i) => h('option', { value: i.name }, `${i.levelLabel}: ${i.function || ''}`)));
      itemField.hint.textContent = list.length
        ? `${list.length} planned items left for this hull. Type a name or pick from the list.`
        : 'Everything for this hull is already on the yard or built into something else.';
    } catch (err) {
      itemField.hint.textContent = err.message;
    } finally {
      refs.item.disabled = false;
    }
  };

  const describeSelection = () => {
    const item = selected();
    if (!item) return;
    const zoneId = defaultZoneId(item);
    if (zoneId && !refs.zone.value) refs.zone.value = String(zoneId);
    itemField.hint.textContent = [item.levelLabel, item.areaName, item.function, `phase ${item.phaseNumber} ${item.phaseName}`]
      .filter(Boolean).join(' · ');
  };

  refs.hull.addEventListener('input', () => { refs.item.value = ''; refs.zone.value = ''; loadPlanned(); });
  refs.item.addEventListener('input', describeSelection);
  loadPlanned();

  const content = h('div', { class: 'form' },
    field('Hull', refs.hull).node,
    itemField.node,
    datalist,
    field('Zone', refs.zone, 'Starts with the zone for its first phase when there is one').node,
    field('Note', refs.note).node);

  openDialog({
    title: 'Place item on the yard',
    submitLabel: 'Place item',
    content,
    onSubmit: async () => {
      const item = selected();
      if (!item) throw new Error('Choose one of the planned items for this hull.');
      const placed = await api.post(`/items/${item.id}/place`, {
        zoneId: refs.zone.value ? Number(refs.zone.value) : null,
        note: refs.note.value,
      });
      toast(`Placed ${placed.name}`);
      emit('items-mutated', { id: placed.id, open: true });
    },
  });
}

// ---------------------------------------------------------------- assemble

/**
 * Joins the selected pieces into their planned parent. The server checks that they are all the pieces of one block
 * or unit and tells the user which ones are missing, so this form only collects the zone and a note.
 */
export function openAssemble(ids) {
  const children = ids.map((id) => state.items.find((i) => i.id === id)).filter(Boolean);
  const parents = new Set(children.map((c) => c.parentName).filter(Boolean));
  const parentName = parents.size === 1 ? [...parents][0] : null;

  const refs = {
    zone: zoneSelect(children[0] ? children[0].zoneId : null),
    note: h('input', { type: 'text', maxlength: 300, placeholder: 'Optional' }),
  };

  const content = h('div', { class: 'form' },
    h('p', { class: 'lead' }, parentName
      ? `Joining ${children.length} items into ${parentName}. They will leave the map and stay listed under its Children tab.`
      : `Joining ${children.length} items.`),
    h('ul', { class: 'chips' }, children.map((c) => h('li', { class: 'chip' }, c.name))),
    parents.size > 1
      ? h('p', { class: 'msg msg-error' }, `These items belong to different parents (${[...parents].join(', ')}). Pick the pieces of one block or unit at a time.`)
      : null,
    field('Place the result in', refs.zone).node,
    field('Note', refs.note).node);

  openDialog({
    title: 'Assemble',
    submitLabel: 'Assemble',
    content,
    onSubmit: async () => {
      const created = await api.post('/items/assemble', {
        childIds: ids,
        zoneId: refs.zone.value ? Number(refs.zone.value) : null,
        note: refs.note.value,
      });
      toast(`Built ${created.name} from ${children.length} items`);
      emit('items-mutated', { id: created.id, open: true, endAssemble: true });
    },
  });
}
