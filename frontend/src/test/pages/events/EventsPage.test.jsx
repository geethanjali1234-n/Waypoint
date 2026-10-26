import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import EventsPage from '../../../pages/events/EventsPage';
import eventApi from '../../../api/eventApi';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';
import jobSeekerEventRegistrationsApi from '../../../api/jobSeekerEventRegistrationsApi';
import useGeolocation from '../../../hooks/useGeolocation';

vi.mock('../../../api/eventApi');
vi.mock('../../../api/jobSeekerProfileApi');
vi.mock('../../../api/jobSeekerEventRegistrationsApi');
vi.mock('../../../hooks/useGeolocation');

const sampleEvent = {
  id: 'event-1',
  title: 'Career Fair 2026',
  companyName: 'Acme Corp',
  eventMode: 'ONLINE',
  eventType: 'JOB_FAIR',
  startDateTime: '2026-12-01T10:00:00',
  registrationDeadline: '2026-11-25T00:00:00',
  capacity: 100,
  registrationCount: 10,
  description: 'Meet employers.',
  callerRegistrationStatus: 'NOT_REGISTERED',
};

const otherEvent = {
  ...sampleEvent,
  id: 'event-2',
  title: 'Networking Night',
};

function renderPage(initialEntries = ['/events']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/events" element={<EventsPage />} />
        <Route path="/events/:eventId/register" element={<div>Register Page Placeholder</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('EventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({ data: { data: [] } });
    useGeolocation.mockReturnValue({
      coords: null,
      requestLocation: vi.fn().mockRejectedValue(new Error('UNSUPPORTED')),
    });
  });

  it('shows a loader while events are being fetched', () => {
    eventApi.getEvents.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading events/i)).toBeInTheDocument();
  });

  it('shows an empty state when there are no events', async () => {
    eventApi.getEvents.mockResolvedValue({ data: { data: [] } });

    renderPage();

    expect(await screen.findByText('No events found')).toBeInTheDocument();
  });

  it('shows an error state when the events request fails', async () => {
    eventApi.getEvents.mockRejectedValue(new Error('network error'));

    renderPage();

    expect(await screen.findByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByText('Failed to load events. Please try again.')).toBeInTheDocument();
  });

  it('renders event cards and auto-selected detail pane when events load', async () => {
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });

    renderPage();

    expect(await screen.findByText('Career Fair 2026')).toBeInTheDocument();
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    await waitFor(() => {
      expect(eventApi.getEventById).toHaveBeenCalledWith('event-1');
    });
    expect(await screen.findByRole('button', { name: 'Register' })).toBeInTheDocument();
  });

  it('re-fetches events with the searched keyword', async () => {
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    const user = userEvent.setup();

    renderPage();
    await screen.findByText('Career Fair 2026');

    await user.type(screen.getByLabelText('Search keyword'), 'career');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => {
      expect(eventApi.getEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({ keyword: 'career' })
      );
    });
  });

  it('navigates to the register page when Register is clicked', async () => {
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    const user = userEvent.setup();

    renderPage();

    const registerButton = await screen.findByRole('button', { name: 'Register' });
    await user.click(registerButton);

    expect(await screen.findByText('Register Page Placeholder')).toBeInTheDocument();
  });

  it('excludes events the jobSeeker is already registered for', async () => {
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent, otherEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: otherEvent } });
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({
      data: { data: [{ eventId: 'event-1' }] },
    });

    renderPage();

    expect(await screen.findByText('Networking Night')).toBeInTheDocument();
    expect(screen.queryByText('Career Fair 2026')).not.toBeInTheDocument();
  });

  it('requests location automatically on load and sends it with the events request', async () => {
    const requestLocation = vi.fn().mockResolvedValue({ lat: 32.8140, lng: -96.9489 });
    useGeolocation.mockReturnValue({ coords: { lat: 32.8140, lng: -96.9489 }, requestLocation });
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });

    renderPage();
    await screen.findByText('Career Fair 2026');

    expect(requestLocation).toHaveBeenCalled();
    expect(eventApi.getEvents).toHaveBeenLastCalledWith(
      expect.objectContaining({ lat: 32.8140, lng: -96.9489 })
    );
  });

  it('does not show an error toast when the automatic on-load location request fails', async () => {
    // beforeEach already wires requestLocation to reject with UNSUPPORTED.
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });

    renderPage();

    expect(await screen.findByText('Career Fair 2026')).toBeInTheDocument();
    expect(screen.queryByText(/location access denied/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/does not support geolocation/i)).not.toBeInTheDocument();
  });

  it('sends maxDistanceMiles once a distance option is selected', async () => {
    useGeolocation.mockReturnValue({
      coords: { lat: 32.8140, lng: -96.9489 },
      requestLocation: vi.fn().mockResolvedValue({ lat: 32.8140, lng: -96.9489 }),
    });
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    const user = userEvent.setup();

    const { container } = renderPage();
    await screen.findByText('Career Fair 2026');

    await user.selectOptions(container.querySelector('select[name="distance"]'), '50');

    await waitFor(() => {
      expect(eventApi.getEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({ maxDistanceMiles: '50', lat: 32.8140, lng: -96.9489 })
      );
    });
  });

  it('shows an error toast and resets the distance filter when location is denied after explicitly selecting a distance', async () => {
    const requestLocation = vi.fn().mockRejectedValue(new Error('DENIED'));
    useGeolocation.mockReturnValue({ coords: null, requestLocation });
    eventApi.getEvents.mockResolvedValue({ data: { data: [sampleEvent] } });
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    const user = userEvent.setup();

    const { container } = renderPage();
    await screen.findByText('Career Fair 2026');

    await user.selectOptions(container.querySelector('select[name="distance"]'), '50');

    expect(await screen.findByText(/location access denied/i)).toBeInTheDocument();
  });
});
