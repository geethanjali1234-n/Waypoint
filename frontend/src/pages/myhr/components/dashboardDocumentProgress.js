import { getDocumentWorkflowType } from '../features/documentRequests/documentWorkflowTypes';
import { getWorkflowState } from '../features/documentRequests/documentPreparation/documentWorkflowState';

/** Presentation from existing request facts; never advance a stop from a title alone. */
export default function dashboardDocumentProgress(request) {
  if (!request) return null;
  const status = request.status;
  const requested = { label: 'Requested', state: 'done' };
  const workflow = getWorkflowState(request.id);
  const type = getDocumentWorkflowType(request);
  const uploadI983 = request.documentType === 'I983' && Boolean(request.originalDocumentId);
  const requiresSignature = !uploadI983 && (request.documentType === 'OFFER_LETTER' || request.documentType === 'I983' || type?.requiresEmployeeSignature || workflow?.requiresEmployeeSignature);
  if (['REJECTED', 'CANCELLED'].includes(status)) return {
    steps: [requested, { label: status === 'REJECTED' ? 'Review rejection' : 'Request cancelled', sublabel: request.rejectionReason, state: 'current' }],
    actionLabel: 'View request details', context: status === 'REJECTED' ? 'Open the request to review the decision.' : 'This request has been cancelled.', needsAction: false,
  };
  if (workflow?.stage === 'SIGNED_RETURNED' || request.employeeSignatureDocumentId) return {
    steps: [requested, { label: 'Submitted', state: 'done' }, { label: 'Review signed return', state: 'current' }, { label: 'Filed', state: 'upcoming' }],
    actionLabel: 'View signing progress', context: 'Your signed response is waiting for review.', needsAction: false,
  };
  const persistedFile = request.signedResponseDocumentId || request.fulfilledDocumentId;
  const complete = Boolean(request.signedResponseDocumentId) || (uploadI983 && status === 'APPROVED' && persistedFile) || (!requiresSignature && status === 'FULFILLED' && persistedFile);
  if (complete) return {
    steps: [requested, { label: 'Submitted', state: 'done' }, ...(uploadI983 || requiresSignature ? [{ label: 'Signed', state: 'done' }] : []), { label: 'Filed', state: 'done' }],
    actionLabel: 'View completed document', context: 'The completed document is available in your request.', needsAction: false,
  };
  if (uploadI983) {
    if (status === 'APPROVED') return { steps: [requested, { label: 'Submitted', state: 'done' }, { label: 'Approved', state: 'done' }], actionLabel: 'View request details', context: 'The request is approved. Open it to see available documents.', needsAction: false };
    return {
      steps: [requested, { label: 'Submitted', state: 'done' }, ...(status === 'RETURNED_FOR_CORRECTION' ? [{ label: 'Correct and resubmit', state: 'current' }, { label: 'Review', state: 'upcoming' }] : [{ label: 'Review', state: 'current', sublabel: 'With HR/Admin for review and signature.' }]), { label: 'Filed', state: 'upcoming' }],
      actionLabel: status === 'RETURNED_FOR_CORRECTION' ? 'Correct and resubmit' : 'View signing progress',
      context: status === 'RETURNED_FOR_CORRECTION' ? 'Review the feedback and upload the corrected document.' : 'Your submitted document is with HR/Admin.',
      needsAction: status === 'RETURNED_FOR_CORRECTION',
    };
  }
  if (workflow?.stage === 'COMPLETED') return { steps: [requested, { label: 'Submitted', state: 'done' }, { label: 'Completed', state: 'done' }], actionLabel: 'View completed request', context: 'Your document workflow is complete.', needsAction: false };

  if (requiresSignature && (request.fulfilledDocumentId || workflow?.stage === 'SENT_TO_EMPLOYEE')) return {
    steps: [requested, { label: 'Submitted', state: 'done' }, { label: 'Review and sign', state: 'current' }, { label: 'Filed', state: 'upcoming' }],
    actionLabel: 'Review and sign', context: 'Review the document and add your signature.', needsAction: true,
  };
  if (!['PENDING', 'SUBMITTED', 'RETURNED_FOR_CORRECTION', 'APPROVED', 'FULFILLED'].includes(status)) return { steps: [], actionLabel: 'View request details', context: 'Open your request to see the available next steps.', needsAction: false };
  const employeeUpload = request.requestDirection === 'TO_EMPLOYEE' && status === 'PENDING';
  return {
    steps: [requested, { label: employeeUpload ? 'Submit documents' : 'Review request', state: 'current' }, { label: 'Filed', state: 'upcoming' }],
    actionLabel: employeeUpload ? 'Submit documents' : 'View request details',
    context: employeeUpload ? 'Provide the documents requested by your manager.' : 'Your request is in progress. Open it for the latest details.', needsAction: employeeUpload,
  };
}
