import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { renderAsync } from 'docx-preview';
import { Button, Loader } from '../components';
import jobApi from '../api/jobApi';
import { APPLICATION_STATUS_OPTIONS } from '../constants/applicationStatuses';
import formatEnum from '../utils/formatEnum';
import './EmployerApplicantsPage.css';

function formatDate(value) {
  if (!value) return 'Not available';

  return new Intl.DateTimeFormat(undefined, {
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(value));
}

function formatSalary(min, max) {
  const money = (value) => `$${Number(value).toLocaleString()}`;
  if (min != null && max != null) return `${money(min)} – ${money(max)}`;
  if (min != null) return `From ${money(min)}`;
  if (max != null) return `Up to ${money(max)}`;
  return 'Not specified';
}

function getInitials(name) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase() || 'A';
}

function getResumePreviewType(fileName, contentType) {
  const normalizedFileName = (fileName || '').toLowerCase();
  const normalizedContentType = (contentType || '').toLowerCase();

  if (normalizedContentType.includes('pdf') || normalizedFileName.endsWith('.pdf')) {
    return 'pdf';
  }

  if (
    normalizedContentType.includes('wordprocessingml')
    || normalizedFileName.endsWith('.docx')
  ) {
    return 'docx';
  }

  return 'unsupported';
}

function DetailItem({ label, value }) {
  return (
    <div className="employer-applicant-detail__item">
      <dt>{label}</dt>
      <dd>{value || 'Not provided'}</dd>
    </div>
  );
}

