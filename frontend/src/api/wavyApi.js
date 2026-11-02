import apiClient from './apiClient';

const wavyApi = {
  capabilities: () => apiClient.get('/myhr/wavy/capabilities', { timeout: 8000 }),
  chat: ({ message, route, history }) => apiClient.post('/myhr/wavy/chat', { message, route, history }, { timeout: 100000 }),
};

export default wavyApi;
