import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EmployerDashboardPage from './EmployerDashboardPage';

const { dashboardContext, navigateMock } = vi.hoisted(() => ({
  dashboardContext: { value: null },
  navigateMock: vi.fn(),
}));

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateMock,
  useOutletContext: () => dashboardContext.value,
}));

function makeDashboard(overrides = {}) {
  return {
    companyName: 'Waypoint Labs',
    openJobsCount: 9,
    totalApplicationsCount: 17,
    upcomingEventsCount: 2,
    totalRegistrationsCount: 4,
    recentJobs: ['Frontend Engineer'],
    recentEvents: ['Career Fair'],
    recentNotifications: ['New application received'],
    refreshDashboard: vi.fn(),
    ...overrides,
  };
}

describe('EmployerDashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dashboardContext.value = makeDashboard();
  });

  it('renders the employer greeting, summary metrics, and recent activity', async () => {
    render(<EmployerDashboardPage />);

    expect(screen.getByRole('heading', { name: 'Hello, Waypoint Labs' })).toBeInTheDocument();
    const summary = screen.getByRole('region', { name: 'Hiring summary' });
    expect(within(summary).getByText('9')).toBeInTheDocument();
    expect(within(summary).getByText('17')).toBeInTheDocument();
    expect(within(summary).getByText('2')).toBeInTheDocument();
    expect(within(summary).getByText('4')).toBeInTheDocument();
    expect(screen.getByText('Frontend Engineer')).toBeInTheDocument();
    expect(screen.getByText('Career Fair')).toBeInTheDocument();
    expect(screen.getByText('New application received')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Recent activity' })).toBeInTheDocument();

    await waitFor(() => expect(dashboardContext.value.refreshDashboard).toHaveBeenCalledTimes(1));
  });

  it('keeps the primary new-posting action', async () => {
    const user = userEvent.setup();
    render(<EmployerDashboardPage />);

    await user.click(screen.getByRole('button', { name: /New posting/ }));

    expect(navigateMock).toHaveBeenCalledWith('/employer/jobs/new');
  });

  it('opens job review from the applications action', async () => {
    const user = userEvent.setup();
    render(<EmployerDashboardPage />);
    await user.click(screen.getByRole('button', { name: 'Review jobs' }));
    expect(navigateMock).toHaveBeenCalledWith('/employer/jobs');
  });

  it('shows empty states when there is no recent activity', () => {
    dashboardContext.value = makeDashboard({
      recentJobs: [],
      recentEvents: [],
      recentNotifications: [],
    });
    render(<EmployerDashboardPage />);

    expect(screen.getByText('No job postings to display')).toBeInTheDocument();
    expect(screen.getByText('No events to display')).toBeInTheDocument();
    expect(screen.getByText('No notifications to display')).toBeInTheDocument();
  });

  it('omits unavailable dashboard counts instead of inventing zeroes', () => {
    dashboardContext.value = makeDashboard({
      openJobsCount: undefined,
      totalApplicationsCount: 'not-a-number',
      upcomingEventsCount: null,
      totalRegistrationsCount: '',
    });
    render(<EmployerDashboardPage />);

    const summary = screen.getByRole('region', { name: 'Hiring summary' });
    expect(within(summary).queryByText('0')).not.toBeInTheDocument();
    expect(within(summary).getByText('Hiring activity will appear when it is available.')).toBeInTheDocument();
  });
});
