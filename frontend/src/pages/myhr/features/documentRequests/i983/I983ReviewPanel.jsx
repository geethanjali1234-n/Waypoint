import { RouteLine } from '../../../../../components';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, FileUpload, Modal, StatusBadge, ValidationMessage } from '../../../../../components';
import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';
import {
  I983_HISTORY_LABELS, I983_STATUS_LABELS, I983_VERSION_LABELS, NO_SIGNATURE_MESSAGE, openI983Document,
} from './i983Workflow';
import EmployeeI983SignSection from './EmployeeI983SignSection';
import I983DocumentPreview from './I983DocumentPreview';
import './i983.css';

const MAX_PDF_BYTES = 10 * 1024 * 1024;
const isPdf = (file) => file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
const dateTime = (value) => (value ? new Date(value).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—');
const errorMessage = (failure, fallback) => failure?.response?.data?.message || fallback;
const statusLabel = (status) => I983_STATUS_LABELS[status] || status;

/**
 * Signed I-983 section of the document request detail page.
 *
 * Reviewers (the assigned HR or any Company Admin) get the action bar
 * View | Download Original | Upload/Replace | Add Signature | Preview |
 * Download Signed | Approve | Return | Reject, the version history and the
 * audit trail. The employee sees their submission, the reason if it was
 * returned (with a corrected upload + resubmit), and the signed I-983 once
 * it is approved.
 */
export default function I983ReviewPanel({ requestId, base, onChanged }) {
  const navigate = useNavigate();
  const [workflow, setWorkflow] = useState(null);
  const [signature, setSignature] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState('');
  const [reasonAction, setReasonAction] = useState(null);
  const [reason, setReason] = useState('');
  const [correctedFile, setCorrectedFile] = useState(null);
  const [resubmitSignature, setResubmitSignature] = useState(null);
  const replaceInputRef = useRef(null);

  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    myHrDocumentRequestApi.getI983(requestId)
      .then((response) => {
        if (!active) return;
        const data = response.data.data;
        setWorkflow(data);
        if (data.canReview) {
          myHrDocumentRequestApi.getI983Signature(requestId)
            .then((signatureResponse) => { if (active) setSignature(signatureResponse.data.data); })
            .catch(() => { if (active) setSignature(null); });
        }
      })
      .catch((failure) => { if (active) setError(errorMessage(failure, 'The I-983 could not be loaded.')); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [requestId]);

  const run = async (key, action, successMessage) => {
    setBusy(key); setError(''); setNotice('');
    try {
      const data = (await action()).data.data;
      setWorkflow(data);
      if (data?.request) onChanged?.(data.request);
      setNotice(successMessage);
      return true;
    } catch (failure) {
      setError(errorMessage(failure, 'That action could not be completed.'));
      return false;
    } finally { setBusy(''); }
  };

  const open = async (documentId, download) => {
    setError('');
    try { await openI983Document(requestId, documentId, download); } catch { setError('The document could not be opened.'); }
  };

  const validPdf = (file, what) => {
    if (!isPdf(file)) { setError(`Upload ${what} as a PDF file.`); return false; }
    if (file.size > MAX_PDF_BYTES) { setError(`${what[0].toUpperCase()}${what.slice(1)} must be 10 MB or smaller.`); return false; }
    return true;
  };

  const replace = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !validPdf(file, 'the replacement')) return;
    await run('replace', () => myHrDocumentRequestApi.replaceI983(requestId, file),
      'Replacement uploaded. Apply the organization signature to the new version before approving.');
  };

  const submitReason = async () => {
    const call = reasonAction === 'return' ? myHrDocumentRequestApi.returnI983 : myHrDocumentRequestApi.rejectI983;
    const ok = await run(reasonAction, () => call(requestId, reason.trim()),
      reasonAction === 'return' ? 'Returned to the employee for correction.' : 'The Signed I-983 request was rejected.');
    if (ok) { setReasonAction(null); setReason(''); }
  };
  const closeReason = () => { if (!busy) { setReasonAction(null); setReason(''); } };

  const selectCorrected = (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !validPdf(file, 'the corrected I-983')) return;
    setError(''); setCorrectedFile(file);
  };

  const resubmit = async () => {
    const ok = await run('resubmit', () => myHrDocumentRequestApi.resubmitI983(requestId, correctedFile, resubmitSignature),
      'Your I-983 was resubmitted for HR/Admin review.');
    if (ok) { setCorrectedFile(null); setResubmitSignature(null); }
  };

  if (loading) return <p role="status">Loading I-983…</p>;
  if (!workflow) return error ? <ValidationMessage message={error} variant="error" /> : null;

  const status = workflow.request?.status;
  const reviewer = workflow.viewerIsReviewer;
  const hasSigned = Boolean(workflow.signedDocumentId);
  const noSignature = workflow.canReview && signature && !signature.available;
  let approveBlocker = null;
  if (workflow.canReview && !hasSigned) approveBlocker = 'Apply the organization signature before approving.';
  else if (workflow.canReview && !workflow.signedIsCurrent) approveBlocker = 'The document changed after it was signed - sign the current version before approving.';

  return (
    <section className="i983-panel" aria-label="Signed I-983">
      <header className="i983-panel__header">
        <div>
          <h3>Signed I-983</h3>
          {workflow.submittedAt && <p>Submitted {dateTime(workflow.submittedAt)}</p>}
        </div>
        <StatusBadge status={status === 'APPROVED' ? 'approved' : status === 'REJECTED' ? 'rejected' : 'pending'} label={statusLabel(status)} />
      </header>

      {error && <ValidationMessage message={error} variant="error" />}
      {notice && <p className="i983-panel__notice" role="status">{notice}</p>}
      {noSignature && <ValidationMessage message={signature.message || NO_SIGNATURE_MESSAGE} variant="error" />}

      <div className="i983-panel__workspace">
        <aside className="i983-panel__progress" aria-label="Document workflow progress">
          <h4>Request progress</h4>
          <RouteLine orientation="vertical" label="I-983 workflow" steps={[
            { label: 'Employee submitted', sublabel: workflow.submittedAt ? dateTime(workflow.submittedAt) : 'Document received', state: 'done' },
            ...(status === 'RETURNED_FOR_CORRECTION' || status === 'REJECTED'
              ? [{ label: status === 'REJECTED' ? 'Review rejection' : 'Correct and resubmit', state: 'current' }]
              : [...(hasSigned && workflow.signedIsCurrent ? [{ label: 'Organization signed', state: 'done' }] : status === 'APPROVED' ? [] : [{ label: 'Review and sign', state: 'current' }]),
                 { label: status === 'APPROVED' ? 'Approved' : 'Review for approval', state: status === 'APPROVED' ? 'done' : hasSigned && workflow.signedIsCurrent ? 'current' : 'upcoming' }]),
          ]} />
        </aside>
        <div className="i983-panel__review-content">
          {reviewer && <I983DocumentPreview requestId={requestId} documentId={workflow.currentDocumentId} />}
      {reviewer ? <>
        <div className="i983-panel__toolbar" role="toolbar" aria-label="I-983 actions">
          <Button type="button" variant="secondary" onClick={() => open(workflow.currentDocumentId, false)}>View</Button>
          <Button type="button" variant="secondary" onClick={() => open(workflow.originalDocumentId, true)}>Download Original</Button>
          <Button type="button" variant="secondary" disabled={!workflow.canReview} loading={busy === 'replace'}
            onClick={() => replaceInputRef.current?.click()}>Upload/Replace</Button>
          <input ref={replaceInputRef} type="file" accept="application/pdf,.pdf" className="i983-panel__file-input"
            aria-label="Replacement I-983 file" tabIndex={-1} onChange={replace} />
          <Button type="button" variant="secondary" disabled={!workflow.canReview}
            onClick={() => navigate(`${base}/document-requests/${requestId}/i983-sign`)}>Add Signature</Button>
          <Button type="button" variant="secondary" disabled={!hasSigned} onClick={() => open(workflow.signedDocumentId, false)}>Preview</Button>
          <Button type="button" variant="secondary" disabled={!hasSigned} onClick={() => open(workflow.signedDocumentId, true)}>Download Signed</Button>
          <span className="i983-panel__toolbar-divider" aria-hidden="true" />
          <Button type="button" disabled={!workflow.canReview || Boolean(approveBlocker)} loading={busy === 'approve'}
            onClick={() => run('approve', () => myHrDocumentRequestApi.approveI983(requestId), 'The Signed I-983 was approved. The employee can now view and download it.')}>Approve</Button>
          <Button type="button" variant="secondary" disabled={!workflow.canReview} onClick={() => setReasonAction('return')}>Return</Button>
          <Button type="button" variant="secondary" className="i983-panel__reject" disabled={!workflow.canReview} onClick={() => setReasonAction('reject')}>Reject</Button>
        </div>
        {approveBlocker && <p className="i983-panel__hint">{approveBlocker}</p>}
        {!workflow.canReview && <p className="i983-panel__hint">This request is {statusLabel(status).toLowerCase()} - review actions are no longer available.</p>}
      </> : (
        <EmployeeView workflow={workflow} status={status} open={open} correctedFile={correctedFile}
          selectCorrected={selectCorrected} clearCorrected={() => setCorrectedFile(null)} resubmit={resubmit} busy={busy}
          signature={resubmitSignature} setSignature={setResubmitSignature} profilePath={`${base}/profile`} />
      )}

      {workflow.returnReason && reviewer && <p className="i983-panel__reason"><strong>Returned for correction:</strong> {workflow.returnReason}</p>}

      {reviewer && <div className="i983-panel__block">
        <h4>Document versions</h4>
        <div className="i983-panel__table-wrap">
          <table>
            <thead><tr><th>Version</th><th>Type</th><th>File</th><th>Uploaded by</th><th>Date</th><th>Actions</th></tr></thead>
            <tbody>{workflow.versions.map((version) => (
              <tr key={version.documentId}>
                <td>v{version.version}{version.documentId === workflow.currentDocumentId ? ' (current)' : ''}</td>
                <td>{I983_VERSION_LABELS[version.kind] || version.kind}{version.kind === 'SIGNED' && version.signatoryName
                  ? <small>{version.signatoryName}{version.signatoryTitle ? `, ${version.signatoryTitle}` : ''} · page {version.signaturePage}</small> : null}</td>
                <td>{version.fileName}</td>
                <td>{version.uploadedByName || '—'}</td>
                <td>{dateTime(version.uploadedAt)}</td>
                <td className="i983-panel__row-actions">
                  <Button type="button" variant="secondary" onClick={() => open(version.documentId, false)}>View</Button>
                  <Button type="button" variant="secondary" onClick={() => open(version.documentId, true)}>Download</Button>
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </div>}

      <div className="i983-panel__block">
        <h4>Activity</h4>
        <ol className="i983-panel__history">
          {workflow.history.map((entry, index) => (
            <li key={`${entry.at}-${index}`}>
              <span className="i983-panel__history-action">{I983_HISTORY_LABELS[entry.action] || entry.action}</span>
              <span className="i983-panel__history-meta">{entry.actorName || 'System'} · {dateTime(entry.at)}</span>
              {entry.detail && <span className="i983-panel__history-detail">{entry.detail}</span>}
            </li>
          ))}
        </ol>
      </div>

        </div>
      </div>

      <Modal isOpen={Boolean(reasonAction)} title={reasonAction === 'return' ? 'Return for correction' : 'Reject Signed I-983'} onClose={closeReason}
        footer={<div className="document-requests__reject-actions">
          <Button type="button" variant="secondary" disabled={Boolean(busy)} onClick={closeReason}>Cancel</Button>
          <Button type="button" className={reasonAction === 'reject' ? 'document-requests__reject-confirm' : undefined}
            disabled={!reason.trim()} loading={busy === reasonAction} onClick={submitReason}>{reasonAction === 'return' ? 'Return to employee' : 'Reject request'}</Button>
        </div>}>
        <div className="document-requests__reject-modal">
          <label htmlFor="i983-reason">{reasonAction === 'return' ? 'What needs to be corrected?' : 'Reason for rejection'} <b>*</b>
            <textarea id="i983-reason" maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)}
              placeholder={reasonAction === 'return' ? 'For example: Section 5 (training goals) is incomplete.' : 'For example: The employee is not eligible for a STEM OPT extension.'} />
            <small>{reason.length}/1000</small>
          </label>
        </div>
      </Modal>
    </section>
  );
}

