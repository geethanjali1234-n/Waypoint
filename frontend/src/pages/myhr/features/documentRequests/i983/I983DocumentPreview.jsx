import { useEffect, useState } from 'react';
import { Button } from '../../../../../components';
import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';

export default function I983DocumentPreview({ requestId, documentId }) {
  const [requested, setRequested] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [preview, setPreview] = useState({});
  useEffect(() => {
    if (!requested || !documentId) return undefined;
    let active = true;
    let url;
    setPreview({ loading: true });
    Promise.resolve().then(() => myHrDocumentRequestApi.getI983Document(requestId, documentId, false))
      .then((response) => {
        if (!active) return;
        if (!(response.data instanceof Blob)) throw new Error('Missing document');
        url = URL.createObjectURL(response.data);
        setPreview({ url });
      }).catch(() => { if (active) setPreview({ error: true }); });
    return () => { active = false; if (url) URL.revokeObjectURL(url); };
  }, [requestId, documentId, requested, attempt]);

  return <section className="i983-preview" aria-label="Document preview">
    <header><h4>Document preview</h4><span>Current version</span></header>
    {preview.url ? <iframe title="I-983 document preview" src={preview.url} /> : <div className="i983-preview__empty">
      <p role={preview.loading || preview.error ? 'status' : undefined}>{preview.loading ? 'Loading document…' : preview.error ? 'The preview could not be loaded. You can still use View or Download Original.' : 'Review the current document before placing your signature.'}</p>
      {!preview.loading && <Button type="button" variant="secondary" disabled={!documentId} onClick={() => { setRequested(true); setAttempt((value) => value + 1); }}>{preview.error ? 'Retry preview' : 'Load document preview'}</Button>}
    </div>}
    <p className="i983-preview__note">Signature placement and confirmation are available through Add Signature.</p>
  </section>;
}
