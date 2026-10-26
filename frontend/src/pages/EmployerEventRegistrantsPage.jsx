import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, EmptyState, Loader, DataTable, StatusBadge } from '../components';
import employerEventApi from '../api/employerEventApi';
import './EmployerEventRegistrantsPage.css';

function formatEnum(value) {
  return value
    ? value.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
    : '—';
}

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

function formatEventDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

export default function EmployerEventRegistrantsPage() {
  const navigate = useNavigate();
  const { eventId } = useParams();
  const [event, setEvent] = useState(null);
  const [registrants, setRegistrants] = useState([]);
  const [status, setStatus] = useState('loading');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    let mounted = true;

    Promise.all([
      employerEventApi.listEmployerEvents(),
      employerEventApi.getEventRegistrants(eventId),
    ])
      .then(([eventsResponse, registrantsResponse]) => {
        if (!mounted) return;
        const ownedEvent = (eventsResponse.data.data || []).find((item) => item.id === eventId);
        if (!ownedEvent) {
          setErrorMessage('Event was not found.');
          setStatus('error');
          return;
        }
        setEvent(ownedEvent);
        setRegistrants(registrantsResponse.data.data || []);
        setStatus('success');
      })
      .catch((error) => {
        if (!mounted) return;
        if (error?.response?.status === 401) {
          localStorage.removeItem('accessToken');
          navigate('/live-login');
          return;
        }
        setErrorMessage(error?.response?.data?.message || 'Unable to load registrants for this event.');
        setStatus('error');
      });

    return () => {
      mounted = false;
    };
  }, [eventId, navigate]);

  if (status === 'loading') {
    return <div className="employer-event-registrants__container"><Loader size="lg" label="Loading registrants…" /></div>;
  }

  if (status === 'error') {
    return (
      <main className="employer-event-registrants__container">
        <Button variant="secondary" onClick={() => navigate('/employer/events')}>← Events</Button>
        <div className="employer-event-registrants__error">
          <h1>Registrants unavailable</h1>
          <p>{errorMessage}</p>
        </div>
      </main>
    );
  }

  const place = event.eventMode === 'ONLINE'
    ? 'Online'
    : [event.venueName, event.address].filter(Boolean).join(' · ') || '—';

  return (
    <main className="employer-event-registrants__container">
      <button type="button" className="employer-event-registrants__back" onClick={() => navigate('/employer/events')}>
        ← Events
      </button>

      <header className="employer-event-registrants__header">
        <div>
          <p className="employer-event-registrants__eyebrow">Registrant review</p>
          <h1>{event.title}</h1>
          <p className="employer-event-registrants__meta">
            {formatEventDate(event.startDateTime)} <span aria-hidden="true">·</span> {place} <span aria-hidden="true">·</span> {formatEnum(event.eventMode)}
          </p>
        </div>
        <div className="employer-event-registrants__header-count">
          <strong>{registrants.length}</strong>
          <span>{registrants.length === 1 ? 'Registrant' : 'Registrants'}</span>
        </div>
      </header>

      <section className="employer-event-registrants__panel" aria-labelledby="registrants-heading">
        <div className="employer-event-registrants__panel-header">
          <div>
            <h2 id="registrants-heading">People who registered</h2>
            <p>Candidates registered for this event are listed below.</p>
          </div>
        </div>

        {registrants.length === 0 ? (
          <EmptyState title="No registrants yet" description="Candidates will appear here when they register for this event." />
        ) : (
          <DataTable label={`Registrants for ${event.title}`}
            getRowId={(registrant) => registrant.jobSeekerProfileId}
            rows={registrants.map((registrant) => ({ ...registrant, person: { name: [registrant.firstName, registrant.lastName].filter(Boolean).join(' '), id: registrant.email } }))}
            columns={[
              { key: 'person', header: 'Registrant', type: 'person' },
              { key: 'registeredAt', header: 'Registered', render: formatDate },
              { key: 'registrationStatus', header: 'Status', render: (value) => <StatusBadge status={value} label={formatEnum(value)} /> },
            ]}
          />
        )}
      </section>
    </main>
  );
}
