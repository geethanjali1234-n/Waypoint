import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import PayrollReadinessPage from '../../../pages/myhr/features/payrollReadiness/PayrollReadinessPage';
import myHrReportApi from '../../../api/myHrReportApi';
import payrollReadinessApi from '../../../api/payrollReadinessApi';
import * as reportExport from '../../../pages/myhr/features/reports/reportExport';

vi.mock('../../../api/myHrReportApi', () => ({
  default: { searchEmployees: vi.fn() },
}));

vi.mock('../../../api/payrollReadinessApi', () => ({
  default: {
    getReadiness: vi.fn(),
    sendReminder: vi.fn(),
    sendBulkReminders: vi.fn(),
  },
}));

vi.mock('../../../pages/myhr/features/reports/reportExport', () => ({
  downloadReportExcel: vi.fn(),
}));

const readinessResponse = {
  period: {
    payrollMonth: '2026-09-01',
    payrollMonthLabel: 'September 2026',
    requiredPeriodStart: '2026-08-01',
    requiredPeriodEnd: '2026-08-31',
    payrollCutoffDate: '2026-09-10',
    daysUntilCutoff: 5,
  },
  summary: {
    totalEmployees: 3,
    readyForPayroll: 1,
    monthSubmitted: 2,
    partiallyApproved: 1,
    actionRequired: 1,
    missingTimesheet: 1,
    incompleteTimesheet: 0,
    awaitingApproval: 1,
    correctionRequired: 0,
    totalApprovedHours: 120,
    totalWorkedHours: 168,
    totalOvertimeHours: 0,
  },
  content: [
    {
      employeeUserId: 'employee-1',
      employeeId: 'TD001',
      employeeName: 'John Doe',
      role: 'EMPLOYEE',
      designation: 'Engineer',
      reportingManagerName: 'Sarah Manager',
      requiredPeriodStart: '2026-08-01',
      requiredPeriodEnd: '2026-08-31',
      existingTimesheetStatuses: [],
      requiredDays: 31,
      coveredDays: 0,
      missingDays: 31,
      approvedCoveredDays: 0,
      submittedCoveredDays: 0,
      monthSubmissionComplete: false,
      coveragePercentage: 0,
      approvedHours: 0,
      workedHours: 0,
      overtimeHours: 0,
      leaveHours: 0,
      readinessState: 'MISSING_TIMESHEET',
      responsibility: 'EMPLOYEE_ACTION_REQUIRED',
      reason: 'No existing timesheet record overlaps the required month.',
      reminderEligible: true,
      lastReminderAt: null,
    },
    {
      employeeUserId: 'employee-2',
      employeeId: 'TD002',
      employeeName: 'Ravi Kumar',
      role: 'EMPLOYEE',
      designation: 'Analyst',
      reportingManagerName: 'Sarah Manager',
      requiredPeriodStart: '2026-08-01',
      requiredPeriodEnd: '2026-08-31',
      existingTimesheetStatuses: ['SUBMITTED'],
      requiredDays: 31,
      coveredDays: 31,
      missingDays: 0,
      approvedCoveredDays: 0,
      submittedCoveredDays: 31,
      monthSubmissionComplete: true,
      coveragePercentage: 100,
      approvedHours: 0,
      workedHours: 168,
      overtimeHours: 0,
      leaveHours: 0,
      readinessState: 'AWAITING_APPROVAL',
      responsibility: 'HR_ADMIN_FOLLOW_UP',
      reason: 'The required month is covered, but at least one timesheet entry is submitted and waiting for approval.',
      reminderEligible: true,
      lastReminderAt: null,
    },
    {
      employeeUserId: 'employee-3',
      employeeId: 'TD003',
      employeeName: 'Ready Employee',
      role: 'HR',
      designation: 'Coordinator',
      reportingManagerName: 'Sarah Manager',
      requiredPeriodStart: '2026-08-01',
      requiredPeriodEnd: '2026-08-31',
      existingTimesheetStatuses: ['APPROVED'],
      requiredDays: 31,
      coveredDays: 31,
      missingDays: 0,
      approvedCoveredDays: 31,
      submittedCoveredDays: 0,
      monthSubmissionComplete: true,
      coveragePercentage: 100,
      approvedHours: 168,
      workedHours: 168,
      overtimeHours: 0,
      leaveHours: 0,
      readinessState: 'READY',
      responsibility: 'NONE',
      reason: 'All dated entries in the required month are approved for payroll readiness.',
      reminderEligible: false,
      lastReminderAt: null,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 3,
  totalPages: 1,
};

function renderPage(role = 'COMPANY_ADMIN') {
  return render(<MemoryRouter><PayrollReadinessPage role={role} /></MemoryRouter>);
}

function mockReadiness(data = readinessResponse) {
  payrollReadinessApi.getReadiness.mockResolvedValue({ data: { data } });
}

describe('PayrollReadinessPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockReadiness();
    myHrReportApi.searchEmployees.mockResolvedValue({ data: { data: [
      { userId: 'employee-1', employeeId: 'TD001', name: 'John Doe', role: 'EMPLOYEE' },
      { userId: 'manager-1', employeeId: 'TD010', name: 'Sarah Manager', role: 'HR' },
    ] } });
  });

  it('loads payroll readiness summary and rows from the backend API', async () => {
    renderPage('COMPANY_ADMIN');

    expect(await screen.findByText('September 2026')).toBeInTheDocument();
    expect(screen.getAllByText('August 1, 2026 - August 31, 2026').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('John Doe')).toBeInTheDocument();
    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument();
    await openPayrollFilters();
    expect(screen.getByRole('button', { name: /partially approved 1/i })).toBeInTheDocument();
    await openPayrollFilters();
    expect(screen.getByRole('button', { name: /month submitted 2/i })).toBeEnabled();
    expect(screen.getByRole('progressbar', { name: 'Timesheet coverage for Ravi Kumar' })).toHaveAttribute('value', '100');
    expect(screen.getByRole('button', { name: /needs follow-up 1/i })).toBeInTheDocument();
    expect(payrollReadinessApi.getReadiness).toHaveBeenCalledWith(expect.objectContaining({ payrollYear: expect.any(Number), payrollMonthNumber: expect.any(Number), status: 'ALL', page: 0, size: 50 }));
  });

  it('drives the payroll year/month from a Month dropdown and a Year dropdown sitting together', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await openPayrollFilters();
    const monthSelect = screen.getByLabelText('Payroll Month');
    await openPayrollFilters();
    const yearSelect = screen.getByLabelText('Payroll Year');
    expect(monthSelect.tagName).toBe('SELECT');
    expect(yearSelect.tagName).toBe('SELECT');

    // Year options span a generous +/- range, not just a few years, for
    // long-term ongoing use.
    const currentYear = new Date().getFullYear();
    const yearOptionValues = within(yearSelect).getAllByRole('option').map((option) => option.value);
    expect(yearOptionValues).toContain(String(currentYear - 20));
    expect(yearOptionValues).toContain(String(currentYear + 15));

    await user.selectOptions(monthSelect, '6');
    await user.selectOptions(yearSelect, '2024');
    await openPayrollFilters();
    await user.click(screen.getByRole('button', { name: /generate/i }));

    expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ payrollYear: 2024, payrollMonthNumber: 6 }));
  });

  it('opens payroll filters on hover and dismisses them with Escape', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    await screen.findByText('John Doe');
    expect(screen.queryByLabelText('Employee')).not.toBeInTheDocument();
    await user.hover(screen.getByRole('button', { name: /^Filters/ }));
    expect(screen.getByRole('region', { name: 'Payroll filters' })).toBeInTheDocument();
    expect(screen.getByLabelText('Payroll Month')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('region', { name: 'Payroll filters' })).not.toBeInTheDocument();
  });
  it('downloads the current page of employee readiness as an Excel file', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    await screen.findByText('John Doe');

    await user.click(screen.getByRole('button', { name: 'Download' }));

    expect(reportExport.downloadReportExcel).toHaveBeenCalledWith(expect.objectContaining({
      filename: 'Payroll_Readiness_September_2026.xls',
      title: 'Payroll Readiness - September 2026',
      rows: readinessResponse.content,
    }));

    // Spot-check the resolver produces human-friendly cell values.
    const { resolveValue, columns } = reportExport.downloadReportExcel.mock.calls[0][0];
    const readyRow = readinessResponse.content[2];
    expect(resolveValue(columns.find((c) => c.key === 'employee'), readyRow)).toBe('Ready Employee');
    expect(resolveValue(columns.find((c) => c.key === 'readiness'), readyRow)).toBe('Ready');
    expect(columns.map((column) => column.header)).toEqual([
      'Employee ID', 'Employee Name', 'Required Period', 'Total Hours', 'Readiness',
    ]);
    const value = (key, row) => resolveValue(columns.find((column) => column.key === key), row);
    expect(value('employeeId', readinessResponse.content[0])).toBe('TD001');
    expect(value('period', readinessResponse.content[0])).toBe('August 1, 2026 - August 31, 2026');
    expect(value('workedHours', readinessResponse.content[1])).toBe(168);
    // OT is already included in backend totals.
    expect(value('workedHours', { ...readyRow, workedHours: 168, overtimeHours: 8 })).toBe(168);
  });

  it('disables the Download button when there are no rows to export', async () => {
    mockReadiness({ ...readinessResponse, content: [], totalElements: 0 });
    renderPage('HR');

    await screen.findByText('No employees match the selected payroll readiness filters.');
    expect(screen.getByRole('button', { name: 'Download' })).toBeDisabled();
  });

  it('uses summary cards as server-side readiness filters', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await openPayrollFilters();
    await user.click(screen.getByRole('button', { name: /missing 1/i }));

    await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'MISSING_TIMESHEET', page: 0 })));
  });

  it('filters full-month submissions from the summary card and dropdown', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    await screen.findByText('John Doe');
    await openPayrollFilters();
    await user.click(screen.getByRole('button', { name: /month submitted 2/i }));
    await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'MONTH_SUBMITTED', page: 0 })));
    await openPayrollFilters();
    expect(screen.getByLabelText('Readiness')).toHaveValue('MONTH_SUBMITTED');
    expect(screen.getByRole('button', { name: /total employees 3/i })).toBeInTheDocument();
  });

  it('selects an employee from backend typeahead and generates a filtered report', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    await screen.findByText('John Doe');

    await openPayrollFilters();
    await user.type(screen.getByLabelText(/^employee$/i), 'Joh');
    await waitFor(() => expect(myHrReportApi.searchEmployees).toHaveBeenCalledWith('Joh', expect.objectContaining({ managerOnly: false })));
    await user.click(await screen.findByRole('option', { name: /john doe/i }));
    await openPayrollFilters();
    await user.click(screen.getByRole('button', { name: /generate/i }));

    await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ search: 'TD001' })));
  });

  it('applies reporting manager filter using backend typeahead', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await openPayrollFilters();
    await user.type(screen.getByLabelText(/reporting manager/i), 'Sar');
    await waitFor(() => expect(myHrReportApi.searchEmployees).toHaveBeenCalledWith('Sar', expect.objectContaining({ managerOnly: true })));
    await user.click(await screen.findByRole('option', { name: /sarah manager/i }));
    await openPayrollFilters();
    await user.click(screen.getByRole('button', { name: /generate/i }));

    await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ managerId: 'manager-1' })));
  });

  it('opens a read-only detail drawer without approval actions', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await user.click(screen.getAllByRole('button', { name: /view/i })[0]);

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'John Doe' })).toBeInTheDocument();
    expect(within(dialog).getByText(/No existing timesheet record overlaps/i)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
  });



  it('sends a single reminder for an eligible row and refreshes data', async () => {
    const user = userEvent.setup();
    payrollReadinessApi.sendReminder.mockResolvedValue({ data: { data: { sent: 1, skippedDuplicate: 0, skippedReady: 0, skippedNoRecipient: 0, failed: 0 } } });
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await user.click(screen.getAllByRole('button', { name: /send reminder/i })[0]);

    await waitFor(() => expect(payrollReadinessApi.sendReminder).toHaveBeenCalledWith('employee-1', expect.stringMatching(/^\d{4}-\d{2}-01$/)));
    expect(await screen.findByText(/1 reminder sent/i)).toBeInTheDocument();
  });

  it('does not show an active reminder action for ready rows', async () => {
    renderPage('COMPANY_ADMIN');
    await screen.findByText('Ready Employee');

    const readyRow = screen.getByText('Ready Employee').closest('tr');
    expect(within(readyRow).getByText(/no reminder needed/i)).toBeInTheDocument();
  });

  it('confirms and sends bulk reminders for selected visible rows', async () => {
    const user = userEvent.setup();
    payrollReadinessApi.sendBulkReminders.mockResolvedValue({ data: { data: { sent: 2, skippedDuplicate: 1, skippedNoRecipient: 0, failed: 0 } } });
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await openPayrollFilters();
    await user.click(screen.getByLabelText(/select john doe/i));
    await user.click(screen.getByRole('button', { name: /send to selected/i }));
    await user.click(screen.getByRole('button', { name: /^send reminders$/i }));

    await waitFor(() => expect(payrollReadinessApi.sendBulkReminders).toHaveBeenCalledWith(
      { employeeUserIds: ['employee-1'] },
      expect.stringMatching(/^\d{4}-\d{2}-01$/),
    ));
    expect(await screen.findByText(/2 sent, 1 already reminded recently/i)).toBeInTheDocument();
  });

  it('keeps server-side pagination controls', async () => {
    const user = userEvent.setup();
    mockReadiness({ ...readinessResponse, totalElements: 75, totalPages: 2 });
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    await user.click(screen.getByRole('button', { name: /next/i }));

    await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 })));
  });

  it('toggles between Table view and Cards view', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    await screen.findByText('John Doe');

    // Switch to Cards view
    const cardsBtn = screen.getByRole('button', { name: /cards/i });
    await user.click(cardsBtn);
    expect(cardsBtn).toHaveAttribute('aria-pressed', 'true');

    // Verify employee cards exist and data is visible
    expect(screen.getByText('John Doe')).toBeInTheDocument();
    expect(screen.getByText('TD001')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Send Reminder' })[0]).toBeInTheDocument();

    // Switch back to Table view
    const tableBtn = screen.getByRole('button', { name: /table/i });
    await user.click(tableBtn);
    expect(tableBtn).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('renders unified pagination bar and compact table structure matching other pages', async () => {
    mockReadiness({ ...readinessResponse, totalElements: 75, totalPages: 2 });
    renderPage('HR');
    await screen.findByText('John Doe');

    // Unified info label
    expect(screen.getByText('Showing 1–3 of 75')).toBeInTheDocument();

    // Unified MyHrPagination navigation
    const paginationNav = screen.getByRole('navigation', { name: /pagination/i });
    expect(paginationNav).toBeInTheDocument();
    expect(within(paginationNav).getByText('Page 1 of 2')).toBeInTheDocument();
    expect(within(paginationNav).getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(within(paginationNav).getByRole('button', { name: /next/i })).not.toBeDisabled();

    // Compact button class applied
    const sendButtons = screen.getAllByRole('button', { name: /send reminder/i });
    expect(sendButtons[0]).toHaveClass('payroll-readiness__action-btn');

    expect(screen.queryByRole('columnheader', { name: 'Leave' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Issue / Reason' })).not.toBeInTheDocument();
    const headers = screen.getAllByRole('columnheader');
    expect(headers[1]).toHaveTextContent('Employee Name');
    expect(within(screen.getByRole('table')).getByText('TD001')).toBeInTheDocument();
    expect(screen.queryByText(readinessResponse.content[0].reason)).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getAllByRole('button', { name: 'View' })[0]);
    const details = within(screen.getByRole('dialog'));
    expect(details.getByText('Leave')).toBeInTheDocument();
    expect(details.getByText('Issue / Reason')).toBeInTheDocument();
    expect(details.getByText(readinessResponse.content[0].reason)).toBeInTheDocument();
  });
});


async function openPayrollFilters() {
  const trigger = screen.getByRole('button', { name: /^Filters/ });
  if (trigger.getAttribute('aria-expanded') !== 'true') await userEvent.setup().click(trigger);
}
it('initializes the selected payroll month and follow-up filter from a dashboard link', async () => {
  vi.clearAllMocks();
  myHrReportApi.searchEmployees.mockResolvedValue({ data: { data: [] } });
  payrollReadinessApi.getReadiness.mockResolvedValue({ data: { data: readinessResponse } });
  render(<MemoryRouter initialEntries={['/myhr/admin/timesheets?view=payroll&payrollMonth=2026-04&status=NEEDS_FOLLOW_UP']}><PayrollReadinessPage role="COMPANY_ADMIN" /></MemoryRouter>);
  await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenCalledWith(expect.objectContaining({ payrollYear: 2026, payrollMonthNumber: 4, status: 'NEEDS_FOLLOW_UP' })));
});
