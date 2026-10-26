import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MyHrRegularChangePasswordPage from '../../../pages/myhr/MyHrRegularChangePasswordPage';
import authApi from '../../../api/authApi';
import { getMyHrSession, saveMyHrAuthentication } from '../../../auth/myHrSession';

vi.mock('../../../api/authApi');
const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal()),
  useNavigate: () => navigate,
}));

async function completeForm(user, values = {}) {
  await user.type(screen.getByLabelText(/^Current Password/), values.currentPassword || 'Current9!');
  await user.type(screen.getByLabelText(/^New Password/), values.newPassword || 'Replacement9!');
  await user.type(screen.getByLabelText(/^Confirm New Password/), values.confirmPassword || 'Replacement9!');
  await user.click(screen.getByRole('button', { name: /^change password$/i }));
}

describe('MyHrRegularChangePasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    saveMyHrAuthentication('signed-jwt', { loginId: 'TD001', role: 'HR', mustChangePassword: false });
  });

  it('changes only the authenticated account and clears the complete session before returning to My HR login', async () => {
    authApi.changePassword.mockResolvedValue({});
    const user = userEvent.setup();
    render(<MemoryRouter><MyHrRegularChangePasswordPage /></MemoryRouter>);

    await completeForm(user);

    await vi.waitFor(() => expect(authApi.changePassword).toHaveBeenCalledWith({
      currentPassword: 'Current9!', newPassword: 'Replacement9!', confirmPassword: 'Replacement9!',
    }));
    expect(authApi.changePassword.mock.calls[0][0]).not.toHaveProperty('userId');
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(getMyHrSession()).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/myhr/login', { replace: true });
  });

  it.each([
    ['', 'Replacement9!', 'Replacement9!', 'All fields are required.'],
    ['Current9!', 'short', 'short', 'New password must be at least 8 characters.'],
    ['Current9!', 'Replacement9!', 'Different9!', 'New password and confirm password do not match.'],
    ['Current9!', 'Current9!', 'Current9!', 'New password must be different from the current password.'],
  ])('rejects invalid input without calling the API', async (currentPassword, newPassword, confirmPassword, message) => {
    const user = userEvent.setup();
    render(<MemoryRouter><MyHrRegularChangePasswordPage /></MemoryRouter>);
    if (currentPassword) await user.type(screen.getByLabelText(/^Current Password/), currentPassword);
    if (newPassword) await user.type(screen.getByLabelText(/^New Password/), newPassword);
    if (confirmPassword) await user.type(screen.getByLabelText(/^Confirm New Password/), confirmPassword);
    await user.click(screen.getByRole('button', { name: /^change password$/i }));
    expect(screen.getByText(message)).toBeInTheDocument();
    expect(authApi.changePassword).not.toHaveBeenCalled();
  });

  it('shows the safe backend message when the current password is rejected', async () => {
    authApi.changePassword.mockRejectedValue({ response: { data: { message: 'Current password is incorrect' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><MyHrRegularChangePasswordPage /></MemoryRouter>);
    await completeForm(user);
    expect(await screen.findByText('Current password is incorrect')).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBe('signed-jwt');
  });

  it.each([
    ['COMPANY_ADMIN', '/myhr/admin/dashboard'],
    ['HR', '/myhr/hr/dashboard'],
    ['EMPLOYEE', '/myhr/employee/dashboard'],
  ])('Cancel returns %s to its dashboard without submitting', async (role, destination) => {
    localStorage.clear();
    saveMyHrAuthentication('signed-jwt', { loginId: 'TD001', role, mustChangePassword: false });
    const user = userEvent.setup();
    render(<MemoryRouter><MyHrRegularChangePasswordPage /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(navigate).toHaveBeenCalledWith(destination);
    expect(authApi.changePassword).not.toHaveBeenCalled();
  });
});
