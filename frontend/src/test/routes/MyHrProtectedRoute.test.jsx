import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MyHrProtectedRoute from '../../routes/MyHrProtectedRoute';
import myHrAuthApi from '../../api/myHrAuthApi';
import { expireMyHrAuthentication, saveMyHrAuthentication } from '../../auth/myHrSession';

vi.mock('../../api/myHrAuthApi');

function renderGuard(path, allowedRoles = ['HR']) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/myhr/login" element={<p>My HR Login</p>} />
        <Route path="/myhr/first-login/change-password" element={<p>First Login Change Password Route</p>} />
        <Route path="/myhr/admin" element={<p>Admin Home</p>} />
        <Route path="/myhr/hr" element={
          <MyHrProtectedRoute allowedRoles={allowedRoles}><p>HR Home</p></MyHrProtectedRoute>
        } />
      </Routes>
    </MemoryRouter>
  );
}

describe('MyHrProtectedRoute', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); });

  it('redirects unauthenticated users to the real My HR login', async () => {
    renderGuard('/myhr/hr');
    expect(await screen.findByText('My HR Login')).toBeInTheDocument();
  });

  it('redirects users requiring a password change', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      loginId: 'TD001', role: 'HR', mustChangePassword: true,
    } } });
    renderGuard('/myhr/hr');
    expect(await screen.findByText('First Login Change Password Route')).toBeInTheDocument();
  });

  it('redirects a wrong role to its own role home', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      loginId: 'TDADMIN', role: 'COMPANY_ADMIN', mustChangePassword: false,
    } } });
    renderGuard('/myhr/hr');
    expect(await screen.findByText('Admin Home')).toBeInTheDocument();
  });

  it('renders an allowed role only after backend validation', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      loginId: 'TD001', role: 'HR', mustChangePassword: false,
    } } });
    renderGuard('/myhr/hr');
    expect(await screen.findByText('HR Home')).toBeInTheDocument();
    expect(myHrAuthApi.currentUser).toHaveBeenCalledOnce();
  });

  it('redirects to login when a protected request reports expired authentication', async () => {
    saveMyHrAuthentication('signed-jwt', { loginId: 'TD001', role: 'HR' });
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      loginId: 'TD001', role: 'HR', mustChangePassword: false,
    } } });
    renderGuard('/myhr/hr');
    expect(await screen.findByText('HR Home')).toBeInTheDocument();

    expireMyHrAuthentication();

    expect(await screen.findByText('My HR Login')).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('myHrAuthenticatedUser')).toBeNull();
  });
});
