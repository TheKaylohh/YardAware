// Thin wrapper over fetch. Errors carry the server's message so the UI can show it as-is.
// Sign-in aware: a 401 sends the browser to the sign-in page, writes carry the CSRF token, a 403 explains itself.

const SIGN_IN_GUARD_KEY = 'yard.signin.redirectedAt';

/** Where the sign-in flow starts. The server sends the browser back to redirect_uri afterwards. */
export const SIGN_IN_HOME = '/auth/login?redirect_uri=%2F';

/** The CSRF token the server stored in the XSRF-TOKEN cookie, or null when authentication is off. */
export function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

function redirectToSignIn() {
  // Never loop: if we were just sent to sign in and still get a 401, show a message instead.
  let last = 0;
  try { last = Number(sessionStorage.getItem(SIGN_IN_GUARD_KEY) || 0); } catch (ignored) { /* storage unavailable */ }
  if (Date.now() - last < 10000) return false;
  try { sessionStorage.setItem(SIGN_IN_GUARD_KEY, String(Date.now())); } catch (ignored) { /* storage unavailable */ }
  const here = window.location.pathname + window.location.search;
  window.location.assign(`/auth/login?redirect_uri=${encodeURIComponent(here)}`);
  return true;
}

async function request(method, path, body) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  const res = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 401) {
    // The page is about to navigate to the sign-in screen, so never resolve.
    if (redirectToSignIn()) return new Promise(() => {});
    throw new Error('You are signed out. Reload the page to sign in again.');
  }
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    if (res.status === 403) {
      message = 'You don\'t have permission to change this. Ask an administrator for editor access.';
    } else {
      try {
        const json = await res.json();
        if (json && json.message) message = json.message;
      } catch (ignored) { /* body wasn't JSON */ }
    }
    throw new Error(message);
  }
  return res.status === 204 ? null : res.json();
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body = {}) => request('POST', path, body),
  put: (path, body = {}) => request('PUT', path, body),
};
