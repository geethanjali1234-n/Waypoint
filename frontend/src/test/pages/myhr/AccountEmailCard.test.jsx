import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AccountEmailCard from '../../../pages/myhr/features/profile/AccountEmailCard';
import authApi from '../../../api/authApi';

vi.mock('../../../api/authApi');

const SESSION = {
  userId: 'admin-1', email: 'tardus.admin.example@example.com', loginId: 'TARDUSADMIN', role: 'COMPANY_ADMIN',
  accessToken: 'fresh-token', tokenType: 'Bearer', accountStatus: 'ACTIVE', isEmailVerified: true,
};
const REQUESTED = { data: { data: { newEmail: 'tardus.admin.example@example.com', expiresInSeconds: 600, resendAfterSeconds: 60 } } };

function renderCard(onSaved = vi.fn()) {
  render(<AccountEmailCard accountEmail="tardus.org.example@example.com" onSaved={onSaved} />);
  return onSaved;
}

async function openAndRequest(user, { email = 'tardus.admin.example@example.com', password = 'Secret123' } = {}) {
  await user.click(screen.getByRole('button', { name: 'Change account email' }));
  await user.type(screen.getByLabelText(/New account email/), email);
  await user.type(screen.getByLabelText(/Current password/), password);
  await user.click(screen.getByRole('button', { name: 'Send verification code' }));
}

describe('AccountEmailCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the current login email and does not call the server until asked', () => {
    renderCard();

    expect(screen.getByText('tardus.org.example@example.com')).toBeInTheDocument();
    expect(authApi.requestEmailChange).not.toHaveBeenCalled();
    expect(authApi.confirmEmailChange).not.toHaveBeenCalled();
  });

  it('checks the new address and the password in the browser before asking the server for a code', async () => {
    const user = userEvent.setup();
    renderCard();

    await user.click(screen.getByRole('button', { name: 'Change account email' }));
    await user.type(screen.getByLabelText(/New account email/), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));
    expect(await screen.findByText('Enter a valid new email address.')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/New account email/));
    await user.type(screen.getByLabelText(/New account email/), 'TARDUS.ORG.EXAMPLE@example.com');
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));
    expect(await screen.findByText('New email must be different from the current email.')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/New account email/));
    await user.type(screen.getByLabelText(/New account email/), 'tardus.admin.example@example.com');
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));
    expect(await screen.findByText('Current password is required.')).toBeInTheDocument();

    expect(authApi.requestEmailChange).not.toHaveBeenCalled();
  });

  it('moves to the code step after the code is sent, and says the current login stays active', async () => {
    authApi.requestEmailChange.mockResolvedValue(REQUESTED);
    const user = userEvent.setup();
    renderCard();

    await openAndRequest(user);

    expect(await screen.findByLabelText(/Verification code/)).toBeInTheDocument();
    expect(screen.getByText('tardus.admin.example@example.com')).toBeInTheDocument();
    expect(screen.getByText(/Your current login email \(tardus.org.example@example.com\) stays active until you do/)).toBeInTheDocument();
    expect(authApi.confirmEmailChange).not.toHaveBeenCalled();
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('does not keep the password after it was sent', async () => {
    authApi.requestEmailChange.mockResolvedValue(REQUESTED);
    const user = userEvent.setup();
    renderCard();

    await openAndRequest(user, { password: 'Secret123' });
    await screen.findByLabelText(/Verification code/);

    expect(document.body.innerHTML).not.toContain('Secret123');
  });

  it('shows the server message when the password is wrong or the address is already registered, and stays on step one', async () => {
    const user = userEvent.setup();
    renderCard();
    authApi.requestEmailChange.mockRejectedValueOnce({ response: { data: { message: 'Email already registered' } } });

    await openAndRequest(user);

    expect(await screen.findByText('Email already registered')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Verification code/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/New account email/)).toBeInTheDocument();
  });

  it('confirms with only the code, then refreshes the stored session and reports the new email', async () => {
    authApi.requestEmailChange.mockResolvedValue(REQUESTED);
    authApi.confirmEmailChange.mockResolvedValue({ data: { data: SESSION } });
    const user = userEvent.setup();
    const onSaved = renderCard();

    await openAndRequest(user);
    await user.type(await screen.findByLabelText(/Verification code/), ' 123456 ');
    await user.click(screen.getByRole('button', { name: 'Confirm email' }));

    await waitFor(() => expect(authApi.confirmEmailChange).toHaveBeenCalledWith({ otp: '123456' }));
    expect(authApi.confirmEmailChange.mock.calls[0][0]).not.toHaveProperty('newEmail');
    expect(onSaved).toHaveBeenCalledWith('tardus.admin.example@example.com');
    expect(localStorage.getItem('accessToken')).toBe('fresh-token');
    expect(await screen.findByText(/Account email updated/)).toBeInTheDocument();
  });

  it('rejects a code that is not six digits without calling the server', async () => {
    authApi.requestEmailChange.mockResolvedValue(REQUESTED);
    const user = userEvent.setup();
    renderCard();

    await openAndRequest(user);
    await user.type(await screen.findByLabelText(/Verification code/), '12ab');
    await user.click(screen.getByRole('button', { name: 'Confirm email' }));

    expect(await screen.findByText('Enter the 6-digit verification code.')).toBeInTheDocument();
    expect(authApi.confirmEmailChange).not.toHaveBeenCalled();
  });

  it('keeps the old session and shows the message when the code is wrong or expired', async () => {
    authApi.requestEmailChange.mockResolvedValue(REQUESTED);
    authApi.confirmEmailChange.mockRejectedValue({ response: { data: { message: 'Invalid or expired verification code' } } });
    const user = userEvent.setup();
    const onSaved = renderCard();

    await openAndRequest(user);
    await user.type(await screen.findByLabelText(/Verification code/), '000000');
    await user.click(screen.getByRole('button', { name: 'Confirm email' }));

    expect(await screen.findByText('Invalid or expired verification code')).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(screen.getByLabelText(/Verification code/)).toBeInTheDocument();
  });

  it('only allows a new code after the cooldown', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    authApi.requestEmailChange.mockResolvedValue({ data: { data: { newEmail: 'tardus.admin.example@example.com', expiresInSeconds: 600, resendAfterSeconds: 3 } } });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderCard();

    await openAndRequest(user);
    const resend = await screen.findByRole('button', { name: /Send a new code in \d+s/ });
    expect(resend).toBeDisabled();

    // The countdown schedules one tick at a time, so let it render between ticks.
    for (let tick = 0; tick < 4; tick += 1) {
      await act(async () => { vi.advanceTimersByTime(1000); });
    }

    expect(await screen.findByRole('button', { name: 'Send a new code' })).toBeEnabled();
  });

  it('cancelling at either step leaves the account untouched', async () => {
    authApi.requestEmailChange.mockResolvedValue(REQUESTED);
    const user = userEvent.setup();
    const onSaved = renderCard();

    await user.click(screen.getByRole('button', { name: 'Change account email' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('tardus.org.example@example.com')).toBeInTheDocument();

    await openAndRequest(user);
    await screen.findByLabelText(/Verification code/);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByRole('button', { name: 'Change account email' })).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(authApi.confirmEmailChange).not.toHaveBeenCalled();
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('never calls the retired one-step endpoint', () => {
    expect(authApi.changeEmail).toBeUndefined();
  });
});
