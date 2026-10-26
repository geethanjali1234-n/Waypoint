import { useState } from 'react';
import { Button, Card, ValidationMessage } from '../../../../components';
import myHrEmployeeApi from '../../../../api/myHrEmployeeApi';
import resolveFileUrl from '../../../../api/resolveFileUrl';
import { extractSignatureFromImage, formatSignatureDetectionDiagnostics, getLastSignatureDetectionDiagnostics } from '../signatureImageProcessing';
import SignaturePreviewImage from '../SignaturePreviewImage';
import SignatureRegionSelector from '../SignatureRegionSelector';
import SignatureField from '../documentRequests/SignatureField';
import '../documentRequests/documentRequests.css';

async function dataUrlToFile(dataUrl, fileName) {
  const response = await fetch(dataUrl);
  return new File([await response.blob()], fileName, { type: 'image/png' });
}

/** Each user stores one reusable PNG signature, no matter how it was made. */
export default function ProfileSignatureCard({ signatureUrl, profileName, onSaved }) {
  const [sourceFile, setSourceFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [selectingRegion, setSelectingRegion] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirmingRemove, setConfirmingRemove] = useState(false);
  const [error, setError] = useState('');

  const reportFailure = (failure, fallback) => {
    if (failure?.response?.status !== 401) setError(failure?.response?.data?.message || fallback);
  };
  const captureSignature = (value) => {
    setError(''); setConfirmingRemove(false); setSourceFile(null); setSelectingRegion(false); setPreview(value || null);
  };
  const selectFile = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 2 * 1024 * 1024) {
      setError('Use a PNG or JPEG signature no larger than 2 MB.'); return;
    }
    setError(''); setConfirmingRemove(false); setSourceFile(file); setPreview(null); setSelectingRegion(false);
    try {
      const extracted = await extractSignatureFromImage(file);
      if (!extracted) {
        const details = formatSignatureDetectionDiagnostics(getLastSignatureDetectionDiagnostics());
        setError(`Couldn't detect a signature from this image. Please upload a clearer image containing the handwritten signature.${details ? ` (${details})` : ''}`);
        setSourceFile(null); return;
      }
      setPreview(extracted);
    } catch { setError('That image could not be processed. Please try a different file.'); setSourceFile(null); }
  };
  const useSelectedRegion = async (region) => {
    setSelectingRegion(false); setError('');
    try {
      const extracted = await extractSignatureFromImage(sourceFile, region);
      if (!extracted) {
        const details = formatSignatureDetectionDiagnostics(getLastSignatureDetectionDiagnostics());
        setError(`Couldn't detect a signature in that selection. Try selecting a tighter area around just the signature.${details ? ` (${details})` : ''}`);
        return;
      }
      setPreview(extracted);
    } catch { setError('That selection could not be processed. Please try again.'); }
  };
  const discardPreview = () => { setPreview(null); setSourceFile(null); setSelectingRegion(false); };
  const useSignature = async () => {
    if (!preview) return;
    setBusy(true); setError('');
    try {
      const file = await dataUrlToFile(preview, 'signature.png');
      const response = await myHrEmployeeApi.uploadMySignature(file);
      onSaved(response?.data?.data); discardPreview();
    } catch (failure) { reportFailure(failure, 'Your signature could not be saved. Please try again.'); }
    finally { setBusy(false); }
  };
  const removeSignature = async () => {
    setBusy(true); setError('');
    try {
      const response = await myHrEmployeeApi.removeMySignature();
      onSaved(response?.data?.data); setConfirmingRemove(false);
    } catch (failure) { reportFailure(failure, 'Your signature could not be removed. Please try again.'); }
    finally { setBusy(false); }
  };

  return <Card title="Signature"><div className="myhr-profile__signature">
    <p className="myhr-profile__signature-intro">Choose the signature to use by default in your WayPoint documents.</p>
    {signatureUrl && !preview && !selectingRegion && <><SignaturePreviewImage className="myhr-profile__signature-image" src={resolveFileUrl(signatureUrl)} alt="Your signature" /><p className="myhr-profile__signature-status">Signature configured</p></>}
    {!signatureUrl && !preview && !selectingRegion && <p className="myhr-profile__signature-empty">No signature has been saved yet.</p>}
    {selectingRegion && sourceFile ? <SignatureRegionSelector file={sourceFile} onSelect={useSelectedRegion} onCancel={() => setSelectingRegion(false)} /> : preview ? (
      <div className="myhr-profile__signature-confirm"><p className="myhr-profile__signature-hint">Signature Preview</p><SignaturePreviewImage className="myhr-profile__signature-image" src={preview} alt="Signature preview" />
        <div className="myhr-profile__signature-actions"><Button type="button" variant="secondary" onClick={discardPreview} disabled={busy}>Choose Different Signature</Button>{sourceFile && <Button type="button" variant="secondary" onClick={() => setSelectingRegion(true)} disabled={busy}>Adjust Selection</Button>}<Button type="button" onClick={useSignature} loading={busy}>Use Signature</Button></div>
      </div>
    ) : <SignatureField onChange={captureSignature} onUploadImage={selectFile} defaultTypedName={profileName} lockTypedName />}
    {!preview && !selectingRegion && (confirmingRemove ? <div className="myhr-profile__signature-actions"><span className="myhr-profile__signature-hint">Remove your signature?</span><Button type="button" variant="secondary" onClick={() => setConfirmingRemove(false)} disabled={busy}>Cancel</Button><Button type="button" onClick={removeSignature} loading={busy}>Remove</Button></div> : signatureUrl && <div className="myhr-profile__signature-actions"><Button type="button" variant="secondary" onClick={() => setConfirmingRemove(true)}>Remove Signature</Button></div>)}
    <p className="myhr-profile__signature-hint">Draw or type your name, or upload a PNG or JPG up to 2 MB. Uploaded photos are cleaned so only the signature is saved.</p>
    {error && <ValidationMessage message={error} variant="error" />}
  </div></Card>;
}
