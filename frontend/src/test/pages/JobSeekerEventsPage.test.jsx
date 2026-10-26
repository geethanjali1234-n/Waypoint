import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerEventsPage from '../../pages/JobSeekerEventsPage';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';

vi.mock('../../api/jobSeekerProfileApi');

describe('JobSeekerEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
  });

  it('renders the placeholder events page heading', async () => {
    render(
      <MemoryRouter>
        <JobSeekerEventsPage />
      </MemoryRouter>
    );

    expect(await screen.findByText('Job Seeker Events')).toBeInTheDocument();
    expect(screen.getByText('This is a temporary placeholder page.')).toBeInTheDocument();
  });
});
