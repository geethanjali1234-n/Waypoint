import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button, FormLabel, MultiSelect, Pagination, StatusBadge } from '../components';
import jobApi from '../api/jobApi';
import { JOB_SALARY_RANGE_OPTIONS } from '../constants/jobSalaryRangeOptions';
import { SKILL_OPTIONS } from './auth/components/constants/registrationOptions';
import { LOCATION_OPTIONS, normalizeLocation, normalizeLocationSearch } from './jobPostingOptions';
import { formatJobSalary, formatPostedAt } from '../utils/jobFormatting';
import './JobPostingPage.css';

const INITIAL_FORM = {
  jobCode: '',
  title: '',
  workMode: '',
  location: '',
  employmentType: '',
  experienceLevel: '',
  requiredSkills: [],
  salaryRange: '',
  salaryMin: '',
  salaryMax: '',
  description: '',
  applicationDeadline: '',
  resumeRequired: 'true',
};

const WORK_TYPES = [
  { label: 'On-site', value: 'ONSITE' },
  { label: 'Remote', value: 'REMOTE' },
  { label: 'Hybrid', value: 'HYBRID' },
];

const EMPLOYMENT_TYPES = [
  { label: 'Full-time', value: 'FULL_TIME' },
  { label: 'Part-time', value: 'PART_TIME' },
  { label: 'Contract', value: 'CONTRACT' },
  { label: 'Internship', value: 'INTERNSHIP' },
];

const EXPERIENCE_LEVELS = [
  { label: 'Entry level', value: 'ENTRY_LEVEL' },
  { label: 'Mid level', value: 'MID_LEVEL' },
  { label: 'Senior level', value: 'SENIOR_LEVEL' },
  { label: 'Lead', value: 'LEAD' },
  { label: 'Executive', value: 'EXECUTIVE' },
];

const ITEMS_PER_PAGE = 10;

function RegistrationField({ id, label, required = false, error, className = '', children }) {
  const classes = ['wp-job-registration__field', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <FormLabel htmlFor={id} required={required}>
        {label}
      </FormLabel>
      {children}
      {error && <small className="wp-job-registration__field-error">{error}</small>}
    </div>
  );
}

function getLocalDateString() {
  const now = new Date();
  const localDate = new Date(now.getTime() - now.getTimezoneOffset() * 60 * 1000);
  return localDate.toISOString().slice(0, 10);
}

function formFromJob(job) {
  return {
    jobCode: job.jobCode || '',
    title: job.title || '',
    workMode: job.workMode || '',
    location: normalizeLocation(job.location || ''),
    employmentType: job.employmentType || '',
    experienceLevel: job.experienceLevel || '',
    requiredSkills: [...(job.requiredSkills || [])],
    salaryRange: job.salaryRange || '',
    salaryMin: job.salaryMin ?? '',
    salaryMax: job.salaryMax ?? '',
    description: job.description || '',
    applicationDeadline: job.applicationDeadline?.slice(0, 10) || '',
    resumeRequired: String(job.resumeRequired ?? true),
  };
}

function formatEnum(value) {
  return value
    ? value.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
    : '';
}

function formatSalary(min, max, range) {
  if (range) return formatJobSalary(range, min, max);
  if (min == null && max == null) return 'Not listed';
  const money = (value) => `$${Number(value).toLocaleString()}`;
  if (min != null && max != null) return `${money(min)} – ${money(max)}`;
  return min != null ? `From ${money(min)}` : `Up to ${money(max)}`;
}

