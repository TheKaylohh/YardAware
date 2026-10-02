// Modal forms: add an item, and assemble several items into a new one.

import { state, emit } from './state.js';
import { api } from './api.js';
import { h, clear, toast, parseSpecs } from './util.js';

const TAG_PATTERN = /^([A-Z]{2})(\d{3})$/;

/** "EA500" -> "Engine room, level 5", or null when the tag isn't valid. */
export function describeTag(raw) {
  const match = TAG_PATTERN.exec((raw || '').trim().toUpperCase());
  if (!match) return null;
  const area = state.meta.areas.find((a) => a.code === match[1]);
  return area ? `${area.name}, level ${match[2][0]}` : null;
}

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

function hullSelect(selectedId) {
  return h('select', {},
    h('option', { value: '' }, 'No hull'),
    state.hulls.map((hl) => h('option', { value: hl.id, selected: hl.id === selectedId }, hl.code)));
}

function zoneSelect(selectedId) {
  return h('select', { required: true },
    h('option', { value: '' }, 'Choose a zone'),
    state.zones.map((z) => h('option', { value: z.id, selected: z.id === selectedId }, z.name)));
}

function typeSelect(selected, skip = () => false) {
  return h('select', {},
    state.meta.types.filter((t) => !skip(t)).map((t) => h('option', { value: t.code, selected: t.code === selected }, t.label)));
}

// ---------------------------------------------------------------- add item

export function openAddItem() {
  const refs = {
    type: typeSelect('SUB_ASSEMBLY'),
    name: h('input', { type: 'text', maxlength: 120, placeholder: 'Unique name, like S041-EA510' }),
    hull: hullSelect(state.hulls.length ? state.hulls[0].id : null),
    zone: zoneSelect(null),
    tag: h('input', { type: 'text', maxlength: 5, placeholder: 'EA500', autocapitalize: 'characters' }),
    quantity: h('input', { type: 'number', min: 1, step: 1, placeholder: '48' }),
    unit: h('input', { type: 'text', maxlength: 12, value: 'pcs' }),
    specs: h('textarea', { rows: 4, spellcheck: 'false', placeholder: 'Weight (t): 92.5\nDrawing: EA510-BLK' }),
    note: h('input', { type: 'text', maxlength: 300, placeholder: 'Optional' }),
  };
  const nameField = field('Name', refs.name, null);
  const tagField = field('Area tag', refs.tag, 'Area code plus three digits, like EA500');
  const qtyRow = h('div', { class: 'field-row' }, field('Quantity', refs.quantity).node, field('Unit', refs.unit).node);

  const isBatch = () => state.meta.types.find((t) => t.code === refs.type.value)?.batch;
  const sync = () => {
    const type = refs.type.value;
    tagField.node.hidden = type !== 'GRAND_BLOCK';
    qtyRow.hidden = !isBatch();
    if (type === 'TOOL') refs.hull.value = '';
    const hull = state.hulls.find((x) => x.id === Number(refs.hull.value));
    const described = describeTag(refs.tag.value);
    tagField.hint.textContent = described || 'Area code plus three digits, like EA500';
    refs.name.placeholder = type === 'GRAND_BLOCK' && hull && described
      ? `Leave blank to use ${hull.code}-${refs.tag.value.trim().toUpperCase()}`
      : 'Unique name, like S041-EA510';
  };
  for (const control of [refs.type, refs.hull, refs.tag]) control.addEventListener('input', sync);
  sync();

  const content = h('div', { class: 'form' },
    field('Type', refs.type).node,
    nameField.node,
    h('div', { class: 'field-row' }, field('Hull', refs.hull).node, field('Zone', refs.zone).node),
    tagField.node,
    qtyRow,
    field('Specs', refs.specs, 'One per line, like Weight (t): 92.5').node,
    field('Note', refs.note).node);

  openDialog({
    title: 'Add item',
    submitLabel: 'Add item',
    content,
    onSubmit: async () => {
      const created = await api.post('/items', {
        name: refs.name.value,
        type: refs.type.value,
        hullId: refs.hull.value ? Number(refs.hull.value) : null,
        zoneId: refs.zone.value ? Number(refs.zone.value) : null,
        tag: refs.tag.value,
        quantity: isBatch() && refs.quantity.value ? Number(refs.quantity.value) : null,
        unit: isBatch() ? refs.unit.value : null,
        specs: parseSpecs(refs.specs.value),
        note: refs.note.value,
      });
      toast(`Added ${created.name}`);
      emit('items-mutated', { id: created.id, open: true });
    },
  });
}

