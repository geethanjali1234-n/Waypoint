import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  TextInput,
  EmailInput,
  PhoneInput,
  PasswordInput,
  Dropdown,
  Button,
  ValidationMessage,
} from '../../../components';
import authApi from '../../../api/authApi';
import { EMAIL_PATTERN, US_PHONE_PATTERN } from '../validation/patterns';
import './EmployerRegistrationForm.css';

const COMPANY_SIZE_OPTIONS = [
  { label: 'Micro', value: 'MICRO' },
  { label: 'Small', value: 'SMALL' },
  { label: 'Medium', value: 'MEDIUM' },
  { label: 'Large', value: 'LARGE' },
  { label: 'Enterprise', value: 'ENTERPRISE' },
];

const KNOWN_FIELDS = [
  'companyName',
  'email',
  'companyAddress',
  'phoneNumber',
  'companySize',
  'password',
  'confirmPassword',
];

const employerRegistrationSchema = z
  .object({
    companyName: z.string().trim().min(1, { message: 'Company name is required' }),
    email: z
      .string()
      .trim()
      .min(1, { message: 'Email is required' })
      .regex(EMAIL_PATTERN, { message: 'Enter a valid email address' }),
    companyAddress: z.string().trim().min(1, { message: 'Company address is required' }),
    phoneNumber: z
      .string()
      .min(1, { message: 'Phone number is required' })
      .regex(US_PHONE_PATTERN, { message: 'Enter a valid 10-digit phone number' }),
    companySize: z.enum(['MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'ENTERPRISE'], {
      message: 'Company size is required',
    }),
    password: z.string().min(8, { message: 'Password must be at least 8 characters' }),
    confirmPassword: z.string().min(1, { message: 'Confirm password is required' }),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

/**
 * EmployerRegistrationForm - collects the fields required by
 * POST /api/auth/register/employer, validates them client-side with the same
 * rules as the backend, and maps backend validation errors back onto the
 * relevant fields on failure.
 *
 * On success, navigates straight to the OTP verification page - it does not
 * log the user in or leave them on this page, since the account still needs
 * email verification before it's usable.
 *
 * @example
 * <EmployerRegistrationForm />
 */
export default function EmployerRegistrationForm() {
  const navigate = useNavigate();
  const [formError, setFormError] = useState(null);

  const {
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(employerRegistrationSchema),
    defaultValues: {
      companyName: '',
      email: '',
      companyAddress: '',
      phoneNumber: '',
      companySize: '',
      password: '',
      confirmPassword: '',
    },
  });

  const onSubmit = async (values) => {
    setFormError(null);

    try {
      const response = await authApi.registerEmployer(values);
      const { userId, email } = response.data.data;
      navigate(`/verify-email?userId=${encodeURIComponent(userId)}&email=${encodeURIComponent(email)}`);
    } catch (submitError) {
      const responseBody = submitError?.response?.data;

      if (responseBody?.data && typeof responseBody.data === 'object') {
        Object.entries(responseBody.data).forEach(([field, message]) => {
          if (KNOWN_FIELDS.includes(field)) {
            setError(field, { type: 'server', message });
          } else {
            setFormError(message);
          }
        });
      } else if (responseBody?.message) {
        setFormError(responseBody.message);
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    }
  };

  return (
    <form className="wp-employer-registration-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {formError && <ValidationMessage message={formError} variant="error" />}

      <Controller
        name="companyName"
        control={control}
        render={({ field }) => (
          <TextInput
            label="Company name"
            name="companyName"
            value={field.value}
            onChange={field.onChange}
            error={errors.companyName?.message}
            required
          />
        )}
      />

      <Controller
        name="email"
        control={control}
        render={({ field }) => (
          <EmailInput
            label="Email"
            name="email"
            value={field.value}
            onChange={field.onChange}
            error={errors.email?.message}
            required
          />
        )}
      />

      <Controller
        name="companyAddress"
        control={control}
        render={({ field }) => (
          <TextInput
            label="Company address"
            name="companyAddress"
            value={field.value}
            onChange={field.onChange}
            error={errors.companyAddress?.message}
            required
          />
        )}
      />

      <Controller
        name="phoneNumber"
        control={control}
        render={({ field }) => (
          <PhoneInput
            label="Phone number"
            name="phoneNumber"
            value={field.value}
            onChange={field.onChange}
            error={errors.phoneNumber?.message}
            required
          />
        )}
      />

      <Controller
        name="companySize"
        control={control}
        render={({ field }) => (
          <Dropdown
            label="Company size"
            name="companySize"
            options={COMPANY_SIZE_OPTIONS}
            value={field.value}
            onChange={field.onChange}
            error={errors.companySize?.message}
            required
          />
        )}
      />

      <Controller
        name="password"
        control={control}
        render={({ field }) => (
          <PasswordInput
            label="Password"
            name="password"
            value={field.value}
            onChange={field.onChange}
            error={errors.password?.message}
            required
          />
        )}
      />

      <Controller
        name="confirmPassword"
        control={control}
        render={({ field }) => (
          <PasswordInput
            label="Confirm password"
            name="confirmPassword"
            value={field.value}
            onChange={field.onChange}
            error={errors.confirmPassword?.message}
            required
          />
        )}
      />

      <Button type="submit" variant="primary" fullWidth loading={isSubmitting}>
        Create employer account
      </Button>
    </form>
  );
}
