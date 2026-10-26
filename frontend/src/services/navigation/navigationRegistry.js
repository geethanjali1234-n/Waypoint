/**
 * Navigation Action Registry & RBAC Strategy
 * Follows OOP (Encapsulation, Polymorphism) and SOLID principles:
 * - Single Responsibility: Manages system navigation targets and role-based permissions.
 * - Open/Closed: New navigation actions can be registered without modifying consuming UI components.
 * - Liskov Substitution & Interface Segregation: All actions adhere to the NavigationAction interface.
 */

export const ROLES = Object.freeze({
  COMPANY_ADMIN: 'COMPANY_ADMIN',
  HR: 'HR',
  EMPLOYEE: 'EMPLOYEE',
});

export const CATEGORIES = Object.freeze({
  TIMESHEETS: 'Timesheets',
  DOCUMENTS: 'Documents',
  WORKFORCE: 'Workforce',
  PROFILE: 'Profile',
  PAYROLL: 'Payroll',
  REPORTS: 'Reports',
  ADMINISTRATION: 'Administration',
});

/**
 * Normalizes role string to standard URL route segment
 * @param {string} role
 * @returns {string} 'admin' | 'hr' | 'employee'
 */
export function getRoleRouteSegment(role) {
  if (role === ROLES.COMPANY_ADMIN) return 'admin';
  if (role === ROLES.HR) return 'hr';
  return 'employee';
}

/**
 * Contextual Role Resolver with Defense-in-Depth validation
 * @param {string} pathname
 * @param {object|null} session
 * @returns {string} canonical Role
 */
export function resolveEffectiveRole(pathname = '', session = null) {
  const sessionRole = session?.role || ROLES.EMPLOYEE;

  // Contextual route segment check
  if (pathname.startsWith('/myhr/admin')) {
    // Only grant COMPANY_ADMIN permissions if session truly has COMPANY_ADMIN role
    return sessionRole === ROLES.COMPANY_ADMIN ? ROLES.COMPANY_ADMIN : sessionRole;
  }
  if (pathname.startsWith('/myhr/hr')) {
    // If admin is browsing HR workspace, scope actions to HR context
    return sessionRole === ROLES.EMPLOYEE ? ROLES.EMPLOYEE : ROLES.HR;
  }
  if (pathname.startsWith('/myhr/employee')) {
    return ROLES.EMPLOYEE;
  }

  return sessionRole;
}

/**
 * Immutable Navigation Action Definition
 */
export class NavigationAction {
  constructor({
    id,
    category,
    icon,
    title,
    subtitle,
    allowedRoles,
    pathResolver,
    keywords = [],
  }) {
    this.id = id;
    this.category = category;
    this.icon = icon;
    this.title = title;
    this.subtitle = subtitle;
    this.allowedRoles = new Set(allowedRoles);
    this.pathResolver = pathResolver;
    this.keywords = keywords;
  }

  isPermittedFor(role) {
    return this.allowedRoles.has(role);
  }

  resolvePath(role) {
    const segment = getRoleRouteSegment(role);
    return typeof this.pathResolver === 'function'
      ? this.pathResolver(segment, role)
      : this.pathResolver;
  }

  matches(query) {
    if (!query) return true;
    const q = query.trim().toLowerCase();
    return (
      this.title.toLowerCase().includes(q) ||
      this.subtitle.toLowerCase().includes(q) ||
      this.category.toLowerCase().includes(q) ||
      this.keywords.some((kw) => kw.toLowerCase().includes(q))
    );
  }
}

/**
 * Navigation Registry Service (Singleton Registry Pattern)
 */
class NavigationRegistryService {
  constructor() {
    this.actions = [
      // 1. Timesheets
      new NavigationAction({
        id: 'timesheets-enter',
        category: CATEGORIES.TIMESHEETS,
        icon: '⏱️',
        title: 'Enter Timesheet',
        subtitle: 'Log hours for active pay period',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR, ROLES.EMPLOYEE],
        pathResolver: (seg) => `/myhr/${seg}/timesheets`,
        keywords: ['hours', 'time', 'clock', 'log'],
      }),
      new NavigationAction({
        id: 'timesheets-approvals',
        category: CATEGORIES.TIMESHEETS,
        icon: '✅',
        title: 'Review Timesheet Approvals',
        subtitle: 'Review & approve submitted team hours',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR],
        pathResolver: (seg) => `/myhr/${seg}/timesheets?view=team`,
        keywords: ['approve', 'reject', 'pending', 'team', 'submissions'],
      }),

