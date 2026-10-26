import apiClient from './apiClient';

const myHrProfileChangeApi = {
  create: (fieldName, requestedValue, requestReason) =>
    apiClient.post('/myhr/profile-change-requests', { fieldName, requestedValue, requestReason }),
  listMine: () => apiClient.get('/myhr/profile-change-requests/mine'),
  listForAdmin: () => apiClient.get('/myhr/profile-change-requests'),
  getDetail: (requestId) => apiClient.get(`/myhr/profile-change-requests/${requestId}`),
  approve: (requestId, processingComment) =>
    apiClient.post(`/myhr/profile-change-requests/${requestId}/approve`, { processingComment }),
  reject: (requestId, processingComment) =>
    apiClient.post(`/myhr/profile-change-requests/${requestId}/reject`, { processingComment }),
};

export default myHrProfileChangeApi;
