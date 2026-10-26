import { useEffect, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { PageHeader, Card, Loader, EmptyState, ValidationMessage, Button, Chip } from '../../components';
import jobApi from '../../api/jobApi';
import { formatJobSalary, formatPostedAt } from '../../utils/jobFormatting';
import formatLocationWithoutZip from '../../utils/formatLocation';
import './JobDetailPage.css';

function formatDate(value) {
  if (!value) return null;

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) return null;

  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
}

export default function JobDetailPage() {
  const { jobId } = useParams();
  const navigate = useNavigate();

  const [job, setJob] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [isNotFound, setIsNotFound] = useState(false);

  useEffect(() => {
    let isCancelled = false;

    setIsLoading(true);
    setError(null);
    setIsNotFound(false);

    jobApi
      .getJobById(jobId)
      .then((response) => {
        if (isCancelled) return;
        setJob(response.data.data);
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

  const handleApply = () => {
    if (job?.hasApplied) return;
    navigate(`/careers/${jobId}/apply`);
  };

  return (
    <>
      <div className="wp-job-detail__back">
        <Link to="/careers">&larr; Back to Careers</Link>
      </div>

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
        <div className="wp-job-detail__content">
          <PageHeader
            title={job.title}
            subtitle={job.companyName}
            action={
              <Button variant="primary" onClick={handleApply} disabled={job.hasApplied}>
                {job.hasApplied ? 'Applied' : 'Apply Now'}
              </Button>
            }
          />

          <Card>
            <p className="wp-job-detail__meta">
              {formatLocationWithoutZip(job.location)} · {job.employmentType} · {job.workMode}
            </p>

            {(job.salaryMin || job.salaryMax) && !job.salaryRange && (
              <p className="wp-job-detail__salary">
                ${job.salaryMin ?? '—'} – ${job.salaryMax ?? '—'}
              </p>
            )}

            {job.salaryRange && (
              <p className="wp-job-detail__salary">
                {formatJobSalary(job.salaryRange, job.salaryMin, job.salaryMax)}
              </p>
            )}

            {job.status && <p className="wp-job-detail__status">Status: {job.status}</p>}

            {job.jobCode && <p className="wp-job-detail__code">Job code: {job.jobCode}</p>}

            {formatPostedAt(job.createdAt) && (
              <p className="wp-job-detail__dates">Posted: {formatPostedAt(job.createdAt)}</p>
            )}

            {formatDate(job.applicationDeadline) && (
              <p className="wp-job-detail__dates">Application deadline: {formatDate(job.applicationDeadline)}</p>
            )}
          </Card>

          <Card title="Description">
            <p className="wp-job-detail__description">{job.description}</p>
          </Card>

          {job.requiredSkills && job.requiredSkills.length > 0 && (
            <Card title="Required Skills">
              <ul className="wp-job-detail__skills">
                {job.requiredSkills.map((skill) => (
                  <li key={skill}>
                    <Chip label={skill} />
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      )}
    </>
  );
}
