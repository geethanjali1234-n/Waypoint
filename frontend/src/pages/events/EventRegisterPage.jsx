import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Breadcrumb,
  PageHeader,
  Card,
  TwoColumnLayout,
  Loader,
  EmptyState,
  ValidationMessage,
  Button,
  Toast,
  TextInput,
  EmailInput,
} from '../../components';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import eventApi from '../../api/eventApi';
import weatherApi from '../../api/weatherApi';
import { downloadIcs } from '../../utils/ics';
import './EventRegisterPage.css';

const MODE_LABEL = { ONLINE: 'Virtual', OFFLINE: 'In-person', HYBRID: 'Hybrid' };

const EVENT_TYPE_LABEL = {
  WORKSHOP: 'Workshop',
  WEBINAR: 'Webinar',
  JOB_FAIR: 'Career fair',
  NETWORKING: 'Meetup',
  INFO_SESSION: 'Info session',
};

function formatDateTime(value) {
  if (!value) return '—';
  return new Date(value).toLocaleString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function formatDate(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function CapacityMeter({ registrationCount, capacity }) {
  if (capacity == null) return <span className="wp-event-register__v">—</span>;
  return (
    <div className="wp-event-register__capacity">
      <progress
        className="wp-event-register__capacity-bar"
        max={capacity}
        value={registrationCount}
        aria-label={`${registrationCount} of ${capacity} spots filled`}
      />
      <span className="wp-event-register__v">
        {registrationCount} / {capacity}
      </span>
    </div>
  );
}

export default function EventRegisterPage() {
  const { eventId } = useParams();
  const navigate = useNavigate();

  const [event, setEvent] = useState(null);
  const [profile, setProfile] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [isNotFound, setIsNotFound] = useState(false);

  const [isRegistered, setIsRegistered] = useState(false);
  const [isRegistering, setIsRegistering] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [toast, setToast] = useState(null);

  const [weather, setWeather] = useState(null);
  const [weatherError, setWeatherError] = useState('');
  const [isCheckingWeather, setIsCheckingWeather] = useState(false);

  useEffect(() => {
    let isCancelled = false;

    setIsLoading(true);
    setError(null);
    setIsNotFound(false);

    Promise.all([eventApi.getEventById(eventId), jobSeekerProfileApi.getProfile()])
      .then(([eventResponse, profileResponse]) => {
        if (isCancelled) return;
        setEvent(eventResponse.data.data);
        setProfile(profileResponse.data.data);
        setIsRegistered(eventResponse.data.data.callerRegistrationStatus === 'REGISTERED');
      })
      .catch((fetchError) => {
        if (isCancelled) return;

        if (fetchError?.response?.status === 404) {
          setIsNotFound(true);
          return;
        }

        const message = fetchError?.response?.data?.message || 'Unable to load this event. Please try again.';
        setError(message);
      })
      .finally(() => {
        if (isCancelled) return;
        setIsLoading(false);
      });

    return () => {
      isCancelled = true;
    };
  }, [eventId]);

  useEffect(() => {
    if (!event || event.eventMode === 'ONLINE') return;

    let isCancelled = false;
    setIsCheckingWeather(true);
    setWeatherError('');

    weatherApi
      .getEventForecast(event.id)
      .then((response) => {
        if (isCancelled) return;
        setWeather(response.data.data);
      })
      .catch((fetchError) => {
        if (isCancelled) return;
        const message =
          fetchError?.response?.data?.message || 'Unable to retrieve the weather forecast.';
        setWeatherError(message);
      })
      .finally(() => {
        if (isCancelled) return;
        setIsCheckingWeather(false);
      });

    return () => {
      isCancelled = true;
    };
  }, [event]);

  const refreshEvent = () => eventApi.getEventById(eventId).then((res) => setEvent(res.data.data));

  const handleRegister = () => {
    if (isRegistering || isRegistered) return;

    if (!localStorage.getItem('accessToken')) {
      setToast({ message: 'Please log in again before registering.', variant: 'error' });
      return;
    }

    setIsRegistering(true);
    setToast(null);

    eventApi
      .registerForEvent(eventId)
      .then(() => {
        setIsRegistered(true);
        setToast({ message: "You're registered for this event!", variant: 'success' });
        return refreshEvent();
      })
      .catch((registerError) => {
        const message =
          registerError?.response?.data?.message || 'Unable to register. Please try again.';
        setToast({ message, variant: 'error' });
        if (message.toLowerCase().includes('already registered')) setIsRegistered(true);
      })
      .finally(() => {
        setIsRegistering(false);
      });
  };

  const handleCancel = () => {
    if (isCancelling) return;

    setIsCancelling(true);
    setToast(null);

    eventApi
      .cancelRegistration(eventId)
      .then(() => {
        setIsRegistered(false);
        setToast({ message: 'Registration cancelled.', variant: 'info' });
        return refreshEvent();
      })
      .catch((cancelError) => {
        const message =
          cancelError?.response?.data?.message || 'Unable to cancel registration. Please try again.';
        setToast({ message, variant: 'error' });
      })
      .finally(() => {
        setIsCancelling(false);
      });
  };

  const handleAddToCalendar = () => {
    if (!event) return;
    downloadIcs(event);
  };

  const isFull = event?.capacity != null && event.registrationCount >= event.capacity && !isRegistered;
  const isPastDeadline = event?.registrationDeadline && new Date(event.registrationDeadline) < new Date();

  const submitLabel = isRegistered
    ? 'Registered'
    : isRegistering
      ? 'Registering…'
      : isFull
        ? 'Event full'
        : isPastDeadline
          ? 'Registration closed'
          : 'Register';

  const mainContent = event && (
    <>
      <Card>
        <div className="wp-event-register__facts-grid">
          <div>
            <p className="wp-event-register__k">Date &amp; time</p>
            <p className="wp-event-register__v">{formatDateTime(event.startDateTime)}</p>
          </div>
          <div>
            <p className="wp-event-register__k">Register by</p>
            <p className="wp-event-register__v">
              {event.registrationDeadline ? formatDate(event.registrationDeadline) : 'No deadline'}
            </p>
          </div>
          <div>
            <p className="wp-event-register__k">Format</p>
            <p className="wp-event-register__v">{MODE_LABEL[event.eventMode] ?? event.eventMode}</p>
          </div>
          <div>
            <p className="wp-event-register__k">Capacity</p>
            <CapacityMeter registrationCount={event.registrationCount ?? 0} capacity={event.capacity} />
          </div>
          <div>
            <p className="wp-event-register__k">Event type</p>
            <p className="wp-event-register__v">{EVENT_TYPE_LABEL[event.eventType] ?? event.eventType}</p>
          </div>
        </div>
      </Card>

      <Card title="About this event">
        <p className="wp-event-register__description">{event.description}</p>
      </Card>

      {(event.venueName || event.address || event.meetingLink) && (
        <Card title="Venue">
          {event.eventMode === 'ONLINE' ? (
            <p className="wp-event-register__v">{event.meetingLink || 'Meeting link available after registration.'}</p>
          ) : (
            <p className="wp-event-register__v">
              {[event.venueName, event.address].filter(Boolean).join(' — ')}
            </p>
          )}
        </Card>
      )}

      {event.eventMode !== 'ONLINE' && (
        <Card title="Weather forecast">
          {isCheckingWeather && <Loader label="Checking the forecast…" />}

          {!isCheckingWeather && weatherError && (
            <ValidationMessage message={weatherError} variant="error" />
          )}

          {!isCheckingWeather && weather && (
            <div className="wp-event-register__weather">
              <div className="wp-event-register__weather-summary">
                <div>
                  <h3 className="wp-event-register__weather-condition">{weather.weather}</h3>
                  <p className="wp-event-register__v">{weather.description}</p>
                </div>
                <div className="wp-event-register__weather-temp">
                  {Math.round(weather.temperature)}°F
                </div>
              </div>

              <div className="wp-event-register__facts-grid">
                <div>
                  <p className="wp-event-register__k">Feels like</p>
                  <p className="wp-event-register__v">{Math.round(weather.feelsLike)}°F</p>
                </div>
                <div>
                  <p className="wp-event-register__k">Humidity</p>
                  <p className="wp-event-register__v">{weather.humidity}%</p>
                </div>
                <div>
                  <p className="wp-event-register__k">Wind speed</p>
                  <p className="wp-event-register__v">{weather.windSpeed?.toFixed(1)} mph</p>
                </div>
                <div>
                  <p className="wp-event-register__k">Rain chance</p>
                  <p className="wp-event-register__v">{Math.round(weather.rainChance)}%</p>
                </div>
              </div>
            </div>
          )}
        </Card>
      )}
    </>
  );

  const sideContent = event && profile && (
    <div className="wp-event-register__panel">
      <h3 className="wp-event-register__panel-title">Register for this event</h3>
      {!isRegistered && event.registrationDeadline && (
        <p className="wp-event-register__deadline">
          Registration closes {formatDate(event.registrationDeadline)}
        </p>
      )}

      {isRegistered ? (
        <>
          <div className="wp-event-register__status">
            <span>You&apos;re registered for this event.</span>
          </div>
          <div className="wp-event-register__post-actions">
            <Button variant="secondary" onClick={handleAddToCalendar}>
              Add to calendar
            </Button>
            <Button variant="secondary" onClick={handleCancel} loading={isCancelling}>
              Cancel registration
            </Button>
          </div>
        </>
      ) : (
        <>
          <div className="wp-event-register__grid-2">
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

          <Button
            variant="primary"
            fullWidth
            onClick={handleRegister}
            disabled={isRegistering || isFull || isPastDeadline}
            loading={isRegistering}
            className="wp-event-register__submit"
          >
            {submitLabel}
          </Button>
        </>
      )}
    </div>
  );

  return (
    <>
      <div className="wp-event-register__content">
        <Breadcrumb items={[{ label: 'Events', href: '/events' }, { label: event?.title || 'Register' }]} />

        {isLoading && <Loader label="Loading event details…" />}

        {!isLoading && error && <ValidationMessage message={error} variant="error" />}

        {!isLoading && isNotFound && (
          <EmptyState
            title="Event not found"
            description="This event may no longer be available."
            action={
              <Button variant="primary" onClick={() => navigate('/events')}>
                Back to Events
              </Button>
            }
          />
        )}

        {!isLoading && !error && !isNotFound && event && (
          <>
            <PageHeader
              title={event.title}
              subtitle={event.companyName ? `${event.companyName} · ${MODE_LABEL[event.eventMode] ?? event.eventMode}` : undefined}
            />

            {toast && <Toast message={toast.message} variant={toast.variant} onClose={() => setToast(null)} />}

            <TwoColumnLayout ratio="2:1" main={mainContent} side={sideContent} />
          </>
        )}
      </div>
    </>
  );
}
