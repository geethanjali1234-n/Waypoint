import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Dropdown, EmailInput, EmptyState, Loader, PhoneInput, TextArea, TextInput, Toast } from '../components';
import { getEmployerProfile, updateEmployerProfile } from '../api/employerProfileApi';
import resolveFileUrl from '../api/resolveFileUrl';
import './EmployerProfilePage.css';

const COMPANY_SIZE_OPTIONS = [
  { value: 'MICRO', label: 'Micro' }, { value: 'SMALL', label: 'Small' },
  { value: 'MEDIUM', label: 'Medium' }, { value: 'LARGE', label: 'Large' },
  { value: 'ENTERPRISE', label: 'Enterprise' },
];

const INDUSTRY_OPTIONS = [
  { value: 'Software', label: 'Software' }, { value: 'Healthcare', label: 'Healthcare' },
  { value: 'Finance', label: 'Finance' }, { value: 'Education', label: 'Education' },
];

const CURRENT_YEAR = new Date().getFullYear();
const FOUNDED_YEAR_OPTIONS = Array.from(
  { length: CURRENT_YEAR - 999 },
  (_, index) => {
    const year = String(CURRENT_YEAR - index);
    return { value: year, label: year };
  },
);

const EMPTY_FORM = {
  companyName: '', companyDescription: '', contactPersonName: '', industry: '', companySize: '',
  foundedYear: '', careersContactEmail: '', website: '', companyAddress: '', phoneNumber: '',
};

function toForm(profile) {
  return Object.fromEntries(Object.keys(EMPTY_FORM).map((field) => [field, profile?.[field] ?? '']));
}

function displayValue(value) {
  return value === null || value === undefined || value === '' ? 'Not provided' : value;
}

function companyInitials(name) {
  if (!name?.trim()) return '—';
  return name.trim().split(/\s+/).slice(0, 2).map((word) => word[0]?.toUpperCase()).join('');
}

function validate(form) {
  const errors = {};
  const year = form.foundedYear === '' ? null : Number(form.foundedYear);
  if (!form.companyName.trim()) errors.companyName = 'Company name is required';
  if (form.companyName.trim().length > 150) errors.companyName = 'Company name must not exceed 150 characters';
  if (form.companyDescription.length > 2000) errors.companyDescription = 'Company description must not exceed 2000 characters';
  if (form.contactPersonName.length > 120) errors.contactPersonName = 'Contact person name must not exceed 120 characters';
  if (form.industry.length > 120) errors.industry = 'Industry must not exceed 120 characters';
  if (year !== null && (!Number.isInteger(year) || year < 1000 || year > CURRENT_YEAR)) errors.foundedYear = `Founded year must be between 1000 and ${CURRENT_YEAR}`;
  if (form.careersContactEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.careersContactEmail)) errors.careersContactEmail = 'Careers contact email must be valid';
  if (form.website && !/^https?:\/\/.+/i.test(form.website)) errors.website = 'Website must begin with http:// or https://';
  if (form.phoneNumber && !/^\+1\d{10}$/.test(form.phoneNumber)) errors.phoneNumber = 'Enter a valid 10-digit phone number';
  return errors;
}

