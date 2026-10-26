import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Dropdown, FileUpload, TextArea } from '../../../../components';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';
import MyHrFeaturePage from '../MyHrFeaturePage';
import { SELF_SERVICE_CATEGORIES, SELF_SERVICE_DOCUMENT_TYPES, SELF_SERVICE_TYPE_CONFIG } from './selfServiceDocumentCatalog';
import EmployeeI983SignSection from './i983/EmployeeI983SignSection';
import './documentRequests.css';

export { SELF_SERVICE_DOCUMENT_TYPES };

// Selections without a matching backend enum value are submitted as OTHER;
// the exact requestTitle (for example, "LCA") lets the HR request detail view
// recognize the document type and route to its preparation workflow.
const BACKEND_DOCUMENT_TYPE_OVERRIDES = {
  SALARY_VERIFICATION: 'OTHER',
  LCA: 'OTHER',
  PERM: 'OTHER',
  I140: 'OTHER',
};

const MAX_I983_BYTES = 10 * 1024 * 1024;
const isPdf = (file) => file.type === 'application/pdf' || /\.pdf$/i.test(file.name);

const typeLabel = (value) => SELF_SERVICE_DOCUMENT_TYPES.find(([v]) => v === value)?.[1] || '';

// Renames a file to its required-document label (keeping the original
// extension) before upload, so HR can identify which of the mandatory
// documents each attachment is - without any backend change, since the
// backend already persists whatever file name it's given.
function labelFile(file, label) {
  const dot = file.name.lastIndexOf('.');
  const extension = dot > -1 ? file.name.slice(dot) : '';
  return new File([file], `${label}${extension}`, { type: file.type });
}

