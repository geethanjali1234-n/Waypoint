import { useState } from 'react';
import { Button, Card, EmailInput, PasswordInput, ValidationMessage } from '../../../../components';
import authApi from '../../../../api/authApi';
import { saveMyHrAuthentication } from '../../../../auth/myHrSession';

const EMPTY_FORM = { newEmail: '', currentPassword: '' };
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function AccountEmailCard({ accountEmail, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [saving, setSaving] = useState(false);

  const change = (name) => (event) => setForm((current) => ({ ...current, [name]: event.target.value }));

  const cancel = () => {
    setEditing(false); setForm(EMPTY_FORM); setError('');
  };

  const submit = async (event) => {
    event.preventDefault();
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
      const updated = (await authApi.changeEmail({ newEmail: nextEmail, currentPassword: form.currentPassword }))?.data?.data;
      saveMyHrAuthentication(updated.accessToken, updated);
      onSaved(updated.email);
      setEditing(false); setForm(EMPTY_FORM); setSuccess('Account email updated.');
    } catch (requestError) {
      setError(requestError?.response?.data?.message || 'Unable to change account email. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  return <Card title="Account Email" actions={!editing && <Button type="button" variant="secondary" onClick={() => { setEditing(true); setSuccess(''); }}>Change account email</Button>}>
    {!editing ? <>
      <p>{accountEmail || 'Not provided'}</p>
      <p>This is your login email and the organization Admin Email used for signing documents.</p>
      {success && <ValidationMessage message={success} variant="success" />}
    </> : <form onSubmit={submit} noValidate>
      <EmailInput name="newEmail" label="New account email" value={form.newEmail} onChange={change('newEmail')} required />
      <PasswordInput name="currentPassword" label="Current password" value={form.currentPassword} onChange={change('currentPassword')} required />
      {error && <ValidationMessage message={error} variant="error" />}
      <div className="wp-change-password-page__actions">
        <Button type="button" variant="secondary" onClick={cancel} disabled={saving}>Cancel</Button>
        <Button type="submit" variant="primary" loading={saving} disabled={saving}>Save email</Button>
      </div>
    </form>}
  </Card>;
}
