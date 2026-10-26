import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, PageHeader, PasswordInput, ValidationMessage } from '../../components';
import authApi from '../../api/authApi';
import { getMyHrSession, getWorkspaceHomePath, saveMyHrSession } from '../../auth/myHrSession';
import '../ChangePasswordPage.css';

const EMPTY = { currentPassword: '', newPassword: '', confirmPassword: '' };

export default function MyHrChangePasswordPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    if (!form.currentPassword || !form.newPassword || !form.confirmPassword) return setError('All fields are required.');
    if (form.newPassword.length < 8) return setError('New password must be at least 8 characters.');
    if (form.newPassword !== form.confirmPassword) return setError('New password and confirm password do not match.');
    setError('');
    setSubmitting(true);
    try {
      await authApi.changeFirstLoginPassword(form);
      const session = { ...getMyHrSession(), mustChangePassword: false };
      saveMyHrSession(session);
      navigate(getWorkspaceHomePath(session.role), { replace: true });
    } catch (requestError) {
      setError(requestError?.response?.data?.message || 'Unable to change password. Please try again.');
      setSubmitting(false);
    }
  };

  const change = (name) => (event) => setForm((current) => ({ ...current, [name]: event.target.value }));

  return (
    <div className="wp-change-password-page">
      <PageHeader title="Create your new password" subtitle="Change your temporary password to continue to your workspace." />
      <Card className="wp-change-password-page__card">
        <form onSubmit={submit} className="wp-change-password-page__form">
          <PasswordInput label="Temporary Password" name="currentPassword" value={form.currentPassword} onChange={change('currentPassword')} required />
          <PasswordInput label="New Password" name="newPassword" value={form.newPassword} onChange={change('newPassword')} required />
          <PasswordInput label="Confirm New Password" name="confirmPassword" value={form.confirmPassword} onChange={change('confirmPassword')} required />
          {error && <ValidationMessage message={error} variant="error" />}
          <Button type="submit" variant="primary" fullWidth loading={submitting}>Change password</Button>
        </form>
      </Card>
    </div>
  );
}
