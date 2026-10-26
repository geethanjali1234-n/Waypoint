import apiClient from './apiClient';

const myHrReportApi = {
  getWorkforceReport: (periodDays) =>
    apiClient.get('/myhr/reports/workforce', { params: { periodDays } }),
  getEmployeeHoursReport: (employeeId, startDate, endDate) =>
    apiClient.get('/myhr/reports/employee-hours', { params: { employeeId, startDate, endDate } }),
  searchEmployees: (query, { managerOnly = false, limit = 10 } = {}) =>
    apiClient.get('/myhr/reports/employees/search', {
      params: { q: query, managerOnly, limit },
    }),
  getTimesheetReport: (params) =>
    apiClient.get('/myhr/reports/timesheets', { params }),
};

export default myHrReportApi;
