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
  MultiSelect,
  Button,
  ValidationMessage,
} from '../../../components';
import authApi from '../../../api/authApi';
import { SKILL_OPTIONS, PREFERRED_JOB_ROLE_OPTIONS } from './constants/registrationOptions';
import { EMAIL_PATTERN, US_PHONE_PATTERN } from '../validation/patterns';
import './JobSeekerRegistrationForm.css';

const KNOWN_FIELDS = [
  'firstName',
  'lastName',
  'email',
  'address',
  'phoneNumber',
  'skills',
  'preferredJobRoles',
  'password',
  'confirmPassword',
];

const jobSeekerRegistrationSchema = z
  .object({
    firstName: z.string().trim().min(1, { message: 'First name is required' }),
    lastName: z.string().trim().min(1, { message: 'Last name is required' }),
    email: z
      .string()
      .trim()
      .min(1, { message: 'Email is required' })
      .regex(EMAIL_PATTERN, { message: 'Enter a valid email address' }),
    address: z.string().trim().min(1, { message: 'Address is required' }),
    phoneNumber: z
      .string()
      .min(1, { message: 'Phone number is required' })
      .regex(US_PHONE_PATTERN, { message: 'Enter a valid 10-digit phone number' }),
    skills: z.array(z.string()).min(3, { message: 'Select at least 3 skills' }),
    preferredJobRoles: z.array(z.string()).min(1, { message: 'Select at least 1 preferred job role' }),
    password: z.string().min(8, { message: 'Password must be at least 8 characters' }),
    confirmPassword: z.string().min(1, { message: 'Confirm password is required' }),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

/**
 * JobSeekerRegistrationForm - collects the fields required by
 * POST /api/auth/register/job-seeker, validates them client-side with the same
 * rules as the backend, and maps backend validation errors back onto the
 * relevant fields on failure.
 *
 * Skills and preferred job roles are collected via searchable MultiSelect
 * fields and stay as arrays in form state from the start - no comma-split
 * transform is needed before submission.
 *
 * On success, navigates straight to the OTP verification page - it does not
 * log the user in or leave them on this page, since the account still needs
 * email verification before it's usable.
 *
 * @example
 * <JobSeekerRegistrationForm />
 */
export default function JobSeekerRegistrationForm() {
  const navigate = useNavigate();
  const [formError, setFormError] = useState(null);

  const {
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(jobSeekerRegistrationSchema),
    defaultValues: {
      firstName: '',
      lastName: '',
      email: '',
      address: '',
      phoneNumber: '',
      skills: [],
      preferredJobRoles: [],
      password: '',
      confirmPassword: '',
    },
  });

  const onSubmit = async (values) => {
    setFormError(null);

    try {
      const response = await authApi.registerJobSeeker(values);
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
    <form className="wp-job-seeker-registration-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {formError && <ValidationMessage message={formError} variant="error" />}

      <Controller
        name="firstName"
        control={control}
        render={({ field }) => (
          <TextInput
            label="First name"
            name="firstName"
            value={field.value}
            onChange={field.onChange}
            error={errors.firstName?.message}
            required
          />
        )}
      />

      <Controller
        name="lastName"
        control={control}
        render={({ field }) => (
          <TextInput
            label="Last name"
            name="lastName"
            value={field.value}
            onChange={field.onChange}
            error={errors.lastName?.message}
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
        name="address"
        control={control}
        render={({ field }) => (
          <TextInput
            label="Address"
            name="address"
            value={field.value}
            onChange={field.onChange}
            error={errors.address?.message}
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
        name="skills"
        control={control}
        render={({ field }) => (
          <MultiSelect
            label="Skills"
            name="skills"
            options={SKILL_OPTIONS}
            selected={field.value}
            onAdd={(value) => field.onChange([...field.value, value])}
            onRemove={(value) => field.onChange(field.value.filter((item) => item !== value))}
            error={errors.skills?.message}
            min={3}
            placeholder="Type to search skills..."
            required
          />
        )}
      />

      <Controller
        name="preferredJobRoles"
        control={control}
        render={({ field }) => (
          <MultiSelect
            label="Preferred job roles"
            name="preferredJobRoles"
            options={PREFERRED_JOB_ROLE_OPTIONS}
            selected={field.value}
            onAdd={(value) => field.onChange([...field.value, value])}
            onRemove={(value) => field.onChange(field.value.filter((item) => item !== value))}
            error={errors.preferredJobRoles?.message}
            min={1}
            placeholder="Type to search job roles..."
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
        Create job seeker account
      </Button>
    </form>
  );
}
