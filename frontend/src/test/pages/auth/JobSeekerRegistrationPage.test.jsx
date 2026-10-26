import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerRegistrationPage from '../../../pages/auth/JobSeekerRegistrationPage';
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
      <JobSeekerRegistrationPage />
    </MemoryRouter>
  );
}

async function selectMultiOption(user, fieldLabel, query) {
  const input = screen.getByLabelText(byLabel(fieldLabel));
  await user.type(input, `${query}{Enter}`);
}

async function fillValidForm(user) {
  await user.type(screen.getByLabelText(byLabel('First name')), 'Ada');
  await user.type(screen.getByLabelText(byLabel('Last name')), 'Lovelace');
  await user.type(screen.getByLabelText(byLabel('Email')), 'ada@example.com');
  await user.type(screen.getByLabelText(byLabel('Address')), '10 Downing St');
  await user.type(screen.getByLabelText(byLabel('Phone number')), '5551234567');
  await selectMultiOption(user, 'Skills', 'Java');
  await selectMultiOption(user, 'Skills', 'React');
  await selectMultiOption(user, 'Skills', 'Docker');
  await selectMultiOption(user, 'Preferred job roles', 'DevOps Engineer');
  await user.type(screen.getByLabelText(byLabel('Password')), 'password123');
  await user.type(screen.getByLabelText(byLabel('Confirm password')), 'password123');
}

describe('JobSeekerRegistrationPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders all registration fields', () => {
    renderPage();

    expect(screen.getByLabelText(byLabel('First name'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Last name'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Email'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Address'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Phone number'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Skills'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Preferred job roles'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Password'))).toBeInTheDocument();
    expect(screen.getByLabelText(byLabel('Confirm password'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create job seeker account/i })).toBeInTheDocument();
  });

  it('shows validation errors and does not call the API when submitted empty', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /create job seeker account/i }));

    expect(await screen.findByText('First name is required')).toBeInTheDocument();
    expect(screen.getByText('Last name is required')).toBeInTheDocument();
    expect(screen.getByText('Select at least 3 skills')).toBeInTheDocument();
    expect(screen.getByText('Select at least 1 preferred job role')).toBeInTheDocument();
    expect(authApi.registerJobSeeker).not.toHaveBeenCalled();
  });

  it('selects skills and preferred job roles via the multi-select suggestions', async () => {
    const user = userEvent.setup();
    renderPage();

    await selectMultiOption(user, 'Skills', 'Java');
    await selectMultiOption(user, 'Skills', 'React');

    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('React')).toBeInTheDocument();
  });

  it('shows a mismatch error when passwords do not match', async () => {
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.clear(screen.getByLabelText(byLabel('Confirm password')));
    await user.type(screen.getByLabelText(byLabel('Confirm password')), 'different123');
    await user.click(screen.getByRole('button', { name: /create job seeker account/i }));

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(authApi.registerJobSeeker).not.toHaveBeenCalled();
  });

  it('submits the form and navigates to email verification on success', async () => {
    authApi.registerJobSeeker.mockResolvedValue({
      data: { data: { userId: 'user-2', email: 'ada@example.com' } },
    });
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /create job seeker account/i }));

    await vi.waitFor(() => {
      expect(authApi.registerJobSeeker).toHaveBeenCalledWith({
        firstName: 'Ada',
        lastName: 'Lovelace',
        email: 'ada@example.com',
        address: '10 Downing St',
        phoneNumber: '+15551234567',
        skills: ['Java', 'React', 'Docker'],
        preferredJobRoles: ['DevOps Engineer'],
        password: 'password123',
        confirmPassword: 'password123',
      });
    });
    expect(mockNavigate).toHaveBeenCalledWith('/verify-email?userId=user-2&email=ada%40example.com');
  });

  it('maps field-level server errors onto the matching input', async () => {
    authApi.registerJobSeeker.mockRejectedValue({
      response: { data: { data: { email: 'Email is already registered' } } },
    });
    const user = userEvent.setup();
    renderPage();

    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /create job seeker account/i }));

    expect(await screen.findByText('Email is already registered')).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('navigates to the employer registration page when the toggle is switched', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('tab', { name: 'MY HR' }));

    expect(mockNavigate).toHaveBeenCalledWith('/register/employer');
  });
});
