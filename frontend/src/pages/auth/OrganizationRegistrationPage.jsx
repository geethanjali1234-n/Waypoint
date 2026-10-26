import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Button, Dropdown, EmailInput, ImageUpload, PageHeader, TextArea, TextInput, ValidationMessage,
} from '../../components';
import authApi from '../../api/authApi';
import AuthLayout from './components/AuthLayout';
import AuthSidePanel from './components/AuthSidePanel';
import './OrganizationRegistrationPage.css';

const COMPANY_SIZES = ['MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'ENTERPRISE'];
const COMPANY_SIZE_OPTIONS = COMPANY_SIZES.map((value) => ({
  value,
  label: value.charAt(0) + value.slice(1).toLowerCase(),
}));

// Same choices as Organization Settings, so a new organization starts complete.
const PAYROLL_PERIOD_OPTIONS = [
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'BIWEEKLY', label: 'Biweekly' },
  { value: 'MONTHLY', label: 'Monthly' },
];

const schema = z.object({
  organizationName: z.string().trim().min(1, 'Organization name is required'),
  organizationEmail: z.string().trim().email('Enter a valid company contact email'),
  phoneNumber: z.string().trim().min(1, 'Phone number is required'),
  state: z.string().trim().min(1, 'State is required'),
  city: z.string().trim().min(1, 'City is required'),
  streetAddress: z.string().trim().min(1, 'Street address is required'),
  zipCode: z.string().trim().min(1, 'ZIP code is required'),
  organizationSize: z.enum(COMPANY_SIZES, { message: 'Company size is required' }),
  industry: z.string().trim().min(1, 'Industry is required'),
  website: z.string(),
  description: z.string().max(2000, 'Description must be 2000 characters or fewer'),
  einNumber: z.string().trim().regex(/^\d{2}-?\d{7}$/, 'EIN must be 9 digits, e.g. 12-3456789'),
  eVerifyNumber: z.string().trim().min(1, 'E-Verify number is required'),
  payrollPeriod: z.enum(PAYROLL_PERIOD_OPTIONS.map((option) => option.value), { message: 'Payroll period is required' }),
  adminName: z.string().trim().min(1, 'Admin name is required'),
  adminEmail: z.string().trim().email('Enter a valid Admin email'),
  adminPhoneNumber: z.string().trim().min(1, 'Admin contact number is required'),
});

const defaults = {
  organizationName: '', organizationEmail: '', phoneNumber: '', state: '', city: '',
  streetAddress: '', zipCode: '', organizationSize: '', industry: '', website: '',
  description: '', einNumber: '', eVerifyNumber: '', payrollPeriod: '',
  adminName: '', adminEmail: '', adminPhoneNumber: '',
};

const LOGO_TYPES = ['image/png', 'image/jpeg'];
const LOGO_MAX_BYTES = 2 * 1024 * 1024;
const orgInitials = (name) => name?.trim() ? name.trim().split(/\s+/).slice(0, 2).map((word) => word[0]?.toUpperCase()).join('') : '';

const fieldError = (error) => error?.response?.data?.message || 'Unable to complete registration. Please try again.';

