import apiClient from './apiClient';
import compressFileForUpload from '../utils/compressFileForUpload';

function toFormData(file) {
  const formData = new FormData();
  formData.append('file', file);
  return formData;
}

const employerProfileApi = {
  getProfile: () => apiClient.get('/employer/profile'),
  updateProfile: (payload) => apiClient.put('/employer/profile', payload),
  uploadLogo: async (file) => apiClient.post('/employer/profile/logo', toFormData(await compressFileForUpload(file)), {
    headers: { 'Content-Type': 'multipart/form-data' },
  }),
};

export default employerProfileApi;

export async function getEmployerProfile() {
  const response = await employerProfileApi.getProfile();
  return response.data.data;
}

export async function updateEmployerProfile(profile) {
  const response = await employerProfileApi.updateProfile(profile);
  return response.data.data;
}
