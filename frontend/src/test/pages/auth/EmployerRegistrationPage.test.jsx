import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import EmployerRegistrationPage from '../../../pages/auth/EmployerRegistrationPage';
import authApi from '../../../api/authApi';
import { byLabel } from '../../testUtils';

vi.mock('../../../api/authApi');

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

function renderPage() {
  return render(
    <MemoryRouter>
      <EmployerRegistrationPage />
    </MemoryRouter>
  );
}

async function fillValidForm(user) {
  await user.type(screen.getByLabelText(byLabel('Company name')), 'Acme Corp');
  await user.type(screen.getByLabelText(byLabel('Email')), 'hiring@acme.com');
  await user.type(screen.getByLabelText(byLabel('Company address')), '1 Infinite Loop');
  await user.type(screen.getByLabelText(byLabel('Phone number')), '5551234567');
  await user.selectOptions(screen.getByLabelText(byLabel('Company size')), 'MEDIUM');
  await user.type(screen.getByLabelText(byLabel('Password')), 'password123');
  await user.type(screen.getByLabelText(byLabel('Confirm password')), 'password123');
}

describe('EmployerRegistrationPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders all registration fields', () => {
    renderPage();

    expect(screen.getByLabelText(byLabel('Company name'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Email'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Company address'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Phone number'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Company size'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Password'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Confirm password'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create employer account/i })).toBeInTheDocument();
  });

  it('shows validation errors and does not call the API when submitted empty', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /create employer account/i }));

    expect(await screen.findByText('Company name is required')).toBeInTheDocument();
    expect(screen.getByText('Company address is required')).toBeInTheDocument();
    expect(screen.getByText('Password must be at least 8 characters')).toBeInTheDocument();
    expect(authApi.registerEmployer).not.toHaveBeenCalled();
  });

  it('shows a mismatch error when passwords do not match', async () => {
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.clear(screen.getByLabelText(byLabel('Confirm password')));
    await user.type(screen.getByLabelText(byLabel('Confirm password')), 'different123');
    await user.click(screen.getByRole('button', { name: /create employer account/i }));

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(authApi.registerEmployer).not.toHaveBeenCalled();
  });

  it('submits the form and navigates to email verification on success', async () => {
    authApi.registerEmployer.mockResolvedValue({
      data: { data: { userId: 'user-1', email: 'hiring@acme.com' } },
    });
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /create employer account/i }));

    await vi.waitFor(() => {
      expect(authApi.registerEmployer).toHaveBeenCalledWith({
        companyName: 'Acme Corp',
        email: 'hiring@acme.com',
        companyAddress: '1 Infinite Loop',
        phoneNumber: '+15551234567',
        companySize: 'MEDIUM',
        password: 'password123',
        confirmPassword: 'password123',
      });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/verify-email?userId=user-1&email=hiring%40acme.com');
  });

  it('maps field-level server errors onto the matching input', async () => {
    authApi.registerEmployer.mockRejectedValue({
      response: { data: { data: { email: 'Email is already registered' } } },
    });
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /create employer account/i }));

    expect(await screen.findByText('Email is already registered')).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('shows a generic error message when the server response has no field details', async () => {
    authApi.registerEmployer.mockRejectedValue({
      response: { data: { message: 'Registration is temporarily unavailable' } },
    });
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /create employer account/i }));

    expect(await screen.findByText('Registration is temporarily unavailable')).toBeInTheDocument();
  });

  it('navigates to the job seeker registration page when the toggle is switched', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('tab', { name: 'Job Seeker' }));

    expect(mockNavigate).toHaveBeenCalledWith('/register/job-seeker');
  });
});
