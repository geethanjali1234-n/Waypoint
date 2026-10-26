/**
 * Shared date-display helpers.
 *
 * All user-visible date-only values in the app render as MM-DD-YYYY; date+time
 * values render as "MM-DD-YYYY, h:mm A" (local time). This module is the one
 * place that formatting lives, so no page hand-rolls its own date rendering.
 *
 * Backend/API date contracts and storage formats are untouched - this only
 * changes how a date already received from the API is displayed.
 */

const DATE_ONLY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const DATE_PARTS = { month: '2-digit', day: '2-digit', year: 'numeric' };
const LONG_DATE_PARTS = { month: 'long', day: 'numeric', year: 'numeric' };
const TIME_PARTS = { hour: 'numeric', minute: '2-digit', hour12: true };

// A bare "YYYY-MM-DD" string must be parsed at LOCAL midnight, not UTC
// midnight - otherwise `new Date("2026-09-15")` (UTC midnight) can render as
// Sep 14 in any timezone behind UTC, shifting the calendar day by one.
function toDate(value) {
  if (value instanceof Date) return value;
  if (typeof value === 'string' && DATE_ONLY_PATTERN.test(value)) {
    return new Date(`${value}T00:00:00`);
  }
  return new Date(value);
}

/**
 * Formats a date-only value (e.g. "2026-09-15", a Date, or an ISO timestamp
 * whose time component should be ignored for display) as MM-DD-YYYY.
 * Returns '' for a missing value, or the original value if it can't be parsed.
 */
export function formatDateOnly(value) {
  if (value === null || value === undefined || value === '') return '';
  const date = toDate(value);
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : '';
  return date.toLocaleDateString('en-US', DATE_PARTS).replace(/\//g, '-');
}

/**
 * Formats a date-only value with the month spelled out (e.g. "September 15,
 * 2026") instead of MM-DD-YYYY. Used only where a document's wording calls
 * for a long-form date - the Offer Letter PDF - everywhere else in the app
 * uses formatDateOnly.
 */
export function formatDateLong(value) {
  if (value === null || value === undefined || value === '') return '';
  const date = toDate(value);
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : '';
  return date.toLocaleDateString('en-US', LONG_DATE_PARTS);
}

/**
 * Formats a date+time value (ISO timestamp, with or without an explicit
 * timezone) as "MM-DD-YYYY, h:mm A" in the viewer's local time. Time
 * information is always preserved - use formatDateOnly for pure calendar dates.
 */
export function formatDateTime(value) {
  if (value === null || value === undefined || value === '') return '';
  const date = toDate(value);
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : '';
  const datePart = date.toLocaleDateString('en-US', DATE_PARTS).replace(/\//g, '-');
  const timePart = date.toLocaleTimeString('en-US', TIME_PARTS);
  return `${datePart}, ${timePart}`;
}
