import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { renderAsync } from 'docx-preview';
import { Button, Dropdown, EmptyState, Modal, SearchInput, FilterPills, StatusBadge as StatusTag, DocumentViewerModal } from '../../../../components';
import { getMyHrSession } from '../../../../auth/myHrSession';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';
import { formatDateOnly } from '../../../../utils/dateFormat';
import { formatDisplayLabel } from '../../../../utils/myHrDisplayLabel';
import { isMobileDevice } from '../../../../utils/deviceDetection';
import MyHrFeaturePage from '../MyHrFeaturePage';
import MyHrPagination from '../MyHrPagination';
import DocumentRoute from '../../components/DocumentRoute';
import Table from '../../components/WorkspaceDataTable';
import StrokeIcon from '../../../../components/global/StrokeIcon';
import { getDocumentWorkflowType } from './documentWorkflowTypes';
import { DOCUMENT_CATEGORY_OPTIONS, documentCategoryLabel } from './documentCategories';
import { getWorkflowState, setWorkflowState } from './documentPreparation/documentWorkflowState';
import SignedDocumentReviewPanel from './documentPreparation/SignedDocumentReviewPanel';
import I983ReviewPanel from './i983/I983ReviewPanel';
import { isI983Workflow } from './i983/i983Workflow';
import { offerLetterStage, documentRequestStatus, requiresAdminApproval } from './offerLetterState';
import HrDocumentApproval from './HrDocumentApproval';
import DocumentFulfillmentEmailComposer from './DocumentFulfillmentEmailComposer';
import './documentRequests.css';

// Formats the Preview button can show in the browser (or via docx-preview).
const UPLOAD_ONLY_ACCEPT = '.pdf,.docx,.png,.jpg,.jpeg';

const documentTitle = (title) => title === 'Separation acknowledgement' ? 'Separation' : title;

const MY_REQUESTS_SCOPES = [
  { value: 'SELF', label: 'For myself', workspace: 'mine' },
  { value: 'EMPLOYEES', label: 'From employees', workspace: 'requested-employees' },
];

