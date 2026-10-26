import apiClient from "./apiClient";
import compressFileForUpload from "../utils/compressFileForUpload";

function toFormData(file) {
    const formData = new FormData();
    formData.append("file", file);
    return formData;
}

const jobSeekerProfileApi = {
    getProfile: () => apiClient.get("/job-seeker/profile"),
    updateProfile: (payload) => apiClient.put("/job-seeker/profile", payload),
    uploadPhoto: async (file) =>
        apiClient.post("/job-seeker/profile/photo", toFormData(await compressFileForUpload(file)), {
            headers: { "Content-Type": "multipart/form-data" },
        }),
    uploadResume: async (file) =>
        apiClient.post("/job-seeker/profile/resume", toFormData(await compressFileForUpload(file)), {
            headers: { "Content-Type": "multipart/form-data" },
        }),
};

export default jobSeekerProfileApi;
