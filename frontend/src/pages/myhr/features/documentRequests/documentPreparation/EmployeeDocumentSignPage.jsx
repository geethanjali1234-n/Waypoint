import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, FileUpload, StatusTag } from '../../../../../components';
import { getMyHrSession } from '../../../../../auth/myHrSession';
import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../../../api/myHrEmployeeApi';
import resolveFileUrl from '../../../../../api/resolveFileUrl';
import { formatDateOnly } from '../../../../../utils/dateFormat';
import MyHrFeaturePage from '../../MyHrFeaturePage';
import DocumentRoute from '../../../components/DocumentRoute';
import SignatureField from '../SignatureField';
import { getDocumentWorkflowType } from '../documentWorkflowTypes';
import { getWorkflowState, setWorkflowState } from './documentWorkflowState';
import PreviewBlocks from './PreviewBlocks';
import { offerLetterStage } from '../offerLetterState';
import { extractSignatureFromImage, formatSignatureDetectionDiagnostics, getLastSignatureDetectionDiagnostics } from '../../signatureImageProcessing';
import SignatureRegionSelector from '../../SignatureRegionSelector';
import '../documentRequests.css';

async function signatureFile(dataUrl) {
  const response = await fetch(dataUrl);
  return new File([await response.blob()], 'employee-signature.png', { type: 'image/png' });
}

