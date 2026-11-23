/**
 * Page keys the Wavi backend sends with an answer (MyHrChatResponse.actions), turned into the signed-in role's own
 * routes. The backend decides which pages an answer points to; this table only knows where each page lives for each
 * role, mirroring MyHrConversationIntents.Page and the routes in AppRouter.jsx. A key a role does not have, or one
 * this table does not know, gives no button.
 */
const PAGES = {
  DOCUMENT_REQUESTS: { label: 'Document Requests', EMPLOYEE: '/myhr/employee/document-requests', HR: '/myhr/hr/document-requests', COMPANY_ADMIN: '/myhr/admin/document-requests' },
  TIMESHEETS: { label: 'Timesheets', EMPLOYEE: '/myhr/employee/timesheets', HR: '/myhr/hr/timesheets', COMPANY_ADMIN: '/myhr/admin/timesheets' },
  MY_PROFILE: { label: 'My Profile', EMPLOYEE: '/myhr/employee/profile', HR: '/myhr/hr/profile', COMPANY_ADMIN: '/myhr/admin/profile' },
  PROFILE_CHANGE_REQUESTS: { label: 'Profile Change Requests', EMPLOYEE: '/myhr/employee/profile-change-requests', HR: '/myhr/hr/profile-change-requests', COMPANY_ADMIN: '/myhr/admin/profile-change-requests' },
  DASHBOARD: { label: 'Dashboard', EMPLOYEE: '/myhr/employee/dashboard', HR: '/myhr/hr/dashboard', COMPANY_ADMIN: '/myhr/admin/dashboard' },
  EMPLOYEES: { label: 'Employees', HR: '/myhr/hr/employees', COMPANY_ADMIN: '/myhr/admin/employees' },
  REPORTS: { label: 'Reports', HR: '/myhr/hr/reports', COMPANY_ADMIN: '/myhr/admin/reports' },
  KNOWLEDGE_BASE: { label: 'Knowledge Base', HR: '/myhr/hr/knowledge-base', COMPANY_ADMIN: '/myhr/admin/knowledge-base' },
  PAYROLL_READINESS: { label: 'Payroll Readiness', HR: '/myhr/hr/payroll-readiness', COMPANY_ADMIN: '/myhr/admin/payroll-readiness' },
  DOCUMENT_APPROVALS: { label: 'Document Approvals', COMPANY_ADMIN: '/myhr/admin/offer-letter-approvals' },
  AUDIT_LOG: { label: 'Audit Log', COMPANY_ADMIN: '/myhr/admin/audit' },
  ORGANIZATION_SETTINGS: { label: 'Organization Settings', COMPANY_ADMIN: '/myhr/admin/organization' },
};

export const WAVI_PAGE_KEYS = Object.keys(PAGES);

export function waviPagePath(key, role) {
  return Object.hasOwn(PAGES, key) ? PAGES[key][role] ?? null : null;
}

/** At most three buttons, each a real page the role has; unknown or unavailable keys are dropped. */
export function resolveWaviActions(keys, role, query = '') {
  if (!Array.isArray(keys)) return [];
  const seen = new Set();
  return keys.filter((key) => typeof key === 'string' && !seen.has(key) && seen.add(key))
    .map((key) => {
      const path = waviPagePath(key, role);
      const letterRequest = key === 'DOCUMENT_REQUESTS' && role === 'EMPLOYEE'
        && /\b(?:letter|letters)\b/i.test(query);
      const expressClearance = key === 'TIMESHEETS' && ['HR', 'COMPANY_ADMIN'].includes(role)
        && /\b(?:express clearance|fast[- ]pass|1[- ]tap)\b/i.test(query);
      return path ? {
        label: letterRequest ? 'Request Letter' : expressClearance ? 'Review & Clear Timesheets' : PAGES[key].label,
        path: expressClearance ? `${path}?view=team&subtab=actions` : path,
      } : null;
    })
    .filter(Boolean)
    .slice(0, 3);
}
