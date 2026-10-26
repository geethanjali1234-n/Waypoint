import apiClient from './apiClient';

const myHrAccountApi = {
  createHrAccount: (profile) => apiClient.post('/myhr/accounts/hr', { profile }),
  createEmployeeAccount: (profile, reportingManagerUserId) =>
    apiClient.post('/myhr/accounts/employees', { profile, reportingManagerUserId }),
  getEligibleReportingManagers: () => apiClient.get('/myhr/accounts/reporting-managers'),
};

export default myHrAccountApi;
