import apiClient from './apiClient';

const jobSeekerJobApi = {
  listOpenJobs: (search = '') => apiClient.get(
    '/job-seeker/jobs',
    search.trim() ? { params: { search: search.trim() } } : undefined,
  ),
  getJob: (jobId) => apiClient.get(`/job-seeker/jobs/${jobId}`),
  applyToJob: (jobId) => apiClient.post(`/job-seeker/jobs/${jobId}/applications`),
};

export default jobSeekerJobApi;
