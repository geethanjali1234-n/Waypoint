import { useEffect, useState } from 'react';
import { Button, Card, Dropdown, EmailInput, ImageUpload, PhoneInput, TextArea, TextInput, ValidationMessage } from '../../../../components';
import myHrOrganizationSettingsApi from '../../../../api/myHrOrganizationSettingsApi';
import resolveFileUrl from '../../../../api/resolveFileUrl';
import { EMAIL_PATTERN } from '../../../auth/validation/patterns';
import { formatUsPhone } from '../../../../utils/phoneFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import AuthorizedSignatureCard from './AuthorizedSignatureCard';
import './organizationSettings.css';

const PAYROLL_PERIOD_OPTIONS = [
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'BIWEEKLY', label: 'Biweekly' },
  { value: 'MONTHLY', label: 'Monthly' },
];

const COMPANY_SIZE_OPTIONS = [
  { value: 'MICRO', label: 'Micro' },
  { value: 'SMALL', label: 'Small' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LARGE', label: 'Large' },
  { value: 'ENTERPRISE', label: 'Enterprise' },
];

const EMPTY_FORM = {
  organizationName: '', organizationEmail: '', phoneNumber: '', streetAddress: '', city: '', state: '',
  zipCode: '', organizationSize: '', industry: '', website: '', einNumber: '', eVerifyNumber: '',
  adminName: '', adminEmail: '', adminPhoneNumber: '',
  description: '', payrollPeriod: '',
};

const fallback = (value) => value == null || value === '' ? 'Not provided' : String(value);
const companySize = (value) => value ? value.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (letter) => letter.toUpperCase()) : 'Not provided';
const payrollLabel = (value) => PAYROLL_PERIOD_OPTIONS.find((option) => option.value === value)?.label || fallback(value);
const EIN_PATTERN = /^\d{2}-?\d{7}$/;
const formatEin = (value) => {
  const digits = String(value || '').replace(/\D/g, '').slice(0, 9);
  return digits.length > 2 ? `${digits.slice(0, 2)}-${digits.slice(2)}` : digits;
};
const orgInitials = (name) => name?.trim() ? name.trim().split(/\s+/).slice(0, 2).map((word) => word[0]?.toUpperCase()).join('') : '—';