export default function OrganizationRegistrationPage() {
  const navigate = useNavigate();
  const [pending, setPending] = useState(null);
  const [otp, setOtp] = useState('');
  const [error, setError] = useState('');
  const [verifying, setVerifying] = useState(false);
  const [resending, setResending] = useState(false);
  const [logo, setLogo] = useState(null);
  const [logoPreviewUrl, setLogoPreviewUrl] = useState(null);
  const [logoError, setLogoError] = useState('');
  const { control, handleSubmit, setError: setFieldError, formState: { errors, isSubmitting } } = useForm({
    resolver: zodResolver(schema), defaultValues: defaults,
  });
  const organizationName = useWatch({ control, name: 'organizationName' });

  useEffect(() => () => { if (logoPreviewUrl) URL.revokeObjectURL(logoPreviewUrl); }, [logoPreviewUrl]);

  const selectLogo = (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!LOGO_TYPES.includes(file.type) || file.size > LOGO_MAX_BYTES) {
      setLogoError('Use a PNG or JPEG logo no larger than 2 MB.');
      return;
    }
    setLogoError(''); setLogo(file); setLogoPreviewUrl(URL.createObjectURL(file));
  };

  const removeLogo = () => { setLogo(null); setLogoPreviewUrl(null); setLogoError(''); };

  const start = async (values) => {
    setError('');
    try {
      const response = logo
        ? await authApi.startOrganizationRegistration(values, logo)
        : await authApi.startOrganizationRegistration(values);
      setPending(response.data.data);
    } catch (requestError) {
      const body = requestError?.response?.data;
      if (body?.data && typeof body.data === 'object') {
        Object.entries(body.data).forEach(([name, message]) => setFieldError(name, { type: 'server', message }));
      } else setError(fieldError(requestError));
    }
  };

  const verify = async (event) => {
    event.preventDefault();
    if (!/^\d{6}$/.test(otp)) return setError('Enter the 6-digit verification code.');
    setError(''); setVerifying(true);
    try {
      await authApi.completeOrganizationRegistration({ registrationId: pending.registrationId, otp });
      navigate('/myhr/login', { replace: true });
    } catch (requestError) {
      setError(fieldError(requestError)); setVerifying(false);
    }
  };

  const resend = async () => {
    setError(''); setResending(true);
    try {
      await authApi.resendOrganizationRegistrationOtp(pending.registrationId);
    } catch (requestError) {
      setError(fieldError(requestError));
    } finally {
      setResending(false);
    }
  };

  return (
    <AuthLayout className="wp-organization-registration" sidePanel={
      <AuthSidePanel workspaceLabel="MY HR" heading="Create your organization workspace."
        description="Verify your Admin email, then use the existing My HR login to manage your company." showLogo />
    }>
      <PageHeader title={pending ? 'Verify Admin email' : 'Create Organization Account'}
        subtitle={pending ? `Enter the code sent to ${pending.adminEmail}` : 'Set up your company and initial Company Admin.'} />
      {error && <ValidationMessage message={error} variant="error" />}
      {pending ? (
        <form className="wp-organization-registration__form" onSubmit={verify} noValidate>
          <TextInput label="Verification code" name="otp" value={otp}
            onChange={(event) => setOtp(event.target.value)} placeholder="123456" required />
          <Button type="submit" variant="primary" fullWidth loading={verifying}>Verify and create account</Button>
          <Button type="button" variant="secondary" fullWidth loading={resending} onClick={resend}>Resend code</Button>
        </form>
      ) : (
        <form className="wp-organization-registration__form" onSubmit={handleSubmit(start)} noValidate>
          <h2>Company details</h2>
          <ImageUpload name="organizationLogo" label="Organization logo (optional)" previewUrl={logoPreviewUrl || undefined}
            initials={orgInitials(organizationName)} onUpload={selectLogo} onRemove={logo ? removeLogo : undefined}
            helperText="PNG or JPG, up to 2 MB. Shown on organization documents." />
          {logoError && <ValidationMessage message={logoError} variant="error" />}
          <Field control={control} errors={errors} name="organizationName" label="Organization name" />
          <EmailField control={control} errors={errors} name="organizationEmail" label="Company contact email" />
          <Field control={control} errors={errors} name="phoneNumber" label="Company phone" />
          <Field control={control} errors={errors} name="streetAddress" label="Street address" />
          <div className="wp-organization-registration__row">
            <Field control={control} errors={errors} name="city" label="City" />
            <Field control={control} errors={errors} name="state" label="State" />
            <Field control={control} errors={errors} name="zipCode" label="ZIP code" />
          </div>
          <Controller name="organizationSize" control={control} render={({ field }) =>
            <Dropdown {...field} label="Company size" options={COMPANY_SIZE_OPTIONS} error={errors.organizationSize?.message} required />} />
          <Field control={control} errors={errors} name="industry" label="Industry" />
          <Field control={control} errors={errors} name="website" label="Website (optional)" required={false} />
          <Controller name="description" control={control} render={({ field }) =>
            <TextArea {...field} label="Organization description (optional)" rows={3} maxLength={2000} error={errors.description?.message} />} />
          <h2>Compliance</h2>
          <div className="wp-organization-registration__pair">
            <Field control={control} errors={errors} name="einNumber" label="EIN number" />
            <Field control={control} errors={errors} name="eVerifyNumber" label="E-Verify number" />
          </div>
          <h2>Payroll &amp; Timesheets</h2>
          <Controller name="payrollPeriod" control={control} render={({ field }) =>
            <Dropdown {...field} label="Payroll period" options={PAYROLL_PERIOD_OPTIONS} placeholder="Select payroll period" error={errors.payrollPeriod?.message} required />} />
          <h2>Initial Company Admin</h2>
          <Field control={control} errors={errors} name="adminName" label="Admin name" />
          <div className="wp-organization-registration__pair">
            <EmailField control={control} errors={errors} name="adminEmail" label="Admin login email" />
            <Field control={control} errors={errors} name="adminPhoneNumber" label="Admin contact number" />
          </div>
          <Button type="submit" variant="primary" fullWidth loading={isSubmitting}>Send verification code</Button>
        </form>
      )}
      <p className="wp-organization-registration__login"><Link to="/myhr/login">Back to My HR login</Link></p>
    </AuthLayout>
  );
}

function Field({ control, errors, name, label, required = true }) {
  return <Controller name={name} control={control} render={({ field }) =>
    <TextInput {...field} label={label} error={errors[name]?.message} required={required} />} />;
}

function EmailField({ control, errors, name, label }) {
  return <Controller name={name} control={control} render={({ field }) =>
    <EmailInput {...field} label={label} error={errors[name]?.message} required />} />;
}