export default function JobPostingPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const today = getLocalDateString();
  const isCreating = location.pathname.endsWith('/new');
  const [editingJob, setEditingJob] = useState(null);
  const [form, setForm] = useState(INITIAL_FORM);
  const [jobs, setJobs] = useState([]);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isPublishing, setIsPublishing] = useState(false);
  const [changingStatusId, setChangingStatusId] = useState('');
  const [pageError, setPageError] = useState('');
  const [formError, setFormError] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [successMessage, setSuccessMessage] = useState('');
  const [currentPage, setCurrentPage] = useState(1);

  useEffect(() => {
    let cancelled = false;

    jobApi.listEmployerJobs()
      .then((response) => {
        if (!cancelled) setJobs(response.data.data || []);
      })
      .catch((error) => {
        if (!cancelled) setPageError(error?.response?.data?.message || 'Unable to load job postings.');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isCreating || editingJob || form.jobCode) return;

    jobApi.getNewJobCode()
      .then((response) => {
        setForm((current) => ({ ...current, jobCode: response.data.data }));
      })
      .catch((error) => {
        setFormError(error?.response?.data?.message || 'Unable to generate a Job ID.');
      });
  }, [editingJob, form.jobCode, isCreating]);

  const filteredJobs = useMemo(() => {
    const query = search.trim().toLowerCase();
    const normalizedLocationQuery = normalizeLocationSearch(search);
    return jobs.filter((job) => {
      const searchableText = [
        job.jobCode,
        job.title,
        job.location,
        job.workMode,
        job.employmentType,
        job.experienceLevel,
        ...(job.requiredSkills || []),
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      const matchesSearch = !query
        || searchableText.includes(query)
        || normalizeLocationSearch(job.location || '').includes(normalizedLocationQuery);
      const matchesStatus = !statusFilter || job.status === statusFilter;
      return matchesSearch && matchesStatus;
    });
  }, [jobs, search, statusFilter]);

  const totalPages = Math.ceil(filteredJobs.length / ITEMS_PER_PAGE);
  const paginatedJobs = useMemo(() => {
    const startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
    return filteredJobs.slice(startIndex, startIndex + ITEMS_PER_PAGE);
  }, [currentPage, filteredJobs]);

  useEffect(() => {
    setCurrentPage(1);
  }, [search, statusFilter]);

  useEffect(() => {
    setCurrentPage((page) => Math.min(page, Math.max(totalPages, 1)));
  }, [totalPages]);

  const updateField = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({ ...current, [name]: value }));
    setFieldErrors((current) => ({ ...current, [name]: undefined }));
    setFormError('');
  };

  const validate = () => {
    const errors = {};
    const requiredFields = [
      ['title', 'Job title is required'],
      ['workMode', 'Work type is required'],
      ['location', 'Location is required'],
      ['employmentType', 'Employment type is required'],
      ['experienceLevel', 'Experience level is required'],
      ['requiredSkills', 'Required skills are required'],
      ['description', 'Description is required'],
      ['applicationDeadline', 'Application deadline is required'],
    ];

    requiredFields.forEach(([field, message]) => {
      const value = form[field];
      if (Array.isArray(value) ? value.length === 0 : !value.trim()) errors[field] = message;
    });

    if (form.applicationDeadline && form.applicationDeadline < today) {
      errors.applicationDeadline = 'Application deadline cannot be in the past';
    }

    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const saveJob = async (event) => {
    event.preventDefault();
    setSuccessMessage('');
    if (!validate()) return;

    const requiredSkills = form.requiredSkills;

    const payload = {
      jobCode: form.jobCode || null,
      title: form.title.trim(),
      workMode: form.workMode,
      location: form.location.trim(),
      employmentType: form.employmentType,
      experienceLevel: form.experienceLevel,
      requiredSkills,
      salaryRange: form.salaryRange || null,
      // Preserve numeric values for older postings that predate salary bands.
      salaryMin: form.salaryRange ? null : (form.salaryMin ? Number(form.salaryMin) : null),
      salaryMax: form.salaryRange ? null : (form.salaryMax ? Number(form.salaryMax) : null),
      description: form.description.trim(),
      applicationDeadline: form.applicationDeadline || null,
      resumeRequired: form.resumeRequired === 'true',
    };

    setIsPublishing(true);
    setFormError('');
    try {
      const response = editingJob
        ? await jobApi.updateJob(editingJob.id, payload)
        : await jobApi.createJob(payload);
      const savedJob = response.data.data;
      setJobs((current) => [savedJob, ...current.filter((job) => job.id !== savedJob.id)]);
      setForm(INITIAL_FORM);
      setEditingJob(null);
      setSuccessMessage(
        `"${savedJob.title || savedJob.jobCode}" was ${editingJob ? 'updated' : 'created'} successfully.`,
      );
      navigate('/employer/jobs');
    } catch (error) {
      const responseBody = error?.response?.data;
      if (responseBody?.data && typeof responseBody.data === 'object') {
        setFieldErrors(responseBody.data);
      }
      setFormError(responseBody?.message || 'Unable to save this job posting.');
    } finally {
      setIsPublishing(false);
    }
  };

  const openEditor = (job) => {
    setEditingJob(job);
    setForm(formFromJob(job));
    setFieldErrors({});
    setFormError('');
    setSuccessMessage('');
  };

  const closeEditor = () => {
    setEditingJob(null);
    setForm(INITIAL_FORM);
    setFieldErrors({});
    setFormError('');
    navigate('/employer/jobs');
  };

  const changeStatus = async (job) => {
    const action = job.status === 'OPEN' ? 'close' : 'reopen';
    setChangingStatusId(job.id);
    setPageError('');
    setSuccessMessage('');
    try {
      const response = action === 'close'
        ? await jobApi.closeJob(job.id)
        : await jobApi.reopenJob(job.id);
      const savedJob = response.data.data;
      setJobs((current) => current.map((item) => (item.id === savedJob.id ? savedJob : item)));
      setSuccessMessage(`"${savedJob.title || savedJob.jobCode}" was ${action === 'close' ? 'closed' : 'reopened'} successfully.`);
    } catch (error) {
      setPageError(error?.response?.data?.message || `Unable to ${action} this job posting.`);
    } finally {
      setChangingStatusId('');
    }
  };

  return (
    <div className="wp-prototype-jobs">
      <main className="wp-prototype-jobs__main">
        <div className="wp-prototype-jobs__heading">
          <div>
            <h1>Job postings</h1>
            <p>Manage only jobs owned by this employer account.</p>
          </div>
          <Button onClick={() => navigate('/employer/jobs/new')}>+ New posting</Button>
        </div>

        {successMessage && <div className="wp-prototype-jobs__success">{successMessage}</div>}
        {pageError && <div className="wp-prototype-jobs__error">{pageError}</div>}

        <section className="wp-prototype-jobs__panel">
          <div className="wp-prototype-jobs__toolbar">
            <h2>Active and closed jobs</h2>
            <div>
              <input
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Search jobs by title or location"
                aria-label="Search jobs"
              />
              <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} aria-label="Filter">
                <option value="">Status</option>
                <option value="OPEN">Active</option>
                <option value="CLOSED">Closed</option>
              </select>
            </div>
          </div>

          <div className="wp-prototype-jobs__table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Job title</th>
                  <th>Type & salary</th>
                  <th>Applications</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {isLoading && <tr><td colSpan="5" className="wp-prototype-jobs__empty">Loading job postings...</td></tr>}
                {!isLoading && filteredJobs.length === 0 && (
                  <tr><td colSpan="5" className="wp-prototype-jobs__empty">No job postings yet. Select "New posting" to create one.</td></tr>
                )}
                {!isLoading && paginatedJobs.map((job) => (
                  <tr key={job.id}>
                    <td>
                      <button
                        type="button"
                        className="wp-prototype-jobs__job-title"
                        onClick={() => navigate(`/employer/jobs/${job.id}/applicants`)}
                      >
                        {job.title}
                      </button>
                      {formatPostedAt(job.createdAt) && <small>Posted: {formatPostedAt(job.createdAt)}</small>}
                      <small>{formatEnum(job.workMode)} · {job.location}</small>
                    </td>
                    <td>
                      <span>{formatEnum(job.employmentType)}</span>
                      <small>{formatSalary(job.salaryMin, job.salaryMax, job.salaryRange)}</small>
                    </td>
                    <td><strong>{job.applicationCount ?? 0}</strong></td>
                    <td><StatusBadge status={job.status === 'OPEN' ? 'active' : job.status} label={job.status === 'OPEN' ? 'Active' : formatEnum(job.status)} /></td>
                    <td>
                      <div className="wp-prototype-jobs__actions">
                        <button type="button" disabled={job.status !== 'OPEN'} onClick={() => openEditor(job)}>
                          Edit
                        </button>
                        {(job.status === 'OPEN' || job.status === 'CLOSED') && (
                          <button
                            type="button"
                            disabled={changingStatusId === job.id}
                            onClick={() => changeStatus(job)}
                          >
                            {changingStatusId === job.id
                              ? 'Saving...'
                              : job.status === 'OPEN' ? 'Close' : 'Reopen'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!isLoading && !pageError && totalPages > 1 && (
            <Pagination
              currentPage={currentPage}
              totalPages={totalPages}
              onPageChange={setCurrentPage}
              className="wp-prototype-jobs__pagination"
            />
          )}
        </section>
      </main>

      {(isCreating || editingJob) && (
        <div className="wp-job-registration" role="dialog" aria-modal="true" aria-labelledby="job-registration-title">
          <button type="button" aria-label="Close job editor" className="wp-job-registration__backdrop" onClick={closeEditor} />
          <form className="wp-job-registration__dialog" onSubmit={saveJob} noValidate>
            <div className="wp-job-registration__header">
              <div>
                <h2 id="job-registration-title">{editingJob ? 'Edit job posting' : 'New job posting'}</h2>
                <p>{editingJob ? 'Updates are saved immediately.' : 'Required skills power job seeker matching.'}</p>
              </div>
              <button type="button" aria-label="Close" onClick={closeEditor}>×</button>
            </div>

            {formError && <div className="wp-job-registration__error">{formError}</div>}

            <div className="wp-job-registration__grid">
              <RegistrationField id="jobCode" label="Job ID">
                <input id="jobCode" value={form.jobCode || 'Generating Job ID...'} disabled />
              </RegistrationField>
              <RegistrationField id="title" label="Job title" required error={fieldErrors.title}>
                <input id="title" name="title" value={form.title} onChange={updateField} className={fieldErrors.title ? 'is-error' : ''} />
              </RegistrationField>
              <RegistrationField id="workMode" label="Work type" required error={fieldErrors.workMode}>
                <select id="workMode" name="workMode" value={form.workMode} onChange={updateField} className={fieldErrors.workMode ? 'is-error' : ''}>
                  <option value="">Select one</option>
                  {WORK_TYPES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </RegistrationField>
              <RegistrationField id="employmentType" label="Employment type" required error={fieldErrors.employmentType}>
                <select id="employmentType" name="employmentType" value={form.employmentType} onChange={updateField} className={fieldErrors.employmentType ? 'is-error' : ''}>
                  <option value="">Select one</option>
                  {EMPLOYMENT_TYPES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </RegistrationField>
              <RegistrationField id="location" label="Location" required error={fieldErrors.location}>
                <select id="location" name="location" value={form.location} onChange={updateField} className={fieldErrors.location ? 'is-error' : ''}>
                  <option value="">Select one</option>
                  {LOCATION_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </RegistrationField>
              <RegistrationField id="experienceLevel" label="Experience level" required error={fieldErrors.experienceLevel}>
                <select id="experienceLevel" name="experienceLevel" value={form.experienceLevel} onChange={updateField} className={fieldErrors.experienceLevel ? 'is-error' : ''}>
                  <option value="">Select one</option>
                  {EXPERIENCE_LEVELS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </RegistrationField>
              <MultiSelect
                label="Required skills"
                name="requiredSkills"
                options={SKILL_OPTIONS}
                selected={form.requiredSkills}
                onAdd={(value) => {
                  setForm((current) => ({ ...current, requiredSkills: [...current.requiredSkills, value] }));
                  setFieldErrors((current) => ({ ...current, requiredSkills: undefined }));
                  setFormError('');
                }}
                onRemove={(value) => setForm((current) => ({
                  ...current,
                  requiredSkills: current.requiredSkills.filter((skill) => skill !== value),
                }))}
                error={fieldErrors.requiredSkills}
                min={1}
                placeholder="Type to search skills..."
                required
                className="wp-job-registration__multi-select"
              />
              <RegistrationField id="salaryRange" label="Salary range">
                <select id="salaryRange" name="salaryRange" value={form.salaryRange} onChange={updateField}>
                  <option value="">Select one</option>
                  {JOB_SALARY_RANGE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </RegistrationField>
              <RegistrationField id="description" label="Description" required error={fieldErrors.description} className="wp-job-registration__wide">
                <textarea id="description" name="description" rows="4" value={form.description} onChange={updateField} className={fieldErrors.description ? 'is-error' : ''} />
              </RegistrationField>
              <RegistrationField id="applicationDeadline" label="Application deadline" required error={fieldErrors.applicationDeadline}>
                <input
                  id="applicationDeadline"
                  name="applicationDeadline"
                  type="date"
                  min={today}
                  value={form.applicationDeadline}
                  onChange={updateField}
                  className={fieldErrors.applicationDeadline ? 'is-error' : ''}
                />
              </RegistrationField>
              <RegistrationField id="resumeRequired" label="Resume required">
                <select id="resumeRequired" name="resumeRequired" value={form.resumeRequired} onChange={updateField}>
                  <option value="true">Yes</option>
                  <option value="false">No</option>
                </select>
              </RegistrationField>
            </div>

            <div className="wp-job-registration__actions">
              <Button variant="secondary" onClick={closeEditor}>Cancel</Button>
              <Button type="submit" loading={isPublishing}>{editingJob ? 'Save changes' : 'Publish posting'}</Button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