export default function OrganizationSettingsPage() {
  const [persisted, setPersisted] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [pendingLogoFile, setPendingLogoFile] = useState(null);
  const [logoPreviewUrl, setLogoPreviewUrl] = useState(null);
  const [removeLogo, setRemoveLogo] = useState(false);

  const resetForm = (data) => setForm({
    organizationName: data?.organizationName || '',
    organizationEmail: data?.organizationEmail || '',
    phoneNumber: data?.phoneNumber || '',
    streetAddress: data?.streetAddress || '',
    city: data?.city || '',
    state: data?.state || '',
    zipCode: data?.zipCode || '',
    organizationSize: data?.organizationSize || '',
    industry: data?.industry || '',
    website: data?.website || '',
    einNumber: data?.einNumber || '',
    eVerifyNumber: data?.eVerifyNumber || '',
    adminName: data?.adminName || '',
    adminEmail: data?.adminEmail || '',
    adminPhoneNumber: data?.adminPhoneNumber || '',
    description: data?.description || '',
    payrollPeriod: data?.payrollPeriod || '',
  });

  const set = (key) => (event) => setForm((current) => ({ ...current, [key]: event.target.value }));
  const setEinNumber = (event) => setForm((current) => ({ ...current, einNumber: formatEin(event.target.value) }));

  useEffect(() => {
    let active = true;
    myHrOrganizationSettingsApi.get()
      .then((response) => {
        if (!active) return;
        const data = response?.data?.data || {};
        setPersisted(data);
        resetForm(data);
      })
      .catch((error) => {
        if (active && error?.response?.status !== 401) setLoadError(error?.response?.data?.message || 'Unable to load organization settings.');
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const cancel = () => {
    resetForm(persisted);
    setEditing(false);
    setSaveError('');
    setSaved(false);
    setPendingLogoFile(null);
    setLogoPreviewUrl(null);
    setRemoveLogo(false);
  };

  const selectLogo = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setPendingLogoFile(file);
    setLogoPreviewUrl(URL.createObjectURL(file));
    setRemoveLogo(false);
  };

  const clearLogo = () => {
    setPendingLogoFile(null);
    setLogoPreviewUrl(null);
    setRemoveLogo(true);
  };

  const save = async (event) => {
    event.preventDefault();
    const organizationName = form.organizationName.trim();
    const organizationEmail = form.organizationEmail.trim();
    const description = form.description.trim();
    const adminEmail = form.adminEmail.trim();
    if (!organizationName) {
      setSaveError('Organization Name is required.');
      return;
    }
    if (!organizationEmail || !EMAIL_PATTERN.test(organizationEmail)) {
      setSaveError('Enter a valid organization email address.');
      return;
    }
    if (adminEmail && !EMAIL_PATTERN.test(adminEmail)) {
      setSaveError('Enter a valid admin email address.');
      return;
    }
    if (form.einNumber.trim() && !EIN_PATTERN.test(form.einNumber.trim())) {
      setSaveError('EIN must be 9 digits, e.g. 12-3456789.');
      return;
    }
    if (description.length > 2000) {
      setSaveError('Description must be 2,000 characters or fewer.');
      return;
    }
    if (!form.payrollPeriod) {
      setSaveError('Payroll Period is required.');
      return;
    }
    const payload = {
      organizationName,
      organizationEmail,
      phoneNumber: form.phoneNumber || null,
      streetAddress: form.streetAddress.trim() || null,
      city: form.city.trim() || null,
      state: form.state.trim() || null,
      zipCode: form.zipCode.trim() || null,
      organizationSize: form.organizationSize || null,
      industry: form.industry.trim() || null,
      website: form.website.trim() || null,
      einNumber: form.einNumber.trim() || null,
      eVerifyNumber: form.eVerifyNumber.trim() || null,
      adminName: form.adminName.trim() || null,
      adminEmail: adminEmail || null,
      adminPhoneNumber: form.adminPhoneNumber || null,
      description,
      payrollPeriod: form.payrollPeriod,
      removeLogo,
      // Still required by the settings API (a primitive boolean) even though
      // signatures are now managed on each user's Profile - never removes one here.
      removeSignature: false,
    };
    setSaving(true); setSaveError(''); setSaved(false);
    try {
      const response = await myHrOrganizationSettingsApi.update(payload);
      let data = response?.data?.data || { ...persisted, ...payload };
      if (pendingLogoFile) {
        const logoResponse = await myHrOrganizationSettingsApi.uploadLogo(pendingLogoFile);
        data = logoResponse?.data?.data || data;
      }
      setPersisted(data); resetForm(data); setEditing(false); setSaved(true);
      setPendingLogoFile(null); setLogoPreviewUrl(null); setRemoveLogo(false);
    } catch (error) {
      if (error?.response?.status !== 401) setSaveError(error?.response?.data?.message || 'Unable to save organization settings. Please try again.');
    } finally { setSaving(false); }
  };

  return <MyHrFeaturePage
    title="Organization Settings"
    description="Review organization information and manage approved workspace settings."
    hideBack className="phase-six organization-settings-page"
  >
    {loading ? <p className="myhr-feature__notice" role="status">Loading organization settings…</p>
      : loadError ? <ValidationMessage message={loadError} variant="error" />
      : <form onSubmit={save} className="organization-settings__settings" noValidate>
        <Card title="Organization Information" actions={!editing && <Button type="button" variant="secondary" onClick={() => { setEditing(true); setSaved(false); }}>Edit Settings</Button>}>
          {editing ? <div className="organization-settings__edit">
            <ImageUpload name="organizationLogo" label="Organization Logo"
              previewUrl={removeLogo ? undefined : (logoPreviewUrl || resolveFileUrl(persisted.organizationLogoUrl))}
              initials={orgInitials(form.organizationName)} onUpload={selectLogo}
              onRemove={(!removeLogo && (logoPreviewUrl || persisted.organizationLogoUrl)) ? clearLogo : undefined}
              helperText="PNG or JPG. Shown on organization documents." />
          <div className="organization-settings__fields">
            <TextInput name="organizationName" label="Organization Name" required value={form.organizationName} onChange={set('organizationName')} />
            <TextInput name="organizationCode" label="Organization Code" value={persisted.organizationCode || ''} disabled hint="Cannot be changed" />
            <EmailInput name="organizationEmail" label="Organization Email" required value={form.organizationEmail} onChange={set('organizationEmail')} />
            <PhoneInput name="phoneNumber" label="Phone Number" value={form.phoneNumber} onChange={set('phoneNumber')} />
            <TextInput name="streetAddress" label="Street Address" value={form.streetAddress} onChange={set('streetAddress')} />
            <TextInput name="city" label="City" value={form.city} onChange={set('city')} />
            <TextInput name="state" label="State" value={form.state} onChange={set('state')} />
            <TextInput name="zipCode" label="ZIP Code" value={form.zipCode} onChange={set('zipCode')} />
            <Dropdown name="organizationSize" label="Company Size" value={form.organizationSize} onChange={set('organizationSize')} options={COMPANY_SIZE_OPTIONS} placeholder="Select company size" />
            <TextInput name="industry" label="Industry" value={form.industry} onChange={set('industry')} />
            <TextInput name="website" label="Website" value={form.website} onChange={set('website')} />
            <TextInput name="einNumber" label="EIN Number" value={form.einNumber} onChange={setEinNumber} maxLength={10}
              hint="9 digits, e.g. 12-3456789" />
            <TextInput name="eVerifyNumber" label="E-Verify Number" value={form.eVerifyNumber} onChange={set('eVerifyNumber')} />
            <TextInput name="adminName" label="Admin Name" value={form.adminName} onChange={set('adminName')} maxLength={160}
              hint="Signs experience, relieving and salary verification letters." />
            <EmailInput name="adminEmail" label="Admin Email" value={form.adminEmail} onChange={set('adminEmail')} />
            <PhoneInput name="adminPhoneNumber" label="Admin Contact Number" value={form.adminPhoneNumber} onChange={set('adminPhoneNumber')}
              hint="Shown as the signatory's mobile on generated letters." />
          </div>
          </div>
            : <>
              {persisted.organizationLogoUrl
                ? <img className="organization-settings__logo" src={resolveFileUrl(persisted.organizationLogoUrl)} alt={`${persisted.organizationName || 'Organization'} logo`} />
                : <div className="organization-settings__logo-fallback" aria-hidden="true">{orgInitials(persisted.organizationName)}</div>}
              <dl className="organization-settings__details">
              <div><dt>Organization Name</dt><dd>{fallback(persisted.organizationName)}</dd></div>
              <div><dt>Organization Code</dt><dd>{fallback(persisted.organizationCode)}</dd></div>
              <div><dt>Organization Email</dt><dd>{fallback(persisted.organizationEmail)}</dd></div>
              <div><dt>Phone Number</dt><dd>{fallback(formatUsPhone(persisted.phoneNumber))}</dd></div>
              <div><dt>Street Address</dt><dd>{fallback(persisted.streetAddress)}</dd></div>
              <div><dt>City</dt><dd>{fallback(persisted.city)}</dd></div>
              <div><dt>State</dt><dd>{fallback(persisted.state)}</dd></div>
              <div><dt>ZIP Code</dt><dd>{fallback(persisted.zipCode)}</dd></div>
              <div><dt>Company Size</dt><dd>{companySize(persisted.organizationSize)}</dd></div>
              <div><dt>Industry</dt><dd>{fallback(persisted.industry)}</dd></div>
              <div><dt>Website</dt><dd>{fallback(persisted.website)}</dd></div>
              <div><dt>EIN Number</dt><dd>{fallback(persisted.einNumber)}</dd></div>
              <div><dt>E-Verify Number</dt><dd>{fallback(persisted.eVerifyNumber)}</dd></div>
              <div><dt>Admin Name</dt><dd>{fallback(persisted.adminName)}</dd></div>
              <div><dt>Admin Email</dt><dd>{fallback(persisted.adminEmail)}</dd></div>
              <div><dt>Admin Contact Number</dt><dd>{fallback(formatUsPhone(persisted.adminPhoneNumber))}</dd></div>
              </dl>
            </>}
        </Card>

        <Card title="Description">
          {editing ? <TextArea name="description" label="Description" value={form.description} onChange={set('description')} rows={4} maxLength={2000} />
            : <p className="organization-settings__read-value">{fallback(persisted.description)}</p>}
        </Card>
        <Card title="Payroll & Timesheets">
          {editing ? <Dropdown name="payrollPeriod" label="Payroll Period" value={form.payrollPeriod} onChange={set('payrollPeriod')} options={PAYROLL_PERIOD_OPTIONS} placeholder="Select payroll period" required />
            : <dl className="organization-settings__details"><div><dt>Payroll Period</dt><dd>{payrollLabel(persisted.payrollPeriod)}</dd></div></dl>}
        </Card>
        {saveError && <ValidationMessage message={saveError} variant="error" />}
        {saved && <p className="myhr-feature__notice" role="status">Organization settings saved.</p>}
        {editing && <div className="organization-settings__save-bar" role="region" aria-label="Save organization settings"><span>Review your changes before saving.</span><Button type="button" variant="secondary" onClick={cancel} disabled={saving}>Cancel</Button><Button type="submit" variant="primary" loading={saving}>Save</Button></div>}
      </form>}
    {/* Saved on its own (not part of the settings form above). */}
    {!loading && !loadError && <AuthorizedSignatureCard />}
  </MyHrFeaturePage>;
}
