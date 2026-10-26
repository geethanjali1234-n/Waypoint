import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import TimesheetsPage from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import { getPeriodStandardHours, isRecordCleanAndAuditReady } from '../../../pages/myhr/features/timesheets/timesheetPeriodRules';
import timesheetApi from '../../../api/timesheetApi';
import { saveMyHrSession } from '../../../auth/myHrSession';

vi.mock('../../../api/timesheetApi', () => ({
  default: {
    list: vi.fn(),
    create: vi.fn(),
    getSettings: vi.fn().mockResolvedValue({ data: { data: { payrollPeriod: 'WEEKLY' } } }),
    review: vi.fn(),
  },
}));
vi.mock('../../../api/documentApi', () => ({
  default: {
    upload: vi.fn(),
    parseTimesheet: vi.fn(),
    download: vi.fn(),
  },
}));
vi.mock('../../../api/myHrEmployeeApi', () => ({
  default: {
    getMine: vi.fn().mockResolvedValue({ data: { data: {} } }),
    reportingManagers: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
}));

describe('Express Clearance Calculation & Redirection Logic', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    timesheetApi.getSettings.mockResolvedValue({ data: { data: { payrollPeriod: 'WEEKLY' } } });
  });

  describe('getPeriodStandardHours', () => {
    it('calculates 40 hours for a standard 1-week period (Mon-Sun: 5 business days)', () => {
      // 2026-09-07 is Monday, 2026-09-13 is Sunday
      const hours = getPeriodStandardHours('2026-09-07', '2026-09-13');
      expect(hours).toBe(40);
    });

    it('calculates 40 hours for a 5-day Mon-Fri period', () => {
      const hours = getPeriodStandardHours('2026-09-07', '2026-09-11');
      expect(hours).toBe(40);
    });

    it('calculates 80 hours for a standard 2-week biweekly period (10 business days)', () => {
      const hours = getPeriodStandardHours('2026-09-07', '2026-09-20');
      expect(hours).toBe(80);
    });

    it('calculates 176 hours for a 30-day month with 22 business days (September 2026)', () => {
      // Sep 1 is Tuesday, Sep 30 is Wednesday: 22 weekdays
      const hours = getPeriodStandardHours('2026-09-01', '2026-09-30');
      expect(hours).toBe(176);
    });

    it('falls back to 40 hours if dates are missing or invalid', () => {
      expect(getPeriodStandardHours(null, null)).toBe(40);
      expect(getPeriodStandardHours('invalid', 'dates')).toBe(40);
      expect(getPeriodStandardHours('2026-09-10', '2026-09-05')).toBe(40);
    });
  });

  describe('isRecordCleanAndAuditReady', () => {
    it('approves a clean standard 40h weekly submission', () => {
      const rec = {
        id: 'rec-1',
        status: 'SUBMITTED',
        periodStartDate: '2026-09-07',
        periodEndDate: '2026-09-13',
        totalHours: 40,
        entries: [
          { workDate: '2026-09-07', hoursWorked: 8 },
          { workDate: '2026-09-08', hoursWorked: 8 },
          { workDate: '2026-09-09', hoursWorked: 8 },
          { workDate: '2026-09-10', hoursWorked: 8 },
          { workDate: '2026-09-11', hoursWorked: 8 },
        ],
      };
      expect(isRecordCleanAndAuditReady(rec)).toBe(true);
    });

    it('rejects a weekly submission with overtime (> 40h)', () => {
      const rec = {
        id: 'rec-2',
        status: 'SUBMITTED',
        periodStartDate: '2026-09-07',
        periodEndDate: '2026-09-13',
        totalHours: 48,
        entries: [
          { workDate: '2026-09-07', hoursWorked: 10 },
          { workDate: '2026-09-08', hoursWorked: 10 },
          { workDate: '2026-09-09', hoursWorked: 10 },
          { workDate: '2026-09-10', hoursWorked: 10 },
          { workDate: '2026-09-11', hoursWorked: 8 },
        ],
      };
      expect(isRecordCleanAndAuditReady(rec)).toBe(false);
    });

    it('rejects a submission with daily overtime even if total is 40h (e.g. 10h on one day)', () => {
      const rec = {
        id: 'rec-3',
        status: 'SUBMITTED',
        periodStartDate: '2026-09-07',
        periodEndDate: '2026-09-13',
        totalHours: 40,
        entries: [
          { workDate: '2026-09-07', hoursWorked: 10 },
          { workDate: '2026-09-08', hoursWorked: 6 },
          { workDate: '2026-09-09', hoursWorked: 8 },
          { workDate: '2026-09-10', hoursWorked: 8 },
          { workDate: '2026-09-11', hoursWorked: 8 },
        ],
      };
      expect(isRecordCleanAndAuditReady(rec)).toBe(false);
    });

    it('correctly approves a full monthly submission (e.g. 160h for 22 business days)', () => {
      const rec = {
        id: 'rec-monthly-clean',
        status: 'SUBMITTED',
        periodStartDate: '2026-09-01',
        periodEndDate: '2026-09-30',
        totalHours: 160,
        entries: Array.from({ length: 20 }, (_, i) => ({
          workDate: `2026-09-${String(i + 1).padStart(2, '0')}`,
          hoursWorked: 8,
        })),
      };
      expect(isRecordCleanAndAuditReady(rec)).toBe(true);
    });

    it('rejects a submission that has a change conflict reason (requires manual review)', () => {
      const rec = {
        id: 'rec-reedit',
        status: 'SUBMITTED',
        periodStartDate: '2026-09-07',
        periodEndDate: '2026-09-13',
        totalHours: 40,
        changeReason: 'Corrected hours per manager rejection',
        entries: [
          { workDate: '2026-09-07', hoursWorked: 8 },
        ],
      };
      expect(isRecordCleanAndAuditReady(rec)).toBe(false);
    });

    it('rejects records that are not in SUBMITTED status', () => {
      expect(isRecordCleanAndAuditReady({ status: 'DRAFT', totalHours: 40 })).toBe(false);
      expect(isRecordCleanAndAuditReady({ status: 'APPROVED', totalHours: 40 })).toBe(false);
      expect(isRecordCleanAndAuditReady({ status: 'REJECTED', totalHours: 40 })).toBe(false);
    });
  });

  describe('Route and Subtab Redirection', () => {
    it('redirects directly to Required Actions workspace and displays the 1-Tap Express bar when url has ?view=team&subtab=actions', async () => {
      saveMyHrSession({ userId: 'hr-user-1', loginId: 'hr@example.com', role: 'HR' });

      const pendingRec = {
        id: 'ts-pending-1',
        employeeUserId: 'emp-jordan-1',
        employeeId: 'EMP-001',
        employeeName: 'Jordan Lee',
        employeeRole: 'EMPLOYEE',
        reportingManagerUserId: 'hr-user-1',
        reportingManagerName: 'Alex HR',
        status: 'SUBMITTED',
        totalHours: 40,
        periodStartDate: '2026-09-07',
        periodEndDate: '2026-09-13',
        entries: [
          { workDate: '2026-09-07', hoursWorked: 8 },
          { workDate: '2026-09-08', hoursWorked: 8 },
          { workDate: '2026-09-09', hoursWorked: 8 },
          { workDate: '2026-09-10', hoursWorked: 8 },
          { workDate: '2026-09-11', hoursWorked: 8 },
        ],
      };

      timesheetApi.list.mockResolvedValue({
        data: { data: [pendingRec] },
      });

      render(
        <MemoryRouter initialEntries={['/myhr/hr/timesheets?view=team&subtab=actions']}>
          <Routes>
            <Route path="/myhr/:role/timesheets" element={<TimesheetsPage role="HR" />} />
          </Routes>
        </MemoryRouter>
      );

      // Verify that Required Actions tab is active
      const actionsTabBtn = await screen.findByRole('button', { name: /Required Actions/i });
      expect(actionsTabBtn).toHaveAttribute('aria-pressed', 'true');

      // Verify that the 1-Tap Express bar is rendered
      expect(await screen.findByRole('region', { name: /1-Tap Express Approval/i })).toBeInTheDocument();
      expect(screen.getByText('1 Clean Submission Verified & Audit-Ready')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Express Approve Clean \(1\)/i })).toBeInTheDocument();
    });
  });
});
