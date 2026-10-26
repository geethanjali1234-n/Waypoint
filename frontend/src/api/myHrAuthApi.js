import apiClient from './apiClient';

const myHrAuthApi = {
  currentUser: () => apiClient.get('/myhr/auth/me'),
};

export default myHrAuthApi;
