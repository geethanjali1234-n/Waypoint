import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Loader, Pagination, ValidationMessage, StatusBadge } from '../components';
import employerEventApi from '../api/employerEventApi';
import { normalizeLocationSearch } from './jobPostingOptions';
import EmployerEventFormPage from './EmployerEventFormPage';
import './EmployerEventsPage.css';

const ITEMS_PER_PAGE = 10;

function formatEnum(value) {
  if (!value) return '—';
  return value
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function eventStatusLabel(status) {
  if (status === 'PUBLISHED') return 'Active';
  if (status === 'CANCELLED' || status === 'COMPLETED') return 'Closed';
  return formatEnum(status);
}

function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function locationLabel(event) {
  if (event.eventMode === 'ONLINE') return event.meetingLink || 'Online';
  return [event.venueName, event.address].filter(Boolean).join(' · ') || '—';
}

export default function EmployerEventsPage() {
  const navigate = useNavigate();
  const [events, setEvents] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [changingStatusId, setChangingStatusId] = useState('');
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);

  const loadEvents = useCallback(async () => {
    setIsLoading(true);
    setError('');
    try {
      const response = await employerEventApi.listEmployerEvents();
      setEvents(response.data.data || []);
    } catch (fetchError) {
      setError(fetchError?.response?.data?.message || 'Unable to load your events.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  const filteredEvents = useMemo(() => {
    const query = search.trim().toLowerCase();
    const normalizedQuery = normalizeLocationSearch(search);
    return events.filter((event) => {
      const normalizedEventAddress = normalizeLocationSearch(event.address || '');
      const matchesSearch = !query || [
        event.title,
        event.eventCode,
        event.eventType,
        event.eventMode,
        event.venueName,
        event.address,
      ].some((value) => value?.toLowerCase().includes(query))
        || (normalizedQuery !== '' && normalizedEventAddress.includes(normalizedQuery));
      const matchesStatus = !statusFilter
        || (statusFilter === 'ACTIVE' && event.status === 'PUBLISHED')
        || (statusFilter === 'CLOSED'
          && (event.status === 'CANCELLED' || event.status === 'COMPLETED'));
      return matchesStatus && matchesSearch;
    });
  }, [events, search, statusFilter]);

  const totalPages = Math.ceil(filteredEvents.length / ITEMS_PER_PAGE);
  const paginatedEvents = useMemo(() => {
    const startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
    return filteredEvents.slice(startIndex, startIndex + ITEMS_PER_PAGE);
  }, [currentPage, filteredEvents]);

  useEffect(() => {
    setCurrentPage(1);
  }, [search, statusFilter]);

  useEffect(() => {
    setCurrentPage((page) => Math.min(page, Math.max(totalPages, 1)));
  }, [totalPages]);


  const closeEvent = async (event) => {
    setChangingStatusId(event.id);
    setError('');
    setSuccessMessage('');
    try {
      const response = await employerEventApi.closeEvent(event.id);
      const closedEvent = response.data.data;
      setEvents((current) => current.map((item) => (item.id === closedEvent.id ? closedEvent : item)));
      setSuccessMessage(`"${closedEvent.title || closedEvent.eventCode}" was closed successfully.`);
    } catch (closeError) {
      setError(closeError?.response?.data?.message || 'Unable to close this event.');
    } finally {
      setChangingStatusId('');
    }
  };

  const reopenEvent = async (event) => {
    setChangingStatusId(event.id);
    setError('');
    setSuccessMessage('');
    try {
      const response = await employerEventApi.reopenEvent(event.id);
      const reopenedEvent = response.data.data;
      setEvents((current) => current.map((item) => (item.id === reopenedEvent.id ? reopenedEvent : item)));
      setSuccessMessage(`"${reopenedEvent.title || reopenedEvent.eventCode}" was reopened successfully.`);
    } catch (reopenError) {
      setError(reopenError?.response?.data?.message || 'Unable to reopen this event.');
    } finally {
      setChangingStatusId('');
    }
  };

  return (
    <div className="wp-prototype-events">
      <main className="wp-prototype-events__main">
        <div className="wp-prototype-events__heading">
          <div>
            <h1>Events</h1>
            <p>Manage only events owned by this employer account.</p>
          </div>
          <Button onClick={() => setIsCreateModalOpen(true)}>+ New event</Button>
        </div>

        {successMessage && <div className="wp-prototype-events__success">{successMessage}</div>}

        {error && (
          <div className="wp-prototype-events__error">
            <ValidationMessage message={error} variant="error" />
            <button type="button" onClick={loadEvents}>Try again</button>
          </div>
        )}

        <section className="wp-prototype-events__panel" aria-labelledby="events-table-title">
          <div className="wp-prototype-events__toolbar">
            <h2 id="events-table-title">Upcoming and past events</h2>
            <div>
              <input
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Search events"
                aria-label="Search events"
              />
              <select
                value={statusFilter}
                onChange={(event) => setStatusFilter(event.target.value)}
                aria-label="Filter events by status"
              >
                <option value="">Status</option>
                <option value="ACTIVE">Active</option>
                <option value="CLOSED">Closed</option>
              </select>
            </div>
          </div>

          <div className="wp-prototype-events__table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Event title</th>
                  <th>Type &amp; mode</th>
                  <th>Date &amp; location</th>
                  <th>Registrations</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {isLoading && (
                  <tr><td colSpan="6" className="wp-prototype-events__empty"><Loader label="Loading events…" /></td></tr>
                )}
                {!isLoading && !error && filteredEvents.length === 0 && (
                  <tr>
                    <td colSpan="6" className="wp-prototype-events__empty">
                      {events.length === 0
                        ? 'No events yet. Select "New event" to create one.'
                        : 'No events match your search and filter.'}
                    </td>
                  </tr>
                )}
                {!isLoading && !error && paginatedEvents.map((event) => (
                  <tr
                    key={event.id}
                    className="wp-prototype-events__event-row"
                  >
                    <td>
                      <Link to={`/employer/events/${event.id}/registrants`} aria-label={`View registrants for ${event.title}`}>{event.title}</Link>
                      <small>{event.eventCode}</small>
                    </td>
                    <td>
                      <span>{formatEnum(event.eventType)}</span>
                      <small>{formatEnum(event.eventMode)}</small>
                    </td>
                    <td className="wp-prototype-events__date-location">
                      <span>{formatDateTime(event.startDateTime)}</span>
                      <small title={locationLabel(event)}>{locationLabel(event)}</small>
                    </td>
                    <td>
                      <strong>{event.registrationCount ?? '—'}</strong>
                      <small>{event.capacity ? `of ${event.capacity} capacity` : 'No capacity limit'}</small>
                    </td>
                    <td>
                      <StatusBadge status={event.status === 'PUBLISHED' ? 'active' : event.status === 'CANCELLED' ? 'inactive' : event.status} label={eventStatusLabel(event.status)} />
                    </td>
                    <td>
                      <div className="wp-prototype-events__actions">
                        <button
                          type="button"
                          disabled={event.status !== 'PUBLISHED'}
                          onClick={(clickEvent) => {
                            clickEvent.stopPropagation();
                            navigate(`/employer/events/${event.id}/edit`);
                          }}
                        >
                          Edit
                        </button>
                        {event.status === 'PUBLISHED' && (
                          <button
                            type="button"
                            disabled={changingStatusId === event.id}
                            onClick={(clickEvent) => {
                              clickEvent.stopPropagation();
                              closeEvent(event);
                            }}
                          >
                            {changingStatusId === event.id ? 'Closing…' : 'Close'}
                          </button>
                        )}
                        {event.status === 'CANCELLED' && (
                          <button
                            type="button"
                            disabled={changingStatusId === event.id}
                            onClick={(clickEvent) => {
                              clickEvent.stopPropagation();
                              reopenEvent(event);
                            }}
                          >
                            {changingStatusId === event.id ? 'Reopening…' : 'Reopen'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!isLoading && !error && totalPages > 1 && (
            <Pagination
              currentPage={currentPage}
              totalPages={totalPages}
              onPageChange={setCurrentPage}
              className="wp-prototype-events__pagination"
            />
          )}
        </section>
      </main>

      {isCreateModalOpen && (
        <EmployerEventFormPage
          isModal
          onCancel={() => setIsCreateModalOpen(false)}
          onSaved={(createdEvent) => {
            setIsCreateModalOpen(false);
            setSuccessMessage(
              createdEvent?.title
                ? `"${createdEvent.title}" was created successfully.`
                : 'Event created successfully.'
            );
            loadEvents();
          }}
        />
      )}

    </div>
  );
}
