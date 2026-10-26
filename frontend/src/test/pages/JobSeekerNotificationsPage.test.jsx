import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerNotificationsPage from '../../pages/JobSeekerNotificationsPage';
import notificationApi from '../../api/notificationApi';

vi.mock('../../api/notificationApi');

describe('JobSeekerNotificationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the notifications page heading and an empty state when there are none', async () => {
    notificationApi.listNotifications.mockResolvedValue({ data: { data: [] } });

    render(
      <MemoryRouter>
        <JobSeekerNotificationsPage />
      </MemoryRouter>
    );

    expect(await screen.findByText('Notifications')).toBeInTheDocument();
    expect(screen.getByText('Updates on your applications and event registrations.')).toBeInTheDocument();
    expect(screen.getByText('No notifications yet')).toBeInTheDocument();
  });
});