export default function EmployerProfilePage() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [errors, setErrors] = useState({});
  const [status, setStatus] = useState('loading');
  const [isEditing, setIsEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [toast, setToast] = useState(null);

  const handleAuthError = useCallback((error) => {
    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('accessToken');
      navigate('/live-login', { replace: true });
      return true;
    }
    return false;
  }, [navigate]);

  const loadProfile = useCallback(async () => {
    try {
      const loadedProfile = await getEmployerProfile();
      setProfile(loadedProfile);
      setForm(toForm(loadedProfile));
      setStatus('ready');
    } catch (error) {
      if (!handleAuthError(error)) setStatus('error');
    }
  }, [handleAuthError]);

  useEffect(() => {
    let current = true;
    getEmployerProfile().then((loadedProfile) => {
      if (!current) return;
      setProfile(loadedProfile);
      setForm(toForm(loadedProfile));
      setStatus('ready');
    }).catch((error) => { if (current && !handleAuthError(error)) setStatus('error'); });
    return () => { current = false; };
  }, [handleAuthError]);

  const details = useMemo(() => [
    ['Employer name', profile?.companyName],
    ['Industry', profile?.industry],
    ['Company size', profile?.companySize ? COMPANY_SIZE_OPTIONS.find((option) => option.value === profile.companySize)?.label : null],
    ['Founded', profile?.foundedYear],
    ['Careers contact', profile?.careersContactEmail],
    ['Website', profile?.website],
  ], [profile]);

  const changeField = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({ ...current, [name]: value }));
    setErrors((current) => ({ ...current, [name]: undefined }));
  };

  const cancelEditing = () => { setForm(toForm(profile)); setErrors({}); setIsEditing(false); };

  const saveProfile = async (event) => {
    event.preventDefault();
    const validationErrors = validate(form);
    if (Object.keys(validationErrors).length) { setErrors(validationErrors); return; }
    setIsSaving(true);
    setToast(null);
    try {
      const savedProfile = await updateEmployerProfile({
        ...form,
        foundedYear: form.foundedYear === '' ? null : Number(form.foundedYear),
        removeCompanyLogo: false,
      });
      setProfile(savedProfile);
      setForm(toForm(savedProfile));
      setIsEditing(false);
      setToast({ variant: 'success', message: 'Employer profile updated successfully' });
    } catch (error) {
      if (!handleAuthError(error)) {
        const backendErrors = error.response?.data?.data;
        if (backendErrors && typeof backendErrors === 'object') setErrors(backendErrors);
        setToast({ variant: 'error', message: error.response?.data?.message || 'Unable to update the employer profile' });
      }
    } finally { setIsSaving(false); }
  };

  return (
    <div className="wp-employer-profile wp-employer-profile__workspace">
      {status === 'loading' && <Loader size="lg" label="Loading employer profile…" />}
      {status === 'error' && <EmptyState title="Unable to load employer profile" description="Check your connection and try again." action={<Button onClick={() => { setStatus('loading'); loadProfile(); }}>Retry</Button>} />}
      {status === 'ready' && (
        <>
          {toast && <Toast {...toast} onClose={() => setToast(null)} />}
          <header className="wp-employer-profile__heading">
            <h1>Employer profile</h1>
            <p>Manage the company information displayed throughout Waypoint.</p>
          </header>
          {!isEditing && (
            <section className="wp-employer-profile__summary" aria-labelledby="profile-summary-title">
              {profile.companyLogoUrl ? <img className="wp-employer-profile__logo" src={resolveFileUrl(profile.companyLogoUrl)} alt={`${profile.companyName} logo`} /> : <div className="wp-employer-profile__logo-fallback" aria-hidden="true">{companyInitials(profile.companyName)}</div>}
              <div className="wp-employer-profile__summary-copy"><h2 id="profile-summary-title">{displayValue(profile.companyName)}</h2>{profile.companyDescription && <p>{profile.companyDescription}</p>}</div>
              <Button onClick={() => { setForm(toForm(profile)); setErrors({}); setIsEditing(true); }}>Edit profile</Button>
            </section>
          )}

          {isEditing ? (
            <form className="wp-employer-profile__form" onSubmit={saveProfile} noValidate>
              <div className="wp-employer-profile__card-heading"><div><p>Company information</p></div></div>
              <div className="wp-employer-profile__form-grid">
                <TextInput name="companyName" label="Company name" value={form.companyName} onChange={changeField} error={errors.companyName} required />
                <Dropdown name="industry" label="Industry" value={form.industry} onChange={changeField} options={INDUSTRY_OPTIONS} error={errors.industry} />
                <Dropdown name="companySize" label="Company size" value={form.companySize} onChange={changeField} options={COMPANY_SIZE_OPTIONS} error={errors.companySize} />
                <Dropdown name="foundedYear" label="Founded year" value={String(form.foundedYear || '')} onChange={changeField} options={FOUNDED_YEAR_OPTIONS} placeholder="Select year" error={errors.foundedYear || errors.foundedYearNotInFuture} />
                <EmailInput name="careersContactEmail" label="Careers contact email" value={form.careersContactEmail} onChange={changeField} error={errors.careersContactEmail} />
                <TextInput name="website" label="Website" value={form.website} onChange={changeField} error={errors.website} placeholder="https://example.com" />
                <TextInput name="companyAddress" label="Company address" value={form.companyAddress} onChange={changeField} error={errors.companyAddress} />
                <PhoneInput name="phoneNumber" label="Phone number" value={form.phoneNumber} onChange={changeField} error={errors.phoneNumber} />
                <TextInput name="contactPersonName" label="Contact person" value={form.contactPersonName} onChange={changeField} error={errors.contactPersonName} />
                <TextArea className="wp-employer-profile__description-field" name="companyDescription" label="Company description" value={form.companyDescription} onChange={changeField} error={errors.companyDescription} maxLength={2000} rows={6} />
              </div>
              <div className="wp-employer-profile__form-actions"><Button type="button" variant="secondary" onClick={cancelEditing} disabled={isSaving}>Cancel</Button><Button type="submit" loading={isSaving}>Save changes</Button></div>
            </form>
          ) : (
            <section className="wp-employer-profile__details" aria-labelledby="company-details-title">
              <div className="wp-employer-profile__card-heading"><div><p>Company information</p><h2 id="company-details-title">Company details</h2></div></div>
              <dl>{details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{label === 'Website' && /^https?:\/\//i.test(value || '') ? <a href={value} target="_blank" rel="noopener noreferrer">{value}</a> : displayValue(value)}</dd></div>)}</dl>
            </section>
          )}
        </>
      )}
    </div>
  );
}
