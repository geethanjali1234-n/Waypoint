import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import VerifyOtpPage from '../../../pages/auth/VerifyOtpPage';
import authApi from '../../../api/authApi';
import { byLabel } from '../../testUtils';

vi.mock('../../../api/authApi');

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

function renderPage(initialEntry = '/verify-email?email=jordan%40example.com') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <VerifyOtpPage />
    </MemoryRouter>
  );
}

describe('VerifyOtpPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the email address the code was sent to, read from the query string', () => {
    renderPage();

    expect(screen.getByText('Enter the 6-digit code we sent to jordan@example.com')).toBeInTheDocument();
  });

  it('falls back to a generic subtitle when no email is present in the query string', () => {
    renderPage('/verify-email');

    expect(screen.getByText('Enter the 6-digit code we sent to your email')).toBeInTheDocument();
  });

  it('shows a validation error and does not call the API for a malformed code', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(byLabel('Verification code')), '123');
    await user.click(screen.getByRole('button', { name: /verify email/i }));

    expect(await screen.findByText('Code must be exactly 6 digits')).toBeInTheDocument();
    expect(authApi.verifyOtp).not.toHaveBeenCalled();
  });

  it('submits the code, shows a success message, and redirects to login', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    authApi.verifyOtp.mockResolvedValue({ data: { data: {} } });
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(byLabel('Verification code')), '123456');
    await user.click(screen.getByRole('button', { name: /verify email/i }));

    expect(await screen.findByText('Email verified successfully. Redirecting to sign in...')).toBeInTheDocument();
    expect(authApi.verifyOtp).toHaveBeenCalledWith({ email: 'jordan@example.com', otp: '123456' });

    await vi.advanceTimersByTimeAsync(1500);
    expect(mockNavigate).toHaveBeenCalledWith('/live-login');

    vi.useRealTimers();
  });

  it('shows the server error message when verification fails', async () => {
    authApi.verifyOtp.mockRejectedValue({
      response: { data: { message: 'Invalid or expired code.' } },
    });
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(byLabel('Verification code')), '654321');
    await user.click(screen.getByRole('button', { name: /verify email/i }));

    expect(await screen.findByText('Invalid or expired code.')).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('surfaces the first field-level server error when present', async () => {
    authApi.verifyOtp.mockRejectedValue({
      response: { data: { data: { otp: 'This code has expired' } } },
    });
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(byLabel('Verification code')), '654321');
    await user.click(screen.getByRole('button', { name: /verify email/i }));

    expect(await screen.findByText('This code has expired')).toBeInTheDocument();
  });
});
