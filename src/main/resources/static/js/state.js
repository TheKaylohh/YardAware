// Shared UI state plus a tiny publish/subscribe bus so modules don't import each other.

export const state = {
  meta: { levels: [], areas: [], phases: [] },
  hulls: [],
  zones: [],
  items: [],              // items on the yard (planned and consumed items are not included)
  mode: 'view',           // 'view' | 'edit'
  hiddenHulls: new Set(), // hull ids
  query: '',
  panelItemId: null,
  pinned: false,
  assembling: false,
  picked: new Set(),
};

const subscribers = new Map();

export function on(event, handler) {
  if (!subscribers.has(event)) subscribers.set(event, []);
  subscribers.get(event).push(handler);
}

export function emit(event, data) {
  for (const handler of subscribers.get(event) || []) handler(data);
}
