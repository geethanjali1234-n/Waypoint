import { RouteLine } from '../../../components';
import dashboardDocumentProgress from './dashboardDocumentProgress';

export default function DocumentRoute({ request, approvalStatus, preparationStep, needsApproval = false, sent = false, nextAction, label = 'Document workflow' }) {
  let steps;
  if (approvalStatus && ['PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SENT'].includes(approvalStatus)) {
    steps = approvalStatus === 'REJECTED'
      ? [{ label: 'Prepared', state: 'done' }, { label: 'Correct and resubmit', state: 'current' }]
      : [
        { label: 'Prepared', state: 'done' },
        { label: approvalStatus === 'PENDING_APPROVAL' ? 'Review and approve' : 'Approved', state: approvalStatus === 'PENDING_APPROVAL' ? 'current' : 'done' },
        { label: approvalStatus === 'APPROVED' && !sent ? 'Send to employee' : 'Sent to employee', state: sent || approvalStatus === 'SENT' ? 'done' : approvalStatus === 'APPROVED' ? 'current' : 'upcoming' },
      ];
  } else if (sent) {
    steps = [{ label: 'Document details', state: 'done' }, { label: 'Previewed', state: 'done' }, { label: 'Sent to employee', state: 'done' }];
  } else if (preparationStep) {
    steps = [{ label: 'Document details', state: preparationStep === 1 ? 'current' : 'done' }, { label: needsApproval ? 'Preview and submit for approval' : 'Preview and send', state: preparationStep === 2 ? 'current' : 'upcoming' }];
  } else {
    steps = dashboardDocumentProgress(request)?.steps || [];
  }
  if (nextAction) steps = steps.map((step) => step.state === 'current' ? { ...step, label: nextAction } : step);
  return steps.length ? <aside className="document-route"><p className="document-route__eyebrow">Next stop</p><RouteLine steps={steps} orientation="vertical" label={label} /></aside> : null;
}