export default function EmployeeDocumentRequestForm({ role = 'EMPLOYEE' }) {
  const navigate = useNavigate();
  const base = role === 'HR' ? '/myhr/hr' : '/myhr/employee';
  const [form, setForm] = useState({ category: '', documentType: '', reason: '', requiredBy: '' });
  const [files, setFiles] = useState([]);
  const [mandatoryFiles, setMandatoryFiles] = useState({});
  const [employeeDocument, setEmployeeDocument] = useState(null);
  // Where the employee placed their own Profile signature on that document (optional, one or more places).
  const [employeeSignature, setEmployeeSignature] = useState(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const typeOptionsForCategory = (categoryId) => {
    const category = SELF_SERVICE_CATEGORIES.find((item) => item.id === categoryId);
    if (!category) return [];
    return SELF_SERVICE_DOCUMENT_TYPES.filter(([value]) => category.typeIds.includes(value));
  };

  const setCategory = (event) => {
    const category = event.target.value;
    setForm((current) => ({ ...current, category, documentType: '' }));
    setFiles([]); setMandatoryFiles({}); setEmployeeDocument(null); setError('');
  };
  const setDocumentType = (event) => {
    setForm((current) => ({ ...current, documentType: event.target.value }));
    setFiles([]); setMandatoryFiles({}); setEmployeeDocument(null); setError('');
  };

  const typeConfig = form.documentType ? SELF_SERVICE_TYPE_CONFIG[form.documentType] : null;
  const selectedLabel = typeLabel(form.documentType);
  const mandatoryUploads = typeConfig?.mandatoryDocumentUploads || null;
  // A single document the employee uploads themselves (Signed I-983), sent
  // to its own submit endpoint instead of as a generic attachment.
  const documentUpload = typeConfig?.employeeDocumentUpload || null;
  const missingMandatoryCount = mandatoryUploads ? mandatoryUploads.filter((slot) => !mandatoryFiles[slot.id]).length : 0;
  // Data-driven: a generic attachment control only appears when the
  // selected type's configuration names an optional/required document (and
  // isn't already handled by the dedicated Mandatory Documents section
  // below) - never shown as though mandatory by default.
  const showAttachments = Boolean(typeConfig && !mandatoryUploads && !documentUpload
    && (typeConfig.requiredDocuments.length > 0 || typeConfig.optionalDocuments.length > 0 || typeConfig.requiresAttachment));

  const setMandatoryFile = (slotId) => (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setMandatoryFiles((current) => ({ ...current, [slotId]: file }));
    setError('');
  };
  const removeMandatoryFile = (slotId) => setMandatoryFiles((current) => {
    const next = { ...current };
    delete next[slotId];
    return next;
  });

  const selectEmployeeDocument = (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!isPdf(file)) return setError('Upload the I-983 as a PDF file.');
    if (file.size > MAX_I983_BYTES) return setError('The I-983 document must be 10 MB or smaller.');
    setEmployeeDocument(file); setError('');
  };
  const openEmployeeDocument = (download) => {
    const url = URL.createObjectURL(employeeDocument);
    if (download) {
      const anchor = document.createElement('a');
      anchor.href = url; anchor.download = employeeDocument.name;
      document.body.appendChild(anchor); anchor.click(); anchor.remove();
    } else window.open(url, '_blank', 'noopener,noreferrer');
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  };

  const submit = async (event) => {
    event.preventDefault();
    const documentTitle = selectedLabel;
    if (!form.documentType || !documentTitle || !form.reason.trim()) return setError('Document type and reason are required.');

    if (documentUpload) {
      if (!employeeDocument) return setError(`Upload the ${documentUpload.label} before submitting.`);
      setSaving(true); setError('');
      try {
        const response = await myHrDocumentRequestApi.submitI983(
          { documentType: form.documentType, documentTitle, reason: form.reason.trim(), requiredBy: form.requiredBy || null },
          employeeDocument,
          employeeSignature,
        );
        const requestId = response?.data?.data?.request?.id;
        navigate(requestId ? `${base}/document-requests/${requestId}` : `${base}/document-requests`, { replace: true });
      } catch (e) { setError(e?.response?.data?.message || 'Your I-983 could not be submitted.'); }
      finally { setSaving(false); }
      return;
    }

    let attachments = files;
    if (mandatoryUploads) {
      const missing = mandatoryUploads.filter((slot) => !mandatoryFiles[slot.id]);
      if (missing.length) {
        return setError(`Missing required documents: ${missing.map((slot) => slot.label).join(', ')}.`);
      }
      attachments = mandatoryUploads.map((slot) => labelFile(mandatoryFiles[slot.id], slot.label));
    }

    setSaving(true); setError('');
    const documentType = BACKEND_DOCUMENT_TYPE_OVERRIDES[form.documentType] || form.documentType;
    try {
      await myHrDocumentRequestApi.createSelfService(
        { documentType, documentTitle, reason: form.reason.trim(), requiredBy: form.requiredBy || null },
        attachments,
      );
      navigate(`${base}/document-requests`, { replace: true });
    } catch (e) { setError(e?.response?.data?.message || 'Document request could not be created.'); }
    finally { setSaving(false); }
  };

  return <MyHrFeaturePage className="phase-six" title="Request a Document" description="Send a request to your active primary reporting manager." fullWidth backTo={`${base}/document-requests`}>
    <form className="request-documents__card request-documents__form employee-document-request" onSubmit={submit} noValidate>
      {error && <p role="alert" className="document-requests__error">{error}</p>}
      <div className="request-documents__grid">
        <Dropdown name="documentCategory" label="Document Category" required value={form.category} placeholder="Select a category"
          onChange={setCategory} options={SELF_SERVICE_CATEGORIES.map((category) => ({ value: category.id, label: category.label }))} />
        <Dropdown name="documentType" label="Document type" required value={form.documentType} disabled={!form.category}
          placeholder={form.category ? 'Select document type' : 'Select a category first'}
          onChange={setDocumentType} options={typeOptionsForCategory(form.category).map(([v, label]) => ({ value: v, label }))} />
      </div>

      {typeConfig && <section className="employee-document-request__section">
        <h3>{typeConfig.description}</h3>
        {typeConfig.employeeInstructions && <p>{typeConfig.employeeInstructions}</p>}

        {documentUpload ? (
          <div className="employee-document-request__mandatory-documents">
            <h4>Upload Document</h4>
            <div className="employee-document-request__upload">
              <span><span>{documentUpload.label}</span><span className="employee-document-request__required-marker" aria-hidden="true">*</span><span className="employee-document-request__sr-only"> required</span></span>
              <FileUpload name={`document-${documentUpload.id}`} fileName={employeeDocument?.name} accept={documentUpload.accept}
                downloadFile={employeeDocument} helperText="PDF only, up to 10 MB."
                onUpload={selectEmployeeDocument} onReplace={selectEmployeeDocument}
                onRemove={() => setEmployeeDocument(null)} removeLabel={`Remove ${documentUpload.label}`} />
              {employeeDocument && <div className="employee-document-request__upload-actions">
                <Button type="button" variant="secondary" onClick={() => openEmployeeDocument(false)}>View</Button>
                <Button type="button" variant="secondary" onClick={() => openEmployeeDocument(true)}>Download</Button>
              </div>}
            </div>
            {employeeDocument && <>
              <h4>Sign Document</h4>
              <EmployeeI983SignSection source={employeeDocument} profilePath={`${base}/profile`}
                placements={employeeSignature} onPlacementsChange={setEmployeeSignature} />
            </>}
          </div>
        ) : mandatoryUploads ? (
          // One full-width section replaces the generic Requirements block
          // for types with real per-document uploads (only Offer Letter
          // today) - the document cards below already convey what's
          // required, so a separate "Required Documents" bullet list would
          // just repeat the same six names.
          <div className="employee-document-request__mandatory-documents">
            <h4>Required Documents</h4>
            <p>Please upload the following documents to request your Offer Letter. Fields marked <b aria-hidden="true">*</b><span className="employee-document-request__sr-only"> are required</span>.</p>
            {missingMandatoryCount > 0 && (
              <p className="employee-document-request__mandatory-hint">
                All {mandatoryUploads.length} required documents must be uploaded before you can submit this request.
              </p>
            )}
            <div className="employee-document-request__mandatory-grid">
              {mandatoryUploads.map((slot) => {
                const uploaded = mandatoryFiles[slot.id];
                return <div key={slot.id} className="employee-document-request__upload">
                  <span><span>{slot.label}</span><span className="employee-document-request__required-marker" aria-hidden="true">*</span><span className="employee-document-request__sr-only"> required</span></span>
                  <FileUpload name={`mandatory-${slot.id}`} fileName={uploaded?.name}
                    downloadFile={uploaded}
                    onUpload={setMandatoryFile(slot.id)} onReplace={setMandatoryFile(slot.id)}
                    onRemove={() => removeMandatoryFile(slot.id)} removeLabel={`Remove ${slot.label}`} />
                </div>;
              })}
            </div>
          </div>
        ) : (
          <div className="employee-document-request__requirements">
            <h4>Requirements</h4>
            <div>
              <h5>Required Information</h5>
              <ul>{typeConfig.requiredInformation.map((item) => <li key={item}>{item}</li>)}</ul>
            </div>
            {typeConfig.requiredDocuments.length > 0 && <div>
              <h5>Required Documents</h5>
              <ul>{typeConfig.requiredDocuments.map((item) => <li key={item}>{item}</li>)}</ul>
            </div>}
            {typeConfig.optionalDocuments.length > 0 && <div>
              <h5>Optional Documents</h5>
              <ul>{typeConfig.optionalDocuments.map((item) => <li key={item}>{item}</li>)}</ul>
            </div>}
            {typeConfig.requiredDocuments.length === 0 && typeConfig.optionalDocuments.length === 0
              && <p className="employee-document-request__no-documents">No supporting documents are required for this request.</p>}
          </div>
        )}
      </section>}

      <TextArea name="reason" label="Reason" required value={form.reason} maxLength={2000}
        onChange={(e) => setForm({ ...form, reason: e.target.value })} />
      <label>Required by<input type="date" value={form.requiredBy} onChange={(e) => setForm({ ...form, requiredBy: e.target.value })} /></label>
      {showAttachments && <>
        <label>Supporting attachments<input type="file" multiple onChange={(e) => setFiles(Array.from(e.target.files || []))} /></label>
        <small className="optional">{typeConfig.requiredDocuments.length > 0 ? 'See Required Documents above.' : 'Optional.'}</small>
      </>}

      <footer><Button type="button" variant="secondary" onClick={() => navigate(`${base}/document-requests`)}>Cancel</Button><Button type="submit" loading={saving}>{documentUpload ? 'Submit' : 'Send request'}</Button></footer>
    </form>
  </MyHrFeaturePage>;
}
