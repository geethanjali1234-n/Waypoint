import apiClient from './apiClient';
import compressFileForUpload from '../utils/compressFileForUpload';

const timesheetApi = {
  list: () => apiClient.get('/myhr/timesheets'),
  get: (timesheetId) => apiClient.get(`/myhr/timesheets/${timesheetId}`),
  create: (payload) => apiClient.post('/myhr/timesheets', payload),
  update: (timesheetId, payload) => apiClient.put(`/myhr/timesheets/${timesheetId}`, payload),
  downloadPdf: (timesheetId) => apiClient.get(`/myhr/timesheets/${timesheetId}/pdf`, { responseType: 'blob' }),
  getSettings: () => apiClient.get('/myhr/timesheets/settings'),
  getPeriod: (start, end) => apiClient.get('/myhr/timesheets/period', { params: { start, end } }),
  downloadPeriodPdf: (start, end) => apiClient.get('/myhr/timesheets/period-pdf', { params: { start, end }, responseType: 'blob' }),
  review: (timesheetId, payload) => apiClient.patch(`/myhr/timesheets/${timesheetId}/review`, payload),
  parseDocument: async (file, { dateOrder = 'MDY', ocrText = null } = {}) => {
    const formData = new FormData();
    formData.append('file', await compressFileForUpload(file));
    formData.append('dateOrder', dateOrder);
    if (ocrText) formData.append('ocrText', ocrText);
    return apiClient.post('/myhr/timesheets/parse-document', formData);
  },
};

export default timesheetApi;
