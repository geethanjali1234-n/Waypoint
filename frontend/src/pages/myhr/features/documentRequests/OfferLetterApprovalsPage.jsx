import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Button, EmptyState, FilterPills, Modal, StatusBadge } from '../../../../components';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';
import { formatDateOnly } from '../../../../utils/dateFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import './documentRequests.css';
import './offerLetterApproval.css';
import DocumentRoute from '../../components/DocumentRoute';

const BASE = '/myhr/admin/offer-letter-approvals';

const STATUS_LABELS = {
  PENDING_APPROVAL: 'PENDING APPROVAL',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  SENT: 'SENT TO EMPLOYEE',
};


const FILTERS = [
  ['PENDING_APPROVAL', 'Pending approval'],
  ['APPROVED', 'Approved'],
  ['REJECTED', 'Rejected'],
  ['ALL', 'All'],
];

const errorMessage = (error, fallback) => error?.response?.data?.message || fallback;
const dateText = (value) => (value ? formatDateOnly(String(value).slice(0, 10)) : '—');
const typeLine = (item) => [item.offerType, item.employmentCategory].filter(Boolean).join(' · ');
const isPdf = (item) => !item?.contentType || /pdf/i.test(item.contentType);
const isOffer = (item) => !item?.documentType || item.documentType === 'OFFER_LETTER';
const documentName = (item) => item?.documentTitle || 'Offer Letter';
// "Offer Letter" for offer letters, "Document" for everything else - used in button labels.
const noun = (item) => (!item || isOffer(item) ? (item ? 'Offer Letter' : 'Document') : 'Document');
const headline = (item) => (isOffer(item) ? item.designation : [documentName(item), item.designation].filter(Boolean).join(' · '));

export function ApprovalStatusBadge({ status }) {
  return <StatusBadge status={status === 'PENDING_APPROVAL' ? 'pending' : status} label={STATUS_LABELS[status] || status} />;
}

/**
 * Company Admin only: the Document Approvals queue (offer letters and every other document HR sends to an employee) (/myhr/admin/offer-letter-approvals) and the review screen
 * for one letter (/myhr/admin/offer-letter-approvals/:requestId). Approving only authorizes HR to send the letter;
 * nothing is sent to the employee here. Authorization is enforced by the server, not by this page.
 */
export default function OfferLetterApprovalsPage() {
  const { requestId } = useParams();
  return requestId ? <ApprovalReview requestId={requestId} /> : <ApprovalList />;
}

function ApprovalList() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [searchParams] = useSearchParams();
  const [filter, setFilter] = useState(() => FILTERS.some(([value]) => value === searchParams.get('status')) ? searchParams.get('status') : null);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    myHrDocumentRequestApi.listOfferLetterApprovals().then((response) => {
      if (!active) return;
      const list = response.data.data;
      setData(list);
      setFilter((current) => current || (list.pending > 0 ? 'PENDING_APPROVAL' : 'ALL'));
    }).catch((failure) => { if (active) setError(errorMessage(failure, 'Offer letter approvals could not be loaded.')); });
    return () => { active = false; };
  }, []);

  const items = (data?.items || []).filter((item) => filter === 'ALL' || item.approvalStatus === filter
    || (filter === 'APPROVED' && item.approvalStatus === 'SENT'));

  return <MyHrFeaturePage hideBack className="phase-six" title="Document Approvals" fullWidth backTo="/myhr/admin/dashboard" backLabel="Back to Dashboard"
    description="Review and approve documents submitted by HR before they are sent to employees.">
    {error && <p role="alert" className="document-requests__error">{error}</p>}
    {!data && !error && <p role="status">Loading document approvals…</p>}
    {data && <>
      <FilterPills label="Filter approvals" value={filter} onChange={setFilter} options={FILTERS.map(([value, label]) => ({ value, label,
        count: value === 'PENDING_APPROVAL' ? data.pending : value === 'APPROVED' ? data.approved : value === 'REJECTED' ? data.rejected : undefined,
      }))} />
      {items.length === 0
        ? <EmptyState title={filter === 'PENDING_APPROVAL' ? 'No documents are currently waiting for your approval.' : 'No documents match this filter.'} />
        : <div className="offer-approvals__list">
          {items.map((item) => (
            <article key={item.requestId} className={`offer-approvals__item ${item.approvalStatus === 'PENDING_APPROVAL' ? 'offer-approvals__item--pending' : ''}`}>
              <div>
                <h3>{item.employeeName}</h3>
                <p className="offer-approvals__meta"><strong>{headline(item)}</strong></p>
                {typeLine(item) && <p className="offer-approvals__meta">{typeLine(item)}</p>}
                <p className="offer-approvals__meta">Submitted by: {item.submittedByName || 'HR'} · {dateText(item.submittedAt)}</p>
                <p className="offer-approvals__meta">Joining Date: {dateText(item.joiningDate)}</p>
              </div>
              <div className="offer-approvals__actions">
                <ApprovalStatusBadge status={item.approvalStatus} />
                <Button type="button" onClick={() => navigate(`${BASE}/${item.requestId}`)}>
                  {item.approvalStatus === 'PENDING_APPROVAL' ? `Review ${noun(item)}` : `View ${noun(item)}`}
                </Button>
              </div>
            </article>
          ))}
        </div>}
    </>}
  </MyHrFeaturePage>;
}

