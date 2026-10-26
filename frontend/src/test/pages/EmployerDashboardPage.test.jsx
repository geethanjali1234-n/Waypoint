import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom';
import EmployerDashboardPage from '../../pages/EmployerDashboardPage';

function renderWithDashboardContext(dashboard) {
  function DashboardContextProvider() {
    return <Outlet context={dashboard} />;
  }

  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route element={<DashboardContextProvider />}>
          <Route index element={<EmployerDashboardPage />} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

describe('EmployerDashboardPage', () => {
  it('renders the company greeting and action counts from outlet context', () => {
    renderWithDashboardContext({
      companyName: 'Waypoint Technologies',
      openJobsCount: 3,
      totalApplicationsCount: 12,
      upcomingEventsCount: 1,
      totalRegistrationsCount: 5,
      recentJobs: [],
      recentEvents: [],
      recentNotifications: [],
      refreshDashboard: vi.fn(),
    });

    expect(screen.getByText('Hello, Waypoint Technologies')).toBeInTheDocument();
    const openPostingsCard = screen.getByText('Open Postings').closest('li');
    expect(within(openPostingsCard).getByText('3')).toBeInTheDocument();
  });
});
