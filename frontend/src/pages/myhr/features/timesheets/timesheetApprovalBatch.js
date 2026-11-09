// Only explicit server-confirmed results count as approvals.
export async function approveTimesheetBatch(records, onReview, onProgress) {
  let approved = 0;
  for (let index = 0; index < records.length; index++) {
    try {
      if (await onReview(records[index], 'APPROVED') === true) approved++;
    } catch {
      // A failed request must not prevent review of the remaining records.
    }
    onProgress?.({ done: index + 1, total: records.length });
  }
  return { approved, failed: records.length - approved };
}
