import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import LoginPage from '../../../pages/auth/LoginPage';
import authApi from '../../../api/authApi';
import { byLabel } from '../../testUtils';

vi.mock('../../../api/authApi');
const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({ ...(await importOriginal()), useNavigate: () => navigate }));

function renderPage() { return render(<MemoryRouter><LoginPage /></MemoryRouter>); }

describe('LoginPage', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); });

  it('shows one login experience with Login ID, password, and Forgot password', () => {
    renderPage();
    expect(screen.getByLabelText(byLabel('Login ID'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Password'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Forgot password?' })).toBeInTheDocument();
    // Offered once in the secondary destination links below sign-in.
    expect(screen.getByRole('link', { name: /Create Organization Account/ }))
      .toHaveAttribute('href', '/register/organization');
    expect(screen.queryByText('Job Seeker')).not.toBeInTheDocument();
    expect(screen.queryByText('MY HR')).not.toBeInTheDocument();
  });

  it('validates required credentials without calling the API', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByText('Login ID is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();
    expect(authApi.login).not.toHaveBeenCalled();
  });

  it.each([
    ['COMPANY_ADMIN', 'TDADMIN', '/myhr/admin'], ['HR', 'TD001', '/myhr/hr'], ['EMPLOYEE', 'TD002', '/myhr/employee'],
    ['WAYPOINT_ADMIN', 'WPADMIN', '/waypoint-admin/dashboard'],
  ])('routes %s to its role workspace', async (role, loginId, destination) => {
    authApi.login.mockResolvedValue({ data: { data: {
      accessToken: 'signed-jwt', loginId, role,
      organizationId: role === 'WAYPOINT_ADMIN' ? null : 'org-1', mustChangePassword: false,
    } } });
    const user = userEvent.setup(); renderPage();
    await user.type(screen.getByLabelText(byLabel('Login ID')), loginId.toLowerCase());
    await user.type(screen.getByLabelText(byLabel('Password')), 'Temporary9!');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(destination));
    expect(authApi.login).toHaveBeenCalledWith({ loginId, password: 'Temporary9!' });
  });

  it('routes a temporary-password account to first-login password setup', async () => {
    authApi.login.mockResolvedValue({ data: { data: { accessToken: 'signed-jwt', loginId: 'TD001', role: 'HR', mustChangePassword: true } } });
    const user = userEvent.setup(); renderPage();
    await user.type(screen.getByLabelText(byLabel('Login ID')), 'TD001');
    await user.type(screen.getByLabelText(byLabel('Password')), 'Temporary9!');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/myhr/first-login/change-password'));
  });

  it('routes a temporary-password Waypoint Admin to platform password setup', async () => {
    authApi.login.mockResolvedValue({ data: { data: { accessToken: 'signed-jwt', loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN', mustChangePassword: true } } });
    const user = userEvent.setup(); renderPage();
    await user.type(screen.getByLabelText(byLabel('Login ID')), 'WPADMIN');
    await user.type(screen.getByLabelText(byLabel('Password')), 'Temporary9!');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/waypoint-admin/first-login/change-password'));
  });

  it('rejects non-My-HR roles and preserves the safe server error', async () => {
    authApi.login.mockResolvedValueOnce({ data: { data: { accessToken: 'token', loginId: 'OTHER', role: 'JOB_SEEKER' } } });
    const user = userEvent.setup(); renderPage();
    await user.type(screen.getByLabelText(byLabel('Login ID')), 'OTHER');
    await user.type(screen.getByLabelText(byLabel('Password')), 'Password1!');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByText('This login is for My HR accounts only.')).toBeInTheDocument();
  });
});