export default function DocumentRequestsPage({ role }) {
  const PAGE_SIZE = 10;
  const navigate = useNavigate();
  const { requestId } = useParams();
  const [searchParams] = useSearchParams();
  const session = getMyHrSession();
  const base = role === 'COMPANY_ADMIN' ? '/myhr/admin' : role === 'HR' ? '/myhr/hr' : '/myhr/employee';
  const requestedWorkspace = searchParams.get('view');
  const activeWorkspace = role === 'HR' && ['mine', 'employees', 'requested'].includes(requestedWorkspace) ? requestedWorkspace : null;
  // HR "My requests": the requests this HR made - either the ones for themselves or the ones they sent to employees.
  const myRequestsView = activeWorkspace === 'requested';
  const [myRequestsScope, setMyRequestsScope] = useState('SELF');
  // Without a view, HR lands on the requests they received (assigned to them to fulfil).
  const listWorkspace = myRequestsView ? MY_REQUESTS_SCOPES.find((scope) => scope.value === myRequestsScope).workspace
    : activeWorkspace || (role === 'HR' ? 'received' : null);
  const [items, setItems] = useState([]);
  const [selected, setSelected] = useState(null);
  const [status, setStatus] = useState('ALL'); const [priorityFilter, setPriorityFilter] = useState('ALL'); const [categoryFilter, setCategoryFilter] = useState('ALL'); const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [loadFailed, setLoadFailed] = useState(false);
  const [file, setFile] = useState(null);
  const [reason, setReason] = useState('');
  const [working, setWorking] = useState(false);
  const [rejectModalOpen, setRejectModalOpen] = useState(false);
  const [docxPreview, setDocxPreview] = useState(null);
  const [docxZoom, setDocxZoom] = useState(1);
  const [activeDocPreview, setActiveDocPreview] = useState(null);
  const docxPreviewRef = useRef(null);
  const [workflowState, setWorkflowStateValue] = useState(null);
  const [viewMode, setViewMode] = useState(() => {
    if (typeof window !== 'undefined') {
      if (isMobileDevice() || window.innerWidth <= 768) {
        return 'cards';
      }
    }
    return 'table';
  });
  const [debouncedQuery, setDebouncedQuery] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query.trim()), 250);
    return () => clearTimeout(timer);
  }, [query]);
  const load = () => {
    setLoading(true); setError(''); setLoadFailed(false);
    const request = requestId ? myHrDocumentRequestApi.get(requestId) : myHrDocumentRequestApi.list({
      page: page - 1, size: PAGE_SIZE, status, priority: priorityFilter, category: categoryFilter,
      query: debouncedQuery || undefined, workspace: listWorkspace || undefined,
    });
    request.then((response) => {
      if (requestId) { setSelected({ ...response.data.data, viewerRole: role, viewerUserId: session?.userId, status: response.data.data.status === 'FULFILLED' ? 'Completed' : response.data.data.status }); setWorkflowStateValue(getWorkflowState(requestId)); }
      else {
        // Keep array support while older servers are rolling out; the current
        // API returns a bounded page so the request table never downloads the
        // full organization worklist.
        const data = response.data.data;
        const pageData = Array.isArray(data) ? { items: data, totalPages: Math.max(1, Math.ceil(data.length / PAGE_SIZE)) } : data;
        setItems((pageData?.items || []).map((item) => ({ ...item, viewerRole: role, viewerUserId: session?.userId })));
        setTotalPages(Math.max(pageData?.totalPages || 1, 1));
        setTotalElements(Array.isArray(data) ? data.length : Number.isFinite(pageData?.totalElements) ? pageData.totalElements : Number.isFinite(pageData?.totalItems) ? pageData.totalItems : null);
      }
    }).catch((requestError) => {
      // apiClient clears an expired My HR session and notifies the protected
      // route for every authenticated 401. Do not briefly replace that
      // redirect with a misleading empty list and load error.
      if (requestError?.response?.status === 401) { setLoadFailed(true); return; }
      setLoadFailed(true);
      setError(requestId ? 'Document request could not be loaded.' : 'Document requests could not be loaded.');
    })
      .finally(() => setLoading(false));
  };
  useEffect(load, [requestId, role, session?.userId, page, status, priorityFilter, categoryFilter, debouncedQuery, listWorkspace]);
  const markCompleted = () => setWorkflowStateValue(setWorkflowState(selected.id, { stage: 'COMPLETED', completedAt: new Date().toISOString(), completedByUserId: session?.userId || null }));
  const visible = items;
  const pageItems = items;
  useEffect(() => { setPage(1); }, [status, priorityFilter, categoryFilter, query, listWorkspace]);
  const replace = (item) => { const displayItem = { ...item, viewerRole: role, viewerUserId: session?.userId, status: item.status === 'FULFILLED' ? 'Completed' : item.status }; setItems((current) => current.map((value) => value.id === item.id ? displayItem : value)); setSelected(displayItem); };
  const fulfill = async (email) => { setWorking(true); setError(''); try { const result = (await myHrDocumentRequestApi.fulfill(selected.id, file, email)).data.data; replace(result.request); setFile(null); if (result.emailDelivery?.status === 'FAILED') setError(result.emailDelivery.message); } catch (e) { setError(e?.response?.data?.message || 'Request could not be fulfilled.'); throw e; } finally { setWorking(false); } };
  const reject = async () => { setWorking(true); setError(''); try { replace((await myHrDocumentRequestApi.reject(selected.id, reason.trim())).data.data); setReason(''); setRejectModalOpen(false); } catch (e) { setError(e?.response?.data?.message || 'Request could not be rejected.'); } finally { setWorking(false); } };
  const closeRejectModal = () => { if (!working) { setRejectModalOpen(false); setReason(''); } };
  useEffect(() => {
    if (!docxPreview || !docxPreviewRef.current) return undefined;
    // Render into a shadow root so the app's global element styles (index.css
    // h1/h2/p, inherited font/colour/line-height) can't restyle the Word
    // content - only docx-preview's own generated styles apply inside it.
    const host = docxPreviewRef.current;
    const root = host.shadowRoot || host.attachShadow({ mode: 'open' });
    const reset = document.createElement('style');
    reset.textContent = ':host { all: initial; display: block; } .docx-wrapper { background: #eef2f0; padding: 20px; }';
    const target = document.createElement('div');
    root.replaceChildren(reset, target);
    let active = true;
    // experimental enables tab-stop rendering (tab-aligned dates, signature
    // lines, columns); without it tabs collapse and text runs together.
    renderAsync(docxPreview.blob, target, target, { inWrapper: true, experimental: true })
      .catch(() => { if (active) setError('Document preview could not be rendered.'); });
    return () => { active = false; root.replaceChildren(); };
  }, [docxPreview]);

  const fetchDocument = async (documentId, download) => {
    try {
      const response = await myHrDocumentRequestApi.getDocument(selected.id, documentId, download);
      const disposition = response.headers?.['content-disposition'] || '';
      const fileName = disposition.match(/filename="([^"]+)"/i)?.[1] || 'document';
      const contentType = response.data?.type || response.headers?.['content-type'] || '';
      const url = URL.createObjectURL(response.data);

      if (download) {
        const link = document.createElement('a');
        link.href = url;
        link.download = fileName;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
        return;
      }

      if (!download && contentType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') {
        setDocxZoom(1);
        setDocxPreview({ blob: response.data, fileName });
        return;
      }

      // Interactive zoomable modal for all document types (PDF, Images, DOCX)
      setActiveDocPreview({
        url,
        blob: response.data,
        fileName,
        contentType
      });
    } catch {
      setError('Document could not be opened.');
    }
  };
  const assigned = selected?.requestedForUserId === session?.userId;
  const canFulfill = selected?.status === 'PENDING' && (assigned || role === 'COMPANY_ADMIN');
  const canReject = canFulfill && ['COMPANY_ADMIN', 'HR'].includes(role);
  // A document HR delivers needs Company Admin approval first. Once submitted it is no longer PENDING, but its sender still needs the way
  // back to it (and the Admin to the approval). A Company Admin delivers directly, so only HR goes through the upload-and-approve flow.
  const approvalState = ['SUBMITTED', 'APPROVED', 'RETURNED_FOR_CORRECTION'].includes(selected?.status);
  const hrApproval = role === 'HR' && assigned && requiresAdminApproval(selected) && selected?.documentType !== 'OFFER_LETTER'
    && (selected?.status === 'PENDING' || approvalState);
  const inOfferApproval = approvalState && requiresAdminApproval(selected)
    && ((selected?.documentType === 'OFFER_LETTER' && (assigned || role === 'COMPANY_ADMIN')) || (role === 'HR' && assigned));
  const adminReview = role === 'COMPANY_ADMIN' && selected?.status === 'SUBMITTED' && requiresAdminApproval(selected);
  // Independent of backend status: once a document (e.g. Offer Letter) has
  // been genuinely sent via a real backend call, its request is already
  // FULFILLED, so canFulfill (PENDING-gated) goes false - but the sign/return
  // panels below still need to render for the assigned recipient/admin.
  const canManage = assigned || role === 'COMPANY_ADMIN';
  // Signed I-983 requests run their own upload -> sign -> approve workflow
  // (I983ReviewPanel) instead of the HR-prepared document flow.
  const isI983 = isI983Workflow(selected);
  const workflowType = isI983 ? null : getDocumentWorkflowType(selected);
  // LCA / PERM / I-140: HR or Admin uploads the existing document instead of
  // preparing one from a template - see documentWorkflowTypes.js.
  const uploadOnly = Boolean(workflowType?.uploadOnly);
  const isOffer = selected?.documentType === 'OFFER_LETTER';
  const awaitingSignatureLabel = role === 'EMPLOYEE' || listWorkspace === 'mine' ? 'Pending Signature' : 'Awaiting Employee Signature';
  const stage = isOffer ? offerLetterStage(selected) : workflowState?.stage || null;
  const responseSubmitted = Boolean(selected?.employeeSignatureDocumentId || selected?.signedResponseDocumentId);
  // The requester is the acceptance signer for every role. Managers issue
  // the offer with the organization signature; they cannot sign for someone
  // else's request.
  const isRequester = selected?.requestedByUserId === session?.userId;
  // Offer Letter's prepare page calls the real fulfill() endpoint, which
  // marks the request FULFILLED and blocks re-preparing it - "Revise &
  // Resend" only makes sense for the other sign-required types, which are
  // still frontend-only prototypes with the backend status left at PENDING.
  const reallyFulfilled = Boolean(selected?.fulfilledDocumentId);
  if (requestId) return <MyHrFeaturePage className="phase-six" title="Document Request Details" description="Review the request and available actions." backTo={`${base}/document-requests`}>
    {error && <p role="alert" className="document-requests__error">{error} <button type="button" onClick={load}>Retry</button></p>}
    {loading ? <p role="status">Loading document request…</p> : selected && <div className={isI983 ? undefined : 'document-workflow-layout'}>
      {!isI983 && <DocumentRoute request={{ ...selected, status: selected.status === 'Completed' ? 'FULFILLED' : selected.status }} nextAction={adminReview ? 'Review for approval' : canFulfill ? workflowType?.prepareLabel || 'Submit completed document' : undefined} />}
      <section className="document-requests__detail"><header><h2>{documentTitle(selected.requestTitle)}</h2></header><p>{selected.requestDescription}</p><dl><div><dt>Requested by</dt><dd>{selected.requestedByName}</dd></div><div><dt>Assigned to</dt><dd>{selected.requestedForName}</dd></div><div><dt>Status</dt><dd>{formatDisplayLabel(documentRequestStatus(selected))}</dd></div><div><dt>Priority</dt><dd>{formatDisplayLabel(selected.priority || 'NORMAL')}</dd></div><div><dt>Category</dt><dd>{documentCategoryLabel(selected.category) || formatDisplayLabel(selected.category) || '—'}</dd></div><div><dt>Required by</dt><dd>{selected.requiredBy ? formatDateOnly(selected.requiredBy) : '—'}</dd></div><div><dt>Direction</dt><dd>{formatDisplayLabel(selected.requestDirection) || '—'}</dd></div></dl>{selected.rejectionReason && <p className="document-requests__rejection"><strong>Rejection reason:</strong> {selected.rejectionReason}</p>}
      <div className="document-requests__attachments">{(selected.attachmentDocumentIds || []).map((id, index) => <div key={id}>{selected.attachmentFileNames?.[index] || `Supporting attachment ${index + 1}`} <Button variant="secondary" onClick={() => fetchDocument(id, false)}>Preview</Button> <Button variant="secondary" onClick={() => fetchDocument(id, true)}>Download</Button></div>)}{selected.fulfilledDocumentId && !isI983 && <div>{selected.signedResponseDocumentId ? (isOffer ? 'Complete offer letter with both signatures' : 'Signed document') : (isOffer ? (isRequester ? 'Offer letter awaiting your signature' : 'Offer letter awaiting requester signature') : (uploadOnly ? `${workflowType.label} document` : 'Completed document'))} <Button variant="secondary" onClick={() => fetchDocument(selected.signedResponseDocumentId || selected.fulfilledDocumentId, false)}>Preview</Button> <Button variant="secondary" onClick={() => fetchDocument(selected.signedResponseDocumentId || selected.fulfilledDocumentId, true)}>Download</Button></div>}</div>
      {hrApproval && (!workflowType || uploadOnly) && <HrDocumentApproval request={selected} label={workflowType?.label || selected.requestTitle}
        uploadOnly={uploadOnly} accept={uploadOnly ? UPLOAD_ONLY_ACCEPT : undefined}
        onStatusChange={(status) => setSelected((current) => ({ ...current, status }))}
        onDelivered={(result) => {
          replace(result.request);
          if (result.emailDelivery?.status === 'FAILED') setError(result.emailDelivery.message || 'The document was sent, but its email could not be delivered.');
        }} />}
      {adminReview && <div className="document-requests__fulfill"><div><h3>Waiting for your approval</h3><p>HR submitted this {selected.documentType === 'OFFER_LETTER' ? 'offer letter' : 'document'} for your approval before it is sent to {selected.requestedByName}.</p></div><Button onClick={() => navigate(`/myhr/admin/offer-letter-approvals/${selected.id}`)}>Review for Approval</Button></div>}
      {canFulfill && !workflowType && !hrApproval && <div className="document-requests__fulfill"><label>Completed document<input type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} /></label>{file && (role === 'EMPLOYEE' ? <Button loading={working} onClick={() => fulfill(null)}>Send</Button> : <DocumentFulfillmentEmailComposer requestId={selected.id} attachmentFileName={file.name} sendLabel="Send to Employee" onSend={fulfill} />)}</div>}
      {canFulfill && uploadOnly && !hrApproval && <div className="document-requests__fulfill"><div><h3>{workflowType.prepareLabel}</h3><p>Upload the {workflowType.label} for {selected.requestedByName}. Once uploaded, they can view and download it, and this request is completed.</p></div><label>{workflowType.label} document<input type="file" accept={UPLOAD_ONLY_ACCEPT} onChange={(e) => setFile(e.target.files?.[0] || null)} /></label>{file && <DocumentFulfillmentEmailComposer requestId={selected.id} attachmentFileName={file.name} sendLabel="Upload & Share" onSend={fulfill} />}</div>}
      {uploadOnly && isRequester && !canFulfill && selected.status === 'PENDING' && <div className="document-requests__fulfill"><StatusTag status="Pending" tone="info" /><p>Your HR or company admin will upload your {workflowType.label} here. You'll be able to view and download it once it's available.</p></div>}
      {(canFulfill || inOfferApproval) && workflowType && !uploadOnly && !stage && <div className="document-requests__fulfill document-requests__fulfill--offer-letter"><div><h3>{workflowType.prepareLabel}</h3><p>{inOfferApproval ? `This ${workflowType.label.toLowerCase()} for ${selected.requestedByName} is waiting on Company Admin approval. Open it to see its approval status or send it once approved.` : `Build the ${workflowType.label.toLowerCase()} for ${selected.requestedByName} using a saved template, then submit it for Company Admin approval.`}</p></div><Button onClick={() => navigate(`${base}/document-requests/${selected.id}/${workflowType.routeSegment}`)}>{inOfferApproval ? `Open ${workflowType.label}` : workflowType.prepareLabel}</Button></div>}
      {canManage && workflowType && stage === 'SENT_TO_EMPLOYEE' && <div className="document-requests__fulfill document-requests__fulfill--offer-letter"><div><h3>{workflowType.label} sent</h3><p>{(isOffer || workflowState?.requiresEmployeeSignature) ? `Sent to ${selected.requestedByName} - awaiting their signature and signed return.` : `Sent to ${selected.requestedByName}.`}</p></div>{!reallyFulfilled && <Button variant="secondary" onClick={() => navigate(`${base}/document-requests/${selected.id}/${workflowType.routeSegment}`)}>Revise &amp; Resend</Button>}</div>}
      {!isOffer && canManage && workflowType && stage !== 'COMPLETED' && (stage === 'SIGNED_RETURNED' || responseSubmitted) && <SignedDocumentReviewPanel request={selected} workflowType={workflowType} workflowState={workflowState || {}} onComplete={markCompleted} onViewOriginal={selected.signedResponseDocumentId ? () => fetchDocument(selected.signedResponseDocumentId, false) : null} />}
      {canManage && workflowType && stage === 'COMPLETED' && <div className="document-requests__fulfill"><h3>{workflowType.label} completed</h3><p>The complete signed document is available above to both HR and the employee.</p></div>}
      {isRequester && workflowType && stage === 'SENT_TO_EMPLOYEE' && (isOffer || workflowState?.requiresEmployeeSignature) && <div className="document-requests__fulfill"><div><h3>Your signature is needed</h3><p>Review, sign, and return this document to your reporting manager.</p></div><Button onClick={() => navigate(`${base}/document-requests/${selected.id}/sign`)}>Review &amp; Sign</Button></div>}
      {!isOffer && isRequester && workflowType && stage !== 'COMPLETED' && (stage === 'SIGNED_RETURNED' || responseSubmitted) && <div className="document-requests__fulfill"><StatusTag status="Signature Response Submitted" tone="info" /><p>HR will review your signature response.</p><Button variant="secondary" onClick={() => fetchDocument(selected.signedResponseDocumentId, false)}>View Submitted Response</Button></div>}
      {isRequester && workflowType && stage === 'COMPLETED' && <div className="document-requests__fulfill"><StatusTag status="Completed" tone="success" /><p>This request has been completed.</p></div>}
      {isI983 && <I983ReviewPanel requestId={selected.id} base={base} onChanged={replace} />}
      {canReject && <div className="document-requests__reject"><div><h3>Unable to complete this request?</h3><p>Explain what needs to change so the requester can act on it.</p></div><Button type="button" variant="secondary" onClick={() => setRejectModalOpen(true)}>Reject request</Button></div>}
    </section></div>}
    <Modal isOpen={rejectModalOpen} title="Reject document request" onClose={closeRejectModal} footer={<div className="document-requests__reject-actions"><Button type="button" variant="secondary" disabled={working} onClick={closeRejectModal}>Cancel</Button><Button type="button" className="document-requests__reject-confirm" disabled={!reason.trim()} loading={working} onClick={reject}>Reject request</Button></div>}>
      <div className="document-requests__reject-modal"><div className="document-requests__reject-modal-intro"><span aria-hidden="true">!</span><div><strong>Send a clear reason to {selected?.requestedByName || 'the requester'}</strong><p>They will see your note and can submit a corrected request.</p></div></div><label htmlFor="document-request-rejection-reason">Reason for rejection <b>*</b><textarea id="document-request-rejection-reason" maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="For example: Please attach the signed document before resubmitting." /><small>{reason.length}/1000</small></label></div>
    </Modal>
    <Modal
      isOpen={Boolean(docxPreview)}
      className="document-requests__docx-modal"
      title={docxPreview?.fileName || 'Document preview'}
      onClose={() => { setDocxPreview(null); setDocxZoom(1); }}
      footer={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', flexWrap: 'wrap', gap: '0.5rem' }}>
          <div className="wp-inline-zoom-toolbar" role="toolbar" aria-label="Preview zoom">
            <button
              type="button"
              className="wp-inline-zoom-btn"
              onClick={() => setDocxZoom((z) => Math.max(0.5, Math.round((z - 0.25) * 100) / 100))}
              disabled={docxZoom <= 0.5}
              title="Zoom out (-)"
              aria-label="Zoom out"
            >
              −
            </button>
            <button
              type="button"
              className="wp-inline-zoom-label"
              onClick={() => setDocxZoom(1)}
              title="Reset zoom to 100%"
              aria-label="Reset zoom to 100%"
            >
              {Math.round(docxZoom * 100)}%
            </button>
            <button
              type="button"
              className="wp-inline-zoom-btn"
              onClick={() => setDocxZoom((z) => Math.min(3.0, Math.round((z + 0.25) * 100) / 100))}
              disabled={docxZoom >= 3.0}
              title="Zoom in (+)"
              aria-label="Zoom in"
            >
              +
            </button>
          </div>
          <Button type="button" variant="secondary" onClick={() => { setDocxPreview(null); setDocxZoom(1); }}>Close</Button>
        </div>
      }
    >
      <div
        ref={docxPreviewRef}
        className="document-requests__docx-preview"
        aria-label="DOCX preview"
        style={{
          transform: `scale(${docxZoom})`,
          transformOrigin: 'top center',
          transition: 'transform 0.15s ease'
        }}
      />
    </Modal>
    <DocumentViewerModal
      isOpen={Boolean(activeDocPreview)}
      onClose={() => {
        if (activeDocPreview?.url) URL.revokeObjectURL(activeDocPreview.url);
        setActiveDocPreview(null);
      }}
      file={activeDocPreview}
      onDownload={() => {
        if (activeDocPreview?.url) {
          const link = document.createElement('a');
          link.href = activeDocPreview.url;
          link.download = activeDocPreview.fileName;
          link.click();
        }
      }}
    />
  </MyHrFeaturePage>;
  return <MyHrFeaturePage className="phase-six"
    title={myRequestsView ? 'My Requests' : 'Document Requests'}
    description={myRequestsView
      ? 'Track the documents you requested for yourself and the documents you requested from your employees.'
      : role === 'HR' && !activeWorkspace ? 'Document requests you received. Fulfill or reject them here.'
      : 'Request, track, fulfill, and reject employee document requests.'}
    fullWidth
    backTo={myRequestsView ? `${base}/document-requests` : `${base}/dashboard`}
    hideBack
  >
    <FilterPills label="Document status filters" value={status} onChange={setStatus} options={[
      { value: 'ALL', label: 'All' }, { value: 'PENDING', label: 'Pending' }, { value: 'FULFILLED', label: 'Completed' }, { value: 'REJECTED', label: 'Rejected' },
    ]} />
    <div className="document-requests__toolbar">
      <div className="document-requests__filters">
        <div className="wp-field document-requests__search-field"><label htmlFor="documentSearch" className="wp-form-label">Search</label><SearchInput id="documentSearch" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Document or person" /></div>
        <Dropdown name="documentRequestStatus" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: 'ALL', label: 'All' }, { value: 'PENDING', label: 'Pending' }, { value: 'SUBMITTED', label: 'Submitted' }, { value: 'RETURNED_FOR_CORRECTION', label: 'Returned for Correction' }, { value: 'APPROVED', label: 'Approved' }, { value: 'AWAITING_SIGNATURE', label: awaitingSignatureLabel }, { value: 'FULFILLED', label: 'Completed' }, { value: 'REJECTED', label: 'Rejected' }]} />
        <Dropdown name="documentRequestPriority" label="Priority" value={priorityFilter} onChange={(e) => setPriorityFilter(e.target.value)} options={[{ value: 'ALL', label: 'All' }, { value: 'NORMAL', label: 'Normal' }, { value: 'HIGH', label: 'High' }, { value: 'URGENT', label: 'Urgent' }]} />
        <Dropdown name="documentRequestCategory" label="Category" value={categoryFilter} onChange={(e) => setCategoryFilter(e.target.value)} options={[{ value: 'ALL', label: 'All' }, ...DOCUMENT_CATEGORY_OPTIONS]} />
        <Button type="button" variant="secondary" className="document-requests__clear" onClick={() => { setStatus('ALL'); setPriorityFilter('ALL'); setCategoryFilter('ALL'); setQuery(''); }} disabled={status === 'ALL' && priorityFilter === 'ALL' && categoryFilter === 'ALL' && !query}>Clear filters</Button>
        <div className="document-requests__view-toggle" role="group" aria-label="View format">
          <button
            type="button"
            className={`document-requests__view-toggle-btn ${viewMode === 'table' ? 'is-active' : ''}`}
            onClick={() => setViewMode('table')}
            aria-pressed={viewMode === 'table'}
            title="Table View"
          >
            <StrokeIcon name="table" size={20} /> Table
          </button>
          <button
            type="button"
            className={`document-requests__view-toggle-btn ${viewMode === 'cards' ? 'is-active' : ''}`}
            onClick={() => setViewMode('cards')}
            aria-pressed={viewMode === 'cards'}
            title="Cards View"
          >
            <StrokeIcon name="cards" size={20} /> Cards
          </button>
        </div>
        {/* HR: the requests they received (default) or the requests they made. */}
        {role === 'HR' && <div className="document-requests__view-toggle document-requests__my-requests" role="group" aria-label="Requests to show">
          {[{ label: 'Received requests', active: !myRequestsView, path: `${base}/document-requests` },
            { label: 'My requests', active: myRequestsView, path: `${base}/document-requests?view=requested` }].map((tab) => (
            <button key={tab.label} type="button" className={`document-requests__view-toggle-btn ${tab.active ? 'is-active' : ''}`}
              aria-pressed={tab.active} onClick={() => { if (!tab.active) navigate(tab.path); }}>{tab.label}</button>
          ))}
        </div>}
        <div className="document-requests__actions">
          {role === 'COMPANY_ADMIN' && <Button variant="secondary" onClick={() => navigate('/myhr/admin/offer-letter-approvals')}>Document Approvals</Button>}
          {role === 'HR' && (
            <>
              {activeWorkspace === 'employees' && (
                <Button onClick={() => navigate(`${base}/document-requests/new`)}>Request from employee</Button>
              )}
              {activeWorkspace === 'mine' && (
                <Button onClick={() => navigate(`${base}/document-requests/request-from-manager`)}>Request for myself</Button>
              )}
            </>
          )}
          {role === 'COMPANY_ADMIN' && <Button onClick={() => navigate(`${base}/document-requests/new`)}>Request from employee</Button>}
          {role === 'EMPLOYEE' && <Button onClick={() => navigate(`${base}/document-requests/new`)}>Request a document</Button>}
        </div>
      </div>
    </div>
    {myRequestsView && <div className="document-requests__scope">
      <div className="document-requests__view-toggle" role="group" aria-label="Show my requests">
        {MY_REQUESTS_SCOPES.map((scope) => <button key={scope.value} type="button"
          className={`document-requests__view-toggle-btn ${myRequestsScope === scope.value ? 'is-active' : ''}`}
          aria-pressed={myRequestsScope === scope.value} onClick={() => setMyRequestsScope(scope.value)}>{scope.label}</button>)}
      </div>
      <p>{myRequestsScope === 'SELF' ? 'Documents you requested for yourself from your reporting manager.'
        : 'Documents you asked your employees to provide.'}</p>
      {myRequestsScope === 'SELF'
        ? <Button className="document-requests__scope-action" onClick={() => navigate(`${base}/document-requests/request-from-manager`)}>Request for myself</Button>
        : <Button className="document-requests__scope-action" onClick={() => navigate(`${base}/document-requests/new`)}>Request from employee</Button>}
    </div>}
    {error && <p role="alert" className="document-requests__error">{error} <button type="button" onClick={load}>Retry</button></p>}
    {loading ? <p role="status">Loading document requests…</p> : !loadFailed && (visible.length === 0 ? <EmptyState title={status !== 'ALL' || priorityFilter !== 'ALL' || categoryFilter !== 'ALL' || query ? 'No matching document requests.'
      : myRequestsView ? (myRequestsScope === 'SELF' ? 'You have not requested any documents for yourself yet.'
        : 'You have not requested any documents from employees yet.')
      : 'No document requests available.'} /> : <>
      {viewMode === 'cards' ? (
        <div className="document-requests__cards-grid">
          {pageItems.map((item) => (
            <div key={item.id} className="document-requests__card">
              <div className="document-requests__card-header">
                <div>
                  <span className="document-requests__card-kicker">{documentCategoryLabel(item.category) || formatDisplayLabel(item.category) || 'DOCUMENT'}</span>
                  <strong className="document-requests__card-title">{documentTitle(item.requestTitle)}</strong>
                </div>
                <StatusTag status={formatDisplayLabel(documentRequestStatus(item))} />
              </div>
              <div className="document-requests__card-body">
                {!myRequestsView && <div className="document-requests__card-row">
                  <span className="document-requests__card-label">Requested by</span>
                  <span>{item.requestedByName || '—'}</span>
                </div>}
                <div className="document-requests__card-row">
                  <span className="document-requests__card-label">{myRequestsView ? 'Requested from' : 'Assigned to'}</span>
                  <span>{item.requestedForName || '—'}</span>
                </div>
                <div className="document-requests__card-row">
                  <span className="document-requests__card-label">Priority</span>
                  <span>{formatDisplayLabel(item.priority || 'NORMAL')}</span>
                </div>
                <div className="document-requests__card-row">
                  <span className="document-requests__card-label">Required by</span>
                  <span>{item.requiredBy ? formatDateOnly(item.requiredBy) : '—'}</span>
                </div>
              </div>
              <div className="document-requests__card-footer">
                <Button variant="secondary" onClick={() => navigate(`${base}/document-requests/${item.id}`)}>
                  View
                </Button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <Table label="Document requests" showFooter={totalElements !== null} pagination={totalElements !== null ? { page, pageSize: PAGE_SIZE, total: totalElements, onPageChange: setPage } : undefined} columns={[
          { key: 'requestTitle', header: 'Document' },
          ...(!myRequestsView ? [{ key: 'requestedByName', header: 'Requested by' }] : []),
          { key: 'requestedForName', header: myRequestsView ? 'Requested from' : 'Assigned to' },
          { key: 'category', header: 'Category' }, { key: 'priority', header: 'Priority' },
          { key: 'requiredBy', header: 'Required by' }, { key: 'requestedAt', header: 'Requested on' },
          { key: 'status', header: 'Status' }, { key: 'action', header: 'Action' },
        ]} rows={pageItems} renderCell={(column, item) => {
          if (column.key === 'requestTitle') return documentTitle(item.requestTitle);
          if (column.key === 'category') return documentCategoryLabel(item.category) || formatDisplayLabel(item.category) || '—';
          if (column.key === 'priority') return formatDisplayLabel(item.priority || 'NORMAL');
          if (['requiredBy', 'requestedAt'].includes(column.key)) return item[column.key] ? formatDateOnly(item[column.key]) : '—';
          if (column.key === 'status') return <StatusTag status={item.status} label={formatDisplayLabel(documentRequestStatus(item))} />;
          if (column.key === 'action') return <Button variant="secondary" onClick={() => navigate(`${base}/document-requests/${item.id}`)}>View</Button>;
          return item[column.key];
        }} />
      )}
      {(viewMode === 'cards' || totalElements === null) && totalPages > 1 && <MyHrPagination currentPage={page} totalPages={totalPages} onPageChange={setPage} />}
    </>)}
  </MyHrFeaturePage>;
}
