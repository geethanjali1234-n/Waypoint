import { useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, useNavigate } from 'react-router-dom';
import { Button, PasswordInput, TextInput, Toast, ValidationMessage } from '../../../components';
import authApi from '../../../api/authApi';
import './ResetPasswordForm.css';

const resetPasswordSchema = z
  .object({
    otp: z.string().trim().regex(/^\d{6}$/, 'Code must be exactly 6 digits'),
    newPassword: z.string().min(8, 'Password must be at least 8 characters'),
    confirmPassword: z.string().min(1, 'Confirm password is required'),
  })
  .refine(({ newPassword, confirmPassword }) => newPassword === confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

function serverMessage(error, fallback) {
  const responseBody = error?.response?.data;
  if (responseBody?.data && typeof responseBody.data === 'object') {
    return Object.values(responseBody.data)[0] ?? responseBody.message ?? fallback;
  }
  return responseBody?.message ?? fallback;
}

export default function ResetPasswordForm({ email }) {
  const navigate = useNavigate();
  const [formError, setFormError] = useState(null);
  const [verifying, setVerifying] = useState(false);
  const [verifiedCode, setVerifiedCode] = useState(null);
  const [completed, setCompleted] = useState(false);
  const {
    control,
    handleSubmit,
    getValues,
    setError,
    clearErrors,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { otp: '', newPassword: '', confirmPassword: '' },
  });

  const currentCode = useWatch({ control, name: 'otp' });
  const codeIsVerified = Boolean(verifiedCode && currentCode === verifiedCode);

  const verifyCode = async () => {
    const otp = getValues('otp').trim();
    setFormError(null);
    clearErrors('otp');
    if (!/^\d{6}$/.test(otp)) {
      setError('otp', { type: 'manual', message: 'Code must be exactly 6 digits' });
      return;
    }

    setVerifying(true);
    try {
      await authApi.verifyResetOtp({ email, otp });
      setVerifiedCode(otp);
    } catch (error) {
      setVerifiedCode(null);
      setError('otp', { type: 'server', message: serverMessage(error, 'Invalid or expired code.') });
    } finally {
      setVerifying(false);
    }
  };

  const onSubmit = async ({ otp, newPassword, confirmPassword }) => {
    setFormError(null);
    if (!codeIsVerified) {
      setFormError('Verify your code before choosing a new password.');
      return;
    }

    try {
      await authApi.resetPassword({ email, otp, newPassword, confirmPassword });
      setCompleted(true);
      window.setTimeout(() => navigate('/myhr/login', { replace: true }), 1500);
    } catch (error) {
      setFormError(serverMessage(error, 'We could not reset your password. Please try again.'));
    }
  };

  return (
    <form className="wp-reset-password-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {completed && <Toast variant="success" message="Password reset successfully. Redirecting to sign in…" />}
      {formError && <ValidationMessage message={formError} variant="error" />}

      <div className="wp-reset-password-form__code-row">
        <Controller
          name="otp"
          control={control}
          render={({ field }) => (
            <TextInput
              label="Six-digit code"
              name="otp"
              value={field.value}
              onChange={(event) => {
                field.onChange(event.target.value.replace(/\D/g, '').slice(0, 6));
                setVerifiedCode(null);
              }}
              error={errors.otp?.message}
              placeholder="123456"
              disabled={completed}
              required
            />
          )}
        />
        <Button
          type="button"
          variant="secondary"
          onClick={verifyCode}
          loading={verifying}
          disabled={completed || codeIsVerified}
        >
          {codeIsVerified ? 'Verified' : 'Verify code'}
        </Button>
      </div>

      {codeIsVerified && <p className="wp-reset-password-form__verified">Code verified. You can now set a new password.</p>}

      <Controller
        name="newPassword"
        control={control}
        render={({ field }) => (
          <PasswordInput
            label="New password"
            name="newPassword"
            value={field.value}
            onChange={field.onChange}
            error={errors.newPassword?.message}
            hint="Use at least 8 characters."
            disabled={!codeIsVerified || completed}
            required
          />
        )}
      />

      <Controller
        name="confirmPassword"
        control={control}
        render={({ field }) => (
          <PasswordInput
            label="Confirm new password"
            name="confirmPassword"
            value={field.value}
            onChange={field.onChange}
            error={errors.confirmPassword?.message}
            disabled={!codeIsVerified || completed}
            required
          />
        )}
      />

      <Button type="submit" variant="primary" fullWidth loading={isSubmitting} disabled={!codeIsVerified || completed}>
        Reset password
      </Button>

      <div className="wp-reset-password-form__links">
        <Link to="/forgot-password">Send a new code</Link>
        <span aria-hidden="true">·</span>
        <Link to="/myhr/login">Back to sign in</Link>
      </div>
    </form>
  );
}
