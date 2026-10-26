import apiClient from './apiClient';
import compressFileForUpload from '../utils/compressFileForUpload';

/**
 * COMPANY_ADMIN organization settings. The backend derives the organization
 * from the authenticated session, so no organization id is ever sent.
 */
const myHrOrganizationSettingsApi = {
  get: () => apiClient.get('/myhr/organization-settings'),
  update: (settings) => apiClient.put('/myhr/organization-settings', settings),
  uploadLogo: async (file) => {
    const formData = new FormData();
    formData.append('file', await compressFileForUpload(file));
    return apiClient.post('/myhr/organization-settings/logo', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  // Authorized Signature (used to sign documents such as the Signed I-983).
  // The image is the Company Admin's own My HR Profile signature.
  getAuthorizedSignature: () => apiClient.get('/myhr/organization-settings/authorized-signature'),
  updateAuthorizedSignature: (payload) => apiClient.put('/myhr/organization-settings/authorized-signature', payload),
};

export default myHrOrganizationSettingsApi;
