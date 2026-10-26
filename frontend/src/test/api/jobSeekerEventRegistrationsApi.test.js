import { describe, it, expect, vi, beforeEach } from 'vitest';
import jobSeekerEventRegistrationsApi from '../../api/jobSeekerEventRegistrationsApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('jobSeekerEventRegistrationsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listMyRegistrations calls GET /job-seeker/registrations', () => {
    jobSeekerEventRegistrationsApi.listMyRegistrations();

    expect(apiClient.get).toHaveBeenCalledWith('/job-seeker/registrations');
  });
});
