import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, EmptyState, Loader, DataTable, StatusBadge } from '../components';
import jobApi from '../api/jobApi';
import './EmployerApplicantsPage.css';

function formatEnum(value) {
  return value
    ? value.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
    : 'Not specified';
}

function formatDate(value) {
  if (!value) return 'Not available';

  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(value));
}

function getErrorMessage(error, fallback) {
  return error?.response?.data?.message || fallback;
}

function getResumeStatus(applicant) {
  if (applicant.hasResume) return 'Resume available';
  if (applicant.resumeRecordExists) return 'Resume file unavailable';
  return 'No resume uploaded';
}

export default function EmployerApplicantsPage() {
  const navigate = useNavigate();
  const { jobId } = useParams();
  const [job, setJob] = useState(null);
  const [applicants, setApplicants] = useState([]);
  const [status, setStatus] = useState('loading');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    let isMounted = true;

    Promise.all([jobApi.getEmployerJob(jobId), jobApi.listJobApplicants(jobId)])
      .then(([jobResponse, applicantsResponse]) => {
        if (!isMounted) return;
        setJob(jobResponse.data.data);
        setApplicants(applicantsResponse.data.data || []);
        setStatus('success');
      })
      .catch((error) => {
        if (!isMounted) return;

        if (error?.response?.status === 401) {
          localStorage.removeItem('accessToken');
          navigate('/live-login');
          return;
        }

        setErrorMessage(getErrorMessage(error, 'Unable to load applicants for this job.'));
        setStatus('error');
      });

    return () => {
      isMounted = false;
    };
  }, [jobId, navigate]);

  if (status === 'loading') {
    return <div className="employer-applicants__container"><Loader size="lg" label="Loading applicants..." /></div>;
  }

  if (status === 'error') {
    return (
      <main className="employer-applicants__container">
        <Button variant="secondary" onClick={() => navigate('/employer/jobs')}>← Job postings</Button>
        <div className="employer-applicants__error">
          <h1>Applicants unavailable</h1>
          <p>{errorMessage}</p>
        </div>
      </main>
    );
  }

  return (
    <main className="employer-applicants__container">
      <button type="button" className="employer-applicants__back" onClick={() => navigate('/employer/jobs')}>
        ← Job postings
      </button>

      <header className="employer-applicants__header">
        <div>
          <p className="employer-applicants__eyebrow">Applicant review</p>
          <h1>{job.title}</h1>
          <p className="employer-applicants__meta">
            {job.location} <span aria-hidden="true">·</span> {formatEnum(job.workMode)} <span aria-hidden="true">·</span> {formatEnum(job.employmentType)}
          </p>
        </div>
        <div className="employer-applicants__header-count">
          <strong>{applicants.length}</strong>
          <span>{applicants.length === 1 ? 'Applicant' : 'Applicants'}</span>
        </div>
      </header>

      <section className="employer-applicants__panel" aria-labelledby="applicants-heading">
        <div className="employer-applicants__panel-header">
          <div>
            <h2 id="applicants-heading">People who applied</h2>
            <p>Select an applicant to review their profile and application details.</p>
          </div>
        </div>

        {applicants.length === 0 ? (
          <EmptyState
            title="No applicants yet"
            description="Applicants will appear here when job seekers apply to this posting."
          />
        ) : (
          <DataTable label={`Applicants for ${job.title}`}
            rows={applicants.map((applicant) => ({ ...applicant, id: applicant.applicationId, person: { name: applicant.fullName, id: applicant.email } }))}
            getRowLabel={(applicant) => applicant.fullName}
            onOpenRow={(applicant) => navigate(`/employer/jobs/${jobId}/applicants/${applicant.applicationId}`)}
            columns={[
              { key: 'person', header: 'Applicant', type: 'person' },
              { key: 'appliedAt', header: 'Applied', render: formatDate },
              { key: 'status', header: 'Status', render: (value) => <StatusBadge status={value} label={formatEnum(value)} /> },
              { key: 'resume', header: 'Resume', render: (_, applicant) => getResumeStatus(applicant) },
            ]}
          />
        )}
      </section>
    </main>
  );
}
