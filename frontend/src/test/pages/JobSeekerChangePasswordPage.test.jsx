import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerChangePasswordPage from '../../pages/JobSeekerChangePasswordPage';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';

vi.mock('../../api/jobSeekerProfileApi');

describe('JobSeekerChangePasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
  });

  it('renders the placeholder change password page heading', async () => {
    render(
      <MemoryRouter>
        <JobSeekerChangePasswordPage />
      </MemoryRouter>
    );

    expect(await screen.findByText('Change Password')).toBeInTheDocument();
    expect(screen.getByText('Coming Soon')).toBeInTheDocument();
  });
});
