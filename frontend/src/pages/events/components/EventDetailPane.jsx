import { Button, Card } from '../../../components';
import formatLocationWithoutZip from '../../../utils/formatLocation';
import './EventDetailPane.css';

const MODE_LABEL = { ONLINE: 'Virtual', OFFLINE: 'In-person', HYBRID: 'Hybrid' };

const EVENT_TYPE_LABEL = {
  WORKSHOP: 'Workshop',
  WEBINAR: 'Webinar',
  JOB_FAIR: 'Career fair',
  NETWORKING: 'Meetup',
  INFO_SESSION: 'Info session',
};

function formatDateTime(dateStr) {
  if (!dateStr) return '—';
  return new Date(dateStr).toLocaleString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function formatDate(dateStr) {
  if (!dateStr) return '—';
  return new Date(dateStr).toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  });
}

function CapacityMeter({ registrationCount, capacity }) {
  if (capacity == null) return <span className="wp-event-detail__fact-value">—</span>;
  const pct = Math.min(100, Math.round((registrationCount / capacity) * 100));
  return (
    <div className="wp-event-detail__capacity">
      <progress
        className="wp-event-detail__capacity-bar"
        max={capacity}
        value={registrationCount}
        aria-label={`${registrationCount} of ${capacity} spots filled`}
      />
      <span className="wp-event-detail__fact-value">
        {registrationCount} / {capacity}
        {pct >= 90 && <span className="wp-event-detail__capacity-warning"> · Almost full</span>}
      </span>
    </div>
  );
}

export default function EventDetailPane({
  event,
  onRegister,
  onCancel,
  onAddToCalendar,
  cancelLoading = false,
}) {
  const isRegistered = event.callerRegistrationStatus === 'REGISTERED';
  const isFull =
    event.capacity != null && event.registrationCount >= event.capacity && !isRegistered;
  const isPastDeadline =
    event.registrationDeadline && new Date(event.registrationDeadline) < new Date();

  return (
    <div className="wp-event-detail">
      <div className="wp-event-detail__header">
        <div className="wp-event-detail__title-group">
          <h2 className="wp-event-detail__title">{event.title}</h2>
          <p className="wp-event-detail__host">{event.companyName}</p>
        </div>
        {isRegistered && (
          <Button variant="secondary" onClick={onAddToCalendar}>
            Add to calendar
          </Button>
        )}
      </div>

      <div className="wp-event-detail__facts">
        <div className="wp-event-detail__fact">
          <span className="wp-event-detail__fact-label">Date &amp; time</span>
          <span className="wp-event-detail__fact-value">{formatDateTime(event.startDateTime)}</span>
        </div>
        <div className="wp-event-detail__fact">
          <span className="wp-event-detail__fact-label">Register by</span>
          <span className="wp-event-detail__fact-value">
            {event.registrationDeadline ? formatDate(event.registrationDeadline) : 'No deadline'}
          </span>
        </div>
        <div className="wp-event-detail__fact">
          <span className="wp-event-detail__fact-label">Format</span>
          <span className="wp-event-detail__fact-value">
            {MODE_LABEL[event.eventMode] ?? event.eventMode}
          </span>
        </div>
        <div className="wp-event-detail__fact">
          <span className="wp-event-detail__fact-label">Location</span>
          <span className="wp-event-detail__fact-value">
            {event.address ? formatLocationWithoutZip(event.address) : 'Virtual'}
          </span>
        </div>
        <div className="wp-event-detail__fact">
          <span className="wp-event-detail__fact-label">Capacity</span>
          <CapacityMeter
            registrationCount={event.registrationCount ?? 0}
            capacity={event.capacity}
          />
        </div>
        <div className="wp-event-detail__fact">
          <span className="wp-event-detail__fact-label">Event type</span>
          <span className="wp-event-detail__fact-value">
            {EVENT_TYPE_LABEL[event.eventType] ?? event.eventType}
          </span>
        </div>
      </div>

      <Card title="About this event">
        <p className="wp-event-detail__description">{event.description}</p>
      </Card>

      <div className="wp-event-detail__rsvp">
        {isRegistered ? (
          <div className="wp-event-detail__registered-banner">
            <span>You&apos;re registered for this event.</span>
            <button
              type="button"
              className="wp-event-detail__cancel-link"
              onClick={onCancel}
              disabled={cancelLoading}
            >
              {cancelLoading ? 'Cancelling…' : 'Cancel registration'}
            </button>
          </div>
        ) : (
          <Button
            variant="primary"
            fullWidth
            onClick={onRegister}
            disabled={isFull || isPastDeadline}
          >
            {isFull ? 'Event full' : isPastDeadline ? 'Registration closed' : 'Register'}
          </Button>
        )}
      </div>
    </div>
  );
}
