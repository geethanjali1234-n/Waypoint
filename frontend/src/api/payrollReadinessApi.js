import apiClient from './apiClient';

const payrollReadinessApi = {
  getReadiness: (params) => apiClient.get('/myhr/payroll-readiness', { params }),
  sendReminder: (employeeUserId, payrollMonth) => apiClient.post(`/myhr/payroll-readiness/${employeeUserId}/reminders`, null, { params: { payrollMonth } }),
  sendBulkReminders: (payload, payrollMonth) => apiClient.post('/myhr/payroll-readiness/reminders/bulk', payload, { params: { payrollMonth } }),
};

export default payrollReadinessApi;
