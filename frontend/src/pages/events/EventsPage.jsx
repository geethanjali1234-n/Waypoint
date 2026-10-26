import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AppLayout,
  Footer,
  FilterBar,
  Dropdown,
  PageHeader,
  SectionContainer,
  Button,
  EmptyState,
  Loader,
  Toast,
} from '../../components';
import useGeolocation from '../../hooks/useGeolocation';
import eventApi from '../../api/eventApi';
import jobSeekerEventRegistrationsApi from '../../api/jobSeekerEventRegistrationsApi';
import EventListItem from './components/EventListItem';
import EventDetailPane from './components/EventDetailPane';
import { downloadIcs } from '../../utils/ics';
import { DISTANCE_OPTIONS } from '../../constants/distanceOptions';
import './EventsPage.css';

// ── Filter option lists ──────────────────────────────────────────────────────
const DATE_OPTIONS = [
  { label: 'This week',     value: 'THIS_WEEK' },
  { label: 'This month',    value: 'THIS_MONTH' },
  { label: 'No preference', value: '' },
];

const EVENT_TYPE_OPTIONS = [
  { label: 'Career fair',    value: 'JOB_FAIR' },
  { label: 'Info session',  value: 'INFO_SESSION' },
  { label: 'Meetup',        value: 'NETWORKING' },
  { label: 'Webinar',       value: 'WEBINAR' },
  { label: 'Workshop',      value: 'WORKSHOP' },
  { label: 'No preference', value: '' },
];

const EVENT_MODE_OPTIONS = [
  { label: 'In-person',      value: 'OFFLINE' },
  { label: 'Virtual',        value: 'ONLINE' },
  { label: 'Hybrid',         value: 'HYBRID' },
  { label: 'No preference',  value: '' },
];

