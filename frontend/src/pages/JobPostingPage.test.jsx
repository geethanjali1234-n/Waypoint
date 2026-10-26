import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import JobPostingPage from './JobPostingPage';
import jobApi from '../api/jobApi';

const { locationPath, navigateMock } = vi.hoisted(() => ({
  locationPath: { value: '/employer/jobs' },
  navigateMock: vi.fn(),
}));

vi.mock('react-router-dom', () => ({
  useLocation: () => ({ pathname: locationPath.value }),
  useNavigate: () => navigateMock,
}));

vi.mock('../api/jobApi', () => ({
  default: {
    listEmployerJobs: vi.fn(),
    getNewJobCode: vi.fn(),
    createJob: vi.fn(),
    updateJob: vi.fn(),
    closeJob: vi.fn(),
    reopenJob: vi.fn(),
  },
}));

function makeJob(overrides = {}) {
  return {
    id: 'job-1',
    jobCode: 'JOB-001',
    title: 'Frontend Engineer',
    workMode: 'ONSITE',
    location: 'Austin, Texas',
    employmentType: 'FULL_TIME',
    experienceLevel: 'MID_LEVEL',
    requiredSkills: ['React'],
    salaryRange: 'RANGE_50K_100K',
    salaryMin: 80000,
    salaryMax: 100000,
    description: 'Build accessible product experiences.',
    applicationDeadline: '2099-12-31',
    resumeRequired: true,
    status: 'OPEN',
    applicationCount: 3,
    createdAt: '2026-08-05T16:19:17',
    ...overrides,
  };
}

function resolveJobs(jobs = []) {
  jobApi.listEmployerJobs.mockResolvedValue({ data: { data: jobs } });
}

