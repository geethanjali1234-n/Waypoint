import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ReportsPage from '../../../pages/myhr/features/reports/ReportsPage';
import myHrReportApi from '../../../api/myHrReportApi';
import timesheetApi from '../../../api/timesheetApi';
import * as reportExport from '../../../pages/myhr/features/reports/reportExport';

vi.mock('../../../api/myHrReportApi', () => ({
  default: {
    getWorkforceReport: vi.fn(),
    getEmployeeHoursReport: vi.fn(),
    searchEmployees: vi.fn(),
    getTimesheetReport: vi.fn(),
  },
}));

vi.mock('../../../api/timesheetApi', () => ({
  default: { get: vi.fn() },
}));

vi.mock('../../../pages/myhr/features/reports/reportExport', () => ({
  downloadReportCsv: vi.fn(),
  downloadReportExcel: vi.fn(),
  downloadReportPdf: vi.fn(),
}));

const mockWorkforceOrg = {
  scope: 'ORGANIZATION',
  totalEmployees: 10,
  activeEmployees: 8,
  inactiveEmployees: 2,
  totalManagers: 2,
  employees: [
    { employeeId: 'TD001', name: 'Alice Smith', designation: 'Lead Engineer', reportingManagerName: 'Bob Jones', accountStatus: 'ACTIVE' },
    { employeeId: 'TD025', name: 'Sarah HR', designation: 'HR Partner', reportingManagerName: 'Bob Jones', accountStatus: 'ACTIVE' },
  ],
  managerSummary: [{ managerName: 'Bob Jones', directReportCount: 5 }],
};

