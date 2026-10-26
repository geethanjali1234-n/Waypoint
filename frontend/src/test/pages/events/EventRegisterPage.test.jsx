import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import EventRegisterPage from '../../../pages/events/EventRegisterPage';
import eventApi from '../../../api/eventApi';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';
import weatherApi from '../../../api/weatherApi';

vi.mock('../../../api/eventApi');
vi.mock('../../../api/jobSeekerProfileApi');
vi.mock('../../../api/weatherApi');

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
  meetingLink: 'https://meet.example.com/fair',
  callerRegistrationStatus: 'NOT_REGISTERED',
};

const sampleOfflineEvent = {
  ...sampleEvent,
  eventMode: 'OFFLINE',
  venueName: 'Waypoint HQ',
  address: 'Austin, Texas',
};

const sampleForecast = {
  weather: 'Clear',
  description: 'clear sky',
  temperature: 88,
  feelsLike: 90,
  humidity: 45,
  windSpeed: 6.5,
  rainChance: 30,
};

const sampleProfile = {
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'ada@example.com',
  phoneNumber: '+11234567890',
};

function renderPage(initialEntry = '/events/event-1/register') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/events" element={<div>Events Page Placeholder</div>} />
        <Route path="/events/:eventId/register" element={<EventRegisterPage />} />
      </Routes>
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.setItem('accessToken', 'valid-token');
});

describe('EventRegisterPage', () => {
  it('shows a loader while event and profile data are being fetched', () => {
    eventApi.getEventById.mockReturnValue(new Promise(() => {}));
    jobSeekerProfileApi.getProfile.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading event details/i)).toBeInTheDocument();
  });

  it('shows a generic error message when the event fails to load', async () => {
    eventApi.getEventById.mockRejectedValue({ response: { data: { message: 'Server exploded' } } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });

    renderPage();

    expect(await screen.findByText('Server exploded')).toBeInTheDocument();
  });

  it('shows a not-found state and navigates back to Events on 404', async () => {
    eventApi.getEventById.mockRejectedValue({ response: { status: 404 } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    const user = userEvent.setup();

    renderPage();

    expect(await screen.findByText('Event not found')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Back to Events' }));

    expect(await screen.findByText('Events Page Placeholder')).toBeInTheDocument();
  });

  it('renders event facts and a disabled, pre-filled registration form when not registered', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });

    renderPage();

    expect(await screen.findByRole('heading', { name: 'Career Fair 2026' })).toBeInTheDocument();
    expect(screen.getByLabelText('First name')).toHaveValue('Ada');
    expect(screen.getByLabelText('Last name')).toHaveValue('Lovelace');
    expect(screen.getByLabelText('Email')).toHaveValue('ada@example.com');
    expect(screen.getByLabelText('First name')).toBeDisabled();

    const registerButton = screen.getByRole('button', { name: 'Register' });
    expect(registerButton).toBeEnabled();
  });

  it('registers successfully and switches to the registered view', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    eventApi.registerForEvent.mockResolvedValue({});
    const user = userEvent.setup();

    renderPage();

    const registerButton = await screen.findByRole('button', { name: 'Register' });
    await user.click(registerButton);

    expect(await screen.findByText("You're registered for this event!")).toBeInTheDocument();
    expect(screen.getByText("You're registered for this event.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add to calendar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel registration' })).toBeInTheDocument();
    expect(eventApi.registerForEvent).toHaveBeenCalledWith('event-1');
  });

  it('shows an error toast and stays unregistered when registration fails', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    eventApi.registerForEvent.mockRejectedValue({
      response: { data: { message: 'Event is full' } },
    });
    const user = userEvent.setup();

    renderPage();

    const registerButton = await screen.findByRole('button', { name: 'Register' });
    await user.click(registerButton);

    expect(await screen.findByText('Event is full')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register' })).toBeInTheDocument();
  });

  it('treats an "already registered" failure as a successful registration', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    eventApi.registerForEvent.mockRejectedValue({
      response: { data: { message: 'You are already registered for this event' } },
    });
    const user = userEvent.setup();

    renderPage();

    const registerButton = await screen.findByRole('button', { name: 'Register' });
    await user.click(registerButton);

    expect(await screen.findByText('You are already registered for this event')).toBeInTheDocument();
    expect(screen.getByText("You're registered for this event.")).toBeInTheDocument();
  });

  it('cancels an existing registration', async () => {
    const registeredEvent = { ...sampleEvent, callerRegistrationStatus: 'REGISTERED' };
    eventApi.getEventById.mockResolvedValue({ data: { data: registeredEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    eventApi.cancelRegistration.mockResolvedValue({});
    const user = userEvent.setup();

    renderPage();

    const cancelButton = await screen.findByRole('button', { name: 'Cancel registration' });
    await user.click(cancelButton);

    expect(await screen.findByText('Registration cancelled.')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Register' })).toBeInTheDocument();
    });
    expect(eventApi.cancelRegistration).toHaveBeenCalledWith('event-1');
  });

  it('fetches and renders the weather forecast for an in-person event', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleOfflineEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    weatherApi.getEventForecast.mockResolvedValue({ data: { data: sampleForecast } });

    renderPage();

    expect(await screen.findByText('Clear')).toBeInTheDocument();
    expect(screen.getByText('clear sky')).toBeInTheDocument();
    expect(screen.getByText('88°F')).toBeInTheDocument();
    expect(screen.getByText('90°F')).toBeInTheDocument();
    expect(screen.getByText('45%')).toBeInTheDocument();
    expect(screen.getByText('6.5 mph')).toBeInTheDocument();
    expect(screen.getByText('30%')).toBeInTheDocument();
    expect(weatherApi.getEventForecast).toHaveBeenCalledWith('event-1');
  });

  it('shows an error message when the weather forecast fails to load', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleOfflineEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });
    weatherApi.getEventForecast.mockRejectedValue({
      response: { data: { message: 'This event does not have a location to check the weather for' } },
    });

    renderPage();

    expect(
      await screen.findByText('This event does not have a location to check the weather for'),
    ).toBeInTheDocument();
  });

  it('does not fetch or render weather for an online event', async () => {
    eventApi.getEventById.mockResolvedValue({ data: { data: sampleEvent } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: sampleProfile } });

    renderPage();

    await screen.findByRole('heading', { name: 'Career Fair 2026' });

    expect(screen.queryByText('Weather forecast')).not.toBeInTheDocument();
    expect(weatherApi.getEventForecast).not.toHaveBeenCalled();
  });
});
