import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { PageHeader, Card, Loader, EmptyState, ValidationMessage, StatusBadge } from "../components";
import jobSeekerApplicationsApi from "../api/jobSeekerApplicationsApi";
import "./JobSeekerApplicationsPage.css";

const STATUS_TONES = {
  APPLIED: "info",
  UNDER_REVIEW: "warning",
  SHORTLISTED: "success",
  SELECTED: "success",
  REJECTED: "error",
  WITHDRAWN: "neutral",
};

function formatStatus(status) {
  return status
    ? status.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase())
    : "";
}

function formatAppliedDate(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

export default function JobSeekerApplicationsPage() {
  const navigate = useNavigate();
  const [applications, setApplications] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let isMounted = true;

    jobSeekerApplicationsApi
      .listMyApplications()
      .then((response) => {
        if (isMounted) setApplications(response.data.data || []);
      })
      .catch((fetchError) => {
        if (isMounted) {
          setError(fetchError?.response?.data?.message || "Unable to load your applications. Please try again.");
        }
      })
      .finally(() => {
        if (isMounted) setIsLoading(false);
      });

    return () => {
      isMounted = false;
    };
  }, []);

  return (
    <div>
      <PageHeader title="Applications" subtitle="Track the roles you've applied to." />

      {isLoading && <Loader label="Loading your applications…" />}

      {!isLoading && error && <ValidationMessage message={error} variant="error" />}

      {!isLoading && !error && applications.length === 0 && (
        <EmptyState
          title="No applications yet"
          description="Jobs you apply to will show up here."
        />
      )}

      {!isLoading && !error && applications.length > 0 && (
        <div className="wp-job-seeker-applications__list">
          {applications.map((application) => (
            <Card key={application.id} className="wp-job-seeker-applications__card">
              <button
                type="button"
                className="wp-job-seeker-applications__row"
                onClick={() =>
                  navigate(`/careers/${application.jobId}/apply?view=1`, {
                    state: {
                      viewStatusLabel: formatStatus(application.status),
                      viewStatusTone: STATUS_TONES[application.status] || "neutral",
                    },
                  })
                }
              >
                <p>{application.jobTitle || "Untitled role"}</p>
                <p>
                  {[application.companyName, application.location].filter(Boolean).join(" · ")}
                </p>
                <StatusBadge status={application.status} label={formatStatus(application.status)} />
                {formatAppliedDate(application.appliedAt) && (
                  <p>Applied {formatAppliedDate(application.appliedAt)}</p>
                )}
              </button>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