export default function EmployerApplicantDetailPage() {
  const navigate = useNavigate();
  const { jobId, applicationId } = useParams();
  const [job, setJob] = useState(null);
  const [applicant, setApplicant] = useState(null);
  const [status, setStatus] = useState('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [resumePreview, setResumePreview] = useState(null);
  const [resumeAction, setResumeAction] = useState('');
  const [resumeError, setResumeError] = useState('');
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
  const [statusUpdateMessage, setStatusUpdateMessage] = useState('');
  const [statusUpdateError, setStatusUpdateError] = useState('');
  const resumePreviewRef = useRef(null);
  const resumeRecordMissing = Boolean(applicant?.resumeRecordExists && !applicant?.hasResume);

  const fetchApplicantData = useCallback(
    () => Promise.all([
      jobApi.getEmployerJob(jobId),
      jobApi.getJobApplicant(jobId, applicationId),
    ]),
    [applicationId, jobId],
  );

  useEffect(() => {
    let isMounted = true;

    fetchApplicantData()
      .then(([jobResponse, applicantResponse]) => {
        if (!isMounted) return;
        setJob(jobResponse.data.data);
        setApplicant(applicantResponse.data.data);
        setErrorMessage('');
        setStatus('success');
      })
      .catch((error) => {
        if (!isMounted) return;

        if (error?.response?.status === 401) {
          localStorage.removeItem('accessToken');
          navigate('/live-login');
          return;
        }

        setErrorMessage(error?.response?.data?.message || 'Unable to load this applicant.');
        setStatus('error');
      });

    return () => {
      isMounted = false;
    };
  }, [fetchApplicantData, navigate]);

  useEffect(() => {
    const previewContainer = resumePreviewRef.current;
    if (!resumePreview || resumePreview.previewType !== 'docx' || !previewContainer) {
      return undefined;
    }

    let cancelled = false;
    previewContainer.innerHTML = '';

    renderAsync(resumePreview.blob, previewContainer, undefined, {
      className: 'employer-resume-docx-preview',
    }).catch(() => {
      if (!cancelled) {
        setResumeError('This document could not be previewed. Download it instead.');
      }
    });

    return () => {
      cancelled = true;
      previewContainer.innerHTML = '';
    };
  }, [resumePreview]);

  useEffect(() => () => {
    if (resumePreview?.url) {
      URL.revokeObjectURL(resumePreview.url);
    }
  }, [resumePreview]);

  const handleResumeError = (error) => {
    if (error?.response?.status === 401) {
      localStorage.removeItem('accessToken');
      navigate('/live-login');
      return true;
    }

    setResumeError(error?.response?.status === 404
      ? 'The resume file is not available in storage.'
      : 'We could not load the resume. Please try again.');
    return false;
  };

  const handleStatusChange = async (event) => {
    const nextStatus = event.target.value;
    if (!applicant || nextStatus === applicant.status || isUpdatingStatus) return;

    setIsUpdatingStatus(true);
    setStatusUpdateMessage('');
    setStatusUpdateError('');

    try {
      const response = await jobApi.updateApplicantStatus(jobId, applicationId, nextStatus);
      setApplicant(response.data.data);
      setStatusUpdateMessage('Application status updated.');
    } catch (error) {
      if (error?.response?.status === 401) {
        localStorage.removeItem('accessToken');
        navigate('/live-login');
        return;
      }

      setStatusUpdateError(error?.response?.data?.message || 'Unable to update the application status.');
    } finally {
      setIsUpdatingStatus(false);
    }
  };

  const handlePreviewResume = async () => {
    if (!applicant?.hasResume || resumeAction) return;

    setResumeAction('preview');
    setResumeError('');

    try {
      const response = await jobApi.getApplicantResume(jobId, applicationId);
      const blob = response.data;
      const url = URL.createObjectURL(blob);

      setResumePreview({
        blob,
        url,
        fileName: applicant.resumeFileName || 'Resume',
        previewType: getResumePreviewType(
          applicant.resumeFileName,
          response.headers['content-type'],
        ),
      });
    } catch (error) {
      handleResumeError(error);
    } finally {
      setResumeAction('');
    }
  };

  const handleDownloadResume = async () => {
    if (!applicant?.hasResume || resumeAction) return;

    setResumeAction('download');
    setResumeError('');

    try {
      const response = await jobApi.getApplicantResume(jobId, applicationId, true);
      const url = URL.createObjectURL(response.data);
      const link = document.createElement('a');
      link.href = url;
      link.download = applicant.resumeFileName || 'resume';
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) {
      handleResumeError(error);
    } finally {
      setResumeAction('');
    }
  };

  if (status === 'loading') {
    return <div className="employer-applicants__container"><Loader size="lg" label="Loading applicant details..." /></div>;
  }

  if (status === 'error') {
    return (
      <main className="employer-applicants__container">
        <Button variant="secondary" onClick={() => navigate(`/employer/jobs/${jobId}/applicants`)}>← Applicants</Button>
        <div className="employer-applicants__error">
          <h1>Applicant unavailable</h1>
          <p>{errorMessage}</p>
        </div>
      </main>
    );
  }

  return (
    <main className="employer-applicants__container">
      <button type="button" className="employer-applicants__back" onClick={() => navigate(`/employer/jobs/${jobId}/applicants`)}>
        ← Applicants for {job.title}
      </button>

      <header className="employer-applicant-detail__hero">
        <div className="employer-applicant-detail__identity">
          <span className="employer-applicant-detail__avatar" aria-hidden="true">{getInitials(applicant.fullName)}</span>
          <div>
            <p className="employer-applicants__eyebrow">Applicant profile</p>
            <h1>{applicant.fullName}</h1>
            <p>{applicant.email}</p>
          </div>
        </div>
        <div className="employer-applicant-detail__hero-actions">
          <div className="employer-applicant-detail__status-control">
            <label htmlFor="applicant-status">Application status</label>
            <select
              id="applicant-status"
              value={applicant.status || 'APPLIED'}
              onChange={handleStatusChange}
              disabled={isUpdatingStatus}
            >
              {APPLICATION_STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </div>
        </div>
      </header>

      {(statusUpdateMessage || statusUpdateError) && (
        <p
          className={`employer-applicant-detail__feedback${statusUpdateError ? ' employer-applicant-detail__feedback--error' : ''}`}
          role={statusUpdateError ? 'alert' : 'status'}
        >
          {statusUpdateError || statusUpdateMessage}
        </p>
      )}

      <div className="employer-applicant-detail__grid">
        <section className="employer-applicant-detail__card">
          <h2>Contact information</h2>
          <dl>
            <DetailItem label="Email" value={applicant.email} />
            <DetailItem label="Phone" value={applicant.phoneNumber} />
            <DetailItem label="Address" value={applicant.address} />
          </dl>
        </section>

        <section className="employer-applicant-detail__card">
          <h2>Application</h2>
          <dl>
            <DetailItem label="Applied for" value={job.title} />
            <DetailItem label="Applied on" value={formatDate(applicant.appliedAt)} />
          </dl>
        </section>

        <section className="employer-applicant-detail__card employer-applicant-detail__card--wide">
          <div className="employer-applicant-detail__resume">
            <span className="employer-applicant-detail__resume-icon" aria-hidden="true">DOC</span>
            <div className="employer-applicant-detail__resume-copy">
              <h2>Resume</h2>
              <p>
                {applicant.hasResume
                  ? (applicant.resumeFileName || 'Resume uploaded')
                  : resumeRecordMissing
                    ? (applicant.resumeFileName || 'Resume file unavailable')
                    : 'No resume uploaded'}
              </p>
            </div>
            <div className="employer-applicant-detail__resume-actions">
              <span className={`employer-applicant-detail__resume-status${applicant.hasResume ? '' : ' employer-applicant-detail__resume-status--empty'}`}>
                {applicant.hasResume ? 'Available' : resumeRecordMissing ? 'Unavailable' : 'Not uploaded'}
              </span>
              <Button
                variant="secondary"
                className="employer-applicant-detail__resume-action"
                onClick={handlePreviewResume}
                loading={resumeAction === 'preview'}
                disabled={!applicant.hasResume || Boolean(resumeAction)}
              >
                Preview
              </Button>
              <Button
                variant="primary"
                className="employer-applicant-detail__resume-action"
                onClick={handleDownloadResume}
                loading={resumeAction === 'download'}
                disabled={!applicant.hasResume || Boolean(resumeAction)}
              >
                Download
              </Button>
            </div>
          </div>
          {resumeRecordMissing && (
            <p className="employer-applicant-detail__resume-error">
              The resume is recorded in the database, but its file is unavailable in storage. Ask the job seeker to upload it again; the new upload will repair this application.
            </p>
          )}
          {resumeError && <p className="employer-applicant-detail__resume-error">{resumeError}</p>}
        </section>

        <section className="employer-applicant-detail__card employer-applicant-detail__card--wide">
          <h2>Skills</h2>
          <div className="employer-applicant-detail__chips">
            {(applicant.skills || []).length > 0
              ? applicant.skills.map((skill) => <span key={skill}>{skill}</span>)
              : <p>Not provided</p>}
          </div>
        </section>

        <section className="employer-applicant-detail__card">
          <h2>Career preferences</h2>
          <dl>
            <DetailItem label="Preferred roles" value={(applicant.preferredJobRoles || []).join(', ')} />
            <DetailItem label="Preferred locations" value={(applicant.preferredLocations || []).join(', ')} />
            <DetailItem label="Employment type" value={formatEnum(applicant.preferredEmploymentType)} />
          </dl>
        </section>

        <section className="employer-applicant-detail__card">
          <h2>Compensation preference</h2>
          <dl>
            <DetailItem label="Expected salary" value={formatSalary(applicant.expectedSalaryMin, applicant.expectedSalaryMax)} />
            <DetailItem label="Last updated" value={formatDate(applicant.updatedAt)} />
          </dl>
        </section>
      </div>

      {resumePreview && (
        <div className="employer-resume-modal" role="dialog" aria-modal="true" aria-labelledby="resume-preview-title">
          <div className="employer-resume-modal__panel">
            <div className="employer-resume-modal__header">
              <div>
                <p className="employer-applicants__eyebrow">Resume preview</p>
                <h2 id="resume-preview-title">{resumePreview.fileName}</h2>
              </div>
              <Button variant="secondary" onClick={() => setResumePreview(null)}>Close</Button>
            </div>
            <div className="employer-resume-modal__content">
              {resumePreview.previewType === 'pdf' && (
                <iframe
                  src={resumePreview.url}
                  title={`Preview of ${resumePreview.fileName}`}
                  className="employer-resume-modal__pdf"
                />
              )}
              {resumePreview.previewType === 'docx' && (
                <div ref={resumePreviewRef} className="employer-resume-modal__docx" />
              )}
              {resumePreview.previewType === 'unsupported' && (
                <div className="employer-resume-modal__unsupported">
                  <h3>Preview is not available for this file type.</h3>
                  <p>Use Download to open the resume on your computer.</p>
                  <Button variant="primary" onClick={handleDownloadResume}>Download resume</Button>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
