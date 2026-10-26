import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import JobSeekerLayout from '../../pages/JobSeekerLayout';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import notificationApi from '../../api/notificationApi';
import resolveFileUrl from '../../api/resolveFileUrl';

vi.mock('../../api/jobSeekerProfileApi');
vi.mock('../../api/notificationApi');
vi.mock('../../api/resolveFileUrl');

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/job-seeker/dashboard']}>
      <Routes>
        <Route element={<JobSeekerLayout />}>
          <Route path="/job-seeker/dashboard" element={<div>Dashboard content</div>} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

describe('JobSeekerLayout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    notificationApi.getUnreadCount.mockResolvedValue({ data: { data: { count: 0 } } });
    notificationApi.listNotifications.mockResolvedValue({ data: { data: [] } });
  });

  it('shows the uploaded profile photo in the header avatar once identity loads', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: '/uploads/pic.png' } },
    });
    resolveFileUrl.mockReturnValue('http://localhost:8081/uploads/pic.png');

    renderLayout();

    const avatarImg = await screen.findByRole('img', { name: 'Ada' });
    expect(avatarImg).toHaveAttribute('src', 'http://localhost:8081/uploads/pic.png');
  });

  it('falls back to initials when the jobSeeker has no profile photo', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
    resolveFileUrl.mockReturnValue(undefined);

    const { container } = renderLayout();

    await screen.findByText('Ada');
    expect(screen.getByText('A')).toBeInTheDocument();
    expect(container.querySelector('.jobSeeker-shell__profile-menu img')).not.toBeInTheDocument();
  });
});
