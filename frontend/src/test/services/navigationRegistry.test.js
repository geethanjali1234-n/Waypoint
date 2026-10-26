import { describe, expect, it } from 'vitest';
import {
  CATEGORIES,
  ROLES,
  getRoleRouteSegment,
  navigationRegistry,
  resolveEffectiveRole,
} from '../../services/navigation/navigationRegistry';

describe('navigationRegistry and RBAC Strategy', () => {
  describe('getRoleRouteSegment', () => {
    it('returns proper URL route segments', () => {
      expect(getRoleRouteSegment(ROLES.COMPANY_ADMIN)).toBe('admin');
      expect(getRoleRouteSegment(ROLES.HR)).toBe('hr');
      expect(getRoleRouteSegment(ROLES.EMPLOYEE)).toBe('employee');
    });
  });

  describe('resolveEffectiveRole (Defense-in-Depth)', () => {
    it('grants COMPANY_ADMIN only when authenticated as COMPANY_ADMIN', () => {
      const adminSession = { userId: 'admin-1', role: ROLES.COMPANY_ADMIN };
      expect(resolveEffectiveRole('/myhr/admin/dashboard', adminSession)).toBe(ROLES.COMPANY_ADMIN);

      // Attempted path privilege escalation by an HR user
      const hrSession = { userId: 'hr-1', role: ROLES.HR };
      expect(resolveEffectiveRole('/myhr/admin/dashboard', hrSession)).toBe(ROLES.HR);
    });

    it('scopes role to HR when browsing /myhr/hr/*', () => {
      const hrSession = { userId: 'hr-1', role: ROLES.HR };
      expect(resolveEffectiveRole('/myhr/hr/timesheets', hrSession)).toBe(ROLES.HR);
    });

    it('scopes role to EMPLOYEE when browsing /myhr/employee/*', () => {
      const empSession = { userId: 'emp-1', role: ROLES.EMPLOYEE };
      expect(resolveEffectiveRole('/myhr/employee/timesheets', empSession)).toBe(ROLES.EMPLOYEE);
    });
  });

  describe('Role-wise Action Permissions (RBAC)', () => {
    it('never grants Audit History or Organization Settings to HR', () => {
      const hrActions = navigationRegistry.getActions(ROLES.HR);
      const actionIds = hrActions.map((a) => a.id);

      expect(actionIds).not.toContain('audit-log');
      expect(actionIds).not.toContain('org-settings');
      expect(hrActions.some((a) => a.category === CATEGORIES.ADMINISTRATION)).toBe(false);
    });

    it('grants Audit History and Organization Settings strictly to COMPANY_ADMIN', () => {
      const adminActions = navigationRegistry.getActions(ROLES.COMPANY_ADMIN);
      const actionIds = adminActions.map((a) => a.id);

      expect(actionIds).toContain('audit-log');
      expect(actionIds).toContain('org-settings');

      const auditAction = adminActions.find((a) => a.id === 'audit-log');
      expect(auditAction.path).toBe('/myhr/admin/audit');

      const orgAction = adminActions.find((a) => a.id === 'org-settings');
      expect(orgAction.path).toBe('/myhr/admin/organization');
    });

    it('limits EMPLOYEE strictly to individual self-service modules', () => {
      const empActions = navigationRegistry.getActions(ROLES.EMPLOYEE);
      const actionIds = empActions.map((a) => a.id);

      expect(actionIds).toEqual([
        'timesheets-enter',
        'doc-requests-all',
        'doc-request-self',
        'profile-changes',
        'my-profile',
      ]);
      expect(actionIds).not.toContain('employees-list');
      expect(actionIds).not.toContain('timesheets-approvals');
      expect(actionIds).not.toContain('reports-catalog');
      expect(actionIds).not.toContain('payroll-readiness');
      expect(actionIds).not.toContain('audit-log');
      expect(actionIds).not.toContain('org-settings');
    });

    it('resolves correct route paths per role', () => {
      const hrActions = navigationRegistry.getActions(ROLES.HR);
      const hrTimesheets = hrActions.find((a) => a.id === 'timesheets-enter');
      expect(hrTimesheets.path).toBe('/myhr/hr/timesheets');

      const adminActions = navigationRegistry.getActions(ROLES.COMPANY_ADMIN);
      const adminTimesheets = adminActions.find((a) => a.id === 'timesheets-enter');
      expect(adminTimesheets.path).toBe('/myhr/admin/timesheets');
    });
  });
});
