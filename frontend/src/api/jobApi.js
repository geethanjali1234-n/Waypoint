import apiClient from './apiClient';

const jobApi = {
  listJobs: (params) => apiClient.get('/jobs', { params }),
  getJobById: (jobId) => apiClient.get(`/jobs/${jobId}`),
  applyForJob: (jobId) => apiClient.post(`/jobs/${jobId}/apply`),
  listEmployerJobs: () => apiClient.get('/employer/jobs'),
  getEmployerJob: (jobId) => apiClient.get(`/employer/jobs/${jobId}`),
  listJobApplicants: (jobId) => apiClient.get(`/employer/jobs/${jobId}/applications`),
  getJobApplicant: (jobId, applicationId) => apiClient.get(`/employer/jobs/${jobId}/applications/${applicationId}`),
  updateApplicantStatus: (jobId, applicationId, status) => apiClient.patch(
    `/employer/jobs/${jobId}/applications/${applicationId}/status`,
    { status },
  ),
  getApplicantResume: (jobId, applicationId, download = false) => apiClient.get(
    `/employer/jobs/${jobId}/applications/${applicationId}/resume`,
    { params: { download }, responseType: 'blob' },
  ),
  getNewJobCode: () => apiClient.get('/employer/jobs/new-code'),
  createJob: (payload) => apiClient.post('/employer/jobs', payload),
  updateJob: (jobId, payload) => apiClient.put(`/employer/jobs/${jobId}`, payload),
  closeJob: (jobId) => apiClient.patch(`/employer/jobs/${jobId}/close`),
  reopenJob: (jobId) => apiClient.patch(`/employer/jobs/${jobId}/reopen`),
};

export default jobApi;