export default function EventsPage() {
  const navigate = useNavigate();

  // ── Search inputs (not applied until Search is clicked) ───────────────────
  const [keyword, setKeyword]   = useState('');
  const [location, setLocation] = useState('');

  // ── Active filters (applied to the API query) ─────────────────────────────
  const [appliedKeyword,  setAppliedKeyword]  = useState('');
  const [appliedLocation, setAppliedLocation] = useState('');
  const [dateFilter,     setDateFilter]     = useState('');
  const [distance,       setDistance]       = useState('');
  const [eventType,      setEventType]      = useState('');
  const [eventMode,      setEventMode]      = useState('');

  // ── Data state ────────────────────────────────────────────────────────────
  const [events,          setEvents]          = useState([]);
  const [listLoading,     setListLoading]     = useState(true);
  const [listError,       setListError]       = useState(null);

  // Events the job seeker has already registered for — excluded from this list
  // since they're shown in the dedicated "Registered events" view instead.
  const [registeredEventIds, setRegisteredEventIds] = useState(new Set());

  const [selectedId,      setSelectedId]      = useState(null);
  const [selectedEvent,   setSelectedEvent]   = useState(null);
  const [detailLoading,   setDetailLoading]   = useState(false);
  const [detailError,     setDetailError]     = useState(null);

  const [cancelLoading,   setCancelLoading]   = useState(false);

  // ── Geolocation (default closest-first sort + explicit distance filter) ───
  const { coords: userCoords, requestLocation } = useGeolocation();

  // ── Toast ─────────────────────────────────────────────────────────────────
  const [toast, setToast] = useState(null); // { message, variant }

  const showToast = (message, variant = 'info') => {
    setToast({ message, variant });
    setTimeout(() => setToast(null), 4000);
  };

  // ── Request geolocation automatically on page load, silently ──────────────
  // Enables "closest to me first" as the default sort even before the
  // job seeker touches any filter. A denial/unsupported browser here just
  // means the list stays in its normal date order - no error shown, since
  // the job seeker never explicitly asked for this.
  useEffect(() => {
    requestLocation().catch(() => {
      /* Silent - default date-ordered results are still correct without coords. */
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Request geolocation when user explicitly picks a distance value ───────
  // Usually a no-op by this point since the effect above already resolved
  // (or failed) location on mount. Skipped when a city is already applied -
  // the backend geocodes and centers on the city itself in that case
  // instead of the job seeker's GPS, so there's nothing for GPS to add.
  useEffect(() => {
    if (!distance || distance === 'any') return;
    if (userCoords) return; // already have it
    if (appliedLocation.trim() !== '') return;
    requestLocation().catch((error) => {
      const message = error.message === 'UNSUPPORTED'
        ? 'Your browser does not support geolocation.'
        : 'Location access denied — distance filter cannot be applied.';
      showToast(message, 'error');
      setDistance('');
    });
  }, [distance, userCoords, requestLocation, appliedLocation]);

  // Already-registered events are excluded — they live in the dedicated
  // "Registered events" view instead. Filtering and closest-first sorting by
  // distance/city both now happen server-side (see fetchEvents).
  const displayedEvents = useMemo(
    () => events.filter((event) => !registeredEventIds.has(event.id)),
    [events, registeredEventIds]
  );

  // ── Keep selection valid whenever displayedEvents changes ─────────────────
  useEffect(() => {
    if (displayedEvents.length === 0) {
      setSelectedId(null);
      return;
    }
    if (!displayedEvents.find((e) => e.id === selectedId)) {
      setSelectedId(displayedEvents[0].id);
    }
  }, [displayedEvents, selectedId]);

  // ── Fetch event list whenever active filters change ────────────────────────
  // lat/lng are sent whenever known so the backend can sort closest-first by
  // default, even without a distance filter selected; maxDistanceMiles is
  // only sent once the job seeker picks a real distance value, which turns
  // that into a hard radius filter (city, when applied, wins as the center).
  const fetchEvents = useCallback(async () => {
    setListLoading(true);
    setListError(null);
    try {
      const res = await eventApi.getEvents({
        keyword:    appliedKeyword,
        location:   appliedLocation,
        dateFilter,
        eventType,
        eventMode,
        lat: userCoords?.lat,
        lng: userCoords?.lng,
        maxDistanceMiles: distance && distance !== 'any' ? distance : undefined,
      });
      const list = res.data.data ?? [];
      setEvents(list);
    } catch {
      setListError('Failed to load events. Please try again.');
      setEvents([]);
      setSelectedId(null);
    } finally {
      setListLoading(false);
    }
  }, [appliedKeyword, appliedLocation, dateFilter, eventType, eventMode, userCoords, distance]);

  useEffect(() => {
    fetchEvents();
  }, [fetchEvents]);

  // ── Fetch the job seeker's registered event ids so they can be excluded ─────
  const fetchRegisteredEventIds = useCallback(async () => {
    try {
      const res = await jobSeekerEventRegistrationsApi.listMyRegistrations();
      const ids = (res.data.data ?? []).map((registration) => registration.eventId);
      setRegisteredEventIds(new Set(ids));
    } catch {
      // Non-fatal — worst case an already-registered event stays visible here.
    }
  }, []);

  useEffect(() => {
    fetchRegisteredEventIds();
  }, [fetchRegisteredEventIds]);

  // ── Fetch full event detail whenever selectedId changes ───────────────────
  useEffect(() => {
    if (!selectedId) {
      setSelectedEvent(null);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    setDetailError(null);
    eventApi
      .getEventById(selectedId)
      .then((res) => {
        if (!cancelled) setSelectedEvent(res.data.data);
      })
      .catch(() => {
        if (!cancelled) setDetailError('Failed to load event details.');
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedId]);

  // ── Handlers ──────────────────────────────────────────────────────────────
  const handleSearch = (e) => {
    e.preventDefault();
    setAppliedKeyword(keyword);
    setAppliedLocation(location);
  };

  const handleClearAll = () => {
    setKeyword('');
    setLocation('');
    setAppliedKeyword('');
    setAppliedLocation('');
    setDateFilter('');
    setDistance('');
    setEventType('');
    setEventMode('');
  };

  // Shared helper: re-fetch the detail pane after a registration change
  const refreshDetail = async (id) => {
    const res = await eventApi.getEventById(id);
    setSelectedEvent(res.data.data);
  };

  const handleRegister = () => {
    if (!selectedId) return;
    navigate(`/events/${selectedId}/register`);
  };

  const handleCancel = async () => {
    if (!selectedId) return;
    setCancelLoading(true);
    try {
      await eventApi.cancelRegistration(selectedId);
      await refreshDetail(selectedId);
      await fetchRegisteredEventIds();
      showToast('Registration cancelled.', 'info');
    } catch (error) {
      const message =
        error?.response?.data?.message ?? 'Failed to cancel registration. Please try again.';
      showToast(message, 'error');
    } finally {
      setCancelLoading(false);
    }
  };

  const handleAddToCalendar = () => {
    if (!selectedEvent) return;
    downloadIcs(selectedEvent);
  };

  return (
    <AppLayout
      footer={<Footer />}
    >
      <SectionContainer spacing="lg">
        <PageHeader eyebrow="Meet your next opportunity" title="Events" summary="Connect with employers at upcoming career events." />

        {/* Search bar */}
        <form className="wp-events-page__search-row" onSubmit={handleSearch}>
          <input
            type="text"
            className="wp-field__control wp-events-page__search-keyword"
            placeholder="Search by keyword"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            aria-label="Search keyword"
          />
          <div className="wp-events-page__search-location">
            <svg
              className="wp-events-page__search-location-icon"
              width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="1.8" aria-hidden="true"
            >
              <path d="M12 21s7-6.6 7-12a7 7 0 1 0-14 0c0 5.4 7 12 7 12z" />
              <circle cx="12" cy="9" r="2.5" />
            </svg>
            <input
              type="text"
              className="wp-field__control wp-events-page__search-location-input"
              placeholder="City, State"
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              aria-label="Location"
            />
          </div>
          <Button type="submit" variant="primary">
            Search
          </Button>
        </form>

        {/* Filter row */}
        <FilterBar>
          <Dropdown
            name="dateFilter"
            options={DATE_OPTIONS}
            value={dateFilter}
            onChange={(e) => setDateFilter(e.target.value)}
            placeholder="Date"
            className="wp-events-page__filter-dropdown"
          />
          <Dropdown
            name="distance"
            options={DISTANCE_OPTIONS}
            value={distance}
            onChange={(e) => setDistance(e.target.value)}
            placeholder="Distance"
            className="wp-events-page__filter-dropdown"
          />
          <Dropdown
            name="eventType"
            options={EVENT_TYPE_OPTIONS}
            value={eventType}
            onChange={(e) => setEventType(e.target.value)}
            placeholder="Event type"
            className="wp-events-page__filter-dropdown"
          />
          <Dropdown
            name="eventMode"
            options={EVENT_MODE_OPTIONS}
            value={eventMode}
            onChange={(e) => setEventMode(e.target.value)}
            placeholder="Event mode"
            className="wp-events-page__filter-dropdown"
          />
          <button type="button" className="wp-events-page__clear-all" onClick={handleClearAll}>
            Clear all
          </button>
        </FilterBar>

        {/* Results count */}
        {!listLoading && !listError && (
          <p className="wp-events-page__results-count">
            Upcoming events &middot; <strong>{displayedEvents.length} results</strong>
          </p>
        )}

        {/* Split list + detail */}
        <div className="wp-events-page__split">
          {/* Left: event list */}
          <div className="wp-events-page__list">
            {listLoading && <Loader label="Loading events…" />}

            {listError && (
              <EmptyState title="Something went wrong" description={listError} />
            )}

            {!listLoading && !listError && displayedEvents.length === 0 && (
              <EmptyState
                title="No events found"
                description="Try adjusting your filters or search terms."
              />
            )}

            {!listLoading &&
              !listError &&
              displayedEvents.map((event) => (
                <EventListItem
                  key={event.id}
                  event={event}
                  selected={event.id === selectedId}
                  onClick={setSelectedId}
                />
              ))}
          </div>

          {/* Right: event detail */}
          <div className="wp-events-page__detail">
            {detailLoading && <Loader label="Loading details…" />}

            {!detailLoading && detailError && (
              <EmptyState title="Could not load event" description={detailError} />
            )}

            {!detailLoading && !detailError && selectedEvent && (
              <EventDetailPane
                event={selectedEvent}
                onRegister={handleRegister}
                onCancel={handleCancel}
                onAddToCalendar={handleAddToCalendar}
                cancelLoading={cancelLoading}
              />
            )}

            {!detailLoading && !detailError && !selectedEvent && !listLoading && (
              <EmptyState
                title="Select an event"
                description="Choose an event from the list to see details."
              />
            )}
          </div>
        </div>
      </SectionContainer>

      {/* Toast notifications */}
      {toast && (
        <div className="wp-events-page__toast-container">
          <Toast
            message={toast.message}
            variant={toast.variant}
            onClose={() => setToast(null)}
          />
        </div>
      )}
    </AppLayout>
  );
}
