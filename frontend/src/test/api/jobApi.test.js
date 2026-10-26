import { describe, it, expect, vi, beforeEach } from 'vitest';
import jobApi from '../../api/jobApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('jobApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listJobs calls GET /jobs with the given params', () => {
    const params = { title: 'Engineer' };
    jobApi.listJobs(params);

    expect(apiClient.get).toHaveBeenCalledWith('/jobs', { params });
  });

  it('getJobById calls GET /jobs/:jobId', () => {
    jobApi.getJobById('job-1');

    expect(apiClient.get).toHaveBeenCalledWith('/jobs/job-1');
  });

  it('applyForJob calls POST /jobs/:jobId/apply', () => {
    jobApi.applyForJob('job-1');

    expect(apiClient.post).toHaveBeenCalledWith('/jobs/job-1/apply');
  });
});
