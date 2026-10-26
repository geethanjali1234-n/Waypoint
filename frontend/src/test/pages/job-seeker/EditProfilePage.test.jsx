import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import EditProfilePage from '../../../pages/job-seeker/EditProfilePage';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';

vi.mock('../../../api/jobSeekerProfileApi');

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

const baseProfile = {
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'ada@example.com',
  phoneNumber: '+15551234567',
  skills: ['Java', 'React'],
  preferredJobRoles: ['Software Engineer'],
  preferredEmploymentType: ['FULL_TIME'],
  expectedSalaryMin: 50000,
  expectedSalaryMax: 100000,
  profilePictureUrl: null,
  resumeFileName: null,
  resumeUploadedAt: null,
  resumeSizeBytes: null,
};

function renderPage() {
  return render(
    <MemoryRouter>
      <EditProfilePage />
    </MemoryRouter>
  );
}

async function loadWithProfile(profileOverrides = {}) {
  jobSeekerProfileApi.getProfile.mockResolvedValue({
    data: { data: { ...baseProfile, ...profileOverrides } },
  });
  renderPage();
  await screen.findByLabelText(/^First name/);
}

describe('EditProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:mock-url');
    URL.revokeObjectURL = vi.fn();
  });

  it('shows a loader while the profile is being fetched', () => {
    jobSeekerProfileApi.getProfile.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading your profile/i)).toBeInTheDocument();
  });

  it('shows an error message when the profile fails to load', async () => {
    jobSeekerProfileApi.getProfile.mockRejectedValue(new Error('network error'));

    renderPage();

    expect(await screen.findByText(/unable to load your profile/i)).toBeInTheDocument();
  });

  it('pre-populates the form with the fetched profile data', async () => {
    await loadWithProfile();

    expect(screen.getByLabelText(/^First name/)).toHaveValue('Ada');
    expect(screen.getByLabelText(/^Last name/)).toHaveValue('Lovelace');
    expect(screen.getByLabelText(/^Phone/)).toHaveValue('(555) 123-4567');
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('React')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Software Engineer' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Full-time' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows a validation error and does not submit when first name is cleared', async () => {
    const user = userEvent.setup();
    await loadWithProfile();

    await user.clear(screen.getByLabelText(/^First name/));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('First name is required')).toBeInTheDocument();
    expect(jobSeekerProfileApi.updateProfile).not.toHaveBeenCalled();
  });

  it('shows a validation error for an incomplete phone number', async () => {
    const user = userEvent.setup();
    await loadWithProfile();

    const phoneInput = screen.getByLabelText(/^Phone/);
    await user.clear(phoneInput);
    await user.type(phoneInput, '555');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Enter a valid 10-digit phone number')).toBeInTheDocument();
    expect(jobSeekerProfileApi.updateProfile).not.toHaveBeenCalled();
  });

  it('requires at least one skill', async () => {
    const user = userEvent.setup();
    await loadWithProfile();

    await user.click(screen.getByRole('button', { name: 'Remove Java' }));
    await user.click(screen.getByRole('button', { name: 'Remove React' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Add at least 1 skill')).toBeInTheDocument();
    expect(jobSeekerProfileApi.updateProfile).not.toHaveBeenCalled();
  });

  it('requires at least one interested role', async () => {
    const user = userEvent.setup();
    await loadWithProfile();

    await user.click(screen.getByRole('button', { name: 'Software Engineer' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Select at least 1 interested role')).toBeInTheDocument();
    expect(jobSeekerProfileApi.updateProfile).not.toHaveBeenCalled();
  });

  it('toggles an employment type chip selection on click', async () => {
    const user = userEvent.setup();
    await loadWithProfile();

    const partTimeChip = screen.getByRole('button', { name: 'Part-time' });
    expect(partTimeChip).toHaveAttribute('aria-pressed', 'false');

    await user.click(partTimeChip);
    expect(partTimeChip).toHaveAttribute('aria-pressed', 'true');

    await user.click(partTimeChip);
    expect(partTimeChip).toHaveAttribute('aria-pressed', 'false');
  });

  it('submits the updated profile and navigates back on success', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.updateProfile.mockResolvedValue({});
    await loadWithProfile();

    await user.clear(screen.getByLabelText(/^Last name/));
    await user.type(screen.getByLabelText(/^Last name/), 'Byron');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(jobSeekerProfileApi.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({
          firstName: 'Ada',
          lastName: 'Byron',
          phoneNumber: '+15551234567',
          skills: ['Java', 'React'],
          preferredJobRoles: ['Software Engineer'],
          preferredEmploymentType: ['FULL_TIME'],
          expectedSalaryMin: 50000,
          expectedSalaryMax: 100000,
          removeProfilePicture: false,
        })
      );
    });
    expect(mockNavigate).toHaveBeenCalledWith('/job-seeker/profile');
  });

  it('changing the salary range dropdown updates the submitted min/max', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.updateProfile.mockResolvedValue({});
    await loadWithProfile();

    await user.selectOptions(screen.getByLabelText('Salary range'), '150000-200000');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(jobSeekerProfileApi.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({ expectedSalaryMin: 150000, expectedSalaryMax: 200000 })
      );
    });
  });

  it('shows a field-specific server validation error without navigating away', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.updateProfile.mockRejectedValue({
      response: { data: { data: { phoneNumber: 'Phone number already in use' } } },
    });
    await loadWithProfile();

    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Phone number already in use')).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('shows a generic form error when the update fails with a message', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.updateProfile.mockRejectedValue({
      response: { data: { message: 'Something broke on the server' } },
    });
    await loadWithProfile();

    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Something broke on the server')).toBeInTheDocument();
  });

  it('shows a fallback error message when the update fails with no response body', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.updateProfile.mockRejectedValue(new Error('network down'));
    await loadWithProfile();

    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument();
  });

  it('uploads the selected photo before saving on submit', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.uploadPhoto.mockResolvedValue({});
    jobSeekerProfileApi.updateProfile.mockResolvedValue({});
    await loadWithProfile();

    const file = new File(['image-bytes'], 'photo.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Add photo'), file);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(jobSeekerProfileApi.uploadPhoto).toHaveBeenCalledWith(file);
      expect(jobSeekerProfileApi.updateProfile).toHaveBeenCalled();
    });
    expect(mockNavigate).toHaveBeenCalledWith('/job-seeker/profile');
  });

  it('marks the photo for removal in the submitted payload', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.updateProfile.mockResolvedValue({});
    await loadWithProfile({ profilePictureUrl: '/uploads/profile/ada.png' });

    await user.click(screen.getByRole('button', { name: 'Remove photo' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(jobSeekerProfileApi.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({ removeProfilePicture: true })
      );
    });
  });

  it('uploads a resume immediately and shows a success toast', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.uploadResume.mockResolvedValue({
      data: { data: { ...baseProfile, resumeFileName: 'resume.pdf' } },
    });
    await loadWithProfile();

    const file = new File(['pdf-bytes'], 'resume.pdf', { type: 'application/pdf' });
    await user.upload(screen.getByLabelText('Upload'), file);

    expect(await screen.findByText('Resume updated')).toBeInTheDocument();
    expect(jobSeekerProfileApi.uploadResume).toHaveBeenCalledWith(file);
    expect(jobSeekerProfileApi.updateProfile).not.toHaveBeenCalled();
  });

  it('shows an error toast when resume upload fails', async () => {
    const user = userEvent.setup();
    jobSeekerProfileApi.uploadResume.mockRejectedValue(new Error('upload failed'));
    await loadWithProfile();

    const file = new File(['pdf-bytes'], 'resume.pdf', { type: 'application/pdf' });
    await user.upload(screen.getByLabelText('Upload'), file);

    expect(await screen.findByText('Failed to upload resume')).toBeInTheDocument();
  });

  it('navigates back to the profile page without saving when Cancel is clicked', async () => {
    const user = userEvent.setup();
    await loadWithProfile();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(mockNavigate).toHaveBeenCalledWith('/job-seeker/profile');
    expect(jobSeekerProfileApi.updateProfile).not.toHaveBeenCalled();
  });
});
