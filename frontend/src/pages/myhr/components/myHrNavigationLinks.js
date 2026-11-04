// Role-based My HR links, shared by the desktop sidebar and the phone drawer.
export const ROLE_LINKS = {
  COMPANY_ADMIN: [['employees', 'Employee Management'], ['profile-change-requests', 'Profile Change Requests'], ['timesheets', 'Timesheets'], ['document-requests', 'Request Documents'], ['reports', 'Reports'], ['audit', 'Audit History'], ['organization', 'Organization Settings'], ['payroll-readiness', 'Payroll Readiness'], ['offer-letter-approvals', 'Document Approvals']],
  HR: [['employees', 'Employee Management'], ['profile-change-requests', 'Profile Change Requests'], ['timesheets', 'Timesheets'], ['document-requests', 'Request Documents'], ['reports', 'Reports'], ['payroll-readiness', 'Payroll Readiness']],
  EMPLOYEE: [['profile', 'My Profile'], ['profile-change-requests', 'Profile Change Requests'], ['timesheets', 'My Timesheets'], ['document-requests', 'Document Requests']],
};

// Same base path the sidebar uses; shared with the phone drawer.
export function myHrBasePath(role) {
  return `/myhr/${role === 'COMPANY_ADMIN' ? 'admin' : role === 'HR' ? 'hr' : 'employee'}`;
}
