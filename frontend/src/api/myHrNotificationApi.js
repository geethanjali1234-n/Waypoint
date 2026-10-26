import apiClient from './apiClient';

const myHrNotificationApi = {
  listNotifications: (limit) =>
    apiClient.get('/myhr/notifications', { params: limit ? { limit } : undefined }),
  getUnreadCount: () => apiClient.get('/myhr/notifications/unread-count'),
  markAsRead: (notificationId) => apiClient.patch(`/myhr/notifications/${notificationId}/read`),
  markAllAsRead: () => apiClient.patch('/myhr/notifications/read-all'),
};

export default myHrNotificationApi;
