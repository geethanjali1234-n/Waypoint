import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Modal, ValidationMessage } from '../../../../../components';
import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';
import { formatDateOnly } from '../../../../../utils/dateFormat';
import MyHrFeaturePage from '../../MyHrFeaturePage';
import { NO_SIGNATURE_MESSAGE } from './i983Workflow';
import { loadImageSize, openPdf } from './pdfRendering';
import PlacementSteps from './PlacementSteps';
import SignaturePlacementEditor from './SignaturePlacementEditor';
import './i983.css';

/**
 * HR/Admin "Add Signature" editor for a Signed I-983. The organization's
 * active Authorized Signature is loaded from Organization Settings (never
 * uploaded here); the reviewer pages through the PDF, clicks where it
 * belongs (as many places as needed), drags/resizes the signature boxes,
 * previews the placement and confirms. The server then applies that same
 * signature at those positions
 * and stores the result as a new signed version.
 */
export default function I983SigningPage({ role }) {
  const navigate = useNavigate();
  const { requestId } = useParams();
  const base = role === 'COMPANY_ADMIN' ? '/myhr/admin' : '/myhr/hr';
  const detailPath = `${base}/document-requests/${requestId}`;

  const [workflow, setWorkflow] = useState(null);
  const [signature, setSignature] = useState(null);
  const [signatureImage, setSignatureImage] = useState(null);
  const [pdf, setPdf] = useState(null);
  const [placements, setPlacements] = useState(null);
  const [previewing, setPreviewing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [applying, setApplying] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [applyError, setApplyError] = useState('');

  // Request, current document and the active organization signature.
  useEffect(() => {
    let active = true;
    let objectUrl = null;
    let loadedPdf = null;
    (async () => {
      setLoading(true); setLoadError('');
      try {
        const data = (await myHrDocumentRequestApi.getI983(requestId)).data.data;
        if (!active) return;
        setWorkflow(data);
        if (!data.canReview) { setLoadError('This I-983 is not awaiting HR/Admin review, so it cannot be signed.'); return; }
        const info = (await myHrDocumentRequestApi.getI983Signature(requestId)).data.data;
        if (!active) return;
        setSignature(info);
        if (info.available) {
          const blob = (await myHrDocumentRequestApi.getI983SignatureImage(requestId)).data;
          objectUrl = URL.createObjectURL(blob);
          const size = await loadImageSize(objectUrl);
          if (!active) return;
          setSignatureImage({ url: objectUrl, ...size });
        }
        const documentBlob = (await myHrDocumentRequestApi.getI983Document(requestId, data.currentDocumentId, false)).data;
        loadedPdf = await openPdf(new Uint8Array(await documentBlob.arrayBuffer()));
        if (!active) { loadedPdf.destroy(); return; }
        setPdf(loadedPdf);
      } catch (failure) {
        if (active) setLoadError(failure?.response?.data?.message || 'The I-983 document could not be opened for signing.');
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      loadedPdf?.destroy();
    };
  }, [requestId]);

  const signable = Boolean(signature?.available && signatureImage);

  const apply = async () => {
    setApplying(true); setApplyError('');
    try {
      await myHrDocumentRequestApi.signI983(requestId, { documentId: workflow.currentDocumentId, placements });
      navigate(detailPath, { replace: true });
    } catch (failure) {
      setApplyError(failure?.response?.data?.message || 'The signature could not be applied. Please try again.');
      setConfirming(false);
    } finally { setApplying(false); }
  };

  const title = 'Add Signature';
  if (loading) return <MyHrFeaturePage className="phase-six" title={title} backTo={detailPath}><p role="status">Opening the I-983…</p></MyHrFeaturePage>;
  if (loadError) return <MyHrFeaturePage className="phase-six" title={title} backTo={detailPath}><ValidationMessage message={loadError} variant="error" /></MyHrFeaturePage>;

  return (
    <MyHrFeaturePage className="phase-six" title={title} description="Place the organization's authorized signature on the Signed I-983." fullWidth backTo={detailPath}>
      <div className="i983-signing">
        <aside className="i983-signing__sidebar">
          <section className="i983-signing__card">
            <h3>Authorized signature</h3>
            {signable ? <>
              <img className="i983-signing__signature-preview" src={signatureImage.url} alt="Organization authorized signature" />
              <dl>
                <div><dt>Signatory</dt><dd>{signature.signatoryName || '—'}</dd></div>
                <div><dt>Title</dt><dd>{signature.signatoryTitle || '—'}</dd></div>
                <div><dt>Effective</dt><dd>{signature.effectiveDate ? formatDateOnly(signature.effectiveDate) : '—'}</dd></div>
              </dl>
              <p className="i983-signing__source">From Organization Settings → Authorized Signature</p>
            </> : <ValidationMessage message={signature?.message || NO_SIGNATURE_MESSAGE} variant="error" />}
          </section>
          <PlacementSteps />
          {applyError && <ValidationMessage message={applyError} variant="error" />}
          <div className="i983-signing__actions">
            <Button type="button" variant="secondary" disabled={!placements} onClick={() => { setPlacements(null); setPreviewing(false); }}>Clear all</Button>
            <Button type="button" variant="secondary" disabled={!placements} onClick={() => setPreviewing((value) => !value)}>
              {previewing ? 'Edit placement' : 'Preview'}
            </Button>
            <Button type="button" disabled={!signable || !placements} onClick={() => setConfirming(true)}>Apply Signature</Button>
          </div>
        </aside>

        <SignaturePlacementEditor pdf={pdf} signatureImage={signable ? signatureImage : null} placements={placements}
          onChange={setPlacements} previewing={previewing} onExitPreview={() => setPreviewing(false)} />
      </div>

      <Modal isOpen={confirming} title="Apply organization signature?" onClose={() => { if (!applying) setConfirming(false); }}
        footer={<div className="document-requests__reject-actions">
          <Button type="button" variant="secondary" disabled={applying} onClick={() => setConfirming(false)}>Cancel</Button>
          <Button type="button" loading={applying} onClick={apply}>Apply Signature</Button>
        </div>}>
        <p>The authorized signature of <strong>{signature?.signatoryName || 'the organization'}</strong> will be permanently applied {placements?.length === 1 ? 'once' : `${placements?.length} times`} (page{new Set(placements?.map((item) => item.page)).size === 1 ? '' : 's'} {[...new Set(placements?.map((item) => item.page))].sort((a, b) => a - b).join(', ')}) and saved as a new signed version. The employee's original document is kept unchanged.</p>
      </Modal>
    </MyHrFeaturePage>
  );
}

