import { I983_STATUS_LABELS } from './i983/i983Workflow';

/** Who approves HR's documents, as shown to HR: "<Organization> Admin", or "Company Admin" until the organization loads. */
export function approverLabel(organizationName) {
  const name = organizationName?.trim();
  return name ? `${name} Admin` : 'Company Admin';
}

/**
 * True for a document HR delivers to an employee - it needs Company Admin approval first (a Company Admin delivers directly).
 * The Signed I-983 has its own review workflow, and a document HR asked the employee for is not delivered by HR.
 * Mirrors the server's rule, which is what actually enforces it.
 */
export function requiresAdminApproval(request) {
  if (!request) return false;
  if (request.documentType === 'OFFER_LETTER') return true;
  return request.documentType !== 'I983' && request.requestDirection === 'TO_MANAGER';
}

// Documents in the Company Admin approval workflow reuse the shared statuses (SUBMITTED / APPROVED / RETURNED_FOR_CORRECTION).
const OFFER_APPROVAL_LABELS = {
  SUBMITTED: 'Pending Admin Approval',
  APPROVED: 'Approved',
  RETURNED_FOR_CORRECTION: 'Approval Rejected',
};

// Offer lifecycle is derived exclusively from persisted request data, across browsers and users.
export function offerLetterStage(request) {
  if (request?.documentType !== 'OFFER_LETTER') return null;
  if (request.signedResponseDocumentId) return 'COMPLETED';
  if (request.fulfilledDocumentId) return 'SENT_TO_EMPLOYEE';
  return null;
}

export function documentRequestStatus(request, role) {
  const stage = offerLetterStage(request);
  if (stage === 'COMPLETED') return 'Completed';
  if (stage === 'SENT_TO_EMPLOYEE') return (role || request?.viewerRole) === 'EMPLOYEE' || request?.requestedByUserId === request?.viewerUserId ? 'Pending Signature' : 'Awaiting Employee Signature';
  if (request?.documentType !== 'I983' && requiresAdminApproval(request) && OFFER_APPROVAL_LABELS[request.status]) {
    // The approval workflow is internal: the employee just sees their request as still pending.
    const isRequester = (role || request?.viewerRole) === 'EMPLOYEE' || request?.requestedByUserId === request?.viewerUserId;
    return isRequester ? 'Pending' : OFFER_APPROVAL_LABELS[request.status];
  }
  if (request?.documentType === 'I983' && I983_STATUS_LABELS[request.status]) return I983_STATUS_LABELS[request.status];
  return request.status === 'FULFILLED' ? 'Completed' : request.status;
}
