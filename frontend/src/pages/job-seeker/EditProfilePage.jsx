import { useEffect, useState } from 'react';
import { Link, useNavigate, useOutletContext } from 'react-router-dom';
import { useForm, useController, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  AppLayout,
  TextInput,
  PhoneInput,
  MultiSelect,
  Dropdown,
  ValidationMessage,
  ImageUpload,
  FileUpload,
  Chip,
  Button,
  Loader,
  Toast,
} from '../../components';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import resolveFileUrl from '../../api/resolveFileUrl';
import { SKILL_OPTIONS, PREFERRED_JOB_ROLE_OPTIONS } from '../auth/components/constants/registrationOptions';
import { EMPLOYMENT_TYPE_OPTIONS } from './constants/employmentTypeOptions';
import { SALARY_RANGE_OPTIONS } from './constants/salaryRangeOptions';
import { US_PHONE_PATTERN } from '../auth/validation/patterns';
import './EditProfilePage.css';

const KNOWN_FIELDS = ['firstName', 'lastName', 'phoneNumber', 'skills', 'preferredJobRoles', 'expectedSalaryMin', 'expectedSalaryMax'];

const editProfileSchema = z
  .object({
    firstName: z.string().trim().min(1, { message: 'First name is required' }),
    lastName: z.string().trim().min(1, { message: 'Last name is required' }),
    phoneNumber: z
      .string()
      .min(1, { message: 'Phone number is required' })
      .regex(US_PHONE_PATTERN, { message: 'Enter a valid 10-digit phone number' }),
    skills: z.array(z.string()).min(1, { message: 'Add at least 1 skill' }),
    preferredJobRoles: z.array(z.string()).min(1, { message: 'Select at least 1 interested role' }),
    preferredEmploymentType: z.array(z.string()),
    expectedSalaryMin: z.string().refine((value) => !value || /^\d+$/.test(value), { message: 'Enter a whole number' }),
    expectedSalaryMax: z.string().refine((value) => !value || /^\d+$/.test(value), { message: 'Enter a whole number' }),
  })
  .refine(
    (data) => !data.expectedSalaryMin || !data.expectedSalaryMax || Number(data.expectedSalaryMin) <= Number(data.expectedSalaryMax),
    { message: 'Salary min must not be greater than salary max', path: ['expectedSalaryMax'] }
  );

function initialsFor(firstName, lastName) {
  return `${firstName?.[0] || ''}${lastName?.[0] || ''}`.toUpperCase();
}

