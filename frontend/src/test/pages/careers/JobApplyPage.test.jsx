import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import JobApplyPage from '../../../pages/careers/JobApplyPage';
import jobApi from '../../../api/jobApi';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';

vi.mock('../../../api/jobApi');
vi.mock('../../../api/jobSeekerProfileApi');

const jobDetail = (overrides = {}) => ({
  id: 'job-1',
  title: 'Backend Engineer',
  companyName: 'Acme Corp',
  location: 'Austin, Texas',
  workMode: 'REMOTE',
  employmentType: 'FULL_TIME',
  description: 'Build backend services.',
  requiredSkills: ['Java', 'Spring'],
  hasApplied: false,
  ...overrides,
});

const profileWithResume = (overrides = {}) => ({
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'ada@example.com',
  phoneNumber: '+11234567890',
  resumeFileName: 'ada_resume.pdf',
  skills: ['Java'],
  ...overrides,
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/careers/job-1/apply']}>
      <Routes>
        <Route path="/careers/:jobId/apply" element={<JobApplyPage />} />
        <Route path="/careers" element={<div>Careers Page Stub</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('JobApplyPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('accessToken', 'valid-token');
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('shows a loader while job and profile are being fetched', () => {
    jobApi.getJobById.mockReturnValue(new Promise(() => {}));
    jobSeekerProfileApi.getProfile.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading job details/i)).toBeInTheDocument();
  });

  it('shows a generic error message when the job fails to load', async () => {
    jobApi.getJobById.mockRejectedValue({ response: { data: { message: 'Server exploded' } } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });

    renderPage();

    expect(await screen.findByText('Server exploded')).toBeInTheDocument();
  });

  it('shows a not-found empty state on a 404', async () => {
    jobApi.getJobById.mockRejectedValue({ response: { status: 404 } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });

    renderPage();

    expect(await screen.findByText('Job not found')).toBeInTheDocument();
  });

  it('renders job info, prefilled profile fields, and skill match', async () => {
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });

    renderPage();

    expect(await screen.findByRole('heading', { name: 'Backend Engineer' })).toBeInTheDocument();
    expect(screen.getByDisplayValue('Ada')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Lovelace')).toBeInTheDocument();
    expect(screen.getByDisplayValue('ada@example.com')).toBeInTheDocument();
    expect(screen.getByText('ada_resume.pdf')).toBeInTheDocument();

    const skillMatchItems = Array.from(document.querySelectorAll('.wp-job-apply__sm-item'));
    const javaItem = skillMatchItems.find((el) => el.textContent.includes('Java'));
    const springItem = skillMatchItems.find((el) => el.textContent.includes('Spring'));
    expect(javaItem).not.toHaveClass('wp-job-apply__sm-item--miss');
    expect(springItem).toHaveClass('wp-job-apply__sm-item--miss');
  });

  it('blocks submission and shows a toast when no resume is on file', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: profileWithResume({ resumeFileName: undefined }) },
    });

    renderPage();

    await screen.findByRole('heading', { name: 'Backend Engineer' });
    await user.click(screen.getByRole('checkbox', { name: /authorized to work/i }));
    await user.click(screen.getByRole('button', { name: /submit application/i }));

    expect(await screen.findByText(/please upload a resume to your profile/i)).toBeInTheDocument();
    expect(jobApi.applyForJob).not.toHaveBeenCalled();
  });

  // NOTE: JobApplyPage.jsx's handleSubmit has an `if (!authorized) { setToast(...) }`
  // branch, but the Checkbox is rendered with the native `required` attribute, so the
  // browser's (and jsdom's) constraint validation blocks the "submit" event entirely
  // before React's onSubmit ever runs when the box is unchecked. That branch is dead
  // code in practice - this test documents the actual (native-blocked) behavior instead.
  it('does not submit when the authorization checkbox is left unchecked (native required validation blocks it)', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });

    renderPage();

    const checkbox = await screen.findByRole('checkbox', { name: /authorized to work/i });
    expect(checkbox).toBeRequired();
    expect(checkbox).not.toBeChecked();

    await user.click(screen.getByRole('button', { name: /submit application/i }));

    expect(jobApi.applyForJob).not.toHaveBeenCalled();
  });

  it('blocks submission when the access token is missing', async () => {
    const user = userEvent.setup();
    localStorage.clear();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });

    renderPage();

    await user.click(await screen.findByRole('checkbox', { name: /authorized to work/i }));
    await user.click(screen.getByRole('button', { name: /submit application/i }));

    expect(await screen.findByText(/please log in again before applying/i)).toBeInTheDocument();
    expect(jobApi.applyForJob).not.toHaveBeenCalled();
  });

  it('submits the application successfully and disables further submits', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });
    jobApi.applyForJob.mockResolvedValue({ data: { data: {} } });

    renderPage();

    await user.click(await screen.findByRole('checkbox', { name: /authorized to work/i }));
    await user.click(screen.getByRole('button', { name: /submit application/i }));

    expect(await screen.findByText('Application submitted successfully.')).toBeInTheDocument();
    expect(jobApi.applyForJob).toHaveBeenCalledWith('job-1');
    expect(screen.getByRole('button', { name: 'Applied' })).toBeDisabled();
  });

  it('shows an error toast when submission fails for a generic reason', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });
    jobApi.applyForJob.mockRejectedValue({ response: { data: { message: 'Network hiccup' } } });

    renderPage();

    await user.click(await screen.findByRole('checkbox', { name: /authorized to work/i }));
    await user.click(screen.getByRole('button', { name: /submit application/i }));

    expect(await screen.findByText('Network hiccup')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /submit application/i })).toBeEnabled();
  });

  it('treats an "already applied" submission error as already applied', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });
    jobApi.applyForJob.mockRejectedValue({
      response: { data: { message: 'You have already applied to this job' } },
    });

    renderPage();

    await user.click(await screen.findByRole('checkbox', { name: /authorized to work/i }));
    await user.click(screen.getByRole('button', { name: /submit application/i }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Applied' })).toBeDisabled());
  });

  it('uploads a replacement resume and updates the profile', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });
    jobSeekerProfileApi.uploadResume.mockResolvedValue({
      data: { data: profileWithResume({ resumeFileName: 'new_resume.pdf' }) },
    });

    renderPage();

    await screen.findByText('ada_resume.pdf');

    const file = new File(['content'], 'new_resume.pdf', { type: 'application/pdf' });
    const input = document.querySelector('input[name="resume"]');
    await user.upload(input, file);

    expect(await screen.findByText('new_resume.pdf')).toBeInTheDocument();
    expect(await screen.findByText('Resume updated')).toBeInTheDocument();
    expect(jobSeekerProfileApi.uploadResume).toHaveBeenCalledWith(file);
  });

  it('shows an error toast when resume upload fails', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: profileWithResume() } });
    jobSeekerProfileApi.uploadResume.mockRejectedValue(new Error('upload failed'));

    renderPage();

    await screen.findByText('ada_resume.pdf');

    const file = new File(['content'], 'new_resume.pdf', { type: 'application/pdf' });
    const input = document.querySelector('input[name="resume"]');
    await user.upload(input, file);

    expect(await screen.findByText(/failed to upload resume/i)).toBeInTheDocument();
  });
});
