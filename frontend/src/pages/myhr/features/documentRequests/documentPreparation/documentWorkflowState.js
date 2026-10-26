/**
 * Frontend-only prototype overlay for the sign/return lifecycle stages
 * (SENT_TO_EMPLOYEE -> SIGNED_RETURNED -> COMPLETED) that the real backend
 * cannot represent yet - DocumentRequest.status stays PENDING throughout,
 * exactly as it already does for the existing generic prepare/send
 * prototype. State is namespaced per requestId in localStorage.
 *
 * Known prototype limitation: localStorage is per-browser, not shared
 * across HR's and the employee's sessions, so this only demonstrates the
 * full hand-off within one browser (e.g. switching role/tab). A real
 * hand-off requires the backend changes described in the implementation
 * plan.
 */
const STORAGE_KEY = 'wp:myhr:documentWorkflowState:v1';

function readAll() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function writeAll(all) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(all));
  } catch {
    // Storage unavailable or over quota - state simply won't persist.
  }
}

export function getWorkflowState(requestId) {
  if (!requestId) return null;
  return readAll()[requestId] || null;
}

export function setWorkflowState(requestId, patch) {
  if (!requestId) return null;
  const all = readAll();
  const next = { ...(all[requestId] || {}), ...patch };
  all[requestId] = next;
  writeAll(all);
  return next;
}

export function clearWorkflowState(requestId) {
  if (!requestId) return;
  const all = readAll();
  delete all[requestId];
  writeAll(all);
}

// The actual signed-document File isn't persisted to localStorage (quota
// risk, and Files aren't JSON-serializable) - only its metadata is, via
// setWorkflowState's signedDocumentFileName. The real Blob is kept here,
// in-memory, for the current browser tab/session only; it does not survive
// a page refresh. This is the realistic Phase 1 tradeoff: within one demo
// session HR can actually preview/download what the employee uploaded, but
// a refresh loses it, same as the rest of this prototype's document
// handling.
const signedDocumentBlobs = new Map();

export function setSignedDocumentBlob(requestId, file) {
  if (!requestId) return;
  signedDocumentBlobs.set(requestId, file || null);
}

export function getSignedDocumentBlob(requestId) {
  return requestId ? signedDocumentBlobs.get(requestId) || null : null;
}
