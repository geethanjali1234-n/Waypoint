import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import JobPostingPage from '../../pages/JobPostingPage';
import EmployerEventsPage from '../../pages/EmployerEventsPage';
import jobApi from '../../api/jobApi';
import employerEventApi from '../../api/employerEventApi';

vi.mock('../../api/jobApi');
vi.mock('../../api/employerEventApi');

const jobs = Array.from({ length: 11 }, (_, index) => ({
  id: `job-${index + 1}`,
  title: `Job ${index + 1}`,
  status: 'OPEN',
  workMode: 'REMOTE',
  location: 'Austin, Texas',
  employmentType: 'FULL_TIME',
  applicationCount: index,
}));

const events = Array.from({ length: 11 }, (_, index) => ({
  id: `event-${index + 1}`,
  eventCode: `EVT-${index + 1}`,
  title: `Event ${index + 1}`,
  status: 'PUBLISHED',
  eventType: 'CAREER_FAIR',
  eventMode: 'ONLINE',
  meetingLink: 'https://example.com/event',
  registrationCount: index,
}));

describe('employer list pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobApi.listEmployerJobs.mockResolvedValue({ data: { data: jobs } });
    employerEventApi.listEmployerEvents.mockResolvedValue({ data: { data: events } });
  });

  it('shows 10 job postings on page one and the remainder on page two', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/employer/jobs']}><JobPostingPage /></MemoryRouter>);

    expect(await screen.findByRole('button', { name: 'Job 10' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Job 11' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByRole('button', { name: 'Job 11' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Job 1' })).not.toBeInTheDocument();
  });

  it('shows 10 events on page one and the remainder on page two', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/employer/events']}><EmployerEventsPage /></MemoryRouter>);

    expect(await screen.findByRole('link', { name: 'View registrants for Event 10' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View registrants for Event 11' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByRole('link', { name: 'View registrants for Event 11' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View registrants for Event 1' })).not.toBeInTheDocument();
  });
});