function EmployeeView({ workflow, status, open, correctedFile, selectCorrected, clearCorrected, resubmit, busy, signature, setSignature, profilePath }) {
  const approved = status === 'APPROVED' && workflow.signedDocumentId;
  return <>
    <div className="i983-panel__documents">
      <div>
        <span>Your submitted I-983</span>
        <div className="i983-panel__row-actions">
          <Button type="button" variant="secondary" onClick={() => open(workflow.currentDocumentId, false)}>View</Button>
          <Button type="button" variant="secondary" onClick={() => open(workflow.currentDocumentId, true)}>Download</Button>
        </div>
      </div>
      {approved && <div>
        <span>Completed signed I-983</span>
        <div className="i983-panel__row-actions">
          <Button type="button" variant="secondary" onClick={() => open(workflow.signedDocumentId, false)}>View</Button>
          <Button type="button" onClick={() => open(workflow.signedDocumentId, true)}>Download Signed I-983</Button>
        </div>
      </div>}
    </div>
    {status === 'SUBMITTED' && <p className="i983-panel__hint">HR/Admin are reviewing your I-983. You can't change it unless they return it for correction.</p>}
    {status === 'RETURNED_FOR_CORRECTION' && <div className="i983-panel__correction">
      <p className="i983-panel__reason"><strong>Returned for correction:</strong> {workflow.returnReason}</p>
      <FileUpload name="i983-corrected" label="Corrected I-983 (optional)" accept="application/pdf,.pdf"
        fileName={correctedFile?.name} downloadFile={correctedFile} helperText="PDF only, up to 10 MB. Leave empty to resubmit your current document."
        onUpload={selectCorrected} onReplace={selectCorrected} onRemove={clearCorrected} removeLabel="Remove corrected I-983" />
      <div className="i983-panel__correction-sign">
        <strong>Sign Document (optional)</strong>
        <EmployeeI983SignSection profilePath={profilePath} placements={signature} onPlacementsChange={setSignature}
          source={correctedFile || { requestId: workflow.request?.id, documentId: workflow.originalDocumentId }} />
      </div>
      <Button type="button" loading={busy === 'resubmit'} onClick={resubmit}>Resubmit</Button>
    </div>}
  </>;
}
