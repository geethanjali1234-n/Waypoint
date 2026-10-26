import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Breadcrumb,
  PageHeader,
  Card,
  TwoColumnLayout,
  Loader,
  EmptyState,
  ValidationMessage,
  Button,
  Chip,
  Toast,
  TextInput,
  EmailInput,
  FileUpload,
  Checkbox,
  StatusTag,
} from '../../components';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import jobApi from '../../api/jobApi';
import { formatJobSalary, formatPostedAt } from '../../utils/jobFormatting';
import formatLocationWithoutZip from '../../utils/formatLocation';
import './JobApplyPage.css';

function formatDate(value) {
  if (!value) return null;

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) return null;

  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export default function JobApplyPage() {
  const { jobId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();

  const isViewRequested = searchParams.get('view') === '1';

  const [job, setJob] = useState(null);
  const [profile, setProfile] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [isNotFound, setIsNotFound] = useState(false);

  const [authorized, setAuthorized] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [hasApplied, setHasApplied] = useState(false);
  const [toast, setToast] = useState(null);

  useEffect(() => {
    let isCancelled = false;

    setIsLoading(true);
    setError(null);
    setIsNotFound(false);

    Promise.all([jobApi.getJobById(jobId), jobSeekerProfileApi.getProfile()])
      .then(([jobResponse, profileResponse]) => {
        if (isCancelled) return;
        setJob(jobResponse.data.data);
        setProfile(profileResponse.data.data);
        if (jobResponse.data.data.hasApplied) setHasApplied(true);
      })
      .catch((fetchError) => {
        if (isCancelled) return;

        if (fetchError?.response?.status === 404) {
          setIsNotFound(true);
          return;
        }

        const message = fetchError?.response?.data?.message || 'Unable to load this job. Please try again.';
        setError(message);
      })
      .finally(() => {
        if (isCancelled) return;
        setIsLoading(false);
      });

    return () => {
      isCancelled = true;
    };
  }, [jobId]);

  const handleResumeReplace = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    jobSeekerProfileApi
      .uploadResume(file)
      .then((response) => {
        setProfile(response.data.data);
        setToast({ message: 'Resume updated', variant: 'success' });
      })
      .catch(() => {
        setToast({ message: 'Failed to upload resume. Please try again.', variant: 'error' });
      });
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    if (isSubmitting || hasApplied) return;

    if (!localStorage.getItem('accessToken')) {
      setToast({ message: 'Please log in again before applying.', variant: 'error' });
      return;
    }

    if (!profile?.resumeFileName) {
      setToast({ message: 'Please upload a resume to your profile before applying.', variant: 'error' });
      return;
    }

    if (!authorized) {
      setToast({ message: 'Please confirm your work authorization before applying.', variant: 'error' });
      return;
    }

    setIsSubmitting(true);
    setToast(null);

    jobApi
      .applyForJob(jobId)
      .then(() => {
        setHasApplied(true);
        setToast({ message: 'Application submitted successfully.', variant: 'success' });
      })
      .catch((applyError) => {
        const message =
          applyError?.response?.data?.message || 'Unable to submit your application. Please try again.';
        setToast({ message, variant: 'error' });
        if (message.toLowerCase().includes('already applied')) setHasApplied(true);
      })
      .finally(() => {
        setIsSubmitting(false);
      });
  };

  const resumeHelperText =
    profile?.resumeUploadedAt && profile?.resumeSizeBytes != null
      ? `From your profile · Uploaded ${new Date(profile.resumeUploadedAt).toLocaleDateString()} · ${Math.round(profile.resumeSizeBytes / 1024)} KB`
      : 'From your profile';

  const matchedSkills = job?.requiredSkills?.length
    ? job.requiredSkills.map((skill) => ({
        skill,
        matched: (profile?.skills || []).some((s) => s.toLowerCase() === skill.toLowerCase()),
      }))
    : [];

  const submitLabel = hasApplied ? 'Applied' : isSubmitting ? 'Submitting…' : 'Submit application';

  // Only trust the view flag once we know the applicant has actually applied,
  // so a stray/typed ?view=1 can't hide the real apply form for a new job.
  const showReadOnly = isViewRequested && hasApplied;
  const viewStatusLabel = location.state?.viewStatusLabel || 'Applied';
  const viewStatusTone = location.state?.viewStatusTone || 'info';

  const mainContent = job && (
    <>
      <Card>
        <div className="wp-job-apply__facts-grid">
          <div>
            <p className="wp-job-apply__k">Work type</p>
            <p className="wp-job-apply__v">{job.workMode}</p>
          </div>
          <div>
            <p className="wp-job-apply__k">Employment type</p>
            <p className="wp-job-apply__v">{job.employmentType}</p>
          </div>
          {(job.salaryMin || job.salaryMax) && !job.salaryRange && (
            <div>
              <p className="wp-job-apply__k">Salary</p>
              <p className="wp-job-apply__v">${job.salaryMin ?? '—'} – ${job.salaryMax ?? '—'}</p>
            </div>
          )}
          {job.salaryRange && (
            <div>
              <p className="wp-job-apply__k">Salary</p>
              <p className="wp-job-apply__v">{formatJobSalary(job.salaryRange, job.salaryMin, job.salaryMax)}</p>
            </div>
          )}
          {formatPostedAt(job.createdAt) && (
            <div>
              <p className="wp-job-apply__k">Posted</p>
              <p className="wp-job-apply__v">{formatPostedAt(job.createdAt)}</p>
            </div>
          )}
          {formatDate(job.applicationDeadline) && (
            <div>
              <p className="wp-job-apply__k">Apply by</p>
              <p className="wp-job-apply__v">{formatDate(job.applicationDeadline)}</p>
            </div>
          )}
        </div>
      </Card>

      <Card title="About the role">
        <p className="wp-job-apply__description">{job.description}</p>
      </Card>

      {job.requiredSkills?.length > 0 && (
        <Card title="Required skills">
          <ul className="wp-job-apply__skills">
            {job.requiredSkills.map((skill) => (
              <li key={skill}>
                <Chip label={skill} />
              </li>
            ))}
          </ul>
        </Card>
      )}

      {matchedSkills.length > 0 && (
        <Card title="Your skill match">
          <div className="wp-job-apply__skillmatch">
            {matchedSkills.map(({ skill, matched }) => (
              <div
                key={skill}
                className={`wp-job-apply__sm-item ${matched ? '' : 'wp-job-apply__sm-item--miss'}`}
              >
                <span className="wp-job-apply__sm-dot">{matched ? '✓' : '+'}</span>
                <span className="wp-job-apply__sm-label">{skill}</span>
                <span className="wp-job-apply__sm-src">
                  {matched ? 'on your profile' : 'not listed — add it to your profile if you have it'}
                </span>
              </div>
            ))}
          </div>
          <p className="wp-job-apply__sm-note">
            Compares this job's required skills with the skills on your profile.
          </p>
        </Card>
      )}
    </>
  );

  const sideContent = job && profile && (
    <div className="wp-job-apply__panel">
      {showReadOnly ? (
        <>
          <h3 className="wp-job-apply__panel-title">Your application</h3>
          <p className="wp-job-apply__panel-lede">You've already applied to this role.</p>
          <div className="wp-job-apply__view-status">
            <StatusTag status={viewStatusLabel} tone={viewStatusTone} />
          </div>
        </>
      ) : (
        <>
          <h3 className="wp-job-apply__panel-title">Apply for this role</h3>
          <p className="wp-job-apply__panel-lede">Your profile fills most of this — review and send.</p>

          <form onSubmit={handleSubmit}>
            <div className="wp-job-apply__grid-2">
              <TextInput label="First name" name="firstName" value={profile.firstName} disabled />
              <TextInput label="Last name" name="lastName" value={profile.lastName} disabled />
            </div>

            <EmailInput label="Email" name="email" value={profile.email} disabled />

            <TextInput
              label="Phone"
              name="phone"
              value={profile.phoneNumber || ''}
              disabled
              hint={!profile.phoneNumber ? 'Add a phone number from your profile' : undefined}
            />

            <FileUpload
              name="resume"
              label="Resume"
              fileName={profile.resumeFileName}
              helperText={resumeHelperText}
              accept=".pdf,.doc,.docx"
              onUpload={handleResumeReplace}
              onReplace={handleResumeReplace}
            />
            {!profile.resumeFileName && (
              <ValidationMessage message="Upload a resume to your profile before applying." variant="error" />
            )}

            <Checkbox
              name="authorized"
              label="I'm authorized to work in the United States"
              checked={authorized}
              onChange={(event) => setAuthorized(event.target.checked)}
              required
            />

            <Button
              type="submit"
              variant="primary"
              loading={isSubmitting}
              disabled={hasApplied}
              fullWidth
              className="wp-job-apply__submit"
            >
              {submitLabel}
            </Button>

            {formatDate(job.applicationDeadline) && (
              <p className="wp-job-apply__deadline-note">Applications close {formatDate(job.applicationDeadline)}</p>
            )}
          </form>
        </>
      )}
    </div>
  );

  return (
    <>
      <div className="wp-job-apply__content">
        <Breadcrumb items={[{ label: 'Careers', href: '/careers' }, { label: job?.title || 'Apply' }]} />

        {isLoading && <Loader label="Loading job details…" />}

        {!isLoading && error && <ValidationMessage message={error} variant="error" />}

        {!isLoading && isNotFound && (
          <EmptyState
            title="Job not found"
            description="This job may no longer be available."
            action={
              <Button variant="primary" onClick={() => navigate('/careers')}>
                Back to Careers
              </Button>
            }
          />
        )}

        {!isLoading && !error && !isNotFound && job && (
          <>
            <PageHeader
              title={job.title}
              subtitle={
                job.companyName
                  ? `${job.companyName} · ${formatLocationWithoutZip(job.location)}`
                  : formatLocationWithoutZip(job.location)
              }
            />

            {toast && <Toast message={toast.message} variant={toast.variant} onClose={() => setToast(null)} />}

            <TwoColumnLayout ratio="2:1" main={mainContent} side={sideContent} />
          </>
        )}
      </div>
    </>
  );
}
