import apiClient from "./apiClient";

const employerNotificationApi = {
    listNotifications: (limit) =>
        apiClient.get("/employer/notifications", { params: limit ? { limit } : undefined }),
    getUnreadCount: () => apiClient.get("/employer/notifications/unread-count"),
    markAsRead: (notificationId) => apiClient.patch(`/employer/notifications/${notificationId}/read`),
    markAllAsRead: () => apiClient.patch("/employer/notifications/read-all"),
};

export default employerNotificationApi;
