import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import WaypointAdminProtectedRoute from '../../routes/WaypointAdminProtectedRoute';
import waypointAdminApi from '../../api/waypointAdminApi';
import { expireMyHrAuthentication, saveMyHrAuthentication } from '../../auth/myHrSession';

vi.mock('../../api/waypointAdminApi');

function renderGuard() {
  return render(
    <MemoryRouter initialEntries={['/waypoint-admin/dashboard']}>
      <Routes>
        <Route path="/login" element={<p>Login</p>} />
        <Route path="/waypoint-admin/dashboard" element={
          <WaypointAdminProtectedRoute><p>Platform Dashboard</p></WaypointAdminProtectedRoute>
        } />
      </Routes>
    </MemoryRouter>,
  );
}

function renderPasswordGuard() {
  return render(
    <MemoryRouter initialEntries={['/waypoint-admin/first-login/change-password']}>
      <Routes>
        <Route path="/waypoint-admin/dashboard" element={<p>Platform Dashboard</p>} />
        <Route path="/waypoint-admin/first-login/change-password" element={
          <WaypointAdminProtectedRoute passwordChangeOnly><p>Change Password</p></WaypointAdminProtectedRoute>
        } />
      </Routes>
    </MemoryRouter>,
  );
}

describe('WaypointAdminProtectedRoute', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); });

  it('redirects an unauthenticated visitor to the shared login', async () => {
    renderGuard();
    expect(await screen.findByText('Login')).toBeInTheDocument();
  });

  it('renders only after backend platform-role validation', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    waypointAdminApi.getDashboard.mockResolvedValue({ data: { data: {
      userId: 'platform-1', loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN', mustChangePassword: false,
    } } });
    renderGuard();
    expect(await screen.findByText('Platform Dashboard')).toBeInTheDocument();
    expect(waypointAdminApi.getDashboard).toHaveBeenCalledOnce();
  });

  it('clears authentication when backend authorization fails', async () => {
    localStorage.setItem('accessToken', 'company-token');
    waypointAdminApi.getDashboard.mockRejectedValue({ response: { status: 403 } });
    renderGuard();
    expect(await screen.findByText('Login')).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('allows a temporary-password platform account into password setup', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    waypointAdminApi.getDashboard.mockResolvedValue({ data: { data: {
      userId: 'platform-1', loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN', mustChangePassword: true,
    } } });
    renderPasswordGuard();
    expect(await screen.findByText('Change Password')).toBeInTheDocument();
  });

  it('redirects when a protected request reports expired authentication', async () => {
    saveMyHrAuthentication('signed-jwt', { loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN' });
    waypointAdminApi.getDashboard.mockResolvedValue({ data: { data: {
      userId: 'platform-1', loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN', mustChangePassword: false,
    } } });
    renderGuard();
    expect(await screen.findByText('Platform Dashboard')).toBeInTheDocument();

    expireMyHrAuthentication();

    expect(await screen.findByText('Login')).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('myHrAuthenticatedUser')).toBeNull();
  });
});
