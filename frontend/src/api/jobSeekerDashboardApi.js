import apiClient from "./apiClient";

const jobSeekerDashboardApi = {
    getDashboard: () => apiClient.get("/job-seeker/dashboard"),
};

export default jobSeekerDashboardApi;
