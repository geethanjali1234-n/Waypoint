import apiClient from './apiClient';
import compressFileForUpload from '../utils/compressFileForUpload';

const documentApi = {
  parseTimesheet: (documentId, dateOrder = 'MDY', ocrText = null) => {
    const params = { dateOrder };
    if (ocrText) params.ocrText = ocrText;
    return apiClient.post(`/myhr/documents/${documentId}/parse-timesheet`, null, { params });
  },
  upload: async (file, documentType = 'TIMESHEET_SUPPORTING_DOCUMENT') => {
    const formData = new FormData();
    formData.append('file', await compressFileForUpload(file));
    formData.append('documentType', documentType);
    return apiClient.post('/myhr/documents', formData);
  },
  download: (documentId) => apiClient.get(`/myhr/documents/${documentId}`, { responseType: 'blob' }),
};

export default documentApi;
