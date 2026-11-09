/** Approved dates remain protected; an explicit correction unlocks only its own record. */
export function getApprovedDayEntries(records = [], correctionRecordId = null) {
  const days = {};
  for (const record of records) {
    if (record.status !== 'APPROVED' || (correctionRecordId && record.id === correctionRecordId)) continue;
    for (const entry of record.entries || []) {
      if (entry.workDate && !days[entry.workDate]) days[entry.workDate] = { ...entry, record };
    }
  }
  return days;
}
