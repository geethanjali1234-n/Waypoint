import apiClient from './apiClient';
import compressFileForUpload from '../utils/compressFileForUpload';

const myHrEmployeeApi = {
  list: () => apiClient.get('/myhr/employees'),
  get: (userId) => apiClient.get(`/myhr/employees/${userId}`),
  getMine: () => apiClient.get('/myhr/employees/me'),
  // The signed-in user's own signature (My HR Profile, any role). The backend
  // takes the user from the session - no user id is ever sent.
  uploadMySignature: async (file) => {
    const formData = new FormData();
    formData.append('file', await compressFileForUpload(file));
    return apiClient.post('/myhr/employees/me/signature', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  removeMySignature: () => apiClient.delete('/myhr/employees/me/signature'),
  reportingManagers: (employeeUserId) =>
    apiClient.get('/myhr/employees/reporting-managers', {
      params: employeeUserId ? { employeeUserId } : undefined,
    }),
  changeReportingManager: (userId, payload) =>
    apiClient.patch(`/myhr/employees/${userId}/reporting-manager`, payload),
  updateStatus: (userId, payload) =>
    apiClient.patch(`/myhr/employees/${userId}/status`, payload),
};

export default myHrEmployeeApi;
