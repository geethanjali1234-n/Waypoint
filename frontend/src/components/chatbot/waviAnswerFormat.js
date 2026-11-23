/**
 * Layout-only structure for a Wavi reply. The backend composes every answer (records, statuses, hours and totals
 * are computed and authorized there). This module only splits the text the server sent into lines: the opening
 * sentence(s), "- " list rows (with the line that introduces them as a heading), and closing notes. It never adds,
 * removes, reorders or reinterprets a word: no status badges, no inferred fields. Every character shown is the
 * server's own, apart from paired "**" emphasis markers, which are rendered as bold.
 */

const ROW_PREFIX = /^-\s+/;

/**
 * Splits a reply into { lead, groups: [{ title, rows }], notes }. A reply without "- " rows has no groups and is
 * shown as plain text. A non-row line directly before rows is that list's heading; when it is the first line, it is
 * also the answer itself and stays the lead.
 */
export function structureAnswer(text) {
  const lines = (typeof text === 'string' ? text : '').split('\n').map((line) => line.trimEnd());
  const lead = [];
  const groups = [];
  const notes = [];
  let current = null;
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!line.trim()) continue;
    if (ROW_PREFIX.test(line)) {
      if (!current) {
        current = { title: null, rows: [] };
        groups.push(current);
      }
      current.rows.push(line.replace(ROW_PREFIX, ''));
      continue;
    }
    const nextIsRow = index + 1 < lines.length && ROW_PREFIX.test(lines[index + 1]);
    if (nextIsRow && (groups.length > 0 || lead.length > 0)) {
      // A heading for the rows that follow ("By submission:").
      current = { title: line.trim().replace(/:$/, ''), rows: [] };
      groups.push(current);
      continue;
    }
    if (groups.length === 0) {
      // Opening sentence(s); a first line that introduces rows is the answer itself.
      lead.push(line);
      if (nextIsRow) {
        current = { title: null, rows: [] };
        groups.push(current);
      }
      continue;
    }
    current = null;
    notes.push(line);
  }
  return { lead, groups, notes };
}

/** Lists longer than this open on the first COLLAPSED_ROWS rows, with "Show all" for the rest. */
export const COLLAPSE_AFTER = 6;
export const COLLAPSED_ROWS = 4;

/**
 * Text split into plain and bold parts from paired "**" markers. An unpaired marker is dropped rather than shown
 * as stray asterisks; the words themselves are never changed.
 */
export function emphasisParts(text) {
  const source = typeof text === 'string' ? text : '';
  const parts = [];
  const pattern = /\*\*(.+?)\*\*/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    if (match.index > last) parts.push({ text: source.slice(last, match.index), bold: false });
    parts.push({ text: match[1], bold: true });
    last = match.index + match[0].length;
  }
  if (last < source.length) parts.push({ text: source.slice(last), bold: false });
  return parts.map((part) => ({ ...part, text: part.text.replace(/\*\*/g, '') })).filter((part) => part.text);
}

/**
 * A user-facing message for a failed chat request, from the HTTP status only (no server text is echoed). Returns
 * { text, retry }: whether sending the same question again can help.
 */
export function chatErrorMessage(error) {
  const status = error?.response?.status;
  if (status === 401) return { text: 'Your Waypoint session has expired. Please sign in again to continue.', retry: false };
  if (status === 403) return { text: "Your account doesn't have access to that. Wavy only shows what your role can see.", retry: false };
  if (status === 429) return { text: 'Wavy is getting a lot of questions right now. Please wait a moment and try again.', retry: true };
  if (status === 400) return { text: "Wavy couldn't read that message. Please rephrase it and try again.", retry: false };
  return { text: 'The Waypoint Assistant is temporarily unavailable. Please try again shortly.', retry: true };
}