describe('JobPostingPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    locationPath.value = '/employer/jobs';
    resolveJobs([makeJob()]);
    jobApi.getNewJobCode.mockResolvedValue({ data: { data: 'JOB-NEW' } });
  });

  it('loads and displays employer job postings', async () => {
    render(<JobPostingPage />);

    expect(await screen.findByRole('button', { name: 'Frontend Engineer' })).toBeInTheDocument();
    expect(screen.getByText('Active', { selector: 'span' })).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText(/Posted:/)).toBeInTheDocument();
    expect(screen.getByText('$50K - $100K')).toBeInTheDocument();
    expect(jobApi.listEmployerJobs).toHaveBeenCalledTimes(1);
  });

  it('filters postings by search text and status', async () => {
    const user = userEvent.setup();
    const closedJob = makeJob({
      id: 'job-2',
      jobCode: 'JOB-002',
      title: 'Data Analyst',
      status: 'CLOSED',
    });
    resolveJobs([makeJob(), closedJob]);
    render(<JobPostingPage />);

    await screen.findByRole('button', { name: 'Frontend Engineer' });
    const search = screen.getByRole('searchbox', { name: 'Search jobs' });
    await user.type(search, 'Data');
    expect(screen.queryByRole('button', { name: 'Frontend Engineer' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Data Analyst' })).toBeInTheDocument();

    await user.clear(search);
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter' }), 'CLOSED');
    expect(screen.queryByRole('button', { name: 'Frontend Engineer' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Data Analyst' })).toBeInTheDocument();
  });

  it('matches state abbreviations and full names in location searches', async () => {
    const user = userEvent.setup();
    const texasFullName = makeJob({ id: 'job-1', title: 'Austin Engineer', location: 'Austin, Texas' });
    const texasAbbreviation = makeJob({ id: 'job-2', title: 'Dallas Engineer', location: 'Dallas, TX' });
    const otherState = makeJob({ id: 'job-3', title: 'Seattle Engineer', location: 'Seattle, WA' });
    resolveJobs([texasFullName, texasAbbreviation, otherState]);
    render(<JobPostingPage />);

    await screen.findByRole('button', { name: 'Austin Engineer' });
    const search = screen.getByRole('searchbox', { name: 'Search jobs' });

    await user.type(search, 'TX');
    expect(screen.getByRole('button', { name: 'Austin Engineer' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dallas Engineer' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Seattle Engineer' })).not.toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'texas');
    expect(screen.getByRole('button', { name: 'Austin Engineer' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dallas Engineer' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Seattle Engineer' })).not.toBeInTheDocument();
  });

  it('shows the empty state when there are no postings', async () => {
    resolveJobs([]);
    render(<JobPostingPage />);

    expect(await screen.findByText('No job postings yet. Select "New posting" to create one.'))
      .toBeInTheDocument();
  });

  it('validates required fields before creating a posting', async () => {
    const user = userEvent.setup();
    locationPath.value = '/employer/jobs/new';
    resolveJobs([]);
    render(<JobPostingPage />);

    expect(await screen.findByRole('heading', { name: 'New job posting' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Publish posting' }));

    expect(await screen.findByText('Job title is required')).toBeInTheDocument();
    expect(screen.getByText('Required skills are required')).toBeInTheDocument();
    expect(jobApi.createJob).not.toHaveBeenCalled();
  });

  it('creates a valid posting with the expected payload', async () => {
    const user = userEvent.setup();
    const savedJob = makeJob({ id: 'job-new', jobCode: 'JOB-NEW', title: 'Product Designer' });
    locationPath.value = '/employer/jobs/new';
    resolveJobs([]);
    jobApi.createJob.mockResolvedValueOnce({ data: { data: savedJob } });
    render(<JobPostingPage />);

    await screen.findByRole('heading', { name: 'New job posting' });
    await user.type(screen.getByRole('textbox', { name: 'Job title' }), 'Product Designer');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Work type' }), 'ONSITE');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Employment type' }), 'FULL_TIME');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Location' }), 'Austin, Texas');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Experience level' }), 'MID_LEVEL');
    await user.type(screen.getByRole('combobox', { name: 'Required skills' }), 'React');
    await user.click(screen.getByRole('button', { name: 'React' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Description' }), {
      target: { value: 'Design clear product experiences.' },
    });
    fireEvent.change(screen.getByLabelText('Salary range'), { target: { value: 'RANGE_50K_100K' } });
    fireEvent.change(screen.getByLabelText(/Application deadline/), { target: { value: '2099-12-31' } });
    await user.click(screen.getByRole('button', { name: 'Publish posting' }));

    await waitFor(() => expect(jobApi.createJob).toHaveBeenCalledWith({
      jobCode: 'JOB-NEW',
      title: 'Product Designer',
      workMode: 'ONSITE',
      location: 'Austin, Texas',
      employmentType: 'FULL_TIME',
      experienceLevel: 'MID_LEVEL',
      requiredSkills: ['React'],
      salaryRange: 'RANGE_50K_100K',
      salaryMin: null,
      salaryMax: null,
      description: 'Design clear product experiences.',
      applicationDeadline: '2099-12-31',
      resumeRequired: true,
    }));
    expect(navigateMock).toHaveBeenCalledWith('/employer/jobs');
  });

  it('closes and reopens a posting using its current status', async () => {
    const user = userEvent.setup();
    const closedJob = makeJob({ status: 'CLOSED' });
    const reopenedJob = makeJob({ status: 'OPEN' });
    jobApi.closeJob.mockResolvedValueOnce({ data: { data: closedJob } });
    jobApi.reopenJob.mockResolvedValueOnce({ data: { data: reopenedJob } });
    render(<JobPostingPage />);

    await screen.findByRole('button', { name: 'Frontend Engineer' });
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(await screen.findByText('"Frontend Engineer" was closed successfully.')).toBeInTheDocument();
    expect(jobApi.closeJob).toHaveBeenCalledWith('job-1');

    await user.click(screen.getByRole('button', { name: 'Reopen' }));
    expect(await screen.findByText('"Frontend Engineer" was reopened successfully.')).toBeInTheDocument();
    expect(jobApi.reopenJob).toHaveBeenCalledWith('job-1');
  });

  it('shows a load error when the postings request fails', async () => {
    jobApi.listEmployerJobs.mockRejectedValueOnce({ response: { data: { message: 'Unable to load jobs.' } } });
    render(<JobPostingPage />);

    expect(await screen.findByText('Unable to load jobs.')).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('heading', { name: 'Job postings' })).toBeInTheDocument();
  });
});
