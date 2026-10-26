import apiClient from './apiClient';

const jobSeekerApplicationsApi = {
  listMyApplications: () => apiClient.get('/job-seeker/applications'),
};

export default jobSeekerApplicationsApi;
