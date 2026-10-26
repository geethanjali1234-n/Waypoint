import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ProfilePage from '../../../pages/job-seeker/ProfilePage';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';
import jobSeekerApplicationsApi from '../../../api/jobSeekerApplicationsApi';
import jobSeekerEventRegistrationsApi from '../../../api/jobSeekerEventRegistrationsApi';

vi.mock('../../../api/jobSeekerProfileApi');
vi.mock('../../../api/jobSeekerApplicationsApi');
vi.mock('../../../api/jobSeekerEventRegistrationsApi');

const baseProfile = {
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'ada@example.com',
  address: 'London',
  phoneNumber: '+11234567890',
  profilePictureUrl: null,
  completionPercentage: 80,
  jobsApplied: 2,
  inReview: 1,
  shortlisted: 0,
  eventsRegistered: 1,
};

function renderPage() {
  return render(
    <MemoryRouter>
      <ProfilePage />
    </MemoryRouter>
  );
}

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a loader while the profile is being fetched', () => {
    jobSeekerProfileApi.getProfile.mockReturnValue(new Promise(() => {}));
    jobSeekerApplicationsApi.listMyApplications.mockReturnValue(new Promise(() => {}));
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading your profile/i)).toBeInTheDocument();
  });

  it('shows an error message when the profile fails to load', async () => {
    jobSeekerProfileApi.getProfile.mockRejectedValue(new Error('network error'));
    jobSeekerApplicationsApi.listMyApplications.mockResolvedValue({ data: { data: [] } });
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({ data: { data: [] } });

    renderPage();

    expect(await screen.findByText(/unable to load your profile/i)).toBeInTheDocument();
  });

  it('renders profile summary, stats, and empty states when there is no recent activity', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: baseProfile } });
    jobSeekerApplicationsApi.listMyApplications.mockResolvedValue({ data: { data: [] } });
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({ data: { data: [] } });

    renderPage();

    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByText('80% complete')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Profile completion' })).toHaveAttribute('aria-valuenow', '80');
    expect(screen.getByText('No applications yet')).toBeInTheDocument();
    expect(screen.getByText('No registered events yet')).toBeInTheDocument();
  });

  it('omits unavailable totals and profile completion', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: { firstName: 'Ada', lastName: 'Lovelace' } } });
    jobSeekerApplicationsApi.listMyApplications.mockResolvedValue({ data: { data: [] } });
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({ data: { data: [] } });
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Ada Lovelace', level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(screen.queryByText('jobs applied')).not.toBeInTheDocument();
    expect(screen.queryByText('in review')).not.toBeInTheDocument();
    expect(screen.queryByText('shortlisted')).not.toBeInTheDocument();
    expect(screen.queryByText('events registered')).not.toBeInTheDocument();
  });

  it('renders recent applications and registrations when present', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: baseProfile } });
    jobSeekerApplicationsApi.listMyApplications.mockResolvedValue({
      data: {
        data: [
          { id: 'app-1', jobTitle: 'Backend Engineer', companyName: 'Acme', location: 'Austin', status: 'UNDER_REVIEW' },
        ],
      },
    });
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockResolvedValue({
      data: {
        data: [
          { id: 'reg-1', eventTitle: 'Career Fair', companyName: 'Acme', startDateTime: '2026-05-01T10:00:00' },
        ],
      },
    });

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Backend Engineer')).toBeInTheDocument();
    });
    expect(screen.getByText('Under Review')).toBeInTheDocument();
    expect(screen.getByText('Career Fair')).toBeInTheDocument();
  });
});
