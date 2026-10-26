import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerApplicationsPage from '../../pages/JobSeekerApplicationsPage';
import jobSeekerApplicationsApi from '../../api/jobSeekerApplicationsApi';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';

vi.mock('../../api/jobSeekerApplicationsApi');
vi.mock('../../api/jobSeekerProfileApi');

function renderPage() {
  return render(
    <MemoryRouter>
      <JobSeekerApplicationsPage />
    </MemoryRouter>
  );
}

describe('JobSeekerApplicationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
  });

  it('shows a loader while applications are being fetched', () => {
    jobSeekerApplicationsApi.listMyApplications.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading your applications/i)).toBeInTheDocument();
  });

  it('shows the server-provided error message when the fetch fails', async () => {
    jobSeekerApplicationsApi.listMyApplications.mockRejectedValue({
      response: { data: { message: 'Session expired' } },
    });

    renderPage();

    expect(await screen.findByText('Session expired')).toBeInTheDocument();
  });

  it('shows a generic error message when the fetch fails without a server message', async () => {
    jobSeekerApplicationsApi.listMyApplications.mockRejectedValue(new Error('network error'));

    renderPage();

    expect(
      await screen.findByText('Unable to load your applications. Please try again.')
    ).toBeInTheDocument();
  });

  it('shows an empty state when there are no applications', async () => {
    jobSeekerApplicationsApi.listMyApplications.mockResolvedValue({ data: { data: [] } });

    renderPage();

    expect(await screen.findByText('No applications yet')).toBeInTheDocument();
  });

  it('renders application cards with formatted status and applied date', async () => {
    jobSeekerApplicationsApi.listMyApplications.mockResolvedValue({
      data: {
        data: [
          {
            id: 'app-1',
            jobTitle: 'Backend Engineer',
            companyName: 'Acme',
            location: 'Austin',
            status: 'UNDER_REVIEW',
            appliedAt: '2026-01-15T09:00:00',
          },
          {
            id: 'app-2',
            jobTitle: null,
            companyName: null,
            location: null,
            status: 'APPLIED',
            appliedAt: null,
          },
        ],
      },
    });

    renderPage();

    expect(await screen.findByText('Backend Engineer')).toBeInTheDocument();
    expect(screen.getByText('Acme · Austin')).toBeInTheDocument();
    expect(screen.getByText('Under Review')).toBeInTheDocument();
    const appliedDate = new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date('2026-01-15T09:00:00'));
    expect(screen.getByText(`Applied ${appliedDate}`)).toBeInTheDocument();

    expect(screen.getByText('Untitled role')).toBeInTheDocument();
    expect(screen.getByText('Applied')).toBeInTheDocument();
  });
});
