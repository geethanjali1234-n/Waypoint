import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, PasswordInput, ValidationMessage } from '../../components';
import authApi from '../../api/authApi';
import { clearMyHrAuthentication, getMyHrHomePath, getMyHrSession } from '../../auth/myHrSession';
import MyHrFeaturePage from './features/MyHrFeaturePage';
import '../ChangePasswordPage.css';

const EMPTY = { currentPassword: '', newPassword: '', confirmPassword: '' };

function validate(form) {
  if (!form.currentPassword || !form.newPassword || !form.confirmPassword) return 'All fields are required.';
  if (form.newPassword.length < 8) return 'New password must be at least 8 characters.';
  if (form.newPassword !== form.confirmPassword) return 'New password and confirm password do not match.';
  if (form.newPassword === form.currentPassword) return 'New password must be different from the current password.';
  return '';
}

export default function MyHrRegularChangePasswordPage() {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const change = (name) => (event) => setForm((current) => ({ ...current, [name]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const validationError = validate(form);
    if (validationError) {
      setError(validationError);
      return;
    }

    setError('');
    setSubmitting(true);
    try {
      await authApi.changePassword(form);
      setForm(EMPTY);
      clearMyHrAuthentication();
      navigate('/myhr/login', { replace: true });
    } catch (requestError) {
      setError(requestError?.response?.data?.message || 'Unable to change password. Please try again.');
      setSubmitting(false);
    }
  };

  return (
    <MyHrFeaturePage title="Change Password" description="Update the password for your My HR account." backTo={`${getMyHrHomePath(session?.role)}/dashboard`}>
      <div className="wp-change-password-page wp-change-password-page--regular">
      <Card className="wp-change-password-page__card">
        <form onSubmit={submit} className="wp-change-password-page__form" noValidate>
          <PasswordInput label="Current Password" name="currentPassword" value={form.currentPassword} onChange={change('currentPassword')} required />
          <PasswordInput label="New Password" name="newPassword" value={form.newPassword} onChange={change('newPassword')} required />
          <PasswordInput label="Confirm New Password" name="confirmPassword" value={form.confirmPassword} onChange={change('confirmPassword')} required />
          {error && <ValidationMessage message={error} variant="error" />}
          <div className="wp-change-password-page__actions">
            <Button type="button" variant="secondary" onClick={() => navigate(`${getMyHrHomePath(session?.role)}/dashboard`)} disabled={submitting}>Cancel</Button>
            <Button type="submit" variant="primary" loading={submitting} disabled={submitting}>Change password</Button>
          </div>
        </form>
      </Card>
      </div>
    </MyHrFeaturePage>
  );
}
