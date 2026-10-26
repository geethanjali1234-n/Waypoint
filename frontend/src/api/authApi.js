import apiClient from "./apiClient";

const authApi = {
    registerEmployer: (payload) => apiClient.post("/auth/register/employer", payload),
    registerJobSeeker: (payload) => apiClient.post("/auth/register/job-seeker", payload),
    verifyOtp: (payload) => apiClient.post("/auth/verify-otp", payload),
    // With a logo the backend takes multipart: the JSON "request" part plus the "logo" file.
    startOrganizationRegistration: (payload, logo) => {
        if (!logo) return apiClient.post("/auth/organization-registration", payload);
        const body = new FormData();
        body.append("request", new Blob([JSON.stringify(payload)], { type: "application/json" }));
        body.append("logo", logo);
        return apiClient.post("/auth/organization-registration", body);
    },
    resendOrganizationRegistrationOtp: (registrationId) =>
        apiClient.post(`/auth/organization-registration/resend/${encodeURIComponent(registrationId)}`),
    completeOrganizationRegistration: (payload) =>
        apiClient.post("/auth/organization-registration/verify", payload),
    login: (payload) => apiClient.post("/auth/login", payload),
    changePassword: (payload) => apiClient.patch("/auth/change-password", payload),
    changeEmail: (payload) => apiClient.patch("/auth/change-email", payload),
    changeFirstLoginPassword: (payload) => apiClient.patch("/auth/first-login/change-password", payload),
    forgotPassword: (payload) => apiClient.post("/auth/forgot-password", payload),
    verifyResetOtp: (payload) => apiClient.post("/auth/verify-reset-otp", payload),
    resetPassword: (payload) => apiClient.post("/auth/reset-password", payload),
};

export default authApi;
