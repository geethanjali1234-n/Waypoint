import apiClient from "./apiClient";

const employerDashboardApi = {
    getEmployerDashboard: () => apiClient.get("/employer/dashboard"),
};

export default employerDashboardApi;
