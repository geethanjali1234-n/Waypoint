import apiClient from './apiClient';
import compressFileForUpload from '../utils/compressFileForUpload';

const jobSeekerResumeApi = {
  uploadResume: async (file) => {
    const formData = new FormData();
    formData.append('file', await compressFileForUpload(file));
    return apiClient.post('/job-seeker/profile/resume', formData);
  },
};

export default jobSeekerResumeApi;
