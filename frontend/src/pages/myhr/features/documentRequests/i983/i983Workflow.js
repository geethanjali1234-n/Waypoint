import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';

/**
 * Shared helpers for the Signed I-983 workflow (Immigration & Visa Support):
 * the employee uploads their I-983, HR/Admin review it, apply the
 * organization's Authorized Signature and approve / return / reject it.
 */

export const I983_STATUS_LABELS = {
  SUBMITTED: 'Submitted',
  RETURNED_FOR_CORRECTION: 'Returned for Correction',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
};

export const I983_STATUS_TONES = {
  SUBMITTED: 'info',
  RETURNED_FOR_CORRECTION: 'warning',
  APPROVED: 'success',
  REJECTED: 'error',
};

export const NO_SIGNATURE_MESSAGE = 'No authorized organization signature is configured. Please configure a signature in Organization Settings before signing this document.';

export const I983_VERSION_LABELS = {
  ORIGINAL: 'Original (employee)', EMPLOYEE_SIGNED: 'Signed by employee', REPLACEMENT: 'Replacement (HR/Admin)', SIGNED: 'Signed (organization)',
};

export const I983_HISTORY_LABELS = {
  SUBMITTED: 'Submitted',
  RESUBMITTED: 'Resubmitted',
  EMPLOYEE_SIGNED: 'Employee signed',
  REVIEWED: 'Reviewed document',
  REPLACED: 'Uploaded replacement',
  SIGNATURE_APPLIED: 'Applied organization signature',
  APPROVED: 'Approved',
  RETURNED_FOR_CORRECTION: 'Returned for correction',
  REJECTED: 'Rejected',
};

/** True for requests that use the upload -> sign -> approve I-983 workflow (not legacy HR-prepared ones). */
export function isI983Workflow(request) {
  return request?.documentType === 'I983' && Boolean(request?.originalDocumentId);
}

/** Opens (new tab) or downloads one version of the I-983. */
export async function openI983Document(requestId, documentId, download) {
  const response = await myHrDocumentRequestApi.getI983Document(requestId, documentId, download);
  const disposition = response.headers?.['content-disposition'] || '';
  const fileName = disposition.match(/filename="([^"]+)"/i)?.[1] || 'I-983.pdf';
  const url = URL.createObjectURL(response.data);
  if (download) {
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = fileName;
    document.body.appendChild(anchor); anchor.click(); anchor.remove();
  } else {
    window.open(url, '_blank', 'noopener,noreferrer');
  }
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
