import apiClient from './apiClient';

const jobSeekerEventRegistrationsApi = {
  listMyRegistrations: () => apiClient.get('/job-seeker/registrations'),
};

export default jobSeekerEventRegistrationsApi;
