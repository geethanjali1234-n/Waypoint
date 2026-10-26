import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { TextInput, Button, ValidationMessage, Toast } from '../../../components';
import authApi from '../../../api/authApi';
import './VerifyOtpForm.css';

const SUCCESS_MESSAGE = 'Email verified successfully. Redirecting to sign in...';
const REDIRECT_DELAY_MS = 1500;

const verifyOtpSchema = z.object({
  otp: z
    .string()
    .trim()
    .min(1, { message: 'Enter the 6-digit code' })
    .regex(/^\d{6}$/, { message: 'Code must be exactly 6 digits' }),
});

/**
 * VerifyOtpForm - submits the 6-digit email verification code for the given
 * email to POST /api/auth/verify-otp. On success, shows a confirmation and
 * redirects to /live-login after a short delay. This never logs the user in,
 * issues a token, or creates a session - login remains a separate flow.
 *
 * @param {Object} props
 * @param {string} props.email - Email address the code was sent to.
 *
 * @example
 * <VerifyOtpForm email="jordan.torres@waypoint.local" />
 */
export default function VerifyOtpForm({ email }) {
  const navigate = useNavigate();
  const [formError, setFormError] = useState(null);
  const [verified, setVerified] = useState(false);

  const {
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(verifyOtpSchema),
    defaultValues: { otp: '' },
  });

  const onSubmit = async ({ otp }) => {
    setFormError(null);

    try {
      await authApi.verifyOtp({ email, otp });
      setVerified(true);
      setTimeout(() => navigate('/live-login'), REDIRECT_DELAY_MS);
    } catch (submitError) {
      const responseBody = submitError?.response?.data;

      if (responseBody?.data && typeof responseBody.data === 'object') {
        const [firstMessage] = Object.values(responseBody.data);
        setFormError(firstMessage ?? responseBody.message ?? 'Invalid or expired code.');
      } else if (responseBody?.message) {
        setFormError(responseBody.message);
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    }
  };

  return (
    <form className="wp-verify-otp-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {verified && <Toast variant="success" message={SUCCESS_MESSAGE} />}

      {formError && <ValidationMessage message={formError} variant="error" />}

      <Controller
        name="otp"
        control={control}
        render={({ field }) => (
          <TextInput
            label="Verification code"
            name="otp"
            value={field.value}
            onChange={field.onChange}
            error={errors.otp?.message}
            placeholder="123456"
            disabled={verified}
            required
          />
        )}
      />

      <Button type="submit" variant="primary" fullWidth loading={isSubmitting} disabled={verified}>
        Verify email
      </Button>
    </form>
  );
}
