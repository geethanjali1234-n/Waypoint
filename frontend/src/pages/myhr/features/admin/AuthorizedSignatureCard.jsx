import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Card, Checkbox, DatePicker, StatusBadge, TextInput, ValidationMessage } from '../../../../components';
import myHrOrganizationSettingsApi from '../../../../api/myHrOrganizationSettingsApi';
import resolveFileUrl from '../../../../api/resolveFileUrl';
import { formatDateOnly } from '../../../../utils/dateFormat';
import SignaturePreviewImage from '../SignaturePreviewImage';
import '../profile/myHrProfile.css';

const EMPTY = { signatoryName: '', signatoryTitle: '', effectiveDate: '', active: false };
const PROFILE_PATH = '/myhr/admin/profile';
const toForm = (data) => ({
  signatoryName: data?.signatoryName || '',
  signatoryTitle: data?.signatoryTitle || '',
  effectiveDate: data?.effectiveDate || '',
  active: Boolean(data?.active),
});

function statusOf(data) {
  if (data?.inEffect) return { label: 'Active', state: 'active' };
  if (data?.active && data?.signatureConfigured) return { label: `Active from ${formatDateOnly(data.effectiveDate)}`, state: 'pending' };
  if (data?.active) return { label: 'Active - Profile signature missing', state: 'rejected' };
  return { label: 'Inactive', state: 'inactive' };
}

/**
 * Admin -> Organization Settings -> Authorized Signature: the signature
 * applied when signing organization documents such as the Signed I-983.
 * Name, title, effective date and active status are set here; the
 * handwritten signature itself is the Company Admin's own My HR Profile
 * signature (saving makes the current admin the signatory), so there is
 * nothing to upload on this page.
 */
export default function AuthorizedSignatureCard() {
  const [data, setData] = useState(null);
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  useEffect(() => {
    let active = true;
    Promise.resolve(myHrOrganizationSettingsApi.getAuthorizedSignature())
      .then((response) => { if (active && response?.data?.data) { setData(response.data.data); setForm(toForm(response.data.data)); } })
      .catch((failure) => { if (active && failure?.response?.status !== 401) setError(failure?.response?.data?.message || 'Unable to load the authorized signature.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const set = (key) => (event) => setForm((current) => ({ ...current, [key]: key === 'active' ? event.target.checked : event.target.value }));

  const save = async () => {
    if (!form.signatoryName.trim()) return setError('Authorized Signatory Name is required.');
    if (!form.signatoryTitle.trim()) return setError('Authorized Signatory Title is required.');
    if (!form.effectiveDate) return setError('Effective Date is required.');
    setSaving(true); setError(''); setSaved('');
    try {
      const response = await myHrOrganizationSettingsApi.updateAuthorizedSignature({
        signatoryName: form.signatoryName.trim(), signatoryTitle: form.signatoryTitle.trim(),
        effectiveDate: form.effectiveDate, active: form.active,
      });
      setData(response.data.data); setForm(toForm(response.data.data)); setEditing(false); setSaved('Authorized signature saved.');
    } catch (failure) {
      if (failure?.response?.status !== 401) setError(failure?.response?.data?.message || 'Unable to save the authorized signature.');
    } finally { setSaving(false); }
  };

  const cancel = () => { setForm(toForm(data)); setEditing(false); setError(''); };
  const status = statusOf(data);
  // Saving always makes the current admin the signatory, so while editing show whose signature that will be.
  const ownerLabel = data?.signatureOwnerIsViewer ? 'your' : `${data?.signatureOwnerName || 'the signatory admin'}'s`;

  return (
    <Card title="Authorized Signature" actions={!loading && !editing && <Button type="button" variant="secondary" className="authorized-signature__edit" onClick={() => { setEditing(true); setSaved(''); }}>Edit Authorization</Button>}>
      {loading ? <p className="myhr-feature__notice" role="status">Loading authorized signature…</p> : <div className="organization-settings__authorized-signature">
        <p>Applied by HR and Admin when signing organization documents such as the Signed I-983. Only an active signature is used.</p>
        <div><StatusBadge status={status.state} label={status.label} /></div>
        {editing ? <div className="organization-settings__fields">
          <TextInput name="authorizedSignatoryName" label="Authorized Signatory Name" required maxLength={160} value={form.signatoryName} onChange={set('signatoryName')} />
          <TextInput name="authorizedSignatoryTitle" label="Authorized Signatory Title" required maxLength={160} value={form.signatoryTitle} onChange={set('signatoryTitle')} />
          <DatePicker name="signatureEffectiveDate" label="Effective Date" required value={form.effectiveDate} onChange={set('effectiveDate')} />
          <Checkbox name="signatureActive" label="Active - use this signature for signing" checked={form.active} onChange={set('active')} />
        </div> : <dl className="organization-settings__details">
          <div><dt>Authorized Signatory Name</dt><dd>{data?.signatoryName || 'Not provided'}</dd></div>
          <div><dt>Authorized Signatory Title</dt><dd>{data?.signatoryTitle || 'Not provided'}</dd></div>
          <div><dt>Effective Date</dt><dd>{data?.effectiveDate ? formatDateOnly(data.effectiveDate) : 'Not provided'}</dd></div>
          <div><dt>Status</dt><dd>{status.label}</dd></div>
        </dl>}
        <div className="myhr-profile__signature">
          <p className="myhr-profile__signature-intro"><strong>Signature</strong> - taken from {editing ? 'your' : ownerLabel} My ADMIN Profile</p>
          {editing && !data?.signatureOwnerIsViewer
            ? <p className="myhr-profile__signature-hint">Saving makes you the signatory, so your own Profile signature will be used.</p>
            : data?.signatureUrl
              ? <SignaturePreviewImage className="myhr-profile__signature-image" src={resolveFileUrl(data.signatureUrl)} alt="Authorized signature" />
              : <p className="myhr-profile__signature-empty">No signature has been uploaded to {ownerLabel} Profile yet.</p>}
          <p className="myhr-profile__signature-hint">To add or change it, update the signature on <Link to={PROFILE_PATH}>My ADMIN Profile</Link>.</p>
        </div>
        {error && <ValidationMessage message={error} variant="error" />}
        {saved && <p className="myhr-feature__notice" role="status">{saved}</p>}
        {editing && <div className="organization-settings__save-bar" role="region" aria-label="Save authorized signature">
          <Button type="button" variant="secondary" onClick={cancel} disabled={saving}>Cancel</Button>
          <Button type="button" loading={saving} onClick={save}>Save Signatory</Button>
        </div>}
      </div>}
    </Card>
  );
}
