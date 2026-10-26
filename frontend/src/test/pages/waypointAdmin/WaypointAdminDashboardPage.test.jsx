import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import WaypointAdminDashboardPage from '../../../pages/waypointAdmin/WaypointAdminDashboardPage';

describe('WaypointAdminDashboardPage', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN' }));
    vi.clearAllMocks();
  });

  it('renders a distinct platform workspace without My HR action cards or invented metrics', () => {
    render(<MemoryRouter><WaypointAdminDashboardPage /></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Waypoint Admin Dashboard' })).toBeInTheDocument();
    expect(screen.getByText('Use Organizations in the navigation to review approved business information.')).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Waypoint Admin navigation' })).toBeInTheDocument();
    expect(screen.queryByText('Employee Management')).not.toBeInTheDocument();
    expect(screen.queryByText('Timesheets')).not.toBeInTheDocument();
  });

  it('clears the shared authentication session on sign out', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    const user = userEvent.setup();
    render(<MemoryRouter><WaypointAdminDashboardPage /></MemoryRouter>);

    await user.click(screen.getByRole('button', { name: /WPADMIN/ }));
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }));

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('myHrAuthenticatedUser')).toBeNull();
  });
});
