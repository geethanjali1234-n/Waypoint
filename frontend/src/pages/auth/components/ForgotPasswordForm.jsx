import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, useNavigate } from 'react-router-dom';
import { Button, EmailInput, ValidationMessage } from '../../../components';
import authApi from '../../../api/authApi';
import { EMAIL_PATTERN } from '../validation/patterns';
import './ForgotPasswordForm.css';

const forgotPasswordSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').regex(EMAIL_PATTERN, 'Enter a valid email address'),
});

export default function ForgotPasswordForm() {
  const navigate = useNavigate();
  const [formError, setFormError] = useState(null);
  const {
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(forgotPasswordSchema), defaultValues: { email: '' } });

  const onSubmit = async ({ email }) => {
    setFormError(null);
    const normalizedEmail = email.trim().toLowerCase();

    try {
      await authApi.forgotPassword({ email: normalizedEmail });
      navigate(`/reset-password?email=${encodeURIComponent(normalizedEmail)}`);
    } catch (submitError) {
      const responseBody = submitError?.response?.data;
      if (responseBody?.data?.email) {
        setError('email', { type: 'server', message: responseBody.data.email });
      } else {
        setFormError(responseBody?.message ?? 'We could not send a reset code. Please try again.');
      }
    }
  };

  return (
    <form className="wp-forgot-password-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {formError && <ValidationMessage message={formError} variant="error" />}

      <Controller
        name="email"
        control={control}
        render={({ field }) => (
          <EmailInput
            label="Email address"
            name="email"
            value={field.value}
            onChange={field.onChange}
            error={errors.email?.message}
            placeholder="you@example.com"
            required
          />
        )}
      />

      <Button type="submit" variant="primary" fullWidth loading={isSubmitting}>
        Send reset code
      </Button>

      <p className="wp-forgot-password-form__help">The code expires in 10 minutes and can only be used once.</p>
      <Link to="/myhr/login" className="wp-forgot-password-form__link">Back to sign in</Link>
    </form>
  );
}
