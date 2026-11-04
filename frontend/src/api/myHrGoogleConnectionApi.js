import apiClient from './apiClient';

/**
 * COMPANY_ADMIN management of the organization's Google email connection. The backend derives the organization
 * from the authenticated session, so no organization id is ever sent, and it builds the Google authorization URL
 * (the browser is only ever redirected to the URL it returns). No token or secret is ever returned by these calls.
 */
const myHrGoogleConnectionApi = {
  getStatus: () => apiClient.get('/myhr/organization-settings/email/google'),
  startConnect: () => apiClient.post('/myhr/organization-settings/email/google/connect'),
  disconnect: () => apiClient.delete('/myhr/organization-settings/email/google'),
  // Sends a fixed message from the connected account to the organization's Organization Email. Takes no input: the
  // recipient, sender and content are all decided by the backend, and there is no fallback to the standard sender.
  sendTest: () => apiClient.post('/myhr/organization-settings/email/google/test'),
};

export default myHrGoogleConnectionApi;
