import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useOutletContext } from 'react-router-dom';
import EmployerLayout from '../../pages/EmployerLayout';
import employerDashboardApi from '../../api/employerDashboardApi';
import employerNotificationApi from '../../api/employerNotificationApi';

vi.mock('../../api/employerDashboardApi');
vi.mock('../../api/employerNotificationApi');
function Page() {
  const context = useOutletContext();
  return <h1>Open jobs: {context.openJobsCount}</h1>;
}
function show() {
  return render(<MemoryRouter initialEntries={['/employer/dashboard']}><Routes>
    <Route element={<EmployerLayout />}>
      <Route path="/employer/dashboard" element={<Page />} />
      <Route path="/employer/jobs" element={<Page />} />
    </Route>
    <Route path="/live-login" element={<h1>Existing employer login</h1>} />
  </Routes></MemoryRouter>);
}
beforeEach(() => {
  vi.resetAllMocks();
  employerDashboardApi.getEmployerDashboard.mockResolvedValue({ data: { data: { companyName: 'Fixture company', email: 'fixture@example.test', openJobsCount: 3 } } });
  employerNotificationApi.getUnreadCount.mockResolvedValue({ data: { data: { count: 2 } } });
  employerNotificationApi.listNotifications.mockResolvedValue({ data: { data: [] } });
});
describe('EmployerLayout frame', () => {
  it('retains outlet data and notification requests while navigating through the sidebar', async () => {
    show();
    expect(await screen.findByRole('heading', { name: 'Open jobs: 3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Fixture company.*fixture@example.test/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Notifications' })).toHaveAccessibleDescription('2 unread notifications'));
    expect(employerDashboardApi.getEmployerDashboard).toHaveBeenCalledOnce();
    await userEvent.setup().click(screen.getByRole('link', { name: 'Job Postings' }));
    expect(screen.getByRole('link', { name: 'Job Postings' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Open jobs: 3' })).toBeInTheDocument();
    // Preserve the existing effect's refresh when the router's navigate reference changes.
    await waitFor(() => expect(employerDashboardApi.getEmployerDashboard).toHaveBeenCalledTimes(2));
    expect(employerNotificationApi.listNotifications).toHaveBeenCalledWith(5);
  });
  it('preserves token clearing and the existing 401 redirect', async () => {
    localStorage.setItem('accessToken', 'fixture-token');
    employerDashboardApi.getEmployerDashboard.mockRejectedValue({ response: { status: 401 } });
    show();
    expect(await screen.findByRole('heading', { name: 'Existing employer login' })).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBeNull();
  });
  it('keeps the server refusal message and does not render protected outlet content on 403', async () => {
    employerDashboardApi.getEmployerDashboard.mockRejectedValue({ response: { status: 403, data: { message: 'Access refused by server' } } });
    show();
    expect(await screen.findByText('Access refused by server')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Open jobs:/ })).not.toBeInTheDocument();
  });
});
