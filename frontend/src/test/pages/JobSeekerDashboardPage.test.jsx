import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerDashboardPage from '../../pages/JobSeekerDashboardPage';
import jobSeekerDashboardApi from '../../api/jobSeekerDashboardApi';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';

vi.mock('../../api/jobSeekerDashboardApi');
vi.mock('../../api/jobSeekerProfileApi');

function renderPage() {
  return render(
    <MemoryRouter>
      <JobSeekerDashboardPage />
    </MemoryRouter>
  );
}

describe('JobSeekerDashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
  });

  it('shows a loading state while the dashboard is being fetched', () => {
    jobSeekerDashboardApi.getDashboard.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText('Loading your dashboard…')).toBeInTheDocument();
  });

  it('shows an error message when the dashboard fails to load', async () => {
    jobSeekerDashboardApi.getDashboard.mockRejectedValue(new Error('network error'));

    renderPage();

    expect(await screen.findByText('Unable to load dashboard')).toBeInTheDocument();
  });

  it('renders the hero section with the greeting name on success', async () => {
    jobSeekerDashboardApi.getDashboard.mockResolvedValue({
      data: { data: { firstName: 'Ada' } },
    });

    renderPage();

    expect(await screen.findByText('Hello, Ada')).toBeInTheDocument();
    expect(
      screen.getByText('Here’s an overview of your career journey.')
    ).toBeInTheDocument();
  });
});
