import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../../components';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import '../features/documentRequests/offerLetterApproval.css';

const APPROVALS_PATH = '/myhr/admin/offer-letter-approvals';

/**
 * Company Admin dashboard card for document approvals (offer letters and every other document HR sends). It is visually distinct from the normal action cards and
 * shows the pending count, so an Admin can see at a glance that an HR offer letter is waiting for them.
 * Rendered only for the COMPANY_ADMIN dashboard; the data endpoint is also Company Admin only on the server.
 */
export default function OfferLetterApprovalCard() {
  const navigate = useNavigate();
  const [pending, setPending] = useState(null);

  useEffect(() => {
    let active = true;
    Promise.resolve(myHrDocumentRequestApi.listOfferLetterApprovals()).then((response) => {
      if (active) setPending(response?.data?.data?.pending ?? 0);
    }).catch(() => { /* the card simply stays hidden if the count cannot be loaded */ });
    return () => { active = false; };
  }, []);

  if (pending === null) return null;
  const attention = pending > 0;

  return (
    <section className={`offer-approval-card ${attention ? 'offer-approval-card--attention' : ''}`} aria-label="Document Approvals">
      <div className="offer-approval-card__main">
        <span className="offer-approval-card__icon" aria-hidden="true">📝</span>
        <div>
          <h3>
            Document Approvals
            {attention && <span className="offer-approval-card__badge">{pending} Pending Approval</span>}
          </h3>
          <p>
            {attention
              ? `${pending} ${pending === 1 ? 'document requires' : 'documents require'} your approval before HR can send ${pending === 1 ? 'it' : 'them'} to the employee.`
              : 'No documents are currently waiting for your approval.'}
          </p>
        </div>
      </div>
      <Button type="button" variant={attention ? 'primary' : 'secondary'} onClick={() => navigate(APPROVALS_PATH)}>
        {attention ? 'Review Approvals' : 'View Approval History'}
      </Button>
    </section>
  );
}
