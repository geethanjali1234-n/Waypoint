/** Suggestions only: preserve recorded work and never infer leave or holidays. */
export function fillBlankWeekdays({ dates = [], today, hours = {}, descriptions = {}, entryTypes = {}, lockedDates = [] }) {
  const next = { ...hours };
  const locked = new Set(lockedDates);
  let filledCount = 0;
  for (const date of new Set(dates)) {
    if (!today || date > today || locked.has(date)) continue;
    const parsed = new Date(`${date}T00:00:00`);
    if (Number.isNaN(parsed.getTime()) || [0, 6].includes(parsed.getDay())) continue;
    if (next[date] != null && String(next[date]).trim() !== '') continue;
    if (String(descriptions[date] || '').trim()) continue;
    if (String(entryTypes[date] || 'WORK').toUpperCase() !== 'WORK') continue;
    next[date] = '8';
    filledCount++;
  }
  return { hours: next, filledCount };
}
