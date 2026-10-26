import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ForgotPasswordPage from '../../../pages/auth/ForgotPasswordPage';
import ResetPasswordPage from '../../../pages/auth/ResetPasswordPage';
import authApi from '../../../api/authApi';

vi.mock('../../../api/authApi');

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => navigate };
});

describe('password reset pages', () => {
  beforeEach(() => vi.clearAllMocks());

  it('requests a reset code and carries the normalized email to the reset page', async () => {
    authApi.forgotPassword.mockResolvedValue({ data: { success: true } });
    const user = userEvent.setup();
    render(<MemoryRouter><ForgotPasswordPage /></MemoryRouter>);

    await user.type(screen.getByLabelText(/Email address/i), 'User@Example.com');
    await user.click(screen.getByRole('button', { name: /send reset code/i }));

    expect(authApi.forgotPassword).toHaveBeenCalledWith({ email: 'user@example.com' });
    expect(navigate).toHaveBeenCalledWith('/reset-password?email=user%40example.com');
  });

  it('uses the same generic transition regardless of account existence', async () => {
    authApi.forgotPassword.mockResolvedValue({ data: {
      success: true,
      message: 'If an account exists for that email, a reset code has been sent.',
    } });
    const user = userEvent.setup();
    render(<MemoryRouter><ForgotPasswordPage /></MemoryRouter>);

    await user.type(screen.getByLabelText(/Email address/i), 'unknown@example.com');
    await user.click(screen.getByRole('button', { name: /send reset code/i }));

    expect(navigate).toHaveBeenCalledWith('/reset-password?email=unknown%40example.com');
  });

  it('requires a valid email before requesting a reset code', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><ForgotPasswordPage /></MemoryRouter>);

    await user.click(screen.getByRole('button', { name: /send reset code/i }));

    expect(await screen.findByText('Email is required')).toBeInTheDocument();
    expect(authApi.forgotPassword).not.toHaveBeenCalled();
  });

  it('verifies the code before enabling and submitting new passwords', async () => {
    authApi.verifyResetOtp.mockResolvedValue({ data: { success: true } });
    authApi.resetPassword.mockResolvedValue({ data: { success: true } });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/reset-password?email=user@example.com']}>
        <ResetPasswordPage />
      </MemoryRouter>
    );

    const newPassword = screen.getByLabelText(/^New password/i);
    expect(newPassword).toBeDisabled();

    await user.type(screen.getByLabelText(/Six-digit code/i), '123456');
    await user.click(screen.getByRole('button', { name: /verify code/i }));
    expect(await screen.findByText(/Code verified/i)).toBeInTheDocument();

    await user.type(newPassword, 'NewPassword1');
    await user.type(screen.getByLabelText(/Confirm new password/i), 'NewPassword1');
    const timeout = vi.spyOn(window, 'setTimeout').mockImplementation((callback) => {
      callback();
      return 1;
    });
    await user.click(screen.getByRole('button', { name: /reset password/i }));

    expect(authApi.resetPassword).toHaveBeenCalledWith({
      email: 'user@example.com',
      otp: '123456',
      newPassword: 'NewPassword1',
      confirmPassword: 'NewPassword1',
    });
    expect(navigate).toHaveBeenCalledWith('/myhr/login', { replace: true });
    timeout.mockRestore();
  });

  it('keeps password fields disabled and shows a safe error for an invalid OTP', async () => {
    authApi.verifyResetOtp.mockRejectedValue({ response: { data: {
      message: 'Invalid or expired verification code',
    } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reset-password?email=user@example.com']}><ResetPasswordPage /></MemoryRouter>);

    await user.type(screen.getByLabelText(/Six-digit code/i), '000000');
    await user.click(screen.getByRole('button', { name: /verify code/i }));

    expect(await screen.findByText('Invalid or expired verification code')).toBeInTheDocument();
    expect(screen.getByLabelText(/^New password/i)).toBeDisabled();
    expect(authApi.resetPassword).not.toHaveBeenCalled();
  });

  it('validates password length and confirmation before reset submission', async () => {
    authApi.verifyResetOtp.mockResolvedValue({ data: { success: true } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reset-password?email=user@example.com']}><ResetPasswordPage /></MemoryRouter>);

    await user.type(screen.getByLabelText(/Six-digit code/i), '123456');
    await user.click(screen.getByRole('button', { name: /verify code/i }));
    await user.type(screen.getByLabelText(/^New password/i), 'short');
    await user.type(screen.getByLabelText(/Confirm new password/i), 'different');
    await user.click(screen.getByRole('button', { name: /reset password/i }));

    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument();
    expect(authApi.resetPassword).not.toHaveBeenCalled();
  });

  it('shows a recovery link when the email query parameter is missing', () => {
    render(<MemoryRouter initialEntries={['/reset-password']}><ResetPasswordPage /></MemoryRouter>);

    expect(screen.getByText(/does not include an email address/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /request a new reset code/i })).toHaveAttribute('href', '/forgot-password');
  });
});
