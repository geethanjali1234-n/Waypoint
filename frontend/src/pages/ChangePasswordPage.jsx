import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader, Card, PasswordInput, Button, ValidationMessage, Toast } from '../components';
import authApi from '../api/authApi';
import './ChangePasswordPage.css';

const EMPTY_FORM = { currentPassword: '', newPassword: '', confirmPassword: '' };
const REDIRECT_DELAY_MS = 1500;

function validate(form) {
  if (!form.currentPassword || !form.newPassword || !form.confirmPassword) {
    return 'All fields are required.';
  }

  if (form.newPassword.length < 8) {
    return 'New password must be at least 8 characters.';
  }

  if (form.newPassword !== form.confirmPassword) {
    return 'New password and confirm password do not match.';
  }

  return null;
}

export default function ChangePasswordPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY_FORM);
  const [validationError, setValidationError] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [toast, setToast] = useState(null);
  const redirectTimeoutRef = useRef(null);

  useEffect(() => {
    return () => {
      if (redirectTimeoutRef.current) {
        clearTimeout(redirectTimeoutRef.current);
      }
    };
  }, []);

  const handleFieldChange = (field) => (event) => {
    setForm((previous) => ({ ...previous, [field]: event.target.value }));
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    if (isSubmitting) return;

    const message = validate(form);
    if (message) {
      setValidationError(message);
      return;
    }

    setValidationError(null);
    setToast(null);
    setIsSubmitting(true);

    authApi
      .changePassword({
        currentPassword: form.currentPassword,
        newPassword: form.newPassword,
        confirmPassword: form.confirmPassword,
      })
      .then(() => {
        setForm(EMPTY_FORM);
        localStorage.removeItem('accessToken');
        setToast({ message: 'Password changed successfully. Please log in again.', variant: 'success' });

        redirectTimeoutRef.current = setTimeout(() => {
          navigate('/live-login', { replace: true });
        }, REDIRECT_DELAY_MS);
      })
      .catch((error) => {
        const message = error?.response?.data?.message || 'Unable to change password. Please try again.';
        setToast({ message, variant: 'error' });
        setIsSubmitting(false);
      });
  };

  return (
    <div className="wp-change-password-page">
      <PageHeader title="Change Password" subtitle="Update the password for your account" />

      {toast && (
        <div className="wp-change-password-page__toast">
          <Toast message={toast.message} variant={toast.variant} onClose={() => setToast(null)} />
        </div>
      )}

      <Card className="wp-change-password-page__card">
        <form onSubmit={handleSubmit} className="wp-change-password-page__form">
          <PasswordInput
            label="Current Password"
            name="currentPassword"
            value={form.currentPassword}
            onChange={handleFieldChange('currentPassword')}
            required
          />
          <PasswordInput
            label="New Password"
            name="newPassword"
            value={form.newPassword}
            onChange={handleFieldChange('newPassword')}
            required
          />
          <PasswordInput
            label="Confirm New Password"
            name="confirmPassword"
            value={form.confirmPassword}
            onChange={handleFieldChange('confirmPassword')}
            required
          />

          {validationError && <ValidationMessage message={validationError} variant="error" />}

          <Button type="submit" variant="primary" loading={isSubmitting} disabled={isSubmitting} fullWidth>
            {isSubmitting ? 'Changing…' : 'Change Password'}
          </Button>
        </form>
      </Card>
    </div>
  );
}