// ---------------------------------------------------------------- assemble

const RANK = { PIPE_OUTFITTING: 0, SUB_ASSEMBLY: 1, SECTION: 2, UNIT: 2, BLOCK: 3, GRAND_BLOCK: 4 };
const NEXT_UP = ['UNIT', 'SECTION', 'BLOCK', 'GRAND_BLOCK', 'GRAND_BLOCK'];

/** Guess what the assembly becomes: sub-assemblies make a section, sections make a block, and so on. */
function suggestType(children) {
  const top = Math.max(...children.map((c) => RANK[c.type] ?? 0));
  return NEXT_UP[top];
}

export function openAssemble(ids) {
  const children = ids.map((id) => state.items.find((i) => i.id === id)).filter(Boolean);
  const hullIds = new Set(children.map((c) => c.hullId).filter((x) => x !== null && x !== undefined));
  const hull = hullIds.size === 1 ? state.hulls.find((x) => x.id === [...hullIds][0]) : null;

  const refs = {
    name: h('input', { type: 'text', maxlength: 120, placeholder: hull ? `Unique name, like ${hull.code}-EA510` : 'Unique name' }),
    type: typeSelect(suggestType(children), (t) => t.batch || t.code === 'TOOL'),
    tag: h('input', { type: 'text', maxlength: 5, placeholder: 'EA500', autocapitalize: 'characters' }),
    zone: zoneSelect(children[0] ? children[0].zoneId : null),
    specs: h('textarea', { rows: 3, spellcheck: 'false', placeholder: 'Weight (t): 410' }),
    note: h('input', { type: 'text', maxlength: 300, placeholder: 'Optional' }),
  };
  const tagField = field('Area tag', refs.tag, 'Area code plus three digits, like EA500');
  const sync = () => {
    tagField.node.hidden = refs.type.value !== 'GRAND_BLOCK';
    const described = describeTag(refs.tag.value);
    tagField.hint.textContent = described || 'Area code plus three digits, like EA500';
    refs.name.placeholder = refs.type.value === 'GRAND_BLOCK' && hull && described
      ? `Leave blank to use ${hull.code}-${refs.tag.value.trim().toUpperCase()}`
      : (hull ? `Unique name, like ${hull.code}-EA510` : 'Unique name');
  };
  refs.type.addEventListener('input', sync);
  refs.tag.addEventListener('input', sync);
  sync();

  const content = h('div', { class: 'form' },
    h('p', { class: 'lead' }, `Joining ${children.length} items. They will leave the map and stay listed under the new item's Children tab.`),
    h('ul', { class: 'chips' }, children.map((c) => h('li', { class: 'chip' }, c.name))),
    hullIds.size > 1 ? h('p', { class: 'msg msg-error' }, 'These items belong to different hulls. Only items from the same hull can be assembled together.') : null,
    field('New item name', refs.name).node,
    h('div', { class: 'field-row' }, field('Becomes', refs.type).node, field('Place in', refs.zone).node),
    tagField.node,
    field('Specs', refs.specs, 'One per line, like Weight (t): 92.5').node,
    field('Note', refs.note).node);

  openDialog({
    title: 'Assemble',
    submitLabel: 'Create assembly',
    content,
    onSubmit: async () => {
      const created = await api.post('/items/assemble', {
        childIds: ids,
        name: refs.name.value,
        type: refs.type.value,
        tag: refs.tag.value,
        zoneId: refs.zone.value ? Number(refs.zone.value) : null,
        specs: parseSpecs(refs.specs.value),
        note: refs.note.value,
      });
      toast(`Created ${created.name} from ${children.length} items`);
      emit('items-mutated', { id: created.id, open: true, endAssemble: true });
    },
  });
}
