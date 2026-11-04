export function requiresTimesheetChangeReason(existing, entries, reeditSubmitted = false) {
  if (reeditSubmitted) return true;
  const dates = new Set(entries.map((entry) => entry.workDate));
  return Boolean(existing && existing.status !== 'DRAFT'
    && (existing.entries || []).some((entry) => dates.has(entry.workDate) && Number(entry.hoursWorked || 0) > 0));
}