function ApprovalReview({ requestId }) {
  const navigate = useNavigate();
  const [item, setItem] = useState(null);
  const [error, setError] = useState('');
  const [pdfUrl, setPdfUrl] = useState('');
  const [working, setWorking] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [reason, setReason] = useState('');

  // clearError is false when reloading after a refused decision, so the reason it was refused stays visible.
  const load = useCallback((clearError = true) => myHrDocumentRequestApi.getOfferLetterApproval(requestId).then((response) => {
    setItem(response.data.data);
    if (clearError) setError('');
  }).catch((failure) => setError(errorMessage(failure, 'The document could not be loaded.'))), [requestId]);

  useEffect(() => { load(); }, [load]);

  // The exact PDF HR submitted. It is only displayed - reviewing never regenerates or changes it.
  useEffect(() => {
    if (!item?.documentId) return undefined;
    let active = true;
    let objectUrl = '';
    myHrDocumentRequestApi.getDocument(requestId, item.documentId).then((response) => {
      if (!active || !response?.data) return;
      objectUrl = URL.createObjectURL(new Blob([response.data], { type: item.contentType || 'application/pdf' }));
      setPdfUrl(objectUrl);
    }).catch(() => { if (active) setPdfUrl(''); });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestId, item?.documentId]);

  const approve = async () => {
    setWorking(true); setError('');
    try {
      setItem((await myHrDocumentRequestApi.approveOfferLetter(requestId)).data.data);
    } catch (failure) {
      setError(errorMessage(failure, 'The document could not be approved.'));
      load(false);
    } finally { setWorking(false); }
  };

  const reject = async () => {
    setWorking(true); setError('');
    try {
      setItem((await myHrDocumentRequestApi.rejectOfferLetter(requestId, reason.trim())).data.data);
      setRejectOpen(false); setReason('');
    } catch (failure) {
      setError(errorMessage(failure, 'The document could not be rejected.'));
      setRejectOpen(false);
      load(false);
    } finally { setWorking(false); }
  };

  const facts = item ? [
    ['Employee Name', item.employeeName], ...(isOffer(item) ? [] : [['Document', documentName(item)]]), ['Designation', item.designation], ['Offer Type', item.offerType],
    ['Employment Category', item.employmentCategory], ['Joining Date', dateText(item.joiningDate)], ['Issue Date', dateText(item.issueDate)],
    ['Pay Rate', item.payRate], ['Work Location', item.workLocation], ['Submitted By', item.submittedByName],
    ['Submitted On', dateText(item.submittedAt)],
  ].filter(([, value]) => value && value !== '—') : [];

  return <MyHrFeaturePage hideBack className="phase-six" title={`Review ${noun(item)}`} fullWidth backTo={BASE} backLabel="Back to Approvals"
    description={`Verify the ${item && !isOffer(item) ? 'document' : 'offer letter'} before approving it. Approving only allows HR to send it to the employee.`}>
    {error && <p role="alert" className="document-requests__error">{error}</p>}
    {!item && !error && <p role="status">Loading offer letter…</p>}
    {item && <div className="document-workflow-layout"><DocumentRoute approvalStatus={item.approvalStatus} />
      <section className="request-documents__card">
      <header><div><span>OFFER LETTER APPROVAL</span><h2>{item.employeeName}</h2></div><ApprovalStatusBadge status={item.approvalStatus} /></header>
      {item.approvalStatus === 'REJECTED' && item.rejectionReason && (
        <div className="offer-approval__banner offer-approval__banner--rejected" role="status">
          <span className="offer-approval__badge">REJECTED</span>
          <p><strong>Reason:</strong> {item.rejectionReason}</p>
        </div>
      )}
      {item.approvalStatus === 'APPROVED' && <div className="offer-approval__banner offer-approval__banner--approved" role="status">
        <span className="offer-approval__badge">APPROVED</span><p>Approved{item.reviewedByName ? ` by ${item.reviewedByName}` : ''}. HR can now send it to the employee.</p></div>}
      <dl className="offer-approvals__facts">
        {facts.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
      </dl>
      {item.rolesAndResponsibilities && <>
        <h3>Roles &amp; Responsibilities</h3>
        <p className="offer-approvals__roles">{item.rolesAndResponsibilities}</p>
      </>}
      <h3>{documentName(item)}</h3>
      <div className="offer-approvals__pdf">
        {!pdfUrl && <p role="status">Loading the {isOffer(item) ? 'offer letter PDF' : 'document'}…</p>}
        {pdfUrl && isPdf(item) && <><iframe title={isOffer(item) ? 'Offer letter PDF' : 'Document PDF'} src={pdfUrl} /><p><a href={pdfUrl} target="_blank" rel="noreferrer">Open the {isOffer(item) ? 'offer letter' : 'document'} in a new tab</a></p></>}
        {pdfUrl && !isPdf(item) && <p><a href={pdfUrl} download={item.fileName || 'document'}>Download {item.fileName || 'the document'} to review it</a></p>}
      </div>
      {item.requiresAuthorizedSignature && item.approvalStatus === 'PENDING_APPROVAL' && <div className="offer-approval__banner offer-approval__banner--pending" role="status">
        <span className="offer-approval__badge">SIGNATURE REQUIRED</span>
        <p>{item.canApprove
          ? 'This document is signed by you as the company admin. Approving it applies your signature from My HR Profile.'
          : `Only ${item.signatoryName || 'the company admin'} can approve or reject this document - approving it applies their signature.`}</p>
      </div>}
      {item.canApprove ? <div className="offer-approvals__decision">
        <Button type="button" loading={working} disabled={working} onClick={approve}>{item.requiresAuthorizedSignature ? 'Approve & Sign' : `Approve ${noun(item)}`}</Button>
        <Button type="button" variant="secondary" className="offer-approvals__reject-btn" disabled={working} onClick={() => setRejectOpen(true)}>Reject {noun(item)}</Button>
      </div> : <div className="offer-approvals__decision">
        <Button type="button" variant="secondary" onClick={() => navigate(BASE)}>Back to Approvals</Button>
      </div>}
    </section></div>}
    <Modal isOpen={rejectOpen} title={`Reject ${noun(item)}`} onClose={() => { if (!working) setRejectOpen(false); }}
      footer={<div className="document-requests__reject-actions">
        <Button type="button" variant="secondary" disabled={working} onClick={() => setRejectOpen(false)}>Cancel</Button>
        <Button type="button" className="offer-approvals__reject-btn" disabled={!reason.trim()} loading={working} onClick={reject}>Reject {noun(item)}</Button>
      </div>}>
      <div className="document-requests__reject-modal">
        <label htmlFor="offer-letter-rejection-reason">Reason for rejection <b>*</b>
          <textarea id="offer-letter-rejection-reason" maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)}
            placeholder="For example: The compensation does not match the approved offer." />
          <small>{reason.length}/1000</small>
        </label>
        <p>HR will see this reason and can correct the {item && !isOffer(item) ? 'document' : 'offer letter'} and submit it again.</p>
      </div>
    </Modal>
  </MyHrFeaturePage>;
}
