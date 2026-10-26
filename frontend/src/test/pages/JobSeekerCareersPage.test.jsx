import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerCareersPage from '../../pages/JobSeekerCareersPage';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';

vi.mock('../../api/jobSeekerProfileApi');

describe('JobSeekerCareersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
  });

  it('renders the placeholder careers page heading', async () => {
    render(
      <MemoryRouter>
        <JobSeekerCareersPage />
      </MemoryRouter>
    );

    expect(await screen.findByText('Job Seeker Careers')).toBeInTheDocument();
    expect(screen.getByText('This is a temporary placeholder page.')).toBeInTheDocument();
  });
});
