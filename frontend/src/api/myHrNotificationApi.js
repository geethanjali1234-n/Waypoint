import apiClient from './apiClient';

const myHrNotificationApi = {
  listNotifications: (limit) =>
    apiClient.get('/myhr/notifications', { params: limit ? { limit } : undefined, timeout: 15000 }),
  getUnreadCount: () => apiClient.get('/myhr/notifications/unread-count', { timeout: 15000 }),
  markAsRead: (notificationId) => apiClient.patch(`/myhr/notifications/${notificationId}/read`, null, { timeout: 15000 }),
  markAllAsRead: () => apiClient.patch('/myhr/notifications/read-all', null, { timeout: 15000 }),
};

export default myHrNotificationApi;