const mockTimesheetReport = {
  content: [
    {
      id: 'ts-1',
      employeeUserId: 'user-1',
      employeeId: 'TD025',
      employeeName: 'Sarah HR',
      employeeRole: 'HR',
      designation: 'HR Partner',
      reportingManagerName: 'Bob Jones',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      totalHours: 42,
      status: 'SUBMITTED',
      issues: ['PENDING_APPROVAL_3_DAYS'],
      submittedAt: '2026-09-20T10:00:00',
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
  summary: { employees: 1, timesheets: 1, totalWorkedHours: 42, awaitingApproval: 1, approved: 0, rejected: 0, draft: 0, exceptions: 1 },
  attentionRequired: [
    { issue: 'REJECTED', label: 'Rejected', count: 0 },
    { issue: 'PENDING_APPROVAL_3_DAYS', label: 'Pending approval > 3 days', count: 1 },
  ],
};

function renderReportsPage(role = 'COMPANY_ADMIN') {
  return render(<MemoryRouter><ReportsPage role={role} /></MemoryRouter>);
}

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));
    vi.clearAllMocks();
    myHrReportApi.getWorkforceReport.mockResolvedValue({ data: { data: mockWorkforceOrg } });
    myHrReportApi.getTimesheetReport.mockResolvedValue({ data: { data: mockTimesheetReport } });
    timesheetApi.get.mockResolvedValue({ data: { data: { ...mockTimesheetReport.content[0], entries: [{ workDate: '2026-09-01', dayOfWeek: 'Tuesday', hoursWorked: 8 }] } } });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders report catalog with Workforce Overview and Timesheet Report cards', () => {
    renderReportsPage('COMPANY_ADMIN');
    expect(screen.getByText('Workforce Overview')).toBeInTheDocument();
    expect(screen.getByText('Timesheet Report')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /run report/i })).toHaveLength(2);
  });

  it('omits workforce totals when the endpoint has not supplied them', async () => {
    myHrReportApi.getWorkforceReport.mockResolvedValue({ data: { data: { scope: 'ORGANIZATION' } } });
    const user = userEvent.setup();
    renderReportsPage('COMPANY_ADMIN');
    await user.click(screen.getAllByRole('button', { name: /run report/i })[0]);
    await screen.findByRole('heading', { level: 2, name: 'Workforce Overview' });
    expect(screen.queryByText('Total Employees')).not.toBeInTheDocument();
    expect(screen.queryByText('Active Employees')).not.toBeInTheDocument();
    expect(screen.queryByText('Inactive Employees')).not.toBeInTheDocument();
    expect(screen.queryByText('Reporting Managers')).not.toBeInTheDocument();
    expect(screen.queryByText('Avg Team Size')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Workforce Status' })).not.toBeInTheDocument();
    expect(await screen.findByText('No employee data available yet.')).toBeInTheDocument();
  });

  it('opens Workforce Overview report, displays KPIs, and triggers PDF export', async () => {
    const user = userEvent.setup();
    renderReportsPage('COMPANY_ADMIN');
    await waitFor(() => expect(myHrReportApi.getWorkforceReport).toHaveBeenCalled());
    await user.click(screen.getAllByRole('button', { name: /run report/i })[0]);
    expect(screen.getByRole('heading', { level: 2, name: 'Workforce Overview' })).toBeInTheDocument();
    expect(screen.getByText('Total Employees')).toBeInTheDocument();
    expect(screen.getByText('Alice Smith')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /download/i }));
    await user.click(screen.getByRole('menuitem', { name: /download as pdf/i }));
    expect(reportExport.downloadReportPdf).toHaveBeenCalledWith(expect.objectContaining({ title: expect.stringContaining('Workforce Overview') }));
  });

  it('generates a paginated timesheet report and opens review details', async () => {
    const user = userEvent.setup();
    renderReportsPage('HR');
    await waitFor(() => expect(myHrReportApi.getWorkforceReport).toHaveBeenCalled());
    await user.click(screen.getAllByRole('button', { name: /run report/i })[1]);
    expect(screen.getByRole('heading', { level: 2, name: 'Timesheet Report' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /generate report/i }));
    await waitFor(() => expect(myHrReportApi.getTimesheetReport).toHaveBeenCalledWith(expect.objectContaining({ scope: 'ACCESSIBLE', page: 0, size: 50 })));
    expect(screen.getByText('Sarah HR')).toBeInTheDocument();
    expect(screen.getByText('HR')).toBeInTheDocument();
    expect(screen.getAllByText('Pending approval > 3 days').length).toBeGreaterThanOrEqual(1);
    await user.click(screen.getAllByRole('button', { name: /review/i }).at(-1));
    await waitFor(() => expect(timesheetApi.get).toHaveBeenCalledWith('ts-1'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('Tuesday')).toBeInTheDocument();
  });

  it('downloads the generated timesheet report as an Excel file named for the selected month', async () => {
    const user = userEvent.setup();
    renderReportsPage('COMPANY_ADMIN');
    await waitFor(() => expect(myHrReportApi.getWorkforceReport).toHaveBeenCalled());
    await user.click(screen.getAllByRole('button', { name: /run report/i })[1]);
    await user.click(screen.getByRole('button', { name: /generate report/i }));
    await waitFor(() => expect(myHrReportApi.getTimesheetReport).toHaveBeenCalled());

    await user.click(screen.getByRole('button', { name: /download excel/i }));

    expect(reportExport.downloadReportExcel).toHaveBeenCalledWith(expect.objectContaining({
      filename: 'September_2026_Employees_Timesheet.xls',
      rows: mockTimesheetReport.content,
    }));
  });

  it('displays search recommendations when typing and selects an employee', async () => {
    const user = userEvent.setup();
    myHrReportApi.searchEmployees.mockResolvedValue({
      data: {
        data: [
          {
            userId: 'user-1',
            employeeId: 'TD025',
            name: 'Sarah HR',
            designation: 'HR Partner',
            reportingManagerName: 'Bob Jones',
          },
        ],
      },
    });

    renderReportsPage('HR');
    await waitFor(() => expect(myHrReportApi.getWorkforceReport).toHaveBeenCalled());
    await user.click(screen.getAllByRole('button', { name: /run report/i })[1]);

    const searchInput = screen.getByRole('combobox', { name: /search/i });
    await user.type(searchInput, 'Sarah');

    await waitFor(() => {
      expect(myHrReportApi.searchEmployees).toHaveBeenCalledWith('Sarah', expect.any(Object));
    });

    const suggestion = await screen.findByRole('option', { name: /select sarah hr/i });
    expect(suggestion).toBeInTheDocument();
    await user.click(suggestion);

    expect(searchInput).toHaveValue('Sarah HR');
  });

  it('filters report when clicking a quick triage pill', async () => {
    const user = userEvent.setup();
    renderReportsPage('HR');
    await waitFor(() => expect(myHrReportApi.getWorkforceReport).toHaveBeenCalled());
    await user.click(screen.getAllByRole('button', { name: /run report/i })[1]);

    const triagePill = screen.getByRole('button', { name: /awaiting approval/i });
    await user.click(triagePill);

    await waitFor(() => {
      expect(myHrReportApi.getTimesheetReport).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'SUBMITTED' })
      );
    });
  });
});