export default function EmployeeDocumentSignPage({ role = 'EMPLOYEE' }) {
  const navigate = useNavigate();
  const { requestId } = useParams();
  const session = getMyHrSession();
  const base = role === 'COMPANY_ADMIN' ? '/myhr/admin' : role === 'HR' ? '/myhr/hr' : '/myhr/employee';
  const detailPath = `${base}/document-requests/${requestId}`;

  const [request, setRequest] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [workflowState, setWorkflowStateValue] = useState(null);
  const [signature, setSignature] = useState('');
  const profileSignatureSelected = useRef(false);
  // Upload Image only: the extracted signature is never used directly - the
  // employee must explicitly confirm it (or discard it and try a different
  // photo) before it becomes `signature`. Draw and Type are unaffected; they
  // still call onChange -> setSignature directly, with no confirm step.
  const [pendingSignature, setPendingSignature] = useState(null);
  // Kept only so "Adjust Selection" can re-run extraction against the same
  // original photo with a manually chosen region - never sent anywhere and
  // never becomes the signature itself.
  const [pendingSourceFile, setPendingSourceFile] = useState(null);
  const [selectingRegion, setSelectingRegion] = useState(false);
  const [signedFile, setSignedFile] = useState(null);
  const [submitError, setSubmitError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setLoading(true); setLoadError('');
    myHrDocumentRequestApi.get(requestId).then((response) => {
      setRequest(response.data.data);
      setWorkflowStateValue(getWorkflowState(requestId));
    }).catch(() => setLoadError('This request could not be loaded.'))
      .finally(() => setLoading(false));
  }, [requestId]);

  // A profile signature is the default for this request, but the signer can
  // still replace it with any of the three capture methods below.
  useEffect(() => {
    let active = true;
    myHrEmployeeApi.getMine()
      .then((response) => {
        const savedSignatureUrl = response?.data?.data?.signatureUrl;
        if (active && savedSignatureUrl && !profileSignatureSelected.current) {
          setSignature(resolveFileUrl(savedSignatureUrl));
        }
      })
      .catch(() => {});
    return () => { active = false; };
  }, []);

  if (loading) return <MyHrFeaturePage className="phase-six" title="Review & Sign" backTo={detailPath}><p role="status">Loading request…</p></MyHrFeaturePage>;
  if (loadError || !request) return <MyHrFeaturePage className="phase-six" title="Review & Sign" backTo={detailPath}><p role="alert" className="document-requests__error">{loadError || 'Request could not be loaded.'}</p></MyHrFeaturePage>;

  const workflowType = getDocumentWorkflowType(request);
  const isOffer = request.documentType === 'OFFER_LETTER';
  const isRequester = request.requestedByUserId === session?.userId;
  const stage = isOffer ? offerLetterStage(request) : workflowState?.stage || null;
  const responseSubmitted = Boolean(request.employeeSignatureDocumentId || request.signedResponseDocumentId);
  const nothingToSign = !responseSubmitted && (!isRequester || (isOffer ? !request.fulfilledDocumentId : (!workflowType || !workflowState || stage === null || !workflowState.requiresEmployeeSignature)));
  const alreadySubmitted = stage === 'SIGNED_RETURNED' || stage === 'COMPLETED' || responseSubmitted;

  // Only the extracted ink - not the uploaded photo/scan itself - becomes the
  // signature: extractSignatureFromImage strips the paper/background and
  // crops to the signature. The result is staged in pendingSignature, not
  // signature, until the employee explicitly confirms it below - so the rest
  // of this page (preview, PDF embedding via submitSignedResponse) never
  // sees the original image, and never sees an unconfirmed extraction either.
  const onUploadSignatureImage = async (event) => {
    const file = event.target.files?.[0];
    // Reset the input immediately (before any early return) so selecting the
    // exact same file again still fires a change event next time - the
    // browser otherwise treats an unchanged input value as a no-op and stays
    // completely silent, with no error and no state change of any kind.
    event.target.value = '';
    if (!file) return;
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 2 * 1024 * 1024) { setSubmitError('Use a PNG or JPEG signature no larger than 2 MB.'); return; }
    setSignedFile(null); setSubmitError(''); setPendingSignature(null); setPendingSourceFile(file); setSelectingRegion(false);
    try {
      const extracted = await extractSignatureFromImage(file);
      if (!extracted) {
        const details = formatSignatureDetectionDiagnostics(getLastSignatureDetectionDiagnostics());
        setSubmitError(`Couldn't detect a signature in that image. Try a clearer photo with the signature standing out from its background, or use Draw or Type instead.${details ? ` (${details})` : ''}`);
        return;
      }
      setPendingSignature(extracted);
    } catch { setSubmitError('That image could not be processed. Try a different file, or use Draw or Type instead.'); }
  };
  const confirmUploadedSignature = () => { profileSignatureSelected.current = true; setSignature(pendingSignature); setPendingSignature(null); setPendingSourceFile(null); };
  const rejectUploadedSignature = () => { setPendingSignature(null); setPendingSourceFile(null); setSelectingRegion(false); };
  // Fallback for a photo with more than one dark region (stray marks,
  // printed text, a second signature) where automatic detection can't
  // reliably tell which one is the real signature - the employee draws a box
  // around it instead of the system guessing.
  const useSelectedRegion = async (region) => {
    setSelectingRegion(false); setSubmitError('');
    try {
      const extracted = await extractSignatureFromImage(pendingSourceFile, region);
      if (!extracted) {
        const details = formatSignatureDetectionDiagnostics(getLastSignatureDetectionDiagnostics());
        setSubmitError(`Couldn't detect a signature in that selection. Try selecting a tighter area around just the signature.${details ? ` (${details})` : ''}`);
        return;
      }
      setPendingSignature(extracted);
    } catch { setSubmitError('That selection could not be processed. Try again, or use Draw or Type instead.'); }
  };

  // Types with a real backend-generated document (Offer Letter, via the
  // prepare page's fulfill() call) have a real fulfilledDocumentId to view;
  // every other sign-required type is still a frontend-only prototype with
  // only the previewBlocks snapshot captured at send time.
  const viewOriginalDocument = async () => {
    try {
      const response = await myHrDocumentRequestApi.getDocument(requestId, request.fulfilledDocumentId, false);
      const url = URL.createObjectURL(response.data);
      window.open(url, '_blank', 'noopener,noreferrer');
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch { setSubmitError('Document could not be opened.'); }
  };
  const viewSubmittedResponse = async () => {
    const documentId = request.signedResponseDocumentId;
    if (!documentId) return;
    try {
      const response = await myHrDocumentRequestApi.getDocument(requestId, documentId, false);
      const url = URL.createObjectURL(response.data); window.open(url, '_blank', 'noopener,noreferrer');
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch { setSubmitError('Your submitted response could not be opened.'); }
  };

  const submit = async () => {
    if (submitting) return;
    if (!isRequester) { setSubmitError('Only the user who requested this document can sign it.'); return; }
    if (!signature && !signedFile) { setSubmitError('Choose one signing option or upload an already-signed document before submitting.'); return; }
    try {
      setSubmitError(''); setSubmitting(true);
      const response = await myHrDocumentRequestApi.submitSignedResponse(requestId, {
        signature: signature ? await signatureFile(signature) : null, signedDocument: signedFile,
      });
      setRequest(response.data.data);
      setWorkflowStateValue(setWorkflowState(requestId, { stage: 'SIGNED_RETURNED' }));
    } catch (error) { setSubmitError(error?.response?.data?.message || 'Your signature response could not be submitted.'); }
    finally { setSubmitting(false); }
  };

  const recipientLabel = role === 'HR' ? 'your reporting manager' : 'HR';
  return <MyHrFeaturePage className="phase-six" title="Review & Sign" description={`Review, sign, and return your ${workflowType?.label?.toLowerCase() || 'document'}.`} fullWidth backTo={detailPath}>
    {submitError && <p role="alert" className="document-requests__error">{submitError}</p>}
    <div className="document-workflow-layout">
      <DocumentRoute request={request} nextAction={isRequester && !alreadySubmitted && !nothingToSign ? 'Review and sign' : undefined} />
    <section className="request-documents__card">
      <header><div><span>{workflowType?.label || request.requestTitle}</span><h2>{request.requestedByName}</h2></div>
        <StatusTag
          status={alreadySubmitted ? (stage === 'COMPLETED' ? 'Completed' : 'Signed Document Submitted') : nothingToSign ? 'Nothing Pending' : 'Awaiting Your Signature'}
          tone={alreadySubmitted ? 'success' : nothingToSign ? 'neutral' : 'warning'} /></header>

      {!isRequester ? (
        <p>Only the user who requested this document can sign it.</p>
      ) : nothingToSign ? (
        <p>There is nothing awaiting your signature for this request right now.</p>
      ) : alreadySubmitted ? (
        <div className="offer-letter__sent">
          <p>{isOffer ? `Your complete offer letter is signed and available to you and ${recipientLabel}.` : `You submitted your signature response. ${recipientLabel} will review it and complete this request.`}{request.signatureSubmittedAt ? ` Signed on ${formatDateOnly(request.signatureSubmittedAt)}.` : ''}</p>
          <p>Your submitted response is saved with this request and remains available here.</p>
          <footer>{request.signedResponseDocumentId && <Button type="button" variant="secondary" onClick={viewSubmittedResponse}>{isOffer ? 'View Signed Offer Letter' : 'View Submitted Response'}</Button>}<Button type="button" variant="secondary" onClick={() => navigate(detailPath)}>Back to Request</Button></footer>
        </div>
      ) : (
        <div className="request-documents__review offer-letter__template">
          <h3>DOCUMENT TO REVIEW</h3>
          {request.fulfilledDocumentId ? (
            <div className="offer-letter__send">
              <Button type="button" variant="secondary" onClick={viewOriginalDocument}>View {workflowType.label}</Button>
            </div>
          ) : (
            <div className="offer-letter__preview" aria-label="Document preview">
              <PreviewBlocks blocks={workflowState.previewBlocks} />
            </div>
          )}

          <h3 className="offer-letter__section-title">Sign</h3>
          <SignatureField onChange={(value) => { profileSignatureSelected.current = true; setSignedFile(null); setPendingSignature(null); setPendingSourceFile(null); setSelectingRegion(false); setSignature(value); }} onUploadImage={(event) => { profileSignatureSelected.current = true; onUploadSignatureImage(event); }} />
          {pendingSignature && !selectingRegion && (
            <div className="employee-sign__signature-confirm">
              <p>Review the signature detected in your photo before using it.</p>
              <div className="employee-sign__signature-preview"><span>Detected signature:</span><img src={pendingSignature} alt="Detected signature, pending confirmation" /></div>
              <div className="employee-sign__signature-confirm-actions">
                <Button type="button" variant="secondary" onClick={rejectUploadedSignature}>Choose a Different Image</Button>
                <Button type="button" variant="secondary" onClick={() => setSelectingRegion(true)}>Adjust Selection</Button>
                <Button type="button" onClick={confirmUploadedSignature}>Use This Signature</Button>
              </div>
            </div>
          )}
          {selectingRegion && pendingSourceFile && (
            <SignatureRegionSelector file={pendingSourceFile} onSelect={useSelectedRegion} onCancel={() => setSelectingRegion(false)} />
          )}
          {signature && !pendingSignature && <div className="employee-sign__signature-preview"><span>Selected signature:</span><img src={signature} alt="Your captured signature" /></div>}

          {!isOffer && <><h3 className="offer-letter__section-title">Or Upload a Signed Document</h3>
          <FileUpload name="signedDocument" label="Signed document" fileName={signedFile?.name}
            helperText="Use this only if you signed the document outside Waypoint." accept=".pdf,.doc,.docx,image/*"
            onUpload={(e) => { setSignature(''); setPendingSignature(null); setPendingSourceFile(null); setSelectingRegion(false); setSignedFile(e.target.files?.[0] || null); }} onReplace={(e) => { setSignature(''); setPendingSignature(null); setPendingSourceFile(null); setSelectingRegion(false); setSignedFile(e.target.files?.[0] || null); }} /></>}
          {isOffer && <p>By submitting, you accept the terms of this offer. Your signature will be added to the complete HR-signed letter.</p>}

          <footer>
            <Button type="button" variant="secondary" onClick={() => navigate(detailPath)}>Cancel</Button>
            <Button type="button" loading={submitting} disabled={submitting || (!signature && !signedFile)} onClick={submit}>Submit to {recipientLabel}</Button>
          </footer>
        </div>
      )}
    </section>
    </div>
  </MyHrFeaturePage>;
}
