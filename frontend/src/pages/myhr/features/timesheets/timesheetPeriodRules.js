export function toDateKey(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function parseDateKey(value) { return new Date(`${value}T00:00:00`); }

export function addDays(dateKey, amount) {
  const date = parseDateKey(dateKey);
  date.setDate(date.getDate() + amount);
  return toDateKey(date);
}

export function daysBetween(start, end) {
  if (!start || !end || start > end) return [];
  const days = [];
  let current = start;
  while (current <= end) {
    days.push(current);
    current = addDays(current, 1);
  }
  return days;
}

export function getPeriodStandardHours(startDate, endDate) {
  if (!startDate || !endDate) return 40;
  const days = daysBetween(startDate, endDate);
  if (!days.length) return 40;

  // Count business days (Monday through Friday)
  const businessDays = days.filter((d) => {
    const dayOfWeek = parseDateKey(d).getDay();
    return dayOfWeek !== 0 && dayOfWeek !== 6;
  }).length;

  // Standard full-time baseline is 8 hours per business day (e.g. 5 days = 40h, 10 days = 80h, 21-22 days = 168-176h)
  const effectiveDays = businessDays > 0 ? businessDays : days.length;
  return effectiveDays * 8;
}

export function isRecordCleanAndAuditReady(rec) {
  if (!rec || rec.status !== 'SUBMITTED') return false;
  const hours = Number(rec.totalHours || 0);
  if (hours <= 0) return false;

  const standardMaxHours = getPeriodStandardHours(rec.periodStartDate, rec.periodEndDate);
  if (hours > standardMaxHours) return false;

  // If entries are present, ensure no single day has daily overtime (> 8 hours)
  if (Array.isArray(rec.entries) && rec.entries.length > 0) {
    const hasDailyOvertime = rec.entries.some((entry) => Number(entry.hoursWorked || 0) > 8);
    if (hasDailyOvertime) return false;
  }

  // If this timesheet has a conflict change reason, it requires manual manager review
  if (rec.changeReason && rec.changeReason.trim().length > 0) {
    return false;
  }

  return true;
}
