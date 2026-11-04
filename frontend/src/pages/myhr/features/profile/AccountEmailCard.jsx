import { useEffect, useState } from 'react';
import { Button, Card, EmailInput, PasswordInput, TextInput, ValidationMessage } from '../../../../components';
import authApi from '../../../../api/authApi';
import { saveMyHrAuthentication } from '../../../../auth/myHrSession';

const EMPTY_FORM = { newEmail: '', currentPassword: '' };
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_PATTERN = /^\d{6}$/;
const DEFAULT_RESEND_SECONDS = 60;

/**
 * Changing the login email takes two steps so the new address is proven before anything changes: the current password
 * requests a six-digit code that is emailed to the NEW address, and only entering that code changes the account. Until
 * then the existing login email stays active. The new address for the second step is held by the server, not sent again.
 */
export default function AccountEmailCard({ accountEmail, onSaved }) {
  const [step, setStep] = useState('view'); // 'view' | 'request' | 'confirm'
  const [form, setForm] = useState(EMPTY_FORM);
  const [pendingEmail, setPendingEmail] = useState('');
  const [code, setCode] = useState('');
  const [resendIn, setResendIn] = useState(0);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (resendIn <= 0) return undefined;
    const timer = setTimeout(() => setResendIn((seconds) => seconds - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendIn]);

  const change = (name) => (event) => setForm((current) => ({ ...current, [name]: event.target.value }));

  const reset = () => {
    setStep('view'); setForm(EMPTY_FORM); setPendingEmail(''); setCode(''); setResendIn(0); setError('');
  };

  const requestCode = async () => {
    const nextEmail = form.newEmail.trim();
    if (!EMAIL_PATTERN.test(nextEmail)) {
      setError('Enter a valid new email address.');
      return;
    }
    if (nextEmail.toLowerCase() === String(accountEmail || '').trim().toLowerCase()) {
      setError('New email must be different from the current email.');
      return;
    }
    if (!form.currentPassword) {
      setError('Current password is required.');
      return;
    }

    setError(''); setSuccess(''); setSaving(true);
    try {
      const sent = (await authApi.requestEmailChange({ newEmail: nextEmail, currentPassword: form.currentPassword }))?.data?.data;
      setPendingEmail(sent?.newEmail || nextEmail);
      setResendIn(sent?.resendAfterSeconds ?? DEFAULT_RESEND_SECONDS);
      setForm((current) => ({ ...current, currentPassword: '' }));
      setCode('');
      setStep('confirm');
    } catch (requestError) {
      setError(requestError?.response?.data?.message || 'Unable to send the verification code. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  const submitRequest = (event) => {
    event.preventDefault();
    requestCode();
  };

  const submitCode = async (event) => {
    event.preventDefault();
    const entered = code.trim();
    if (!CODE_PATTERN.test(entered)) {
      setError('Enter the 6-digit verification code.');
      return;
    }

    setError(''); setSaving(true);
    try {
      const updated = (await authApi.confirmEmailChange({ otp: entered }))?.data?.data;
      saveMyHrAuthentication(updated.accessToken, updated);
      onSaved(updated.email);
      reset();
      setSuccess('Account email updated. Use the new email the next time you sign in.');
    } catch (requestError) {
      setError(requestError?.response?.data?.message || 'Unable to verify the code. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  const resend = async () => {
    if (resendIn > 0 || saving) return;
    // The password is not kept after the first step, so asking again returns to it.
    setStep('request'); setError(''); setCode('');
  };

  return <Card title="Account Email" actions={step === 'view' && <Button type="button" variant="secondary" onClick={() => { setStep('request'); setSuccess(''); }}>Change account email</Button>}>
    {step === 'view' && <>
      <p>{accountEmail || 'Not provided'}</p>
      <p>This is your login email and the organization Admin Email used for signing documents.</p>
      {success && <ValidationMessage message={success} variant="success" />}
    </>}

    {step === 'request' && <form onSubmit={submitRequest} noValidate>
      <p>Your current login email stays active until you confirm the new one with a code.</p>
      <EmailInput name="newEmail" label="New account email" value={form.newEmail} onChange={change('newEmail')} required />
      <PasswordInput name="currentPassword" label="Current password" value={form.currentPassword} onChange={change('currentPassword')} required />
      {error && <ValidationMessage message={error} variant="error" />}
      <div className="wp-change-password-page__actions">
        <Button type="button" variant="secondary" onClick={reset} disabled={saving}>Cancel</Button>
        <Button type="submit" variant="primary" loading={saving} disabled={saving}>Send verification code</Button>
      </div>
    </form>}

    {step === 'confirm' && <form onSubmit={submitCode} noValidate>
      <p>We sent a 6-digit code to <strong>{pendingEmail}</strong>. Enter it to confirm the new email. Your current login email ({accountEmail}) stays active until you do.</p>
      <TextInput name="otp" label="Verification code" value={code} onChange={(event) => setCode(event.target.value)} placeholder="123456" required />
      {error && <ValidationMessage message={error} variant="error" />}
      <div className="wp-change-password-page__actions">
        <Button type="button" variant="secondary" onClick={reset} disabled={saving}>Cancel</Button>
        <Button type="button" variant="secondary" onClick={resend} disabled={saving || resendIn > 0}>
          {resendIn > 0 ? `Send a new code in ${resendIn}s` : 'Send a new code'}
        </Button>
        <Button type="submit" variant="primary" loading={saving} disabled={saving}>Confirm email</Button>
      </div>
    </form>}
  </Card>;
}