function toggleValue(list, value) {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

/**
 * EditProfilePage - lets the job seeker edit personal info, skills, interested
 * roles, and preferences, plus replace their photo/resume. Email is shown
 * read-only (it lives on User, not JobSeekerProfile, and changing it has
 * verification implications out of scope here). Photo/resume uploads are
 * independent actions that save immediately, matching the two dedicated
 * multipart endpoints.
 *
 * @example
 * <Route path="/job-seeker/profile/edit" element={<EditProfilePage />} />
 */
export default function EditProfilePage() {
  const navigate = useNavigate();
  const { refreshIdentity } = useOutletContext() ?? {};
  const [profile, setProfile] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [formError, setFormError] = useState(null);
  const [toast, setToast] = useState(null);
  const [pendingPhotoFile, setPendingPhotoFile] = useState(null);
  const [photoPreviewUrl, setPhotoPreviewUrl] = useState(null);
  const [removePhoto, setRemovePhoto] = useState(false);

  const {
    control,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(editProfileSchema),
    defaultValues: {
      firstName: '',
      lastName: '',
      phoneNumber: '',
      skills: [],
      preferredJobRoles: [],
      preferredEmploymentType: [],
      expectedSalaryMin: '',
      expectedSalaryMax: '',
    },
  });

  const { field: salaryMinField } = useController({ name: 'expectedSalaryMin', control });
  const { field: salaryMaxField } = useController({ name: 'expectedSalaryMax', control });

  const salaryRangeValue = SALARY_RANGE_OPTIONS.find((option) => {
    const [min, max] = option.value.split('-');
    return String(salaryMinField.value) === min && String(salaryMaxField.value) === max;
  })?.value ?? '';

  const handleSalaryRangeChange = (event) => {
    const [min, max] = event.target.value.split('-');
    salaryMinField.onChange(min);
    salaryMaxField.onChange(max);
  };

  useEffect(() => {
    let isMounted = true;

    jobSeekerProfileApi
      .getProfile()
      .then((response) => {
        if (!isMounted) return;
        const data = response.data.data;
        setProfile(data);
        reset({
          firstName: data.firstName || '',
          lastName: data.lastName || '',
          phoneNumber: data.phoneNumber || '',
          skills: data.skills || [],
          preferredJobRoles: data.preferredJobRoles || [],
          preferredEmploymentType: data.preferredEmploymentType || [],
          expectedSalaryMin: data.expectedSalaryMin != null ? String(data.expectedSalaryMin) : '',
          expectedSalaryMax: data.expectedSalaryMax != null ? String(data.expectedSalaryMax) : '',
        });
      })
      .catch(() => {
        if (isMounted) setLoadError('Unable to load your profile. Please try again.');
      })
      .finally(() => {
        if (isMounted) setIsLoading(false);
      });

    return () => {
      isMounted = false;
    };
  }, [reset]);

  useEffect(() => {
    return () => {
      if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
    };
  }, [photoPreviewUrl]);

  const headerUser = profile
    ? {
        name: `${profile.firstName} ${profile.lastName}`.trim(),
        email: profile.email,
        initials: initialsFor(profile.firstName, profile.lastName),
      }
    : { name: '', initials: '' };

  const onSubmit = async (values) => {
    setFormError(null);

    const payload = {
      firstName: values.firstName,
      lastName: values.lastName,
      phoneNumber: values.phoneNumber,
      skills: values.skills,
      preferredJobRoles: values.preferredJobRoles,
      preferredEmploymentType: values.preferredEmploymentType,
      expectedSalaryMin: values.expectedSalaryMin ? Number(values.expectedSalaryMin) : null,
      expectedSalaryMax: values.expectedSalaryMax ? Number(values.expectedSalaryMax) : null,
      removeProfilePicture: removePhoto,
    };

    try {
      if (pendingPhotoFile) {
        await jobSeekerProfileApi.uploadPhoto(pendingPhotoFile);
      }
      await jobSeekerProfileApi.updateProfile(payload);
      refreshIdentity?.();
      navigate('/job-seeker/profile');
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

  const handlePhotoSelect = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    setPendingPhotoFile(file);
    setPhotoPreviewUrl(URL.createObjectURL(file));
    setRemovePhoto(false);
  };

  const handleRemovePhoto = () => {
    setPendingPhotoFile(null);
    setPhotoPreviewUrl(null);
    setRemovePhoto(true);
  };

  const handleResumeUpload = async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    try {
      const response = await jobSeekerProfileApi.uploadResume(file);
      setProfile(response.data.data);
      setToast({ message: 'Resume updated', variant: 'success' });
    } catch {
      setToast({ message: 'Failed to upload resume', variant: 'error' });
    }
  };

  if (isLoading) {
    return (
      <AppLayout>
        <div className="wp-edit-profile-page">
          <Loader label="Loading your profile..." />
        </div>
      </AppLayout>
    );
  }

  if (loadError || !profile) {
    return (
      <AppLayout>
        <div className="wp-edit-profile-page">
          <ValidationMessage message={loadError || 'Profile not found'} variant="error" />
        </div>
      </AppLayout>
    );
  }

  const resumeHelperText =
    profile.resumeUploadedAt && profile.resumeSizeBytes != null
      ? `Uploaded ${new Date(profile.resumeUploadedAt).toLocaleDateString()} · ${Math.round(profile.resumeSizeBytes / 1024)} KB`
      : undefined;

  const photoPreviewSrc = removePhoto ? undefined : (photoPreviewUrl || resolveFileUrl(profile.profilePictureUrl));
  const hasPhoto = Boolean(photoPreviewSrc);

  return (
    <AppLayout>
      <div className="wp-edit-profile-page">
        {toast && <Toast message={toast.message} variant={toast.variant} onClose={() => setToast(null)} />}

        <Link to="/job-seeker/profile" className="wp-edit-profile-page__back">
          ← Back to profile
        </Link>
        <h1 className="wp-edit-profile-page__title">Edit profile</h1>

        <h3 className="wp-edit-profile-page__section-heading">Profile picture</h3>
        <ImageUpload
          name="profilePicture"
          previewUrl={photoPreviewSrc}
          initials={headerUser.initials}
          onUpload={handlePhotoSelect}
          onRemove={hasPhoto ? handleRemovePhoto : undefined}
        />

        <h3 className="wp-edit-profile-page__section-heading">Personal info</h3>
        <div className="wp-edit-profile-page__grid">
          <Controller
            name="firstName"
            control={control}
            render={({ field }) => (
              <TextInput label="First name" name="firstName" value={field.value} onChange={field.onChange} error={errors.firstName?.message} required />
            )}
          />
          <Controller
            name="lastName"
            control={control}
            render={({ field }) => (
              <TextInput label="Last name" name="lastName" value={field.value} onChange={field.onChange} error={errors.lastName?.message} required />
            )}
          />
          <TextInput label="Email" name="email" value={profile.email} disabled hint="Contact support to change your email" />
          <Controller
            name="phoneNumber"
            control={control}
            render={({ field }) => (
              <PhoneInput label="Phone" name="phoneNumber" value={field.value} onChange={field.onChange} error={errors.phoneNumber?.message} required />
            )}
          />
        </div>

        <h3 className="wp-edit-profile-page__section-heading">Resume</h3>
        <FileUpload
          name="resume"
          fileName={profile.resumeFileName}
          helperText={resumeHelperText}
          accept=".pdf,.doc,.docx"
          onUpload={handleResumeUpload}
          onReplace={handleResumeUpload}
        />

        <h3 className="wp-edit-profile-page__section-heading">Skills</h3>
        <Controller
          name="skills"
          control={control}
          render={({ field }) => (
            <MultiSelect
              name="skills"
              options={SKILL_OPTIONS}
              selected={field.value}
              onAdd={(value) => field.onChange([...field.value, value])}
              onRemove={(value) => field.onChange(field.value.filter((item) => item !== value))}
              error={errors.skills?.message}
              placeholder="Type to search skills..."
            />
          )}
        />

        <h3 className="wp-edit-profile-page__section-heading">Interested roles</h3>
        <Controller
          name="preferredJobRoles"
          control={control}
          render={({ field }) => (
            <div className="wp-edit-profile-page__pills">
              {PREFERRED_JOB_ROLE_OPTIONS.map((option) => (
                <Chip
                  key={option.value}
                  label={option.label}
                  selectable
                  selected={field.value.includes(option.value)}
                  onClick={() => field.onChange(toggleValue(field.value, option.value))}
                />
              ))}
            </div>
          )}
        />
        <ValidationMessage message={errors.preferredJobRoles?.message} variant="error" />

        <h3 className="wp-edit-profile-page__section-heading">Preferences</h3>
        <Dropdown
          name="expectedSalaryRange"
          label="Salary range"
          options={SALARY_RANGE_OPTIONS}
          value={salaryRangeValue}
          onChange={handleSalaryRangeChange}
          placeholder="Select a salary range"
          error={errors.expectedSalaryMin?.message || errors.expectedSalaryMax?.message}
        />

        <p className="wp-edit-profile-page__label">Employment type</p>
        <Controller
          name="preferredEmploymentType"
          control={control}
          render={({ field }) => (
            <div className="wp-edit-profile-page__pills">
              {EMPLOYMENT_TYPE_OPTIONS.map((option) => (
                <Chip
                  key={option.value}
                  label={option.label}
                  selectable
                  selected={field.value.includes(option.value)}
                  onClick={() => field.onChange(toggleValue(field.value, option.value))}
                />
              ))}
            </div>
          )}
        />

        {formError && <ValidationMessage message={formError} variant="error" />}

        <div className="wp-edit-profile-page__actions">
          <Button variant="secondary" onClick={() => navigate('/job-seeker/profile')}>
            Cancel
          </Button>
          <Button variant="primary" fullWidth loading={isSubmitting} onClick={handleSubmit(onSubmit)}>
            Save changes
          </Button>
        </div>
      </div>
    </AppLayout>
  );
}
