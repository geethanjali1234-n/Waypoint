import { describe, it, expect, vi, beforeEach } from 'vitest';
import jobSeekerApplicationsApi from '../../api/jobSeekerApplicationsApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('jobSeekerApplicationsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listMyApplications calls GET /job-seeker/applications', () => {
    jobSeekerApplicationsApi.listMyApplications();

    expect(apiClient.get).toHaveBeenCalledWith('/job-seeker/applications');
  });
});