      // 2. Documents
      new NavigationAction({
        id: 'doc-requests-all',
        category: CATEGORIES.DOCUMENTS,
        icon: '📄',
        title: 'Document Requests',
        subtitle: 'View tracked documents and requests',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR, ROLES.EMPLOYEE],
        pathResolver: (seg) => `/myhr/${seg}/document-requests`,
        keywords: ['files', 'letters', 'forms', 'records'],
      }),
      new NavigationAction({
        id: 'doc-request-employee',
        category: CATEGORIES.DOCUMENTS,
        icon: '➕',
        title: 'Request from Employee',
        subtitle: 'Ask an employee to provide documents',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR],
        pathResolver: (seg) => `/myhr/${seg}/document-requests/new`,
        keywords: ['onboarding', 'tax', 'w4', 'i9', 'benefits'],
      }),
      new NavigationAction({
        id: 'doc-request-self',
        category: CATEGORIES.DOCUMENTS,
        icon: '👤',
        title: 'Request for Myself',
        subtitle: 'Ask your manager for a letter or document',
        allowedRoles: [ROLES.HR, ROLES.EMPLOYEE],
        pathResolver: (seg) => `/myhr/${seg}/document-requests/request-from-manager`,
        keywords: ['experience letter', 'relieving', 'verification', 'manager'],
      }),

      // 3. Workforce Management
      new NavigationAction({
        id: 'employees-list',
        category: CATEGORIES.WORKFORCE,
        icon: '👥',
        title: 'Employee Directory',
        subtitle: 'Search and manage employee records',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR],
        pathResolver: (seg) => `/myhr/${seg}/employees`,
        keywords: ['people', 'staff', 'roster', 'directory', 'reports'],
      }),

      // 4. Profile Change Requests
      new NavigationAction({
        id: 'profile-changes',
        category: CATEGORIES.PROFILE,
        icon: '📝',
        title: 'Profile Change Requests',
        subtitle: 'Review personal details and updates',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR, ROLES.EMPLOYEE],
        pathResolver: (seg) => `/myhr/${seg}/profile-change-requests`,
        keywords: ['address', 'name', 'phone', 'contact', 'changes'],
      }),

      // 5. Payroll Readiness
      new NavigationAction({
        id: 'payroll-readiness',
        category: CATEGORIES.PAYROLL,
        icon: '💳',
        title: 'Payroll Readiness',
        subtitle: 'Validate timesheets and readiness gate',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR],
        pathResolver: (seg) => `/myhr/${seg}/payroll-readiness`,
        keywords: ['pay', 'salary', 'timesheet ready', 'reminders'],
      }),

      // 6. Reports
      new NavigationAction({
        id: 'reports-catalog',
        category: CATEGORIES.REPORTS,
        icon: '📊',
        title: 'Timesheet & Workforce Reports',
        subtitle: 'Bounded reports, Excel & PDF export',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR],
        pathResolver: (seg) => `/myhr/${seg}/reports`,
        keywords: ['analytics', 'charts', 'export', 'excel', 'pdf'],
      }),

      // 7. Administration (STRICT COMPANY_ADMIN ONLY)
      new NavigationAction({
        id: 'offer-letter-approvals',
        category: CATEGORIES.ADMINISTRATION,
        icon: '📝',
        title: 'Document Approvals',
        subtitle: 'Review and approve documents submitted by HR',
        allowedRoles: [ROLES.COMPANY_ADMIN],
        pathResolver: () => '/myhr/admin/offer-letter-approvals',
        keywords: ['offer', 'document', 'approval', 'approve', 'hr', 'pending'],
      }),
      new NavigationAction({
        id: 'audit-log',
        category: CATEGORIES.ADMINISTRATION,
        icon: '📜',
        title: 'Audit History',
        subtitle: 'Immutable timeline of compliance actions',
        allowedRoles: [ROLES.COMPANY_ADMIN],
        pathResolver: () => '/myhr/admin/audit',
        keywords: ['compliance', 'security', 'logs', 'audit trail'],
      }),
      new NavigationAction({
        id: 'org-settings',
        category: CATEGORIES.ADMINISTRATION,
        icon: '🏢',
        title: 'Organization Settings',
        subtitle: 'Company policies, payroll frequency and branding',
        allowedRoles: [ROLES.COMPANY_ADMIN],
        pathResolver: () => '/myhr/admin/organization',
        keywords: ['company', 'branding', 'logo', 'policy', 'settings'],
      }),

      // 8. Personal Profile
      new NavigationAction({
        id: 'my-profile',
        category: CATEGORIES.PROFILE,
        icon: '👤',
        title: 'My Profile',
        subtitle: 'View your profile details and settings',
        allowedRoles: [ROLES.COMPANY_ADMIN, ROLES.HR, ROLES.EMPLOYEE],
        pathResolver: (seg) => `/myhr/${seg}/profile`,
        keywords: ['account', 'user', 'personal', 'me'],
      }),
    ];
  }

  /**
   * Retrieves navigation actions permitted for a given role and matching an optional search query.
   * @param {string} role
   * @param {string} query
   * @returns {Array<{ id: string, category: string, icon: string, title: string, subtitle: string, path: string }>}
   */
  getActions(role, query = '') {
    return this.actions
      .filter((action) => action.isPermittedFor(role) && action.matches(query))
      .map((action) => ({
        id: action.id,
        category: action.category,
        icon: action.icon,
        title: action.title,
        subtitle: action.subtitle,
        path: action.resolvePath(role),
      }));
  }
}

export const navigationRegistry = new NavigationRegistryService();
export default navigationRegistry;
