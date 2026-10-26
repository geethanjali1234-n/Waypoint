import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerRegisteredEventsPage from '../../pages/JobSeekerRegisteredEventsPage';
import jobSeekerEventRegistrationsApi from '../../api/jobSeekerEventRegistrationsApi';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';

vi.mock('../../api/jobSeekerEventRegistrationsApi');
vi.mock('../../api/jobSeekerProfileApi');

function renderPage() {
  return render(
    <MemoryRouter>
      <JobSeekerRegisteredEventsPage />
    </MemoryRouter>
  );
}

describe('JobSeekerRegisteredEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
  });

  it('shows a loader while registrations are being fetched', () => {
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading your registered events/i)).toBeInTheDocument();
  });

  it('shows the server-provided error message when the fetch fails', async () => {
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockRejectedValue({
      response: { data: { message: 'Session expired' } },
    });

    renderPage();

    expect(await screen.findByText('Session expired')).toBeInTheDocument();
  });

  it('shows a generic error message when the fetch fails without a server message', async () => {
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockRejectedValue(new Error('network error'));

    renderPage();

    expect(
      await screen.findByText('Unable to load your registered events. Please try again.')
    ).toBeInTheDocument();
  });

  it('shows an empty state when there are no registrations', async () => {
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({ data: { data: [] } });

    renderPage();

    expect(await screen.findByText('No registered events yet')).toBeInTheDocument();
  });

  it('renders registration cards with the event title, company, and formatted date', async () => {
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({
      data: {
        data: [
          {
            id: 'reg-1',
            eventTitle: 'Career Fair',
            companyName: 'Acme',
            startDateTime: '2026-05-01T10:00:00',
          },
          {
            id: 'reg-2',
            eventTitle: null,
            companyName: 'Globex',
            startDateTime: null,
          },
        ],
      },
    });

    renderPage();

    expect(await screen.findByText('Career Fair')).toBeInTheDocument();
    const eventDate = new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date('2026-05-01T10:00:00'));
    expect(screen.getByText(eventDate)).toBeInTheDocument();
    expect(screen.getAllByText('Registered')).toHaveLength(2);

    expect(screen.getByText('Untitled event')).toBeInTheDocument();
    expect(screen.getByText('Globex')).toBeInTheDocument();
  });
});
