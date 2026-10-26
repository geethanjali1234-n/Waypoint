import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { PageHeader, Card, Loader, EmptyState, ValidationMessage, StatusBadge } from "../components";
import jobSeekerEventRegistrationsApi from "../api/jobSeekerEventRegistrationsApi";
import "./JobSeekerApplicationsPage.css";

function formatEventDate(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

export default function JobSeekerRegisteredEventsPage() {
  const navigate = useNavigate();
  const [registrations, setRegistrations] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let isMounted = true;

    jobSeekerEventRegistrationsApi
      .listMyRegistrations()
      .then((response) => {
        if (isMounted) setRegistrations(response.data.data || []);
      })
      .catch((fetchError) => {
        if (isMounted) {
          setError(fetchError?.response?.data?.message || "Unable to load your registered events. Please try again.");
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
      <PageHeader title="Registered Events" subtitle="Events you've registered for." />

      {isLoading && <Loader label="Loading your registered events…" />}

      {!isLoading && error && <ValidationMessage message={error} variant="error" />}

      {!isLoading && !error && registrations.length === 0 && (
        <EmptyState
          title="No registered events yet"
          description="Events you register for will show up here."
        />
      )}

      {!isLoading && !error && registrations.length > 0 && (
        <div className="wp-job-seeker-registrations__list">
          {registrations.map((registration) => (
            <Card key={registration.id} className="wp-job-seeker-applications__card">
              <button
                type="button"
                className="wp-job-seeker-applications__row"
                onClick={() => navigate(`/events/${registration.eventId}/register`)}
              >
                <p>{registration.eventTitle || "Untitled event"}</p>
                <p>{registration.companyName}</p>
                <StatusBadge status="active" label="Registered" />
                {formatEventDate(registration.startDateTime) && (
                  <p>{formatEventDate(registration.startDateTime)}</p>
                )}
              </button>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
