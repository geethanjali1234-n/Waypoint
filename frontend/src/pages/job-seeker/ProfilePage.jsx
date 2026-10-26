import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AppLayout, Card, Avatar, Button, StatCard, EmptyState, Loader, ValidationMessage, StatusBadge } from '../../components';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import jobSeekerApplicationsApi from '../../api/jobSeekerApplicationsApi';
import jobSeekerEventRegistrationsApi from '../../api/jobSeekerEventRegistrationsApi';
import resolveFileUrl from '../../api/resolveFileUrl';
import './ProfilePage.css';

const RECENT_APPLICATIONS_LIMIT = 3;
const RECENT_REGISTRATIONS_LIMIT = 3;

const STATUS_TONES = {
  APPLIED: 'info',
  UNDER_REVIEW: 'warning',
  SHORTLISTED: 'success',
  SELECTED: 'success',
  REJECTED: 'error',
  WITHDRAWN: 'neutral',
};

function formatStatus(status) {
  return status
    ? status.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
    : '';
}

function formatEventDate(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function formatPhoneForDisplay(phoneNumber) {
  if (!phoneNumber || !phoneNumber.startsWith('+1') || phoneNumber.length !== 12) {
    return phoneNumber;
  }
  const digits = phoneNumber.slice(2);
  return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
}

function initialsFor(firstName, lastName) {
  return `${firstName?.[0] || ''}${lastName?.[0] || ''}`.toUpperCase();
}

/**
 * ProfilePage - job seeker's own profile summary. Job tracking shows the
 * job seeker's most recent applications; Registered events shows their most
 * recent active event registrations.
 *
 * @example
 * <Route path="/job-seeker/profile" element={<ProfilePage />} />
 */
export default function ProfilePage() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [applications, setApplications] = useState([]);
  const [registrations, setRegistrations] = useState([]);

  useEffect(() => {
    let isMounted = true;

    jobSeekerProfileApi
      .getProfile()
      .then((response) => {
        if (isMounted) setProfile(response.data.data);
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
  }, []);

  useEffect(() => {
    let isMounted = true;

    jobSeekerApplicationsApi
      .listMyApplications()
      .then((response) => {
        if (isMounted) setApplications(response.data.data || []);
      })
      .catch(() => {
        // Recent applications are supplementary here; the dedicated
        // Applications page surfaces load failures directly.
      });

    return () => {
      isMounted = false;
    };
  }, []);

  useEffect(() => {
    let isMounted = true;

    jobSeekerEventRegistrationsApi
      .listMyRegistrations()
      .then((response) => {
        if (isMounted) setRegistrations(response.data.data || []);
      })
      .catch(() => {
        // Recent registrations are supplementary here; failures are silent
        // since there is no dedicated registrations page to surface them.
      });

    return () => {
      isMounted = false;
    };
  }, []);

  const goToApplication = (application) =>
    navigate(`/careers/${application.jobId}/apply?view=1`, {
      state: {
        viewStatusLabel: formatStatus(application.status),
        viewStatusTone: STATUS_TONES[application.status] || 'neutral',
      },
    });

  const goToRegistration = (registration) => navigate(`/events/${registration.eventId}/register`);

  const headerUser = profile
    ? { name: `${profile.firstName} ${profile.lastName}`.trim(), email: profile.email, initials: initialsFor(profile.firstName, profile.lastName) }
    : { name: '', initials: '' };

  if (isLoading) {
    return (
      <AppLayout>
        <div className="wp-profile-page">
          <Loader label="Loading your profile..." />
        </div>
      </AppLayout>
    );
  }

  if (loadError || !profile) {
    return (
      <AppLayout>
        <div className="wp-profile-page">
          <ValidationMessage message={loadError || 'Profile not found'} variant="error" />
        </div>
      </AppLayout>
    );
  }

  const fullName = `${profile.firstName} ${profile.lastName}`.trim();
  const subtitleParts = [profile.address, profile.email, formatPhoneForDisplay(profile.phoneNumber)].filter(Boolean);

  return (
    <AppLayout>
      <div className="wp-profile-page">
        <Card className="wp-profile-page__summary">
          <div className="wp-profile-page__identity">
            <Avatar imageUrl={resolveFileUrl(profile.profilePictureUrl)} initials={headerUser.initials} size="lg" alt={fullName} />
            <div>
              <h1 className="wp-profile-page__name">{fullName}</h1>
              <p className="wp-profile-page__subtitle">{subtitleParts.join(' · ')}</p>
            </div>
          </div>
          <div className="wp-profile-page__summary-actions">
            <Button variant="secondary" onClick={() => navigate('/job-seeker/profile/edit')}>
              Edit profile
            </Button>
            {Number.isFinite(profile.completionPercentage) && <div className="wp-profile-page__completion">
              <div className="wp-profile-page__completion-track" role="progressbar" aria-label="Profile completion" aria-valuenow={profile.completionPercentage} aria-valuemin={0} aria-valuemax={100}>
                <div
                  className="wp-profile-page__completion-fill"
                  style={{ width: `${profile.completionPercentage}%` }}
                />
              </div>
              <span>{profile.completionPercentage}% complete</span>
            </div>}
          </div>
        </Card>

        <div className="wp-profile-page__stats">
          {Number.isFinite(profile.jobsApplied) && <StatCard value={profile.jobsApplied} label="jobs applied" />}
          {Number.isFinite(profile.inReview) && <StatCard value={profile.inReview} label="in review" />}
          {Number.isFinite(profile.shortlisted) && <StatCard value={profile.shortlisted} label="shortlisted" />}
          {Number.isFinite(profile.eventsRegistered) && <StatCard value={profile.eventsRegistered} label="events registered" />}
        </div>

        <Card
          title="Job tracking"
          actions={
            <Link className="wp-profile-page__link" to="/job-seeker/applications">
              View all applications
            </Link>
          }
        >
          {applications.length === 0 ? (
            <EmptyState
              title="No applications yet"
              description="Jobs you apply to will show up here."
            />
          ) : (
            applications.slice(0, RECENT_APPLICATIONS_LIMIT).map((application) => (
              <button
                key={application.id}
                type="button"
                className="wp-profile-page__application-row"
                onClick={() => goToApplication(application)}
              >
                <div>
                  <p>{application.jobTitle || 'Untitled role'}</p>
                  <p>{[application.companyName, application.location].filter(Boolean).join(' · ')}</p>
                </div>
                <StatusBadge status={application.status} label={formatStatus(application.status)} />
              </button>
            ))
          )}
        </Card>

        <Card
          title="Registered events"
          actions={
            <Link className="wp-profile-page__link" to="/job-seeker/registrations">
              View all registered events
            </Link>
          }
        >
          {registrations.length === 0 ? (
            <EmptyState
              title="No registered events yet"
              description="Events you register for will show up here."
            />
          ) : (
            registrations.slice(0, RECENT_REGISTRATIONS_LIMIT).map((registration) => (
              <button
                key={registration.id}
                type="button"
                className="wp-profile-page__application-row"
                onClick={() => goToRegistration(registration)}
              >
                <div>
                  <p>{registration.eventTitle || 'Untitled event'}</p>
                  <p>{[registration.companyName, formatEventDate(registration.startDateTime)].filter(Boolean).join(' · ')}</p>
                </div>
                <StatusBadge status="active" label="Registered" />
              </button>
            ))
          )}
        </Card>
      </div>
    </AppLayout>
  );
}
