import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MyHrChangePasswordPage from '../../../pages/myhr/MyHrChangePasswordPage';
import authApi from '../../../api/authApi';

vi.mock('../../../api/authApi');
const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal()),
  useNavigate: () => navigate,
}));

describe('MyHrChangePasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ role: 'HR', mustChangePassword: true }));
  });

  it('uses the first-login endpoint and routes to the role root', async () => {
    authApi.changeFirstLoginPassword.mockResolvedValue({});
    const user = userEvent.setup();
    render(<MemoryRouter><MyHrChangePasswordPage /></MemoryRouter>);

    await user.type(screen.getByLabelText(/Temporary Password/), 'Temporary9!');
    await user.type(screen.getByLabelText(/^New Password/), 'Permanent9!');
    await user.type(screen.getByLabelText(/Confirm New Password/), 'Permanent9!');
    await user.click(screen.getByRole('button', { name: /change password/i }));

    await vi.waitFor(() => expect(authApi.changeFirstLoginPassword).toHaveBeenCalledWith({
      currentPassword: 'Temporary9!', newPassword: 'Permanent9!', confirmPassword: 'Permanent9!',
    }));
    expect(navigate).toHaveBeenCalledWith('/myhr/hr', { replace: true });
  });

  it('routes a Waypoint Admin back to the platform dashboard', async () => {
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ role: 'WAYPOINT_ADMIN', mustChangePassword: true }));
    authApi.changeFirstLoginPassword.mockResolvedValue({});
    const user = userEvent.setup();
    render(<MemoryRouter><MyHrChangePasswordPage /></MemoryRouter>);

    await user.type(screen.getByLabelText(/Temporary Password/), 'Temporary9!');
    await user.type(screen.getByLabelText(/^New Password/), 'Permanent9!');
    await user.type(screen.getByLabelText(/Confirm New Password/), 'Permanent9!');
    await user.click(screen.getByRole('button', { name: /change password/i }));

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(
      '/waypoint-admin/dashboard', { replace: true },
    ));
  });
});
