import { describe, it, expect, vi, beforeEach } from 'vitest';
import jobSeekerDashboardApi from '../../api/jobSeekerDashboardApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('jobSeekerDashboardApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getDashboard calls GET /job-seeker/dashboard', () => {
    jobSeekerDashboardApi.getDashboard();

    expect(apiClient.get).toHaveBeenCalledWith('/job-seeker/dashboard');
  });
});
