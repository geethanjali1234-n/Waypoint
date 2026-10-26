import apiClient from "./apiClient";

const notificationApi = {
    listNotifications: (limit) =>
        apiClient.get("/job-seeker/notifications", { params: limit ? { limit } : undefined }),
    getUnreadCount: () => apiClient.get("/job-seeker/notifications/unread-count"),
    markAsRead: (notificationId) => apiClient.patch(`/job-seeker/notifications/${notificationId}/read`),
    markAllAsRead: () => apiClient.patch("/job-seeker/notifications/read-all"),
};

export default notificationApi;
