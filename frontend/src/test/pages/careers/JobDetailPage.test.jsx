import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import JobDetailPage from '../../../pages/careers/JobDetailPage';
import jobApi from '../../../api/jobApi';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';

vi.mock('../../../api/jobApi');
vi.mock('../../../api/jobSeekerProfileApi');

const jobDetail = (overrides = {}) => ({
  id: 'job-1',
  jobCode: 'JOB-1',
  title: 'Backend Engineer',
  companyName: 'Acme Corp',
  location: 'Austin, Texas',
  workMode: 'REMOTE',
  employmentType: 'FULL_TIME',
  description: 'Build backend services.',
  requiredSkills: ['Java', 'Spring'],
  ...overrides,
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/careers/job-1']}>
      <Routes>
        <Route path="/careers/:jobId" element={<JobDetailPage />} />
        <Route path="/careers/:jobId/apply" element={<div>Apply Page Stub</div>} />
        <Route path="/careers" element={<div>Careers Page Stub</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('JobDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: { firstName: 'Ada' } } });
  });

  it('shows a loader while the job is being fetched', () => {
    jobApi.getJobById.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading job details/i)).toBeInTheDocument();
  });

  it('shows a generic error message on a non-404 failure', async () => {
    jobApi.getJobById.mockRejectedValue({ response: { status: 500, data: { message: 'Server exploded' } } });

    renderPage();

    expect(await screen.findByText('Server exploded')).toBeInTheDocument();
  });

  it('shows a not-found empty state on a 404 and can navigate back to Careers', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockRejectedValue({ response: { status: 404 } });

    renderPage();

    expect(await screen.findByText('Job not found')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /back to careers/i }));

    expect(await screen.findByText('Careers Page Stub')).toBeInTheDocument();
  });

  it('renders job details, salary, and skills when loaded', async () => {
    jobApi.getJobById.mockResolvedValue({
      data: { data: jobDetail({ salaryMin: 70000, salaryMax: 90000 }) },
    });

    renderPage();

    expect(await screen.findByText('Backend Engineer')).toBeInTheDocument();
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    expect(screen.getByText(/Austin, Texas · FULL_TIME · REMOTE/)).toBeInTheDocument();
    expect(screen.getByText('$70000 – $90000')).toBeInTheDocument();
    expect(screen.getByText('Build backend services.')).toBeInTheDocument();
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('Spring')).toBeInTheDocument();
  });

  it('navigates to the apply page when Apply Now is clicked', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    await user.click(await screen.findByRole('button', { name: /apply now/i }));

    expect(await screen.findByText('Apply Page Stub')).toBeInTheDocument();
  });

  it('navigates back to Careers via the back link', async () => {
    const user = userEvent.setup();
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    await user.click(await screen.findByText(/back to careers/i));

    expect(await screen.findByText('Careers Page Stub')).toBeInTheDocument();
  });
});
