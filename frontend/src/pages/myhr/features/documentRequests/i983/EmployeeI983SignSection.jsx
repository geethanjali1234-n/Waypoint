import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, ValidationMessage } from '../../../../../components';
import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../../../api/myHrEmployeeApi';
import resolveFileUrl from '../../../../../api/resolveFileUrl';
import { loadImageSize, openPdf } from './pdfRendering';
import PlacementSteps from './PlacementSteps';
import SignaturePlacementEditor from './SignaturePlacementEditor';
import './i983.css';

/**
 * "Sign Document" for the employee's own Signed I-983: their My HR Profile
 * signature is placed exactly where they choose - in one or more places - on the PDF they are
 * submitting (a newly chosen File, or - when resubmitting - the document
 * already on the request). The placements are handed to the parent, which
 * sends it with the submission; the server applies the signature and keeps
 * the unsigned upload as the original.
 */
export default function EmployeeI983SignSection({ source, profilePath, placements, onPlacementsChange }) {
  const [open, setOpen] = useState(false);
  const [signatureImage, setSignatureImage] = useState(undefined);
  const [pdf, setPdf] = useState(null);
  const [previewing, setPreviewing] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const sourceKey = source instanceof File ? `${source.name}:${source.size}:${source.lastModified}` : source?.documentId;

  const placed = placements?.length || 0;
  const pages = [...new Set((placements || []).map((item) => item.page))].sort((a, b) => a - b);

  // A different document invalidates any placements made on the previous one.
  useEffect(() => { onPlacementsChange(null); setPreviewing(false); }, [sourceKey]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open || !source) return undefined;
    let active = true;
    let loadedPdf = null;
    (async () => {
      setLoading(true); setError('');
      try {
        const profile = (await myHrEmployeeApi.getMine()).data.data;
        if (!active) return;
        if (profile?.signatureUrl) {
          const url = resolveFileUrl(profile.signatureUrl);
          const size = await loadImageSize(url);
          if (active) setSignatureImage({ url, ...size });
        } else setSignatureImage(null);
        const data = source instanceof File ? await source.arrayBuffer()
          : await (await myHrDocumentRequestApi.getI983Document(source.requestId, source.documentId, false)).data.arrayBuffer();
        loadedPdf = await openPdf(new Uint8Array(data));
        if (!active) { loadedPdf.destroy(); return; }
        setPdf(loadedPdf);
      } catch {
        if (active) setError('The document could not be opened for signing.');
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; loadedPdf?.destroy(); setPdf(null); };
  }, [open, sourceKey]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!source) return null;

  if (!open) {
    return (
      <div className="i983-employee-sign">
        {placed
          ? <p className="i983-employee-sign__summary" role="status">✓ Your signature is placed {placed === 1 ? 'once' : `${placed} times`} (page{pages.length === 1 ? '' : 's'} {pages.join(', ')}). It will be applied when you submit.</p>
          : <p className="i983-employee-sign__summary">Sign the I-983 with your Profile signature, exactly where it belongs on the form.</p>}
        <div className="i983-panel__row-actions">
          <Button type="button" variant={placed ? 'secondary' : 'primary'} onClick={() => setOpen(true)}>{placed ? 'Edit Signatures' : 'Sign Document'}</Button>
          {placed > 0 && <Button type="button" variant="secondary" onClick={() => onPlacementsChange(null)}>Remove Signatures</Button>}
        </div>
      </div>
    );
  }

  return (
    <div className="i983-employee-sign i983-employee-sign--open">
      <div className="i983-signing">
        <aside className="i983-signing__sidebar">
          <section className="i983-signing__card">
            <h3>Your signature</h3>
            {signatureImage === undefined ? <p role="status">Loading your signature…</p>
              : signatureImage ? <>
                <img className="i983-signing__signature-preview" src={signatureImage.url} alt="Your signature" />
                <p className="i983-signing__source">From your My HR Profile</p>
              </> : <ValidationMessage variant="error"
                message="You haven't uploaded a signature yet. Add one on your My HR Profile, then come back to sign." />}
            {signatureImage === null && <p className="i983-signing__source"><Link to={profilePath}>Go to My HR Profile</Link></p>}
          </section>
          <PlacementSteps />
          {error && <ValidationMessage message={error} variant="error" />}
          <div className="i983-signing__actions">
            <Button type="button" variant="secondary" disabled={!placed} onClick={() => { onPlacementsChange(null); setPreviewing(false); }}>Clear all</Button>
            <Button type="button" variant="secondary" disabled={!placed} onClick={() => setPreviewing((value) => !value)}>
              {previewing ? 'Edit placement' : 'Preview'}
            </Button>
            <Button type="button" disabled={!placed} onClick={() => { setOpen(false); setPreviewing(false); }}>Done</Button>
            <Button type="button" variant="secondary" onClick={() => { setOpen(false); setPreviewing(false); }}>Close</Button>
          </div>
        </aside>
        {loading && !pdf ? <p role="status">Opening the document…</p>
          : <SignaturePlacementEditor pdf={pdf} signatureImage={signatureImage || null} placements={placements}
            onChange={onPlacementsChange} previewing={previewing} onExitPreview={() => setPreviewing(false)} />}
      </div>
    </div>
  );
}
