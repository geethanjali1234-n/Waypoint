import { describe, expect, it } from 'vitest';
import { formatReportingManager } from './reportingManagerLabel';

describe('formatReportingManager', () => {
  it('shows only "Full Name (Designation)" for every manager role', () => {
    expect(formatReportingManager({ name: 'Vasavi Anna', roleLabel: 'COMPANY_ADMIN' })).toBe('Vasavi Anna (Company Admin)');
    expect(formatReportingManager({ name: 'Vasavi Anna', roleLabel: 'HR' })).toBe('Vasavi Anna (HR)');
    expect(formatReportingManager({ name: 'Mani Kumar Chinthakuntla', designation: 'EMPLOYEE' })).toBe('Mani Kumar Chinthakuntla (Employee)');
  });

  it('never shows employee, login or database IDs', () => {
    const text = formatReportingManager({
      userId: 'selfreg-admin-3b33275aa0aa902ddf894fe9', employeeId: 'TARDU002', loginId: 'TARDUADMIN',
      email: 'a@b.com', name: 'vasavi anna', roleLabel: 'Company Admin',
    });
    expect(text).toBe('Vasavi Anna (Company Admin)');
    expect(text).not.toMatch(/selfreg|TARDU|@|·/);
  });

  it('fixes capitalization without damaging mixed-case names', () => {
    expect(formatReportingManager({ name: 'vasavi ANNA', roleLabel: 'HR' })).toBe('Vasavi Anna (HR)');
    expect(formatReportingManager({ name: "Sean O'Neil-McDonald", roleLabel: 'HR' })).toBe("Sean O'Neil-McDonald (HR)");
  });

  it('never falls back to an ID and says "Not assigned" when nothing real is known', () => {
    expect(formatReportingManager(null)).toBe('Not assigned');
    expect(formatReportingManager(undefined)).toBe('Not assigned');
    expect(formatReportingManager({})).toBe('Not assigned');
    expect(formatReportingManager({ userId: 'abc123', employeeId: 'TARDU002', loginId: 'TARDUADMIN' })).toBe('Not assigned');
    expect(formatReportingManager({ name: 'Vasavi Anna' })).toBe('Vasavi Anna');
    expect(formatReportingManager({ roleLabel: 'HR' })).toBe('HR');
  });
});
