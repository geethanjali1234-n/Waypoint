import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EmployerProfilePage from './EmployerProfilePage';
import { getEmployerProfile, updateEmployerProfile } from '../api/employerProfileApi';

const { navigateMock } = vi.hoisted(() => ({
  navigateMock: vi.fn(),
}));

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateMock,
}));

vi.mock('../api/employerProfileApi', () => ({
  getEmployerProfile: vi.fn(),
  updateEmployerProfile: vi.fn(),
}));

vi.mock('../api/resolveFileUrl', () => ({
  default: (path) => path || '',
}));

function makeProfile(overrides = {}) {
  return {
    companyName: 'Waypoint Labs',
    companyDescription: 'A product team building better career connections.',
    contactPersonName: 'Alex Morgan',
    industry: 'Software',
    companySize: 'SMALL',
    foundedYear: 2020,
    careersContactEmail: 'careers@waypoint.test',
    website: 'https://waypoint.test',
    companyAddress: '123 Main Street, Austin, TX',
    phoneNumber: '+15125550123',
    companyLogoUrl: null,
    ...overrides,
  };
}

describe('EmployerProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getEmployerProfile.mockResolvedValue(makeProfile());
    updateEmployerProfile.mockResolvedValue(makeProfile());
  });

  it('loads and displays the employer profile summary', async () => {
    render(<EmployerProfilePage />);

    expect(await screen.findByRole('heading', { name: 'Employer profile' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Waypoint Labs' })).toBeInTheDocument();
    expect(screen.getByText('Software')).toBeInTheDocument();
    expect(screen.getByText('Small')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'https://waypoint.test' })).toHaveAttribute(
      'href',
      'https://waypoint.test',
    );
    expect(getEmployerProfile).toHaveBeenCalledTimes(1);
  });

  it('validates edited profile fields before saving', async () => {
    const user = userEvent.setup();
    render(<EmployerProfilePage />);

    await screen.findByRole('heading', { name: 'Employer profile' });
    await user.click(screen.getByRole('button', { name: 'Edit profile' }));

    const emailInput = screen.getByRole('textbox', { name: 'Careers contact email' });
    await user.clear(emailInput);
    await user.type(emailInput, 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Careers contact email must be valid')).toBeInTheDocument();
    expect(updateEmployerProfile).not.toHaveBeenCalled();
  });

  it('saves valid edits and shows the success message', async () => {
    const user = userEvent.setup();
    const savedProfile = makeProfile({ companyName: 'Waypoint Careers' });
    updateEmployerProfile.mockResolvedValueOnce(savedProfile);
    render(<EmployerProfilePage />);

    await screen.findByRole('heading', { name: 'Employer profile' });
    await user.click(screen.getByRole('button', { name: 'Edit profile' }));
    const companyInput = screen.getByRole('textbox', { name: 'Company name' });
    await user.clear(companyInput);
    await user.type(companyInput, 'Waypoint Careers');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(updateEmployerProfile).toHaveBeenCalledWith(expect.objectContaining({
      companyName: 'Waypoint Careers',
      foundedYear: 2020,
      removeCompanyLogo: false,
    })));
    expect(await screen.findByRole('status')).toHaveTextContent('Employer profile updated successfully');
    expect(screen.getByRole('heading', { name: 'Waypoint Careers' })).toBeInTheDocument();
  });

  it('edits the founded year with a year-only dropdown', async () => {
    const user = userEvent.setup();
    render(<EmployerProfilePage />);

    await screen.findByRole('heading', { name: 'Employer profile' });
    await user.click(screen.getByRole('button', { name: 'Edit profile' }));

    const foundedYear = screen.getByRole('combobox', { name: 'Founded year' });
    expect(foundedYear).toHaveValue('2020');
    expect(screen.queryByDisplayValue('2020-01-01')).not.toBeInTheDocument();

    await user.selectOptions(foundedYear, '2015');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(updateEmployerProfile).toHaveBeenCalledWith(expect.objectContaining({
      foundedYear: 2015,
    })));
  });

  it('shows a recoverable error when the profile cannot be loaded', async () => {
    getEmployerProfile.mockRejectedValueOnce(new Error('Request failed'));
    render(<EmployerProfilePage />);

    expect(await screen.findByText('Unable to load employer profile')).toBeInTheDocument();
    expect(screen.getByText('Check your connection and try again.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
