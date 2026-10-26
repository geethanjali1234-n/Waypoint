import apiClient from './apiClient';

const waypointAdminApi = {
  getDashboard: () => apiClient.get('/waypoint-admin/dashboard'),
  getOrganizations: ({ page = 0, size = 10, search } = {}) => apiClient.get('/waypoint-admin/organizations', {
    params: { page, size, ...(search ? { search } : {}) },
  }),
  getOrganization: (organizationId) => apiClient.get(`/waypoint-admin/organizations/${organizationId}`),
};

export default waypointAdminApi;
