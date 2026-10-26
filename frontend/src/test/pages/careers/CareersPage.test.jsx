import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import CareersPage from '../../../pages/careers/CareersPage';
import jobApi from '../../../api/jobApi';
import jobSeekerProfileApi from '../../../api/jobSeekerProfileApi';
import useGeolocation from '../../../hooks/useGeolocation';

vi.mock('../../../api/jobApi');
vi.mock('../../../api/jobSeekerProfileApi');
vi.mock('../../../hooks/useGeolocation');

const jobSummary = (overrides = {}) => ({
  id: 'job-1',
  title: 'Backend Engineer',
  location: 'Austin, Texas',
  ...overrides,
});

const jobDetail = (overrides = {}) => ({
  id: 'job-1',
  jobCode: 'JOB-1',
  title: 'Backend Engineer',
  companyName: 'Acme Corp',
  location: 'Austin, Texas',
  workMode: 'REMOTE',
  employmentType: 'FULL_TIME',
  description: 'Build backend services.',
  requiredSkills: ['Java'],
  ...overrides,
});

function jobListResponse({ content = [jobSummary()], totalPages = 1, totalElements = content.length } = {}) {
  return { data: { data: { content, totalPages, totalElements } } };
}

function renderPage(initialEntries = ['/careers']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/careers" element={<CareersPage />} />
        <Route path="/careers/:jobId/apply" element={<div>Apply Page Stub</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('CareersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({ data: { data: { firstName: 'Ada' } } });
    useGeolocation.mockReturnValue({
      coords: null,
      requestLocation: vi.fn().mockRejectedValue(new Error('UNSUPPORTED')),
    });
  });

  it('shows a loader while jobs are being fetched', () => {
    jobApi.listJobs.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByText(/loading jobs/i)).toBeInTheDocument();
  });

  it('shows an error message when the job list fails to load', async () => {
    jobApi.listJobs.mockRejectedValue({ response: { data: { message: 'Server exploded' } } });

    renderPage();

    expect(await screen.findByText('Server exploded')).toBeInTheDocument();
  });

  it('shows an empty state when there are no jobs', async () => {
    jobApi.listJobs.mockResolvedValue(jobListResponse({ content: [], totalElements: 0 }));

    renderPage();

    expect(await screen.findByText('0 results')).toBeInTheDocument();
    expect(screen.getByText('No jobs are available yet.')).toBeInTheDocument();
  });

  it('renders job list and auto-selects the first job detail', async () => {
    jobApi.listJobs.mockResolvedValue(
      jobListResponse({
        content: [jobSummary(), jobSummary({ id: 'job-2', title: 'Frontend Engineer', location: 'Dallas, Texas' })],
        totalElements: 2,
      })
    );
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    expect(await screen.findByText('2 results')).toBeInTheDocument();
    expect(screen.getByText('Frontend Engineer')).toBeInTheDocument();

    await waitFor(() => expect(jobApi.getJobById).toHaveBeenCalledWith('job-1'));
    expect(await screen.findByText('Acme Corp')).toBeInTheDocument();
    expect(screen.getByText('Build backend services.')).toBeInTheDocument();
  });

  it('re-fetches jobs with search filters applied', async () => {
    const user = userEvent.setup();
    jobApi.listJobs.mockResolvedValue(jobListResponse());
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    await screen.findByText('1 result');

    await user.type(screen.getByPlaceholderText(/job title or role/i), 'Engineer');
    await user.type(screen.getByPlaceholderText(/city/i), 'Dallas');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() =>
      expect(jobApi.listJobs).toHaveBeenLastCalledWith({
        page: 0,
        size: 10,
        title: 'Engineer',
        location: 'Dallas',
      })
    );
  });

  it('paginates by requesting the next page from the server', async () => {
    const user = userEvent.setup();
    jobApi.listJobs.mockResolvedValue(
      jobListResponse({ content: [jobSummary(), jobSummary({ id: 'job-2', title: 'Frontend Engineer' })], totalPages: 2, totalElements: 15 })
    );
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    await screen.findByText('15 results');

    const nav = screen.getByRole('navigation', { name: /pagination/i });
    await user.click(within(nav).getByRole('button', { name: '2' }));

    await waitFor(() =>
      expect(jobApi.listJobs).toHaveBeenLastCalledWith({ page: 1, size: 10 })
    );
  });

  it('navigates to the apply page when Apply now is clicked', async () => {
    const user = userEvent.setup();
    jobApi.listJobs.mockResolvedValue(jobListResponse());
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    await screen.findByText('Acme Corp');
    await user.click(screen.getByRole('button', { name: /apply now/i }));

    expect(await screen.findByText('Apply Page Stub')).toBeInTheDocument();
  });

  it('requests location automatically on load and sends it with the jobs request', async () => {
    const requestLocation = vi.fn().mockResolvedValue({ lat: 32.8140, lng: -96.9489 });
    useGeolocation.mockReturnValue({ coords: { lat: 32.8140, lng: -96.9489 }, requestLocation });
    jobApi.listJobs.mockResolvedValue(jobListResponse());
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();
    await screen.findByText('1 result');

    expect(requestLocation).toHaveBeenCalled();
    expect(jobApi.listJobs).toHaveBeenLastCalledWith(
      expect.objectContaining({ lat: 32.8140, lng: -96.9489 })
    );
  });

  it('does not show an error toast when the automatic on-load location request fails', async () => {
    // beforeEach already wires requestLocation to reject with UNSUPPORTED.
    jobApi.listJobs.mockResolvedValue(jobListResponse());
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });

    renderPage();

    expect(await screen.findByText('1 result')).toBeInTheDocument();
    expect(screen.queryByText(/location access denied/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/does not support geolocation/i)).not.toBeInTheDocument();
  });

  it('sends maxDistanceMiles once a distance option is selected', async () => {
    useGeolocation.mockReturnValue({
      coords: { lat: 32.8140, lng: -96.9489 },
      requestLocation: vi.fn().mockResolvedValue({ lat: 32.8140, lng: -96.9489 }),
    });
    jobApi.listJobs.mockResolvedValue(jobListResponse());
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    const user = userEvent.setup();

    const { container } = renderPage();
    await screen.findByText('1 result');

    await user.selectOptions(container.querySelector('select[name="distance"]'), '50');

    await waitFor(() => {
      expect(jobApi.listJobs).toHaveBeenLastCalledWith(
        expect.objectContaining({ maxDistanceMiles: '50', lat: 32.8140, lng: -96.9489 })
      );
    });
  });

  it('shows an error toast and resets the distance filter when location is denied after explicitly selecting a distance', async () => {
    const requestLocation = vi.fn().mockRejectedValue(new Error('DENIED'));
    useGeolocation.mockReturnValue({ coords: null, requestLocation });
    jobApi.listJobs.mockResolvedValue(jobListResponse());
    jobApi.getJobById.mockResolvedValue({ data: { data: jobDetail() } });
    const user = userEvent.setup();

    const { container } = renderPage();
    await screen.findByText('1 result');

    await user.selectOptions(container.querySelector('select[name="distance"]'), '50');

    expect(await screen.findByText(/location access denied/i)).toBeInTheDocument();
  });
});
