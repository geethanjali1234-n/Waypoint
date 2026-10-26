import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import TimesheetsPage from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import timesheetApi from '../../../api/timesheetApi';
import documentApi from '../../../api/documentApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import payrollReadinessApi from '../../../api/payrollReadinessApi';
import * as reportExport from '../../../pages/myhr/features/reports/reportExport';

vi.mock('../../../api/timesheetApi', () => ({
  default: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    downloadPdf: vi.fn(),
    downloadPeriodPdf: vi.fn(),
    getPeriod: vi.fn(),
    getSettings: vi.fn(),
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
    getMine: vi.fn(),
    reportingManagers: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
}));

// The embedded Payroll Readiness tab (PayrollReadinessWorkspace) pulls its
// own data independently of everything else on this page.
vi.mock('../../../api/payrollReadinessApi', () => ({
  default: {
    getReadiness: vi.fn().mockResolvedValue({ data: { data: { content: [], summary: {}, period: null, totalElements: 0, totalPages: 0 } } }),
    sendReminder: vi.fn(),
    sendBulkReminders: vi.fn(),
  },
}));

vi.mock('../../../api/myHrReportApi', () => ({
  default: {
    searchEmployees: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
}));

vi.mock('tesseract.js', () => ({
  createWorker: vi.fn().mockResolvedValue({
    recognize: vi.fn().mockResolvedValue({ data: { text: '2026-09-08 8.0\n2026-09-09 8.0\n2026-09-10 7.5' } }),
    terminate: vi.fn().mockResolvedValue(),
  }),
}));

// Date helpers mirroring TimesheetsPage's own toDateKey/addDays/currentWeek so
// fixtures that need to line up with "today" or "this week" stay correct no
// matter what day the suite actually runs on, instead of hardcoding a date
// string that was only ever true on the day this test was written.
function toDateKey(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
function addDaysKey(dateKey, amount) {
  const date = new Date(`${dateKey}T00:00:00`);
  date.setDate(date.getDate() + amount);
  return toDateKey(date);
}
function currentWeekPeriod() {
  const today = new Date();
  const mondayOffset = today.getDay() === 0 ? -6 : 1 - today.getDay();
  const monday = new Date(today);
  monday.setDate(today.getDate() + mondayOffset);
  const start = toDateKey(monday);
  return { start, end: addDaysKey(start, 6) };
}

// Drives the single-calendar DateRangePicker: opens it via its label, steps
// month-by-month (in whichever direction is needed) until the target month is
// showing, then picks the start/end days and hits Apply. Stepping instead of
// assuming a fixed number of clicks keeps this correct regardless of what
// "today" actually is when the suite runs.
async function pickDateRange(user, toggleLabel, { startKey, endKey }) {
  await openTimesheetFilters();
  await user.click(screen.getByLabelText(toggleLabel));
  const popover = screen.getByRole('dialog', { name: new RegExp(`select ${toggleLabel}`, 'i') });
  const targetMonthLabel = new Date(`${startKey}T00:00:00`).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const stepButtonName = new Date(`${startKey}T00:00:00`) < new Date() ? 'Previous month' : 'Next month';
  let guard = 0;
  while (!within(popover).queryByText(targetMonthLabel) && guard < 300) {
    await user.click(within(popover).getByRole('button', { name: stepButtonName }));
    guard += 1;
  }
  // Regex, not an exact string: a date already inside the control's current
  // value renders with a ", selected"/", unavailable" suffix on its label.
  await user.click(within(popover).getByRole('button', { name: new RegExp(`^${formatDateLong(startKey)}`) }));
  await user.click(within(popover).getByRole('button', { name: new RegExp(`^${formatDateLong(endKey)}`) }));
  await user.click(within(popover).getByRole('button', { name: 'Apply' }));
}

function formatDateLong(dateKey) {
  return new Date(`${dateKey}T00:00:00`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

const period = {
  periodStartDate: '2026-08-31',
  periodEndDate: '2026-09-06',
};

function record(overrides = {}) {
  // Legacy single-document shorthand some tests pass (supportingDocumentId /
  // supportingDocumentName) is translated into the real API shape
  // (supportingDocuments: [{id, fileName}]) so call sites don't all need
  // updating individually.
  const { supportingDocumentId, supportingDocumentName, ...rest } = overrides;
  return {
    id: 'timesheet-1',
    employeeUserId: 'employee-1',
    employeeId: 'TD003',
    employeeName: 'Jordan Davis',
    employeeRole: 'EMPLOYEE',
    reportingManagerUserId: 'hr-1',
    reportingManagerLoginId: 'TD002',
    ...period,
    entries: [
      {
        workDate: '2026-09-01',
        dayOfWeek: 'TUESDAY',
        hoursWorked: 8,
        description: 'Feature development',
      },
    ],
    totalHours: 8,
    status: 'DRAFT',
    ...(supportingDocumentId ? { supportingDocuments: [{ id: supportingDocumentId, fileName: supportingDocumentName || null }] } : {}),
    ...rest,
  };
}

function renderPage(role, userId = 'employee-1', initialPath = '/myhr/timesheets') {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({
    userId,
    loginId: userId === 'hr-1' ? 'TD002' : userId === 'admin-1' ? 'TDADMIN' : 'TD003',
    role,
    organizationId: 'org-1',
  }));

  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <TimesheetsPage role={role} />
    </MemoryRouter>,
  );
}

describe('TimesheetsPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-09-15T12:00:00Z'));
    vi.clearAllMocks();
    localStorage.clear();
    timesheetApi.getSettings.mockResolvedValue({ data: { data: { payrollPeriod: 'MONTHLY' } } });
    myHrEmployeeApi.getMine.mockResolvedValue({
      data: {
        data: {
          reportingManager: { name: 'Sarah Connor', loginId: 'TD001' },
        },
      },
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the actual working week with one current stop and empty days rather than a target clock', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [record({
      periodStartDate: '2026-09-14', periodEndDate: '2026-09-18', status: 'SUBMITTED',
      entries: [{ workDate: '2026-09-14', hoursWorked: 7.5 }], totalHours: 7.5,
    })] } });
    renderPage('EMPLOYEE');
    expect(await screen.findByRole('heading', { name: "You're 1 of 5 days in." })).toBeInTheDocument();
    const route = screen.getByRole('list', { name: "This week's recorded hours" });
    expect(within(route).getAllByRole('listitem')).toHaveLength(5);
    expect(within(route).getByText('7.50h')).toBeInTheDocument();
    expect(within(route).getByText('Not logged').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(within(route).queryByText('0.00h')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enter Timesheet' })).toHaveTextContent('Log hours');
  });

  it('opens the standard-week action through the existing entry flow without saving or filling future days', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    const user = userEvent.setup();
    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Log standard week' }));
    await screen.findByRole('button', { name: 'Standard 40h' });
    const sliders = screen.getAllByRole('slider', { name: /Hours slider for/ });
    expect(sliders.filter((slider) => !slider.disabled)).toHaveLength(2);
    expect(sliders.filter((slider) => !slider.disabled).every((slider) => slider.value === '8')).toBe(true);
    expect(sliders.filter((slider) => slider.disabled).every((slider) => slider.value === '0')).toBe(true);
    expect(screen.getByText('Total recorded hours')).toBeInTheDocument();
    expect(screen.queryByText('CAPACITY SCORE')).not.toBeInTheDocument();
    expect(timesheetApi.create).not.toHaveBeenCalled();
    expect(timesheetApi.update).not.toHaveBeenCalled();
  });

  it('bulk approves only selected pending submissions and retains failed decisions for retry', async () => {
    const first = record({ id: 'bulk-first', status: 'SUBMITTED', employeeName: 'First Pending' });
    const second = record({ id: 'bulk-second', status: 'SUBMITTED', employeeName: 'Second Pending' });
    const approved = record({ id: 'bulk-approved', status: 'APPROVED', employeeName: 'Already Approved' });
    timesheetApi.list.mockResolvedValue({ data: { data: [first, second, approved] } });
    timesheetApi.review.mockResolvedValueOnce({ data: { data: { ...first, status: 'APPROVED' } } }).mockRejectedValueOnce({ response: { data: { message: 'Decision refused' } } });
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
    await screen.findByText('First Pending');
    expect(screen.getByRole('checkbox', { name: /Select Already Approved/ })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Select this page' }));
    await user.click(screen.getByRole('button', { name: 'Approve 2 selected' }));
    expect(await screen.findByText(/1 approved\. 1 could not be approved/)).toBeInTheDocument();
    expect(timesheetApi.review).toHaveBeenNthCalledWith(1, 'bulk-first', { status: 'APPROVED', comment: null });
    expect(timesheetApi.review).toHaveBeenNthCalledWith(2, 'bulk-second', { status: 'APPROVED', comment: null });
    expect(screen.getByRole('button', { name: 'Approve 1 selected' })).toBeEnabled();
    expect(screen.getByRole('checkbox', { name: /Select Second Pending/ })).toBeChecked();
    timesheetApi.review.mockResolvedValueOnce({ data: { data: { ...second, status: 'APPROVED' } } });
    await user.click(screen.getByRole('button', { name: 'Approve 1 selected' }));
    expect(await screen.findByText('1 approved.')).toBeInTheDocument();
    expect(timesheetApi.review).toHaveBeenCalledTimes(3);
  });

  it('switches review weeks without changing the records and restores all dates', async () => {
    const current = record({ id: 'week-current', status: 'SUBMITTED', employeeName: 'Current Week', periodStartDate: '2026-09-14', periodEndDate: '2026-09-18', entries: [{ workDate: '2026-09-14', hoursWorked: 7 }] });
    const previous = record({ id: 'week-previous', status: 'SUBMITTED', employeeName: 'Previous Week', periodStartDate: '2026-09-07', periodEndDate: '2026-09-11', entries: [{ workDate: '2026-09-07', hoursWorked: 8 }] });
    timesheetApi.list.mockResolvedValue({ data: { data: [current, previous] } });
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
    await screen.findByText('Current Week');
    await user.click(screen.getByRole('button', { name: 'This week' }));
    expect(screen.queryByText('Previous Week')).not.toBeInTheDocument();
    expect(screen.getByText('7h')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Previous week' }));
    expect(screen.getByText('Previous Week')).toBeInTheDocument();
    expect(screen.queryByText('Current Week')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'All dates' }));
    expect(screen.getByText('Current Week')).toBeInTheDocument();
    expect(screen.getByText('Previous Week')).toBeInTheDocument();
    expect(timesheetApi.review).not.toHaveBeenCalled();
  });

  it('shows attachment counts only from supplied document data, including legacy single documents', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({ id: 'documents-many', status: 'SUBMITTED', employeeName: 'Multiple Documents', supportingDocuments: [{ id: 'doc-a' }, { id: 'doc-b' }] }),
      { ...record({ id: 'documents-legacy', status: 'SUBMITTED', employeeName: 'Legacy Document' }), supportingDocumentId: 'legacy-doc' },
      record({ id: 'documents-empty', status: 'SUBMITTED', employeeName: 'No Documents', supportingDocuments: [] }),
      record({ id: 'documents-unknown', status: 'SUBMITTED', employeeName: 'Unavailable Documents' }),
    ] } });
    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
    await screen.findByText('Multiple Documents');
    const table = screen.getByRole('table', { name: 'Team timesheet submissions' });
    const headers = within(table).getAllByRole('columnheader');
    const attachmentIndex = headers.findIndex((header) => header.textContent === 'Attachments');
    for (const [name, expected] of [['Multiple Documents', '2'], ['Legacy Document', '1'], ['No Documents', '0'], ['Unavailable Documents', '—']]) {
      const row = within(table).getByText(name).closest('tr');
      expect(within(row).getAllByRole('cell')[attachmentIndex]).toHaveTextContent(expected);
    }
  });

  it('uses the dashboard standard-week shortcut without saving, submitting or filling future days', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    renderPage('EMPLOYEE', 'employee-1', { pathname: '/myhr/employee/timesheets', search: '?mode=enter', state: { timesheetEntryMethod: 'standard' } });
    expect(await screen.findByRole('button', { name: 'Standard 40h' })).toBeInTheDocument();
    const sliders = screen.getAllByRole('slider', { name: /Hours slider for/ });
    expect(sliders.filter((slider) => !slider.disabled)).toHaveLength(2);
    expect(sliders.filter((slider) => !slider.disabled).every((slider) => slider.value === '8')).toBe(true);
    expect(sliders.filter((slider) => slider.disabled).every((slider) => slider.value === '0')).toBe(true);
    expect(timesheetApi.create).not.toHaveBeenCalled();
    expect(timesheetApi.update).not.toHaveBeenCalled();
    expect(timesheetApi.review).not.toHaveBeenCalled();
  });

  it('opens only submitted records from a dashboard attention link', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({ id: 'submitted-1', status: 'SUBMITTED', employeeName: 'Pending One' }),
      record({ id: 'submitted-2', status: 'SUBMITTED', employeeName: 'Pending Two' }),
      record({ id: 'approved-1', status: 'APPROVED', employeeName: 'Already Approved' }),
    ] } });
    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets?view=team&subtab=submissions&status=SUBMITTED');
    expect(await screen.findByText('Pending One')).toBeInTheDocument();
    expect(screen.getByText('Pending Two')).toBeInTheDocument();
    expect(screen.queryByText('Already Approved')).not.toBeInTheDocument();
  });
  it('imports dates and hours, lets the employee correct them, and submits the original document', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    documentApi.upload.mockResolvedValue({ data: { data: { id: 'import-doc', fileName: 'company.csv' } } });
    documentApi.parseTimesheet.mockResolvedValue({ data: { data: {
      periodStartDate: '2025-01-02', periodEndDate: '2025-01-02', extractionStatus: 'SUCCESS',
      message: 'Verify every date and hour.', entries: [{ workDate: '2025-01-02', hoursWorked: 7.5, description: 'Client work' }],
    } } });
    timesheetApi.create.mockResolvedValue({ data: { data: record({ status: 'SUBMITTED', supportingDocumentId: 'import-doc' }) } });
    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.selectOptions(screen.getByLabelText('Document date format'), 'DMY');
    await user.upload(screen.getByLabelText('Timesheet document'), new File(['Date,Hours'], 'company.csv', { type: 'text/csv' }));
    expect(await screen.findByRole('heading', { name: 'Add daily hours' })).toBeInTheDocument();
    expect(documentApi.parseTimesheet).toHaveBeenCalledWith('import-doc', 'DMY');
    expect(screen.getByLabelText('Hours')).toHaveValue(7.5);
    expect(screen.getByText('Attached timesheet: company.csv')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Hours'));
    await user.type(screen.getByLabelText('Hours'), '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));
    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      supportingDocumentIds: ['import-doc'], status: 'SUBMITTED', periodStartDate: '2025-01-02',
      entries: [expect.objectContaining({ workDate: '2025-01-02', hoursWorked: 8 })],
    }));
  });

  it('retains the uploaded document when extraction fails and allows manual entry', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    documentApi.upload.mockResolvedValue({ data: { data: { id: 'scan-doc', fileName: 'scan.pdf' } } });
    documentApi.parseTimesheet.mockRejectedValue(new Error('Unavailable'));
    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.upload(screen.getByLabelText('Timesheet document'), new File(['scan'], 'scan.pdf', { type: 'application/pdf' }));
    expect(await screen.findByRole('heading', { name: 'Choose your date range' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));
    expect(screen.getByText('Attached timesheet: scan.pdf')).toBeInTheDocument();
    expect(screen.getByText('Document attached, but extraction failed. Enter hours manually.')).toBeInTheDocument();
  });

  it('combines hours and dates extracted from multiple uploaded documents into one timesheet', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    documentApi.upload
      .mockResolvedValueOnce({ data: { data: { id: 'doc-a', fileName: 'week1.csv' } } })
      .mockResolvedValueOnce({ data: { data: { id: 'doc-b', fileName: 'week2.csv' } } });
    documentApi.parseTimesheet
      .mockResolvedValueOnce({ data: { data: {
        periodStartDate: '2026-09-01', periodEndDate: '2026-09-02', extractionStatus: 'SUCCESS',
        message: 'Extracted 2 days.', entries: [
          { workDate: '2026-09-01', hoursWorked: 8, description: 'Week 1 Monday' },
          { workDate: '2026-09-02', hoursWorked: 7, description: 'Week 1 Tuesday' },
        ],
      } } })
      .mockResolvedValueOnce({ data: { data: {
        periodStartDate: '2026-09-14', periodEndDate: '2026-09-15', extractionStatus: 'SUCCESS',
        message: 'Extracted 2 days.', entries: [
          { workDate: '2026-09-14', hoursWorked: 6, description: 'Week 2 Monday' },
          { workDate: '2026-09-15', hoursWorked: 5, description: 'Week 2 Tuesday' },
        ],
      } } });
    timesheetApi.create.mockResolvedValue({ data: { data: record({ status: 'SUBMITTED' }) } });

    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.upload(
      screen.getByLabelText('Timesheet document'),
      [new File(['a'], 'week1.csv', { type: 'text/csv' }), new File(['b'], 'week2.csv', { type: 'text/csv' })],
    );

    expect(await screen.findByRole('heading', { name: 'Add daily hours' })).toBeInTheDocument();
    // Combined range spans both documents, not just the first or last.
    expect(screen.getByText('Sep 1 - 15, 2026')).toBeInTheDocument();
    expect(screen.getByText('Hours pre-filled from 2 documents')).toBeInTheDocument();
    expect(screen.getByText('Attached timesheet: week1.csv')).toBeInTheDocument();
    expect(screen.getByText('Attached timesheet: week2.csv')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));
    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-15',
      supportingDocumentIds: ['doc-a', 'doc-b'],
      entries: expect.arrayContaining([
        expect.objectContaining({ workDate: '2026-09-01', hoursWorked: 8 }),
        expect.objectContaining({ workDate: '2026-09-14', hoursWorked: 6 }),
      ]),
    }));
  });

  it('tells the user which uploaded documents could not be read instead of silently defaulting the dates', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    documentApi.upload
      .mockResolvedValueOnce({ data: { data: { id: 'doc-x', fileName: 'scan1.pdf' } } })
      .mockResolvedValueOnce({ data: { data: { id: 'doc-y', fileName: 'scan2.pdf' } } });
    documentApi.parseTimesheet
      .mockResolvedValueOnce({ data: { data: {
        periodStartDate: null, periodEndDate: null, extractionStatus: 'FALLBACK',
        message: 'Dates and hours could not be automatically detected reliably. Check the selected date order and enter hours manually.',
        entries: [],
      } } })
      .mockResolvedValueOnce({ data: { data: {
        periodStartDate: null, periodEndDate: null, extractionStatus: 'FALLBACK',
        message: 'The document could not be read. Enter the dates and hours manually or upload a text document.',
        entries: [],
      } } });

    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.upload(
      screen.getByLabelText('Timesheet document'),
      [new File(['a'], 'scan1.pdf', { type: 'application/pdf' }), new File(['b'], 'scan2.pdf', { type: 'application/pdf' })],
    );

    // Lands on the date-range step (not silently on hours with a misleading default range)
    // with a clear, per-file explanation of what went wrong.
    expect(await screen.findByRole('heading', { name: 'Choose your date range' })).toBeInTheDocument();
    expect(screen.getByText(/Could not read scan1\.pdf:.*Could not read scan2\.pdf:/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));
    // Both documents are still attached as evidence despite failing extraction.
    expect(screen.getByText('Attached timesheet: scan1.pdf')).toBeInTheDocument();
    expect(screen.getByText('Attached timesheet: scan2.pdf')).toBeInTheDocument();
  });

  it('requires correction confirmation for imported hours that overlap a submission', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [record({ status: 'SUBMITTED' })] } });
    documentApi.upload.mockResolvedValue({ data: { data: { id: 'new-doc', fileName: 'changed.csv' } } });
    documentApi.parseTimesheet.mockResolvedValue({ data: { data: {
      periodStartDate: '2026-09-01', periodEndDate: '2026-09-01', extractionStatus: 'SUCCESS',
      entries: [{ workDate: '2026-09-01', hoursWorked: 6, description: 'Corrected' }],
    } } });
    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.upload(screen.getByLabelText('Timesheet document'), new File(['x'], 'changed.csv', { type: 'text/csv' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm re-edit' }));
    expect(screen.getByLabelText('Hours')).toHaveValue(6);
    expect(screen.getByText('Attached timesheet: changed.csv')).toBeInTheDocument();
    expect(screen.getByLabelText(/Reason for change/)).toBeInTheDocument();
  });

  it.each([['HR', 'hr-1'], ['COMPANY_ADMIN', 'admin-1']])('%s can view the original document alongside hours and approve', async (role, userId) => {
    const user = userEvent.setup();
    const submitted = record({ status: 'SUBMITTED', supportingDocumentId: 'proof-1', supportingDocumentName: 'original.pdf' });
    timesheetApi.list.mockResolvedValue({ data: { data: [submitted] } });
    timesheetApi.review.mockResolvedValue({ data: { data: { ...submitted, status: 'APPROVED' } } });
    documentApi.download.mockResolvedValue({ data: new Blob(['pdf'], { type: 'application/pdf' }) });
    URL.createObjectURL = vi.fn(() => 'blob:preview');
    URL.revokeObjectURL = vi.fn();
    renderPage(role, userId);
    await user.click(await screen.findByRole('button', { name: /^(View details|Review)$/ }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Attached timesheet: original.pdf')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'View document' }));
    expect(await within(dialog).findByTitle('Timesheet document preview')).toHaveAttribute('src', 'blob:preview');
    expect(documentApi.download).toHaveBeenCalledWith('proof-1');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(timesheetApi.review).toHaveBeenCalledWith('timesheet-1', expect.objectContaining({ status: 'APPROVED' }));
  });

  it('downloads the original attached document and handles blob error extraction cleanly', async () => {
    const user = userEvent.setup();
    const submitted = record({ status: 'SUBMITTED', supportingDocumentId: 'proof-2', supportingDocumentName: 'Timesheet_Signoff_Lilly_W36.pdf' });
    timesheetApi.list.mockResolvedValue({ data: { data: [submitted] } });
    documentApi.download.mockResolvedValue({ data: new Blob(['%PDF-1.4 test'], { type: 'application/pdf' }) });
    URL.createObjectURL = vi.fn(() => 'blob:download-url');
    URL.revokeObjectURL = vi.fn();

    renderPage('HR', 'hr-1');
    await user.click(await screen.findByRole('button', { name: /^(View details|Review)$/ }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Attached timesheet: Timesheet_Signoff_Lilly_W36.pdf')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Download original' }));
    expect(documentApi.download).toHaveBeenCalledWith('proof-2');

    // Also verify View document renders preview and open in new tab
    await user.click(within(dialog).getByRole('button', { name: 'View document' }));
    expect(await within(dialog).findByTitle('Timesheet document preview')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: /Open in new tab/i })).toHaveAttribute('href', 'blob:download-url');
  });

  it('previews a mislabeled image attachment as text when the downloaded bytes are text', async () => {
    const user = userEvent.setup();
    const submitted = record({
      status: 'SUBMITTED',
      supportingDocumentId: 'proof-text-fallback',
      supportingDocumentName: 'WhatsApp_Image_2026-09-21.jpeg',
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [submitted] } });
    documentApi.download.mockResolvedValue({
      data: new Blob(['Waypoint Certified Supporting Document\nFile Name: WhatsApp_Image_2026-09-21.jpeg'], { type: 'image/jpeg' }),
      headers: { 'content-type': 'image/jpeg' },
    });

    renderPage('HR', 'hr-1');
    await user.click(await screen.findByRole('button', { name: /^(View details|Review)$/ }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'View document' }));

    expect(await within(dialog).findByText(/Waypoint Certified Supporting Document/)).toBeInTheDocument();
    expect(within(dialog).queryByAltText('Attached timesheet')).not.toBeInTheDocument();
  });

  it('guides an Employee through daily hours and saves a draft through the API', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.create.mockResolvedValue({ data: { data: record({ id: 'created-1' }) } });

    renderPage('EMPLOYEE');

    expect(await screen.findByRole('heading', { name: 'My Timesheets' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    expect(screen.getByRole('heading', { name: 'Choose your date range' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));
    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '7.5');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      status: 'DRAFT',
      entries: [expect.objectContaining({ hoursWorked: 7.5 })],
    }));
    expect(await screen.findByText('Draft saved.')).toBeInTheDocument();
  });

  it('records a described zero-hour day (e.g. sick leave) as a covered entry, but leaves an untouched zero-hour day out', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.create.mockResolvedValue({ data: { data: record({ id: 'created-leave' }) } });

    renderPage('EMPLOYEE');

    expect(await screen.findByRole('heading', { name: 'My Timesheets' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await pickDateRange(user, 'Timesheet period', { startKey: '2026-09-01', endKey: '2026-09-03' });
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const hoursInputs = screen.getAllByLabelText('Hours');
    const descriptionInputs = screen.getAllByLabelText('Description (optional)');

    // Sept 1: an ordinary worked day.
    await user.clear(hoursInputs[0]);
    await user.type(hoursInputs[0], '8');
    // Sept 2: zero hours, but explicitly noted as leave — must still be recorded.
    await user.type(descriptionInputs[1], 'Sick leave');
    // Sept 3: left entirely untouched (0 hours, no note) — stays excluded, same as before.

    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    const reviewTable = screen.getByRole('table', { name: 'Timesheet review' });
    expect(within(reviewTable).getAllByRole('row')).toHaveLength(3); // header + 2 recorded days
    expect(within(reviewTable).getAllByText('Sick leave').length).toBeGreaterThanOrEqual(1);
    expect(within(reviewTable).queryByText('2026-09-03', { exact: false })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      entries: [
        expect.objectContaining({ workDate: '2026-09-01', hoursWorked: 8 }),
        expect.objectContaining({ workDate: '2026-09-02', hoursWorked: 0, entryType: 'SICK_LEAVE', description: 'Sick leave' }),
      ],
    }));
  });

  it('still blocks submission when every day in range is left at zero hours with no note', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });

    renderPage('EMPLOYEE');

    expect(await screen.findByRole('heading', { name: 'My Timesheets' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    expect(await screen.findByText('Add at least one hour, a non-work entry type, or a note for a day with zero hours before reviewing the timesheet.')).toBeInTheDocument();
    expect(timesheetApi.create).not.toHaveBeenCalled();
  });

  it('uploads a supporting document and attaches its id to the submitted timesheet', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.create.mockResolvedValue({ data: { data: record({ id: 'created-1', status: 'SUBMITTED' }) } });
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-9', fileName: 'evidence.pdf', contentType: 'application/pdf', fileSize: 9 } },
    });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));
    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    const file = new File(['pdf-bytes'], 'evidence.pdf', { type: 'application/pdf' });
    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), file);

    expect(documentApi.upload).toHaveBeenCalledWith(file);
    expect(await screen.findByText('Attached timesheet: evidence.pdf')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    expect(timesheetApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ supportingDocumentIds: ['doc-9'], status: 'SUBMITTED' }),
    );
  });

  it('downloads active pay period PDF and does not show individual download button in the history row', async () => {
    const user = userEvent.setup();
    URL.createObjectURL = vi.fn(() => 'blob:mock-url');
    URL.revokeObjectURL = vi.fn();
    const submitted = record({ id: 'submitted-1', status: 'SUBMITTED', submittedAt: '2026-09-02T10:00:00' });
    timesheetApi.list.mockResolvedValue({ data: { data: [submitted] } });
    timesheetApi.downloadPeriodPdf.mockResolvedValue({ data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }) });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));
    const table = await screen.findByRole('table');
    // Individual record should have View details, but NOT Download button
    expect(within(table).getByRole('button', { name: /^(View details|Review)$/ })).toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Download' })).not.toBeInTheDocument();

    // Active period can be downloaded
    await user.click(screen.getByRole('button', { name: 'Download Period PDF' }));
    expect(timesheetApi.downloadPeriodPdf).toHaveBeenCalled();
    expect(URL.createObjectURL).toHaveBeenCalled();
  });

  it('keeps a just-submitted current-period timesheet visible in the history view', async () => {
    const user = userEvent.setup();
    const serverRecords = [];
    timesheetApi.list.mockImplementation(() => Promise.resolve({ data: { data: [...serverRecords] } }));
    timesheetApi.create.mockImplementation((payload) => {
      const created = record({
        id: 'submitted-1',
        status: payload.status,
        submittedAt: '2026-09-02T10:00:00',
        periodStartDate: payload.periodStartDate,
        periodEndDate: payload.periodEndDate,
        entries: payload.entries,
        totalHours: payload.entries.reduce((sum, entry) => sum + entry.hoursWorked, 0),
      });
      serverRecords.push(created);
      return Promise.resolve({ data: { data: created } });
    });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));
    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-hist-1', fileName: 'timesheet.pdf' } },
    });
    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), new File(['pdf'], 'timesheet.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    expect(await screen.findByRole('heading', { name: 'Timesheet submitted' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Back to overview' }));
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    const table = await screen.findByRole('table');
    expect(within(table).getByRole('button', { name: /^(View details|Review)$/ })).toBeInTheDocument();
    expect(screen.queryByText('No submitted timesheets yet.')).not.toBeInTheDocument();
  });

  it('shows the most recently submitted timesheets first in history', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({
        id: 'older-submission',
        periodStartDate: '2026-09-14',
        periodEndDate: '2026-09-20',
        totalHours: 22,
        status: 'SUBMITTED',
        submittedAt: '2026-09-21T12:43:00',
      }),
      record({
        id: 'newer-submission',
        periodStartDate: '2026-09-14',
        periodEndDate: '2026-09-27',
        totalHours: 46,
        status: 'APPROVED',
        submittedAt: '2026-09-22T15:20:00',
      }),
    ] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));
    const rows = within(await screen.findByRole('table')).getAllByRole('row');

    expect(rows[1]).toHaveTextContent('Sep 14 - 27, 2026');
    expect(rows[2]).toHaveTextContent('Sep 14 - 20, 2026');
  });

  it('submitting a new week keeps a previously approved week visible (creates a new record, does not overwrite)', async () => {
    const user = userEvent.setup();
    const approvedWeek1 = record({
      id: 'ts-approved-w1',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-06',
      status: 'APPROVED',
      submittedAt: '2026-09-02T09:00:00',
      updatedAt: '2026-09-03T09:00:00',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
        { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 8 },
      ],
      totalHours: 16,
    });
    const serverRecords = [approvedWeek1];
    timesheetApi.list.mockImplementation(() => Promise.resolve({ data: { data: [...serverRecords] } }));
    timesheetApi.create.mockImplementation((payload) => {
      const created = record({
        id: 'ts-submitted-w2',
        status: payload.status,
        periodStartDate: payload.periodStartDate,
        periodEndDate: payload.periodEndDate,
        submittedAt: '2026-09-09T10:00:00',
        updatedAt: '2026-09-09T10:00:00',
        entries: payload.entries.map((e) => ({ ...e, dayOfWeek: 'X' })),
        totalHours: payload.entries.reduce((s, e) => s + e.hoursWorked, 0),
      });
      serverRecords.push(created);
      return Promise.resolve({ data: { data: created } });
    });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));

    // Pick a fresh week that does not overlap the approved one
    await pickDateRange(user, 'Timesheet period', { startKey: '2026-09-08', endKey: '2026-09-09' });
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    // No conflict modal for a non-overlapping range
    expect(screen.queryByRole('heading', { name: /Timesheet Conflict|Re-enter Submitted/ })).not.toBeInTheDocument();

    const hoursInputs = screen.getAllByLabelText('Hours');
    await user.clear(hoursInputs[0]);
    await user.type(hoursInputs[0], '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-week-2', fileName: 'timesheet.pdf' } },
    });
    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), new File(['pdf'], 'timesheet.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    await screen.findByRole('heading', { name: 'Timesheet submitted' });

    // A brand new record was created — the approved week was NOT updated in place
    expect(timesheetApi.create).toHaveBeenCalled();
    expect(timesheetApi.update).not.toHaveBeenCalled();

    // View Hours still shows the approved week alongside the newly pending day
    await user.click(screen.getByRole('button', { name: 'Back to overview' }));
    await user.click(screen.getByRole('button', { name: 'View Hours' }));
    expect(screen.getByRole('heading', { name: 'Hours overview' })).toBeInTheDocument();
    expect(screen.getAllByText('Approved').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('Pending').length).toBeGreaterThanOrEqual(1);
  });

  it('gives HR a team view and keeps direct reports separate from the HR user', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({ id: 'hr-own', employeeUserId: 'hr-1', employeeId: 'TD002', employeeName: 'Sarah Williams', employeeRole: 'HR' }),
      record({ id: 'employee-report', employeeUserId: 'employee-1', status: 'SUBMITTED' }),
    ] } });

    renderPage('HR', 'hr-1');

    expect(await screen.findByText('Jordan Davis')).toBeInTheDocument();
    expect(screen.queryByText('No direct-report timesheets found.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^(View details|Review)$/ })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('tab', { name: 'My Timesheets' }));
    expect(screen.getByRole('heading', { name: /You're \d of 5 days in\./ })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'View Hours' }));
    expect(screen.getByRole('heading', { name: 'Hours overview' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
  });

  it('opens Team Timesheets on All Submissions by default, listed first among the review sub-tabs', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({ id: 'ts-default-tab', employeeName: 'Default Tab Employee', status: 'APPROVED' }),
    ] } });

    renderPage('HR', 'hr-1');

    expect(await screen.findByText('Default Tab Employee')).toBeInTheDocument();
    const subTabs = within(screen.getByRole('navigation', { name: 'Review workspace views' })).getAllByRole('button', { name: /All Submissions|Required Actions|By Employee/ });
    expect(subTabs.map((tab) => tab.textContent)).toEqual([
      expect.stringContaining('All Submissions'),
      expect.stringContaining('Required Actions'),
      expect.stringContaining('By Employee'),
    ]);
    expect(subTabs[0]).toHaveAttribute('aria-pressed', 'true');
  });

  it.each([
    ['HR', 'hr-1', ['My Timesheets', 'Team Timesheets', 'Payroll Readiness']],
    ['COMPANY_ADMIN', 'admin-1', ['Team Timesheets', 'Payroll Readiness']],
  ])('keeps Payroll Readiness reachable through existing navigation for %s', async (role, userId, expectedTabs) => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    const user = userEvent.setup();

    renderPage(role, userId);

    await screen.findByRole('heading', { name: 'Timesheets' });
    if (role === 'HR') expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(expectedTabs);
    else expect(screen.queryByRole('tab', { name: 'Team Timesheets' })).not.toBeInTheDocument();

    await user.click(screen.getByRole(role === 'HR' ? 'tab' : 'link', { name: 'Payroll Readiness' }));

    expect(screen.getByRole('tab', { name: 'Payroll Readiness', selected: true })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Payroll Readiness' })).toBeInTheDocument();
    expect(screen.getByText(/Track employee timesheets, follow up on missing submissions/)).toBeInTheDocument();
    expect(payrollReadinessApi.getReadiness).toHaveBeenCalled();

    // Switching back to Team Timesheets leaves the payroll workspace and its
    // requests behind entirely.
    await user.click(screen.getByRole('tab', { name: 'Team Timesheets' }));
    expect(screen.queryByRole('heading', { name: 'Payroll Readiness' })).not.toBeInTheDocument();
  });

  it('deep-links straight into the Payroll Readiness tab via ?view=payroll (the redirected /payroll-readiness URL target)', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });

    renderPage('HR', 'hr-1', '/myhr/timesheets?view=payroll');

    expect(screen.getByRole('tab', { name: 'Payroll Readiness', selected: true })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Payroll Readiness' })).toBeInTheDocument();
  });

  it('shows recorded hours across the selected month and supports period navigation', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [record({
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Feature development' },
        { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 7.5, description: 'Testing' },
      ],
      totalHours: 15.5,
    })] } });

    renderPage('EMPLOYEE');

    await userEvent.setup().click(await screen.findByRole('button', { name: 'View Hours' }));
    expect(screen.getByRole('heading', { name: 'Hours overview' })).toBeInTheDocument();
    expect(screen.getByText('15.50h')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next period' })).toBeEnabled();

    await userEvent.setup().click(screen.getByRole('button', { name: 'Previous period' }));
    expect(screen.getAllByText('August 2026').length).toBeGreaterThan(0);
  });

  it('calculates total hours for a selected calendar date range', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({
        id: 'pending-range-hours',
        periodStartDate: '2026-09-01',
        periodEndDate: '2026-09-04',
        status: 'SUBMITTED',
        entries: [
          { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
          { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 8 },
        ],
        totalHours: 16,
      }),
      record({
        id: 'approved-range-hours',
        periodStartDate: '2026-09-01',
        periodEndDate: '2026-09-05',
        status: 'APPROVED',
        entries: [
          { workDate: '2026-09-03', dayOfWeek: 'THURSDAY', hoursWorked: 8 },
          { workDate: '2026-09-04', dayOfWeek: 'FRIDAY', hoursWorked: 12 },
          { workDate: '2026-09-05', dayOfWeek: 'SATURDAY', hoursWorked: 6 },
        ],
        totalHours: 26,
      }),
    ] } });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'View Hours' }));
    await user.click(screen.getByRole('button', { name: /Select date range/i }));
    const picker = screen.getByRole('dialog', { name: 'Select date range' });
    await user.click(within(picker).getByRole('button', { name: /Sep 1, 2026 8.00h/i }));
    await user.hover(within(picker).getByRole('button', { name: /Sep 4, 2026 12.00h/i }));

    expect(screen.getByText('Sep 1 - Sep 4, 2026')).toBeInTheDocument();
    expect(screen.getByText('16.00h')).toBeInTheDocument();
    expect(screen.getByText('20.00h')).toBeInTheDocument();
    expect(screen.getByText('36.00h')).toBeInTheDocument();

    await user.click(within(picker).getByRole('button', { name: /Sep 4, 2026 12.00h/i }));
    await user.click(within(picker).getByRole('button', { name: 'Apply' }));
    expect(screen.getByRole('button', { name: 'Clear range' })).toBeInTheDocument();
  });

  it('View Hours shows approval status per day (green approved, amber pending) with no month/week/day toggle', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({
        id: 'ts-vh-approved',
        periodStartDate: '2026-09-01',
        periodEndDate: '2026-09-07',
        status: 'APPROVED',
        submittedAt: '2026-09-02T09:00:00',
        updatedAt: '2026-09-03T09:00:00',
        entries: [
          { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
          { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 8 },
        ],
        totalHours: 16,
      }),
      record({
        id: 'ts-vh-pending',
        periodStartDate: '2026-09-08',
        periodEndDate: '2026-09-14',
        status: 'SUBMITTED',
        submittedAt: '2026-09-09T09:00:00',
        updatedAt: '2026-09-09T09:00:00',
        entries: [
          { workDate: '2026-09-08', dayOfWeek: 'MONDAY', hoursWorked: 7.5 },
        ],
        totalHours: 7.5,
      }),
    ] } });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'View Hours' }));
    expect(screen.getByRole('heading', { name: 'Hours overview' })).toBeInTheDocument();

    // No day/week/month view switcher any more
    expect(screen.queryByRole('tab', { name: 'Month' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Week' })).not.toBeInTheDocument();

    // Per-day approval status shows in the calendar
    expect(screen.getAllByText('Approved').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('Pending').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Waiting for approval')).toBeInTheDocument();
    expect(screen.getByText('23.50h')).toBeInTheDocument();
  });

  it('gives Company Admin an organization review table with role and status filters', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [
      record({ status: 'SUBMITTED' }),
      record({ id: 'hr-report', employeeUserId: 'hr-1', employeeId: 'TD002', employeeName: 'Sarah Williams', employeeRole: 'HR', status: 'SUBMITTED' }),
    ] } });

    renderPage('COMPANY_ADMIN', 'admin-1');

    const table = await screen.findByRole('table');
    expect(within(table).getByText('Jordan Davis')).toBeInTheDocument();
    expect(within(table).getByText('Sarah Williams')).toBeInTheDocument();
    expect(screen.queryByLabelText('Role')).not.toBeInTheDocument();
    const filterButton = screen.getByRole('button', { name: /^Filters/ });
    const user = userEvent.setup();
    await user.hover(filterButton);
    expect(screen.getByRole('region', { name: 'Timesheet filters' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('region', { name: 'Timesheet filters' })).not.toBeInTheDocument();
    await openTimesheetFilters();
    await userEvent.setup().selectOptions(screen.getByLabelText('Role'), 'HR');
    expect(within(table).queryByText('Jordan Davis')).not.toBeInTheDocument();
    expect(within(table).getByText('Sarah Williams')).toBeInTheDocument();
  });

  it('lets Company Admin approve a submitted organization timesheet from its details', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [record({ status: 'SUBMITTED' })] } });
    timesheetApi.review.mockResolvedValue({ data: { data: record({ status: 'APPROVED', reviewedByUserId: 'admin-1', reviewedAt: '2026-09-09T14:00:00' }) } });

    renderPage('COMPANY_ADMIN', 'admin-1');

    const table = await screen.findByRole('table');
    expect(within(table).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    await user.click(within(table).getByRole('button', { name: /^(View details|Review)$/ }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }));

    expect(timesheetApi.review).toHaveBeenCalledWith('timesheet-1', { status: 'APPROVED', comment: null });
    expect(await screen.findByText('Timesheet approved.')).toBeInTheDocument();
    expect(within(table).getByText('Approved')).toBeInTheDocument();
  });

  it('re-entering hours on an existing draft replaces the daily value rather than summing', async () => {
    const user = userEvent.setup();
    const existingDraft = record({
      id: 'draft-1',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Initial work' },
      ],
      totalHours: 8,
      status: 'DRAFT',
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [existingDraft] } });
    timesheetApi.update.mockResolvedValue({
      data: {
        data: {
          ...existingDraft,
          entries: [
            { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 6, description: 'Updated' },
          ],
          totalHours: 6,
        },
      },
    });

    renderPage('EMPLOYEE');

    expect(await screen.findByRole('heading', { name: 'My Timesheets' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    // Verify existing 8h is prefilled on Tuesday Sep 1
    const hoursInput = screen.getByLabelText((content, element) => element.name === 'hours-2026-09-01');
    expect(hoursInput).toHaveValue(8);

    // Replace 8 with 6
    await user.clear(hoursInput);
    await user.type(hoursInput, '6');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    // Must update with 6, NOT 14 (8+6)
    expect(timesheetApi.update).toHaveBeenCalledWith('draft-1', expect.objectContaining({
      status: 'DRAFT',
      entries: [expect.objectContaining({ workDate: '2026-09-01', hoursWorked: 6 })],
    }));
    expect(await screen.findByText('Draft saved.')).toBeInTheDocument();
  });

  it('View Hours does not sum hours from duplicate/re-entered records for the same day', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({
      data: {
        data: [
          record({
            id: 'draft-old',
            createdAt: '2026-09-01T08:00:00',
            updatedAt: '2026-09-01T08:00:00',
            entries: [{ workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 }],
            totalHours: 8,
          }),
          record({
            id: 'draft-new',
            createdAt: '2026-09-01T12:00:00',
            updatedAt: '2026-09-01T12:00:00',
            entries: [{ workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 6 }],
            totalHours: 6,
          }),
        ],
      },
    });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'View Hours' }));
    expect(screen.getByRole('heading', { name: 'Hours overview' })).toBeInTheDocument();
    // Replaced 6.00h should be shown, NOT 14.00h (8 + 6)
    expect(screen.getAllByText('6.00h').length).toBeGreaterThan(0);
    expect(screen.queryByText('14.00h')).not.toBeInTheDocument();
  });

  it('shows existing dates in modal and allows re-entry of submitted timesheet with reason and replaced hours', async () => {
    const user = userEvent.setup();
    const thisWeek = currentWeekPeriod();
    // "Today" rather than a fixed weekday: it's guaranteed to fall inside the
    // current week's Mon-Sun range (so it's never read-only as a future day)
    // no matter which day of the week the suite happens to run on.
    const tuesday = toDateKey(new Date());
    const existingSubmitted = record({
      id: 'submitted-1',
      periodStartDate: thisWeek.start,
      periodEndDate: thisWeek.end,
      status: 'SUBMITTED',
      submittedAt: `${tuesday}T10:00:00`,
      entries: [
        { workDate: tuesday, dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Initial submission' },
      ],
      totalHours: 8,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [existingSubmitted] } });
    timesheetApi.update.mockResolvedValue({
      data: {
        data: {
          ...existingSubmitted,
          entries: [
            { workDate: tuesday, dayOfWeek: 'TUESDAY', hoursWorked: 7.5, description: 'Corrected' },
          ],
          totalHours: 7.5,
          changeReason: 'Corrected Tuesday hours per manager approval',
          status: 'SUBMITTED',
        },
      },
    });

    renderPage('EMPLOYEE');

    expect(await screen.findByRole('heading', { name: 'My Timesheets' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    // Confirmation modal should show existing dates and submitted notice
    expect(await screen.findByRole('heading', { name: 'Re-enter Submitted Timesheet?' })).toBeInTheDocument();
    expect(screen.getByText('Timesheet already submitted for this period')).toBeInTheDocument();
    expect(screen.getByText('Dates & hours currently recorded:')).toBeInTheDocument();
    expect(screen.getByText('approved changed timesheet')).toBeInTheDocument();

    // Confirm re-edit
    await user.click(screen.getByRole('button', { name: 'Confirm re-edit' }));

    // Re-edit banner and Reason for change field must be visible
    expect(screen.getByText('Re-editing submitted timesheet')).toBeInTheDocument();
    const reasonInput = screen.getByLabelText(/Reason for change/i);
    expect(reasonInput).toBeInTheDocument();

    // Pre-filled existing 8h
    const hoursInput = screen.getByLabelText((content, element) => element.name === `hours-${tuesday}`);
    expect(hoursInput).toHaveValue(8);

    // Try reviewing without reason - must show error
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    expect(await screen.findByText('Please provide a reason for the change before reviewing the timesheet.')).toBeInTheDocument();

    // Enter reason and replace 8h with 7.5h
    await user.type(reasonInput, 'Corrected Tuesday hours per manager approval');
    await user.clear(hoursInput);
    await user.type(hoursInput, '7.5');

    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    // Review step shows reason for change and recommendation callout
    expect(screen.getByText('Corrected Tuesday hours per manager approval')).toBeInTheDocument();
    expect(screen.getAllByText(/Approved changed timesheet mandatory/i).length).toBeGreaterThan(0);

    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-chg-1', fileName: 'amended.pdf' } },
    });
    await user.upload(screen.getByLabelText('Supporting document (Approved changed timesheet mandatory)'), new File(['pdf'], 'amended.pdf', { type: 'application/pdf' }));

    // Submit
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    expect(timesheetApi.update).toHaveBeenCalledWith('submitted-1', expect.objectContaining({
      confirmReedit: true,
      changeReason: 'Corrected Tuesday hours per manager approval',
      status: 'SUBMITTED',
      entries: [expect.objectContaining({ workDate: tuesday, hoursWorked: 7.5 })],
    }));
    expect(await screen.findByRole('heading', { name: 'Timesheet submitted' })).toBeInTheDocument();
  });

  it('does not display re-edit in submitted timesheets view and provides calculated period download', async () => {
    const user = userEvent.setup();
    const existingSubmitted = record({
      id: 'submitted-2',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      status: 'SUBMITTED',
      submittedAt: '2026-09-08T10:00:00',
      entries: [{ workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8 }],
      totalHours: 8,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [existingSubmitted] } });
    timesheetApi.downloadPeriodPdf.mockResolvedValue({ data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }) });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    const table = await screen.findByRole('table');
    // Ensure Re-edit button is NOT present in submitted timesheets as requested
    expect(within(table).queryByRole('button', { name: 'Re-edit' })).not.toBeInTheDocument();
    expect(within(table).getByRole('button', { name: /^(View details|Review)$/ })).toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Download' })).not.toBeInTheDocument();

    // Verify calculated period download button is present and functional
    const periodPdfButton = screen.getByRole('button', { name: 'Download Period PDF' });
    expect(periodPdfButton).toBeInTheDocument();
    await user.click(periodPdfButton);
    expect(timesheetApi.downloadPeriodPdf).toHaveBeenCalled();
  });

  it('prevents accidental value changes on mouse wheel and arrow keys for hours input', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const hoursInput = screen.getAllByLabelText('Hours')[0];
    hoursInput.focus();
    expect(hoursInput).toHaveFocus();

    const blurSpy = vi.spyOn(hoursInput, 'blur');
    const wheelEvent = new WheelEvent('wheel', { bubbles: true, cancelable: true });
    hoursInput.dispatchEvent(wheelEvent);

    expect(wheelEvent.defaultPrevented).toBe(true);
    expect(blurSpy).toHaveBeenCalled();

    const upEvent = new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true });
    hoursInput.dispatchEvent(upEvent);
    expect(upEvent.defaultPrevented).toBe(true);

    const downEvent = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    hoursInput.dispatchEvent(downEvent);
    expect(downEvent.defaultPrevented).toBe(true);
  });

  it('detects conflict and prompts re-entry modal when selected range overlaps with an existing submitted timesheet', async () => {
    const user = userEvent.setup();
    const existingSubmitted = {
      ...record({
        id: 'submitted-overlap',
        status: 'SUBMITTED',
        periodStartDate: '2026-09-07',
        periodEndDate: '2026-09-13',
        submittedAt: '2026-09-07T12:00:00Z',
        entries: [
          { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8, description: 'Monday dev' },
          { workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Tuesday dev' },
          { workDate: '2026-09-09', dayOfWeek: 'WEDNESDAY', hoursWorked: 8, description: 'Wednesday dev' },
        ],
        totalHours: 24,
      }),
    };

    timesheetApi.list.mockResolvedValue({ data: { data: [existingSubmitted] } });

    renderPage('EMPLOYEE');

    // Click "Enter Timesheet"
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));

    // Select a partial/overlapping range: Sep 7 to Sep 10 (not exact match to Sep 7 - 13)
    await pickDateRange(user, 'Timesheet period', { startKey: '2026-09-07', endKey: '2026-09-10' });

    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    // Re-entry modal should appear detecting the overlap conflict
    expect(await screen.findByRole('heading', { name: /Timesheet Conflict|Re-enter Submitted/ })).toBeInTheDocument();
    expect(screen.getByText('Timesheet already submitted for overlapping period')).toBeInTheDocument();
    expect(screen.getByText('Dates & hours currently recorded:')).toBeInTheDocument();

    // Confirm re-edit
    await user.click(screen.getByRole('button', { name: 'Confirm re-edit' }));

    // Hours step should open with the submitted timesheet loaded
    expect(screen.getByText('Re-editing submitted timesheet')).toBeInTheDocument();
    expect(screen.getByLabelText(/Reason for change/i)).toBeInTheDocument();

    // The recorded hours should be prefilled
    const hoursInputs = screen.getAllByLabelText('Hours');
    expect(hoursInputs[0]).toHaveValue(8);
    expect(hoursInputs[1]).toHaveValue(8);
    expect(hoursInputs[2]).toHaveValue(8);
  });

  it('disables picking a future start date, and marks future days read-only for a range that extends into the future', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));

    const today = toDateKey(new Date());

    // A future date cannot even be picked as the start of a new range - the
    // calendar disables it outright instead of rejecting it after the fact.
    await user.click(screen.getByLabelText('Timesheet period'));
    const popover = screen.getByRole('dialog', { name: /select timesheet period/i });
    const futureStart = addDaysKey(today, 5);
    const targetMonthLabel = new Date(`${futureStart}T00:00:00`).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    let guard = 0;
    while (!within(popover).queryByText(targetMonthLabel) && guard < 300) {
      await user.click(within(popover).getByRole('button', { name: 'Next month' }));
      guard += 1;
    }
    expect(within(popover).getByRole('button', { name: new RegExp(`^${formatDateLong(futureStart)}`) })).toBeDisabled();
    await user.click(screen.getByLabelText('Timesheet period')); // close without selecting

    // A range that straddles today keeps past days editable and locks future days
    const rangeStart = addDaysKey(today, -2);
    const rangeEnd = addDaysKey(today, 3);
    await pickDateRange(user, 'Timesheet period', { startKey: rangeStart, endKey: rangeEnd });
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const todayHours = await screen.findByLabelText((_, el) => el.name === `hours-${today}`);
    expect(todayHours).not.toBeDisabled();
    expect(screen.getByLabelText((_, el) => el.name === `hours-${addDaysKey(today, 1)}`)).toBeDisabled();
    expect(screen.getByLabelText((_, el) => el.name === `hours-${rangeEnd}`)).toBeDisabled();
    expect(timesheetApi.create).not.toHaveBeenCalled();
  });

  it('detects conflict for REJECTED timesheet, shows conflicted dates in modal, and requires reason for change on re-entry', async () => {
    const user = userEvent.setup();
    const rejectedRecord = record({
      id: 'ts-rejected-1',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      status: 'REJECTED',
      totalHours: 28.5,
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8.5 },
        { workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
        { workDate: '2026-09-09', dayOfWeek: 'WEDNESDAY', hoursWorked: 8 },
        { workDate: '2026-09-10', dayOfWeek: 'THURSDAY', hoursWorked: 2 },
        { workDate: '2026-09-11', dayOfWeek: 'FRIDAY', hoursWorked: 2 },
      ],
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [rejectedRecord] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));

    await pickDateRange(user, 'Timesheet period', { startKey: '2026-09-07', endKey: '2026-09-13' });

    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    // Re-entry modal should appear showing conflicted dates
    expect(await screen.findByRole('heading', { name: /Timesheet Conflict: Period Previously Rejected|Re-enter/ })).toBeInTheDocument();
    expect(screen.getByText('Timesheet already rejected for this period')).toBeInTheDocument();
    expect(screen.getByText('Dates & hours currently recorded:')).toBeInTheDocument();
    expect(screen.getByText('8.50h')).toBeInTheDocument();

    // Confirm re-edit
    await user.click(screen.getByRole('button', { name: 'Confirm re-edit' }));

    // Hours step should open with re-edit banner and Reason for change
    expect(screen.getByText('Re-editing submitted timesheet')).toBeInTheDocument();
    const reasonInput = screen.getByLabelText(/Reason for change/i);
    expect(reasonInput).toBeInTheDocument();

    // Hours should be prefilled
    const hours = screen.getAllByLabelText('Hours');
    expect(hours[0]).toHaveValue(8.5);

    // Attempting to proceed without reason should show error
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    expect(screen.getByText(/Please provide a reason for the change/i)).toBeInTheDocument();

    // Fill reason and proceed
    await user.type(reasonInput, 'Corrected hours per manager review');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    expect(screen.getByText('Review and submit')).toBeInTheDocument();
    expect(screen.getByText('Corrected hours per manager review')).toBeInTheDocument();
  });

  it('fixes cadence to organization settings (MONTHLY), displays policy badge, updates re-edited dates in place, and disallows toggling', async () => {
    const user = userEvent.setup();
    timesheetApi.getSettings.mockResolvedValue({ data: { data: { payrollPeriod: 'MONTHLY' } } });

    // Older monthly submission for Sep 1-30 with Sep 7 = 8h, Sep 8 = 8h
    const olderMonthly = record({
      id: 'ts-older-month',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      status: 'SUBMITTED',
      submittedAt: '2026-09-01T10:00:00',
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8 },
        { workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
      ],
      totalHours: 16,
    });

    // Newer re-edited submission for Sep 7-13 where Sep 7 was changed from 8h to 6h, and Sep 9 = 4h
    const newerReedit = record({
      id: 'ts-newer-reedit',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      status: 'SUBMITTED',
      submittedAt: '2026-09-09T10:00:00',
      updatedAt: '2026-09-09T10:00:00',
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 6 },
        { workDate: '2026-09-09', dayOfWeek: 'WEDNESDAY', hoursWorked: 4 },
      ],
      totalHours: 10,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [newerReedit, olderMonthly] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    // Fixed cadence policy badge should be rendered
    expect(screen.getByText('Organization Policy')).toBeInTheDocument();
    expect(screen.getByText(/Monthly \(1st–30th\)/i)).toBeInTheDocument();

    // No cadence toggle switcher buttons should exist
    expect(screen.queryByRole('button', { name: /Weekly \(1st–7th/i })).not.toBeInTheDocument();

    // Verify Monthly view:
    // Sep 7 (6h overwritten), Sep 8 (8h), Sep 9 (4h) => Total = 18.00h
    expect(screen.getByText('Calculated Total Hours')).toBeInTheDocument();
    expect(screen.getByText('18.00h')).toBeInTheDocument();

    // Table rows should have NO Re-edit button
    const table = screen.getByRole('table');
    expect(within(table).queryByRole('button', { name: 'Re-edit' })).not.toBeInTheDocument();
  });

  it('fixes cadence to organization settings (WEEKLY) and shows weekly interval navigation', async () => {
    const user = userEvent.setup();
    timesheetApi.getSettings.mockResolvedValue({ data: { data: { payrollPeriod: 'WEEKLY' } } });

    const olderMonthly = record({
      id: 'ts-older-month',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      status: 'SUBMITTED',
      submittedAt: '2026-09-01T10:00:00',
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8 },
        { workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
      ],
      totalHours: 16,
    });

    const newerReedit = record({
      id: 'ts-newer-reedit',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      status: 'SUBMITTED',
      submittedAt: '2026-09-09T10:00:00',
      updatedAt: '2026-09-09T10:00:00',
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 6 },
        { workDate: '2026-09-09', dayOfWeek: 'WEDNESDAY', hoursWorked: 4 },
      ],
      totalHours: 10,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [newerReedit, olderMonthly] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    // Fixed Weekly cadence policy badge should be rendered
    expect(screen.getByText('Organization Policy')).toBeInTheDocument();
    expect(screen.getByText('Weekly (1st–7th, 8th–14th...)')).toBeInTheDocument();

    // No toggle switcher buttons should exist
    expect(screen.queryByRole('button', { name: /Monthly \(1st–/i })).not.toBeInTheDocument();

    // Week 1 (1st - 7th): Sep 7 has 6h => Total = 6.00h
    expect((await screen.findAllByText('6.00h')).length).toBeGreaterThanOrEqual(1);

    // Switch to Week 2 (8th - 14th): Sep 8 has 8h, Sep 9 has 4h => Total = 12.00h
    await user.click(screen.getByRole('tab', { name: /Week 2/i }));
    expect((await screen.findAllByText('12.00h')).length).toBeGreaterThanOrEqual(1);
  });

  it('shows read-only view on View submission without options to re-submit, and allows navigating to submitted timesheets', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.create.mockImplementation((payload) => Promise.resolve({
      data: {
        data: record({
          id: 'submitted-new',
          status: 'SUBMITTED',
          submittedAt: '2026-09-09T10:00:00',
          periodStartDate: payload.periodStartDate,
          periodEndDate: payload.periodEndDate,
          entries: payload.entries,
          totalHours: payload.entries.reduce((sum, entry) => sum + entry.hoursWorked, 0),
        }),
      },
    }));

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-view-sub', fileName: 'timesheet.pdf' } },
    });
    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), new File(['pdf'], 'timesheet.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    // Verify success view
    expect(await screen.findByRole('heading', { name: 'Timesheet submitted' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View submission' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View submitted timesheets' })).toBeInTheDocument();

    // Click "View submission"
    await user.click(screen.getByRole('button', { name: 'View submission' }));

    // Read-only view should be displayed
    expect(await screen.findByRole('heading', { name: 'Timesheet submission (Read-only)' })).toBeInTheDocument();
    expect(screen.getByText('Timesheet Submitted & Pending Review')).toBeInTheDocument();
    expect(screen.getByText('Submitted')).toBeInTheDocument();

    // Crucial requirement: MUST NOT have submit or save draft or back to hours buttons!
    expect(screen.queryByRole('button', { name: 'Submit timesheet' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Back to hours' })).not.toBeInTheDocument();

    // Read-only actions should be present
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back to overview' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View submitted timesheets' })).toBeInTheDocument();

    // Click "View submitted timesheets" from the read-only view
    await user.click(screen.getByRole('button', { name: 'View submitted timesheets' }));
    expect(await screen.findByRole('heading', { name: 'Submitted timesheets' })).toBeInTheDocument();
  });

  it('keeps one shared Back control without repeating the feature header', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));
    expect(await screen.findByRole('heading', { name: 'Submitted timesheets' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'My Timesheets' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Back to overview' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Back' }));

    expect(await screen.findByRole('heading', { name: /You're \d of 5 days in\./ })).toBeInTheDocument();
  });

  it('closing a detail modal opened from history returns to history, not the overview or dashboard', async () => {
    const user = userEvent.setup();
    const submitted = record({ id: 'sub-1', status: 'SUBMITTED', submittedAt: '2026-09-02T10:00:00' });
    timesheetApi.list.mockResolvedValue({ data: { data: [submitted] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));
    const table = await screen.findByRole('table');
    await user.click(within(table).getByRole('button', { name: /^(View details|Review)$/ }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    await user.click(within(screen.getByRole('dialog')).getAllByRole('button', { name: 'Close' })[0]);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Submitted timesheets' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /You're \d of 5 days in\./ })).not.toBeInTheDocument();
  });

  it('navigates directly to Submitted Timesheets from the submission success screen', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.create.mockImplementation((payload) => Promise.resolve({
      data: {
        data: record({
          id: 'submitted-direct',
          status: 'SUBMITTED',
          submittedAt: '2026-09-09T10:00:00',
          periodStartDate: payload.periodStartDate,
          periodEndDate: payload.periodEndDate,
          entries: payload.entries,
          totalHours: payload.entries.reduce((sum, entry) => sum + entry.hoursWorked, 0),
        }),
      },
    }));

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '6');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-sub-nav', fileName: 'timesheet.pdf' } },
    });
    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), new File(['pdf'], 'timesheet.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    expect(await screen.findByRole('heading', { name: 'Timesheet submitted' })).toBeInTheDocument();

    // Click "View submitted timesheets" directly from success screen
    await user.click(screen.getByRole('button', { name: 'View submitted timesheets' }));
    expect(await screen.findByRole('heading', { name: 'Submitted timesheets' })).toBeInTheDocument();
  });

  it('shows Pending badge and amber style for waiting for approval records, and Approved badge for approved records in the View Hours calendar', async () => {
    const user = userEvent.setup();
    const approvedMonthly = record({
      id: 'ts-approved-month',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      status: 'APPROVED',
      submittedAt: '2026-09-01T10:00:00',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
        { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 8 },
      ],
      totalHours: 16,
    });

    const pendingWeekly = record({
      id: 'ts-pending-week',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      status: 'SUBMITTED',
      submittedAt: '2026-09-09T10:00:00',
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8 },
        { workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
      ],
      totalHours: 16,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [pendingWeekly, approvedMonthly] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'View Hours' }));

    // Verify Approved badges for approved dates
    expect(screen.getAllByText('Approved').length).toBeGreaterThanOrEqual(2);

    // Verify Pending badges for waiting for approval dates
    expect(screen.getAllByText('Pending').length).toBeGreaterThanOrEqual(2);

    // Verify legend
    expect(screen.getByText('Waiting for approval')).toBeInTheDocument();
    expect(screen.getAllByText('Approved').length).toBeGreaterThanOrEqual(1);
  });

  it('shows previous approved hours if latest timesheet was rejected, and empty if no prior approval exists', async () => {
    const user = userEvent.setup();
    // Older approved record covering Sep 1 - Sep 4 (8h each) and Sep 7 (8h)
    const olderApproved = record({
      id: 'ts-older-approved',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      status: 'APPROVED',
      submittedAt: '2026-09-01T10:00:00',
      updatedAt: '2026-09-01T10:00:00',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8 },
      ],
      totalHours: 16,
    });

    // Newer timesheet was REJECTED: tried to change Sep 7 to 12h, and tried to add Sep 8 with 8h
    const newerRejected = record({
      id: 'ts-newer-rejected',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      status: 'REJECTED',
      submittedAt: '2026-09-08T10:00:00',
      updatedAt: '2026-09-08T10:00:00',
      entries: [
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 12 },
        { workDate: '2026-09-08', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
      ],
      totalHours: 20,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [newerRejected, olderApproved] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'View Hours' }));

    // For Sep 7: Rejected 12h should NOT be shown. It should fall back to previous approved 8.00h!
    expect(screen.queryByText('12.00h')).not.toBeInTheDocument();
    // Both Sep 1 and Sep 7 should show approved 8.00h
    expect(screen.getAllByText('8.00h').length).toBeGreaterThanOrEqual(2);

    // For Sep 8: Was rejected with NO previous approved record => should NOT show 8.00h for Sep 8!
    // Total hours for the month should be 8h (Sep 1) + 8h (Sep 7 fallback) = 16.00h
    expect(screen.getAllByText('16.00h').length).toBeGreaterThanOrEqual(1);
  });

  it('displays rejected alert banner with reason in submitted timesheets and navigates to resubmit flow', async () => {
    const user = userEvent.setup();
    const rejectedTimesheet = record({
      id: 'ts-rejected-1',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
      status: 'REJECTED',
      reviewComment: 'Overtime hours not approved by client.',
      reportingManagerLoginId: 'TD001',
      submittedAt: '2026-09-02T10:00:00',
      reviewedAt: '2026-09-03T11:00:00',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 10, description: 'Overtime dev' },
      ],
      totalHours: 10,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [rejectedTimesheet] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    // Verify rejection alert banner
    const alertBanner = screen.getByRole('alert');
    expect(within(alertBanner).getByText(/Timesheet rejected by TD001/i)).toBeInTheDocument();
    expect(within(alertBanner).getByText(/Overtime hours not approved by client/i)).toBeInTheDocument();

    // Verify table shows reason and Resubmit button
    const table = screen.getByRole('table');
    expect(within(table).getByText(/Reason: Overtime hours not approved by client/i)).toBeInTheDocument();
    const resubmitBtn = within(table).getByRole('button', { name: 'Resubmit' });
    expect(resubmitBtn).toBeInTheDocument();

    // Click Revise & Resubmit from the alert banner
    await user.click(within(alertBanner).getByRole('button', { name: 'Revise & Resubmit' }));

    // Should navigate to hours step and display reviewer feedback notice
    expect(screen.getByText('Reviewer feedback to address:')).toBeInTheDocument();
    expect(screen.getByText(/Overtime hours not approved by client/i)).toBeInTheDocument();
  });

  it('Ignore button dismisses the rejection alert banner from history', async () => {
    const user = userEvent.setup();
    const rejectedTimesheet = record({
      id: 'ts-rejected-ignore',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
      status: 'REJECTED',
      reviewComment: 'Adjust hours on Friday.',
      reportingManagerLoginId: 'TD001',
      submittedAt: '2026-09-02T10:00:00',
      reviewedAt: '2026-09-03T11:00:00',
      entries: [{ workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 }],
      totalHours: 8,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [rejectedTimesheet] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    const alertBanner = screen.getByRole('alert');
    expect(within(alertBanner).getByText(/Timesheet rejected by TD001/i)).toBeInTheDocument();

    await user.click(within(alertBanner).getByRole('button', { name: 'Ignore' }));

    expect(screen.queryByText(/Timesheet rejected by TD001/i)).not.toBeInTheDocument();
    // still on history; the record itself remains in the table
    expect(screen.getByRole('heading', { name: 'Submitted timesheets' })).toBeInTheDocument();
  });

  it('displays rejection notice and resubmit button inside Timesheet Details modal', async () => {
    const user = userEvent.setup();
    const rejectedTimesheet = record({
      id: 'ts-rejected-details',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
      status: 'REJECTED',
      reviewComment: 'Please fix Friday hours.',
      reportingManagerLoginId: 'TD001',
      submittedAt: '2026-09-02T10:00:00',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8 },
      ],
      totalHours: 8,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [rejectedTimesheet] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    const table = screen.getByRole('table');
    await user.click(within(table).getByRole('button', { name: /^(View details|Review)$/ }));

    // Modal should show rejection notice and note
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Review note: "Please fix Friday hours."/i)).toBeInTheDocument();

    // Modal footer should have Resubmit timesheet button
    const modalResubmitBtn = screen.getByRole('button', { name: 'Resubmit timesheet' });
    expect(modalResubmitBtn).toBeInTheDocument();

    await user.click(modalResubmitBtn);

    // Should take user to entry step with feedback displayed
    expect(screen.getByText('Reviewer feedback to address:')).toBeInTheDocument();
    expect(screen.getByText(/Please fix Friday hours/i)).toBeInTheDocument();
  });

  it('prompts HR for mandatory rejection reason in modal before rejecting a timesheet', async () => {
    const user = userEvent.setup();
    const submittedTs = record({
      id: 'ts-to-reject',
      employeeUserId: 'employee-1',
      employeeName: 'Jordan Davis',
      status: 'SUBMITTED',
      totalHours: 8,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [submittedTs] } });
    timesheetApi.review.mockResolvedValue({
      data: {
        data: {
          ...submittedTs,
          status: 'REJECTED',
          reviewComment: 'Missing documentation for Friday overtime.',
        },
      },
    });

    renderPage('HR', 'hr-1');

    const table = await screen.findByRole('table');
    await user.click(within(table).getByRole('button', { name: /^(View details|Review)$/ }));
    const rejectBtn = within(screen.getByRole('dialog')).getByRole('button', { name: 'Reject' });
    await user.click(rejectBtn);

    // Reject Timesheet modal should open
    expect(screen.getByRole('heading', { name: 'Reject Timesheet' })).toBeInTheDocument();
    expect(screen.getByText(/Reject timesheet for Jordan Davis/i)).toBeInTheDocument();

    // Trying to submit without reason shows validation error
    const confirmRejectBtn = screen.getByRole('button', { name: 'Confirm Rejection' });
    await user.click(confirmRejectBtn);
    expect(await screen.findByText('Please provide a reason for rejecting this timesheet.')).toBeInTheDocument();
    expect(timesheetApi.review).not.toHaveBeenCalled();

    // Typing a reason and submitting sends review API call
    const reasonInput = screen.getByLabelText(/Reason for Rejection/i);
    await user.type(reasonInput, 'Missing documentation for Friday overtime.');
    await user.click(confirmRejectBtn);

    expect(timesheetApi.review).toHaveBeenCalledWith('ts-to-reject', {
      status: 'REJECTED',
      comment: 'Missing documentation for Friday overtime.',
    });
    expect(await screen.findByText('Timesheet rejected.')).toBeInTheDocument();
  });

  it('navigates directly to history view and opens timesheet details when URL search params are provided', async () => {
    const submittedTs = record({
      id: 'ts-redirect-target',
      employeeUserId: 'employee-1',
      status: 'SUBMITTED',
      totalHours: 16,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [submittedTs] } });

    renderPage('EMPLOYEE', 'employee-1', '/myhr/employee/timesheets?mode=history&timesheetId=ts-redirect-target');

    // Should directly display history view
    expect(await screen.findByRole('heading', { name: 'Submitted timesheets' })).toBeInTheDocument();

    // Should automatically open the timesheet details modal for ts-redirect-target
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText('16.00h')).toBeInTheDocument();
  });

  it('opens active pay period details modal when clicking View Period Details on the summary card', async () => {
    const user = userEvent.setup();
    const ts1 = record({
      id: 'ts-monthly-1',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      status: 'SUBMITTED',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Client work' },
        { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 7.5, description: 'Code review' },
      ],
      totalHours: 15.5,
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [ts1] } });

    renderPage('EMPLOYEE');

    await screen.findByRole('heading', { name: 'My Timesheets' });
    await user.click(screen.getByRole('button', { name: 'Submitted Timesheets' }));

    // Both View Period Details and Download Period PDF should be present on the active pay period card
    const viewPeriodBtn = screen.getByRole('button', { name: 'View Period Details' });
    expect(viewPeriodBtn).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download Period PDF' })).toBeInTheDocument();

    // Click View Period Details
    await user.click(viewPeriodBtn);

    // Modal opens showing period details and entries
    const modal = await screen.findByRole('dialog');
    expect(modal).toBeInTheDocument();
    expect(within(modal).getByText('15.50h')).toBeInTheDocument();
    expect(within(modal).getByText('Client work')).toBeInTheDocument();
    expect(within(modal).getByText('Code review')).toBeInTheDocument();
    expect(within(modal).getByRole('button', { name: 'Download Period PDF' })).toBeInTheDocument();
  });

  it('displays reporting manager full name on review and submitted view instead of generic label', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue({
      data: {
        data: {
          reportingManager: { name: 'Sarah Connor', loginId: 'TD001' },
        },
      },
    });
    timesheetApi.create.mockResolvedValue({
      data: {
        data: record({
          id: 'created-sub-1',
          status: 'SUBMITTED',
          reportingManagerName: 'Sarah Connor',
          reportingManagerLoginId: 'TD001',
        }),
      },
    });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));
    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    // Verify manager name is displayed on review card, NOT 'Your reporting manager'
    expect(screen.getByText('Sarah Connor')).toBeInTheDocument();
    expect(screen.queryByText('Your reporting manager')).not.toBeInTheDocument();

    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-mgr-1', fileName: 'timesheet.pdf' } },
    });
    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), new File(['pdf'], 'timesheet.pdf', { type: 'application/pdf' }));

    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    // On success step
    expect(await screen.findByRole('heading', { name: 'Timesheet submitted' })).toBeInTheDocument();
    expect(screen.getByText(/was sent to your reporting manager \(Sarah Connor\)/)).toBeInTheDocument();

    // View submission
    await user.click(screen.getByRole('button', { name: 'View submission' }));
    expect(await screen.findByText(/sent to your reporting manager \(Sarah Connor\)/)).toBeInTheDocument();
  });

  it('renders Organization timesheets heading and allows COMPANY_ADMIN to review and approve/reject submissions', async () => {
    const user = userEvent.setup();
    const submittedRecord = record({
      id: 'sub-emp-1',
      employeeUserId: 'emp-99',
      employeeId: 'TD099',
      employeeName: 'Alice Walker',
      reportingManagerUserId: 'hr-1',
      reportingManagerLoginId: 'TD002',
      reportingManagerName: 'Bob Manager',
      status: 'SUBMITTED',
      totalHours: 40,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [submittedRecord] } });
    timesheetApi.review.mockResolvedValue({
      data: {
        data: {
          ...submittedRecord,
          status: 'APPROVED',
          reviewComment: 'Approved by admin',
        },
      },
    });

    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/timesheets');

    // Admin should see Organization timesheets heading
    expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
    expect(screen.queryByText('Review and approve or reject timesheets across your organization.')).not.toBeInTheDocument();

    // Table displays employee and reporting manager name
    const table = screen.getByRole('table');
    expect(within(table).getByText('Alice Walker')).toBeInTheDocument();
    expect(within(table).getByText('Bob Manager')).toBeInTheDocument();

    // Admin clicks View details
    await user.click(within(table).getByRole('button', { name: /^(View details|Review)$/ }));

    const modal = await screen.findByRole('dialog');
    expect(within(modal).getByText('Bob Manager')).toBeInTheDocument();
    const approveBtn = within(modal).getByRole('button', { name: 'Approve' });
    const rejectBtn = within(modal).getByRole('button', { name: 'Reject' });
    expect(approveBtn).toBeInTheDocument();
    expect(rejectBtn).toBeInTheDocument();

    // Admin approves
    await user.click(approveBtn);
    expect(timesheetApi.review).toHaveBeenCalledWith('sub-emp-1', expect.objectContaining({
      status: 'APPROVED',
    }));
  });

  it('opens timesheet details as popup modal on top of Admin organization table when navigated from notification, and closing it leaves Admin on the table', async () => {
    const user = userEvent.setup();
    const submittedRecord = record({
      id: 'sub-emp-notify',
      employeeUserId: 'emp-50',
      employeeId: 'TD050',
      employeeName: 'Lilly M',
      reportingManagerUserId: 'mgr-1',
      reportingManagerLoginId: 'TD001',
      reportingManagerName: 'John Doe',
      status: 'SUBMITTED',
      totalHours: 23,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [submittedRecord] } });

    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/timesheets?timesheetId=sub-emp-notify');

    // Admin should see Organization timesheets in background, NOT employee "Enter Timesheet"
    expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
    expect(screen.queryByText('Enter Timesheet')).not.toBeInTheDocument();
    expect(screen.queryByText('What would you like to do?')).not.toBeInTheDocument();

    // The modal details should be automatically open
    const modal = await screen.findByRole('dialog');
    expect(within(modal).getByText('Lilly M')).toBeInTheDocument();
    expect(within(modal).getByText('John Doe')).toBeInTheDocument();

    // Closing the modal leaves Admin on the Organization timesheets table
    const closeButtons = within(modal).getAllByRole('button', { name: 'Close' });
    await user.click(closeButtons[closeButtons.length - 1]);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
    expect(screen.queryByText('What would you like to do?')).not.toBeInTheDocument();
  });

  it('strictly forces Organization timesheets for COMPANY_ADMIN even if view=mine is in URL', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });

    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/timesheets?view=mine');

    expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
    expect(screen.queryByText('Enter Timesheet')).not.toBeInTheDocument();
    expect(screen.queryByText('What would you like to do?')).not.toBeInTheDocument();
    // Admin has no "My Timesheets" of their own, so an invalid/irrelevant
    // view=mine falls back to Team Timesheets - but the tab bar itself is
    // still present, now offering Team Timesheets and Payroll Readiness.
    expect(screen.queryByRole('tab', { name: 'My Timesheets' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'View' })).toHaveValue('submissions');
    expect(screen.getByRole('link', { name: 'Payroll Readiness' })).toBeInTheDocument();
  });

  it('filters using status pills and clears all filters with Clear filters', async () => {
    const user = userEvent.setup();
    const r1 = record({ id: 'ts-sub', employeeName: 'Anna Sub', status: 'SUBMITTED' });
    const r2 = record({ id: 'ts-app', employeeName: 'Ben App', status: 'APPROVED' });
    timesheetApi.list.mockResolvedValue({ data: { data: [r1, r2] } });

    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/timesheets');

    expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
    expect(screen.getByText('Anna Sub')).toBeInTheDocument();
    expect(screen.getByText('Ben App')).toBeInTheDocument();

    await openTimesheetFilters();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
    let clearBtn;

    // Click "Submitted" stat card
    const submittedCard = screen.getByRole('button', { name: /^Pending 1$/ });
    await user.click(submittedCard);

    // Only Anna Sub should be visible
    expect(screen.getByText('Anna Sub')).toBeInTheDocument();
    expect(screen.queryByText('Ben App')).not.toBeInTheDocument();
    await openTimesheetFilters();
    clearBtn = screen.getByRole('button', { name: 'Clear filters' });
    expect(clearBtn).not.toBeDisabled();

    // Click Clear filters
    await user.click(clearBtn);

    // Both should be visible again
    expect(screen.getByText('Anna Sub')).toBeInTheDocument();
    expect(screen.getByText('Ben App')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('allows choosing manual entry card directly from the entry method options', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    expect(screen.getByText('Upload Timesheet Document')).toBeInTheDocument();
    expect(screen.getByText('Enter Hours Manually')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    expect(await screen.findByRole('heading', { name: 'Choose your date range' })).toBeInTheDocument();
  });

  it('supports uploading an image timesheet with automated calculation and submission for approval', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    documentApi.upload.mockResolvedValue({ data: { data: { id: 'img-doc-1', fileName: 'timesheet_scan.png' } } });
    documentApi.parseTimesheet.mockResolvedValue({
      data: {
        data: {
          periodStartDate: '2026-09-08',
          periodEndDate: '2026-09-10',
          extractionStatus: 'SUCCESS',
          message: 'Extracted 3 days totaling 23.50 hours.',
          totalHours: 23.5,
          entries: [
            { workDate: '2026-09-08', hoursWorked: 8.0, description: 'Development' },
            { workDate: '2026-09-09', hoursWorked: 8.0, description: 'Design' },
            { workDate: '2026-09-10', hoursWorked: 7.5, description: 'Testing' },
          ],
        },
      },
    });
    timesheetApi.create.mockResolvedValue({
      data: { data: record({ status: 'SUBMITTED', supportingDocumentId: 'img-doc-1' }) },
    });

    renderPage('EMPLOYEE');
    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));

    const fakeImage = new File(['fake-png-bytes'], 'timesheet_scan.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Timesheet document'), fakeImage);

    expect(await screen.findByRole('heading', { name: 'Add daily hours' })).toBeInTheDocument();
    expect(screen.getByText('Hours pre-filled from timesheet_scan.png')).toBeInTheDocument();
    expect(screen.getByText('Attached timesheet: timesheet_scan.png')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    expect(await screen.findByRole('heading', { name: 'Review and submit' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));
    expect(timesheetApi.create).toHaveBeenCalledWith(
      expect.objectContaining({
        supportingDocumentIds: ['img-doc-1'],
        status: 'SUBMITTED',
        periodStartDate: '2026-09-08',
        periodEndDate: '2026-09-10',
      })
    );
  });

  it('blocks manual timesheet submission when no supporting document is uploaded and shows an inline error', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '8');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    expect(screen.getByLabelText('Supporting document (mandatory)')).toBeInTheDocument();

    // Click submit without uploading a document
    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));

    // Must be blocked with error messages
    expect(timesheetApi.create).not.toHaveBeenCalled();
    expect(await screen.findByText('A supporting document is mandatory to submit your timesheet.')).toBeInTheDocument();
    expect(await screen.findByText('Please upload a supporting timesheet document before submitting.')).toBeInTheDocument();

    // Once a document is uploaded, clicking submit succeeds
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-uploaded-1', fileName: 'signed_card.pdf' } },
    });
    timesheetApi.create.mockResolvedValue({
      data: { data: record({ status: 'SUBMITTED', supportingDocumentId: 'doc-uploaded-1' }) },
    });

    await user.upload(screen.getByLabelText('Supporting document (mandatory)'), new File(['pdf'], 'signed_card.pdf', { type: 'application/pdf' }));
    expect(await screen.findByText('Attached timesheet: signed_card.pdf')).toBeInTheDocument();
    expect(screen.queryByText('A supporting document is mandatory to submit your timesheet.')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));
    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      status: 'SUBMITTED',
      supportingDocumentIds: ['doc-uploaded-1'],
    }));
  });

  it('allows saving a draft without a supporting document in manual entry', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.create.mockResolvedValue({ data: { data: record({ id: 'draft-no-doc', status: 'DRAFT' }) } });
    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.click(screen.getByRole('button', { name: 'Skip to manual entry' }));
    await user.click(screen.getByRole('button', { name: 'Continue to hours' }));

    const hoursInput = screen.getAllByLabelText('Hours')[0];
    await user.clear(hoursInput);
    await user.type(hoursInput, '6');
    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));

    // Saving draft without a document should succeed
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      status: 'DRAFT',
      supportingDocumentIds: [],
    }));
    expect(await screen.findByText('Draft saved.')).toBeInTheDocument();
  });

  it('extracts and populates calendar format timesheet with 168 hours and project description', async () => {
    const user = userEvent.setup();
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    documentApi.upload.mockResolvedValue({
      data: { data: { id: 'doc-cal-1', fileName: 'calendar_screenshot.png' } },
    });
    timesheetApi.create.mockResolvedValue({
      data: { data: record({ status: 'SUBMITTED', totalHours: 168, supportingDocumentId: 'doc-cal-1' }) },
    });

    const entries = [];
    for (let day = 1; day <= 31; day++) {
      const dateStr = `2026-08-${String(day).padStart(2, '0')}`;
      const d = new Date(2026, 7, day);
      const isWeekend = d.getDay() === 0 || d.getDay() === 6;
      entries.push({
        workDate: dateStr,
        dayOfWeek: isWeekend ? (d.getDay() === 0 ? 'Sun' : 'Sat') : 'Mon',
        hoursWorked: isWeekend ? 0 : 8,
        description: isWeekend ? '' : 'SAP Security Projects',
      });
    }

    documentApi.parseTimesheet.mockResolvedValue({
      data: {
        data: {
          periodStartDate: '2026-08-01',
          periodEndDate: '2026-08-31',
          totalHours: 168.0,
          entries,
          extractionStatus: 'SUCCESS',
          message: 'Extracted 21 working days totaling 168.00 hours for August 2026 (SAP Security Projects). Verify dates and daily hours.',
        },
      },
    });

    renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Enter Timesheet' }));
    await user.upload(
      screen.getByLabelText('Timesheet document'),
      new File(['fake-calendar-image'], 'calendar_screenshot.png', { type: 'image/png' })
    );

    expect(await screen.findByText(/Extracted 21 working days totaling 168.00 hours for August 2026/)).toBeInTheDocument();
    expect(screen.getByText('Attached timesheet: calendar_screenshot.png')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Review timesheet' }));
    expect(screen.getByText('168.00h')).toBeInTheDocument();
    expect(screen.getByText('Attached timesheet: calendar_screenshot.png')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit timesheet' }));
    expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({
      periodStartDate: '2026-08-01',
      periodEndDate: '2026-08-31',
      status: 'SUBMITTED',
      supportingDocumentIds: ['doc-cal-1'],
      entries: expect.arrayContaining([
        expect.objectContaining({
          workDate: '2026-08-03',
          hoursWorked: 8,
          description: 'SAP Security Projects',
        }),
      ]),
    }));
  });

  it('does not display draft timesheets or draft filter/stat in HR direct-report review workspace or admin organization review workspace', async () => {
    const draftTs = record({ id: 'ts-draft', employeeName: 'Draft User', status: 'DRAFT' });
    const subTs = record({ id: 'ts-sub', employeeName: 'Sub User', status: 'SUBMITTED' });
    const appTs = record({ id: 'ts-app', employeeName: 'App User', status: 'APPROVED' });
    timesheetApi.list.mockResolvedValue({ data: { data: [draftTs, subTs, appTs] } });

    // 1. HR Review Workspace - lands on All Submissions by default
    renderPage('HR', 'hr-1');
    expect(await screen.findByRole('heading', { name: 'Direct-report timesheets' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /filter by draft/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'My team 2' })).toBeInTheDocument();
    expect(screen.getByText('Sub User')).toBeInTheDocument();
    expect(screen.getByText('App User')).toBeInTheDocument();
    expect(screen.queryByText('Draft User')).not.toBeInTheDocument();

    await openTimesheetFilters();
    const hrStatusSelect = screen.getByLabelText('Status');
    const hrOptions = Array.from(hrStatusSelect.querySelectorAll('option')).map((o) => o.value);
    expect(hrOptions).toEqual(['ALL', 'SUBMITTED', 'APPROVED', 'REJECTED']);

    // Switch to Required Actions: only the pending submitted record is shown
    await userEvent.setup().click(screen.getByRole('button', { name: /Required Actions/i }));
    expect(screen.getByText('Sub User')).toBeInTheDocument();
    expect(screen.queryByText('App User')).not.toBeInTheDocument();
    expect(screen.queryByText('Draft User')).not.toBeInTheDocument();

    // 2. Company Admin Organization Review Workspace
    cleanup();
    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/timesheets');
    expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /filter by draft/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'My team 2' })).toBeInTheDocument();
    expect(screen.getByText('Sub User')).toBeInTheDocument();
    expect(screen.getByText('App User')).toBeInTheDocument();
    expect(screen.queryByText('Draft User')).not.toBeInTheDocument();

    await openTimesheetFilters();
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument();
    const statusButtons = within(screen.getByRole('group', { name: 'Submission status shortcuts' })).getAllByRole('button');
    expect(statusButtons.map((button) => button.textContent)).toEqual(['Pending 1', 'My team 2', 'Approved 1', 'Rejected 0']);
  });

  it('splits direct report timesheets into Required Actions, By Employee directory with profile, and All Submissions', async () => {
    const user = userEvent.setup();
    const subRecord = record({
      id: 'ts-req-1',
      employeeUserId: 'emp-101',
      employeeId: 'TD101',
      employeeName: 'Elena Rostova',
      employeeRole: 'EMPLOYEE',
      reportingManagerUserId: 'hr-1',
      reportingManagerName: 'Sarah Williams',
      status: 'SUBMITTED',
      totalHours: 40,
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
      entries: [
        { workDate: '2026-09-01', dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Design specs' },
        { workDate: '2026-09-02', dayOfWeek: 'WEDNESDAY', hoursWorked: 8, description: 'API routing' },
        { workDate: '2026-09-03', dayOfWeek: 'THURSDAY', hoursWorked: 8, description: 'UI mockups' },
        { workDate: '2026-09-04', dayOfWeek: 'FRIDAY', hoursWorked: 8, description: 'Testing' },
        { workDate: '2026-09-07', dayOfWeek: 'MONDAY', hoursWorked: 8, description: 'Documentation' },
      ],
    });

    const appRecord = record({
      id: 'ts-app-1',
      employeeUserId: 'emp-102',
      employeeId: 'TD102',
      employeeName: 'Marcus Vance',
      employeeRole: 'EMPLOYEE',
      reportingManagerUserId: 'hr-1',
      reportingManagerName: 'Sarah Williams',
      status: 'APPROVED',
      totalHours: 16,
      periodStartDate: '2026-08-25',
      periodEndDate: '2026-08-31',
      entries: [
        { workDate: '2026-08-25', dayOfWeek: 'TUESDAY', hoursWorked: 8, description: 'Refactoring' },
        { workDate: '2026-08-26', dayOfWeek: 'WEDNESDAY', hoursWorked: 8, description: 'Bug fixes' },
      ],
    });

    timesheetApi.list.mockResolvedValue({ data: { data: [subRecord, appRecord] } });
    timesheetApi.review.mockResolvedValue({
      data: {
        data: {
          ...subRecord,
          status: 'APPROVED',
        },
      },
    });

    renderPage('HR', 'hr-1');

    // 1. Lands on "All Submissions" by default; switch to "Required Actions"
    expect(await screen.findByRole('heading', { name: 'Direct-report timesheets' })).toBeInTheDocument();
    expect(screen.getByText('Required Actions')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Required Actions/i }));
    expect(screen.getByText('Action Needed: 1 timesheet awaiting your review')).toBeInTheDocument();
    expect(screen.getByText('Elena Rostova')).toBeInTheDocument();
    expect(screen.queryByText('Marcus Vance')).not.toBeInTheDocument(); // Approved record is NOT in Required Actions

    // 2. Switch to "By Employee" directory
    const byEmployeeTab = screen.getByRole('button', { name: /By Employee/i });
    await user.click(byEmployeeTab);

    // Both employees are in the directory
    expect(await screen.findByText('Elena Rostova')).toBeInTheDocument();
    expect(screen.getByText('Marcus Vance')).toBeInTheDocument();

    // Elena has 1 Pending badge in the directory
    expect(screen.getByText('1 Pending')).toBeInTheDocument();

    // Click "View Profile & Hours →" for Elena Rostova
    const viewProfileButtons = screen.getAllByRole('button', { name: /View Profile & Hours/i });
    await user.click(viewProfileButtons[0]);

    // 3. In Employee Timesheet Profile
    expect(await screen.findByRole('heading', { name: 'Elena Rostova' })).toBeInTheDocument();
    expect(screen.getByText(/Employee ID:/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Back to Employee Directory/i })).toBeInTheDocument();

    // Summary metric cards
    expect(screen.getAllByText('Total Hours').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Days Worked')).toBeInTheDocument();
    expect(screen.getAllByText('Approved Hours').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Pending Hours').length).toBeGreaterThanOrEqual(1);

    // Date range preset dropdown is present
    expect(screen.getByLabelText('Date range preset')).toBeInTheDocument();
    expect(screen.getByLabelText('Start date')).toBeInTheDocument();
    expect(screen.getByLabelText('End date')).toBeInTheDocument();

    // Aggregation toggle buttons
    const monthBtn = screen.getByRole('tab', { name: 'Month-wise' });
    const weekBtn = screen.getByRole('tab', { name: 'Week-wise' });
    const dayBtn = screen.getByRole('tab', { name: 'Day-wise' });
    expect(monthBtn).toBeInTheDocument();
    expect(weekBtn).toBeInTheDocument();
    expect(dayBtn).toBeInTheDocument();

    // Test Week-wise toggle
    await user.click(weekBtn);
    expect(screen.getByText('Week Period')).toBeInTheDocument();

    // Test Day-wise toggle
    await user.click(dayBtn);
    expect(screen.getByText('Task / Description')).toBeInTheDocument();
    expect(screen.getByText('Design specs')).toBeInTheDocument();
    expect(screen.getByText('API routing')).toBeInTheDocument();

    // Submitted records section shows Elena's timesheet with View details and Download PDF
    expect(screen.getByRole('heading', { name: 'Submitted Timesheet Records' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeInTheDocument();

    // Back to Directory
    await user.click(screen.getByRole('button', { name: /Back to Employee Directory/i }));
    expect(await screen.findByText('Elena Rostova')).toBeInTheDocument();
    expect(screen.getByText('Marcus Vance')).toBeInTheDocument();

    // 4. Switch back to Required Actions and test inline Approve
    const reqActionsTab = screen.getByRole('button', { name: /Required Actions/i });
    await user.click(reqActionsTab);

    const approveBtn = screen.getByRole('button', { name: 'Approve' });
    const rejectBtn = screen.getByRole('button', { name: 'Reject' });
    const viewBtn = screen.getByRole('button', { name: /^(View details|Review)$/ });
    expect(approveBtn).toBeInTheDocument();
    expect(rejectBtn).toBeInTheDocument();
    expect(viewBtn).toBeInTheDocument();

    await user.click(approveBtn);
    expect(timesheetApi.review).toHaveBeenCalledWith('ts-req-1', expect.objectContaining({
      status: 'APPROVED',
    }));

    // 5. Switch to "All Submissions" tab
    const allSubmissionsTab = screen.getByRole('button', { name: /All Submissions/i });
    await user.click(allSubmissionsTab);

    // All records (both Elena and Marcus) are visible in the full historical table
    expect(await screen.findByText('Elena Rostova')).toBeInTheDocument();
    expect(screen.getByText('Marcus Vance')).toBeInTheDocument();
    await openTimesheetFilters();
    expect(screen.getByLabelText('Status')).toBeInTheDocument();
  });

  it('does not push dialog actions to history and Back button navigates to previous page', async () => {
    const user = userEvent.setup();
    const submitted = record({ id: 'sub-test-nav', employeeName: 'Sarah Smith', status: 'SUBMITTED', submittedAt: '2026-09-02T10:00:00' });
    timesheetApi.list.mockResolvedValue({ data: { data: [submitted] } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');

    expect(await screen.findByRole('heading', { name: 'Direct-report timesheets' })).toBeInTheDocument();

    // Opening View details opens the modal directly without polluting the URL or history
    const viewBtn = screen.getByRole('button', { name: /^(View details|Review)$/ });
    await user.click(viewBtn);

    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    // Closing the modal returns cleanly to the page without leaving residual state
    const closeBtn = within(screen.getByRole('dialog')).getAllByRole('button', { name: 'Close' })[0];
    await user.click(closeBtn);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Direct-report timesheets' })).toBeInTheDocument();
  });

  it('handles multi-criteria filtering, sorting, and clearing in By Employee directory', async () => {
    const user = userEvent.setup();
    const records = [
      record({ id: 'ts-alice', employeeUserId: 'emp-alice', employeeId: 'EMP-ALICE', employeeName: 'Alice Walker', designation: 'Data Engineer', status: 'SUBMITTED', totalHours: 40 }),
      record({ id: 'ts-bob', employeeUserId: 'emp-bob', employeeId: 'EMP-BOB', employeeName: 'Bob Miller', designation: 'QA Engineer', status: 'APPROVED', totalHours: 80 }),
      record({ id: 'ts-charlie', employeeUserId: 'emp-charlie', employeeId: 'EMP-CHARLIE', employeeName: 'Charlie Davis', designation: 'Data Engineer', status: 'REJECTED', totalHours: 20 }),
    ];
    timesheetApi.list.mockResolvedValue({ data: { data: records } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');

    // Switch to By Employee tab
    const empTab = await screen.findByRole('button', { name: /By Employee/i });
    await user.click(empTab);

    // Initial state: all 3 employees visible
    expect(await screen.findByText('Alice Walker')).toBeInTheDocument();
    expect(screen.getByText('Bob Miller')).toBeInTheDocument();
    expect(screen.getByText('Charlie Davis')).toBeInTheDocument();
    expect(screen.getByText(/Showing 1–3 of 3 employees/i)).toBeInTheDocument();

    // 1. Filter by Submissions: "With pending submissions"
    await openTimesheetFilters();
    const pendingDropdown = screen.getByLabelText('Submissions');
    await user.selectOptions(pendingDropdown, 'WITH_PENDING');

    expect(screen.getByText('Alice Walker')).toBeInTheDocument();
    expect(screen.queryByText('Bob Miller')).not.toBeInTheDocument();
    expect(screen.queryByText('Charlie Davis')).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 1–1 of 1 employee/i)).toBeInTheDocument();

    // 2. Filter by Submissions: "No pending submissions"
    await user.selectOptions(pendingDropdown, 'NO_PENDING');
    expect(screen.queryByText('Alice Walker')).not.toBeInTheDocument();
    expect(screen.getByText('Bob Miller')).toBeInTheDocument();
    expect(screen.getByText('Charlie Davis')).toBeInTheDocument();

    // 3. Combine with Role / Title filter: "Data Engineer"
    await openTimesheetFilters();
    const roleDropdown = screen.getByLabelText('Role / Title');
    await user.selectOptions(roleDropdown, 'Data Engineer');
    expect(screen.getByText('Charlie Davis')).toBeInTheDocument();
    expect(screen.queryByText('Bob Miller')).not.toBeInTheDocument();
    expect(screen.queryByText('Alice Walker')).not.toBeInTheDocument();

    // 4. Click "Clear filters"
    await openTimesheetFilters();
    let clearBtn = screen.getByRole('button', { name: 'Clear filters' });
    expect(clearBtn).toBeEnabled();
    await user.click(clearBtn);

    // All 3 restored
    expect(screen.getByText('Alice Walker')).toBeInTheDocument();
    expect(screen.getByText('Bob Miller')).toBeInTheDocument();
    expect(screen.getByText('Charlie Davis')).toBeInTheDocument();
    expect(clearBtn).toBeDisabled();

    // 5. Test Sorting: "Highest total hours"
    await openTimesheetFilters();
    const sortDropdown = screen.getByLabelText('Sort by');
    await user.selectOptions(sortDropdown, 'HOURS_DESC');

    // Retrieve rows to verify order: Bob (80h) -> Alice (40h) -> Charlie (20h)
    const rows = screen.getAllByRole('row');
    // Header is row 0, data starts at row 1
    expect(within(rows[1]).getByText('Bob Miller')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Alice Walker')).toBeInTheDocument();
    expect(within(rows[3]).getByText('Charlie Davis')).toBeInTheDocument();
  });

  it('handles period presets, sorting, pagination, and clearing in All Submissions', async () => {
    const user = userEvent.setup();
    // Generate 12 timesheet records to test pagination (PAGE_SIZE = 10)
    const records = Array.from({ length: 12 }, (_, i) => record({
      id: `ts-page-${i + 1}`,
      employeeUserId: `emp-${i + 1}`,
      employeeId: `EMP-${String(i + 1).padStart(2, '0')}`,
      employeeName: `Employee ${String(i + 1).padStart(2, '0')}`,
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
      totalHours: (i + 1) * 5,
      status: i % 2 === 0 ? 'SUBMITTED' : 'APPROVED',
      submittedAt: `2026-09-${String(i + 1).padStart(2, '0')}T10:00:00`,
    }));
    timesheetApi.list.mockResolvedValue({ data: { data: records } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');

    // Switch to All Submissions tab
    const allTab = await screen.findByRole('button', { name: /All Submissions/i });
    await user.click(allTab);

    // Default sort is NEWEST_SUBMITTED: Employee 12 down to Employee 03 on page 1
    expect(await screen.findByText('Employee 12')).toBeInTheDocument();
    expect(screen.getByText('Employee 03')).toBeInTheDocument();
    expect(screen.queryByText('Employee 01')).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 1–10 of 12/i)).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    // Click Next
    const nextBtn = screen.getByRole('button', { name: 'Next page' });
    await user.click(nextBtn);

    // Page 2 shows items 02 and 01
    expect(screen.getByText('Employee 02')).toBeInTheDocument();
    expect(screen.getByText('Employee 01')).toBeInTheDocument();
    expect(screen.queryByText('Employee 12')).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 11–12 of 12/i)).toBeInTheDocument();
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();

    // Click Previous
    const prevBtn = screen.getByRole('button', { name: 'Previous page' });
    await user.click(prevBtn);
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    // Test Status filter: "Approved"
    await openTimesheetFilters();
    const statusDropdown = screen.getByLabelText('Status');
    await user.selectOptions(statusDropdown, 'APPROVED');

    // 6 records are approved -> fits in 1 page (no pagination buttons needed)
    expect(screen.getByText(/Showing 1–6 of 6/i)).toBeInTheDocument();
    expect(screen.queryByText('Employee 01')).not.toBeInTheDocument(); // was SUBMITTED
    expect(screen.getByText('Employee 02')).toBeInTheDocument(); // was APPROVED

    // Test Clear filters
    await openTimesheetFilters();
    let clearBtn = screen.getByRole('button', { name: 'Clear filters' });
    await user.click(clearBtn);

    // Full 12 records restored, page reset to 1
    expect(screen.getByText(/Showing 1–10 of 12/i)).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
  });

  it('buckets a submission by its period START month, not by every month it merely overlaps', async () => {
    const user = userEvent.setup();
    const currentMonthStart = toDateKey(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
    // A period that starts the day before this month begins and runs deep into
    // it (e.g. "Aug 31 - Sep 29") should be treated as a PREVIOUS month
    // submission, not a current-month one, even though it mostly overlaps it.
    const spanningRecord = record({
      id: 'ts-spanning',
      employeeUserId: 'emp-spanning',
      employeeId: 'EMP-SPAN',
      employeeName: 'Spanning Employee',
      periodStartDate: addDaysKey(currentMonthStart, -1),
      periodEndDate: addDaysKey(currentMonthStart, 28),
      totalHours: 100,
      status: 'APPROVED',
    });
    const currentMonthRecord = record({
      id: 'ts-current',
      employeeUserId: 'emp-current',
      employeeId: 'EMP-CUR',
      employeeName: 'Current Month Employee',
      periodStartDate: currentMonthStart,
      periodEndDate: addDaysKey(currentMonthStart, 6),
      totalHours: 40,
      status: 'APPROVED',
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [spanningRecord, currentMonthRecord] } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /All Submissions/i }));

    expect(await screen.findByText('Current Month Employee')).toBeInTheDocument();
    expect(screen.getByText('Spanning Employee')).toBeInTheDocument();

    await openTimesheetFilters();
    const periodDropdown = screen.getByLabelText('Period');
    await user.selectOptions(periodDropdown, 'CURRENT_MONTH');

    expect(screen.getByText('Current Month Employee')).toBeInTheDocument();
    expect(screen.queryByText('Spanning Employee')).not.toBeInTheDocument();

    await user.selectOptions(periodDropdown, 'PREVIOUS_MONTH');

    expect(screen.getByText('Spanning Employee')).toBeInTheDocument();
    expect(screen.queryByText('Current Month Employee')).not.toBeInTheDocument();
  });

  it('keeps Period and Custom range mutually exclusive in All Submissions - picking one disables and clears the other', async () => {
    const user = userEvent.setup();
    const currentMonthStart = toDateKey(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
    const currentMonthRecord = record({
      id: 'ts-all-current',
      employeeUserId: 'emp-all-current',
      employeeName: 'Current Month Submission',
      periodStartDate: currentMonthStart,
      periodEndDate: addDaysKey(currentMonthStart, 6),
      status: 'APPROVED',
    });
    // Fixed, safely-in-the-past dates for the custom-range step, so this
    // record never accidentally matches the Current month preset above.
    const oldRecord = record({
      id: 'ts-all-old',
      employeeUserId: 'emp-all-old',
      employeeName: 'Old Submission',
      periodStartDate: '2020-01-01',
      periodEndDate: '2020-01-07',
      status: 'APPROVED',
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [currentMonthRecord, oldRecord] } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /All Submissions/i }));

    expect(await screen.findByText('Current Month Submission')).toBeInTheDocument();
    expect(screen.getByText('Old Submission')).toBeInTheDocument();

    // Picking a Period preset disables Custom range entirely.
    await openTimesheetFilters();
    const periodDropdown = screen.getByLabelText('Period');
    await user.selectOptions(periodDropdown, 'CURRENT_MONTH');
    expect(screen.getByText('Current Month Submission')).toBeInTheDocument();
    expect(screen.queryByText('Old Submission')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Custom range')).toBeDisabled();

    // Switching back to "All periods" re-enables Custom range; picking a
    // range there in turn disables Period and resets it back to "All periods".
    await user.selectOptions(periodDropdown, 'ALL');
    expect(screen.getByLabelText('Custom range')).not.toBeDisabled();
    await pickDateRange(user, 'Custom range', { startKey: '2020-01-01', endKey: '2020-01-07' });

    expect(screen.getByText('Old Submission')).toBeInTheDocument();
    expect(screen.queryByText('Current Month Submission')).not.toBeInTheDocument();
    await openTimesheetFilters();
    expect(screen.getByLabelText('Period')).toBeDisabled();
    await openTimesheetFilters();
    expect(screen.getByLabelText('Period')).toHaveValue('ALL');

    // Clear filters re-enables both.
    await openTimesheetFilters();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(screen.getByText('Current Month Submission')).toBeInTheDocument();
    expect(screen.getByText('Old Submission')).toBeInTheDocument();
    await openTimesheetFilters();
    expect(screen.getByLabelText('Period')).not.toBeDisabled();
    expect(screen.getByLabelText('Custom range')).not.toBeDisabled();
  });

  it('a custom range only matches periods that START inside it, not every period merely touching it', async () => {
    const user = userEvent.setup();
    // Regression coverage: picking Sep 1-30 was wrongly pulling in a
    // submission whose period starts Aug 31 and only crosses one day into
    // September.
    const spanningRecord = record({
      id: 'ts-spanning-aug',
      employeeUserId: 'emp-spanning-aug',
      employeeName: 'Late August Submission',
      periodStartDate: '2026-08-31',
      periodEndDate: '2026-09-06',
      status: 'REJECTED',
    });
    const septemberRecord = record({
      id: 'ts-clean-sept',
      employeeUserId: 'emp-clean-sept',
      employeeName: 'Clean September Submission',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-30',
      status: 'REJECTED',
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [spanningRecord, septemberRecord] } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /All Submissions/i }));

    expect(await screen.findByText('Late August Submission')).toBeInTheDocument();
    expect(screen.getByText('Clean September Submission')).toBeInTheDocument();

    await pickDateRange(user, 'Custom range', { startKey: '2026-09-01', endKey: '2026-09-30' });

    expect(screen.getByText('Clean September Submission')).toBeInTheDocument();
    expect(screen.queryByText('Late August Submission')).not.toBeInTheDocument();
  });

  it('handles sorting, search, and pagination in Required Actions workspace', async () => {
    const user = userEvent.setup();
    // 12 SUBMITTED records for Required Actions pagination
    const records = Array.from({ length: 12 }, (_, i) => record({
      id: `req-ts-${i + 1}`,
      employeeUserId: `emp-${i + 1}`,
      employeeId: `EMP-${String(i + 1).padStart(2, '0')}`,
      employeeName: `Pending Emp ${String(i + 1).padStart(2, '0')}`,
      status: 'SUBMITTED',
      totalHours: (i + 1) * 3,
      submittedAt: `2026-09-${String(i + 1).padStart(2, '0')}T12:00:00`,
    }));
    timesheetApi.list.mockResolvedValue({ data: { data: records } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /Required Actions/i }));

    // 12 newest first (12 down to 03 on page 1)
    expect(await screen.findByText(/Action Needed: 12 timesheets awaiting your review/i)).toBeInTheDocument();
    expect(screen.getByText('Pending Emp 12')).toBeInTheDocument();
    expect(screen.getByText('Pending Emp 03')).toBeInTheDocument();
    expect(screen.queryByText('Pending Emp 01')).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 1–10 of 12 pending submissions/i)).toBeInTheDocument();

    // Search filter for Emp 01
    await openTimesheetFilters();
    const searchInput = screen.getByLabelText('Search pending submissions');
    await user.type(searchInput, 'Pending Emp 01');

    expect(screen.getByText('Pending Emp 01')).toBeInTheDocument();
    expect(screen.queryByText('Pending Emp 12')).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 1–1 of 1 pending submission/i)).toBeInTheDocument();

    // Clear search
    await user.clear(searchInput);
    expect(screen.getByText(/Showing 1–10 of 12 pending submissions/i)).toBeInTheDocument();

    // Test sort by Oldest submitted
    await openTimesheetFilters();
    const sortDropdown = screen.getByLabelText('Sort by');
    await user.selectOptions(sortDropdown, 'OLDEST_SUBMITTED');

    // Emp 01 has oldest date -> appears on page 1
    expect(screen.getByText('Pending Emp 01')).toBeInTheDocument();
  });

  it('filters Required Actions by a custom range picked from a single calendar popover, matching any period that overlaps it', async () => {
    const user = userEvent.setup();
    const julyRecord = record({
      id: 'req-ts-july',
      employeeUserId: 'emp-july',
      employeeName: 'July Employee',
      status: 'SUBMITTED',
      periodStartDate: '2026-07-01',
      periodEndDate: '2026-07-31',
    });
    const septemberRecord = record({
      id: 'req-ts-sept',
      employeeUserId: 'emp-sept',
      employeeName: 'September Employee',
      status: 'SUBMITTED',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [julyRecord, septemberRecord] } });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /Required Actions/i }));
    expect(await screen.findByText('July Employee')).toBeInTheDocument();
    expect(screen.getByText('September Employee')).toBeInTheDocument();

    // The old two separate native date inputs are gone - one calendar popover.
    expect(screen.queryByLabelText('From date')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('End date')).not.toBeInTheDocument();
    await pickDateRange(user, 'Period', { startKey: '2026-07-01', endKey: '2026-07-31' });

    expect(screen.getByText('July Employee')).toBeInTheDocument();
    expect(screen.queryByText('September Employee')).not.toBeInTheDocument();

    await openTimesheetFilters();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(screen.getByText('July Employee')).toBeInTheDocument();
    expect(screen.getByText('September Employee')).toBeInTheDocument();
  });

  it('opens Document Preview modal when clicking supporting document in Required Actions table, with Download as PDF option', async () => {
    const user = userEvent.setup();
    const pendingWithDoc = record({
      id: 'ts-doc-1',
      employeeId: 'TD002',
      employeeName: 'Lilly M',
      status: 'SUBMITTED',
      supportingDocumentId: 'doc-file-123',
      supportingDocumentName: 'Tardus_Inc_Agreement.png',
      periodStartDate: '2026-06-22',
      periodEndDate: '2026-06-28',
      totalHours: 40,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [pendingWithDoc] } });
    documentApi.download.mockResolvedValue({
      data: new Blob(['fake-image-bytes'], { type: 'image/png' }),
    });

    const createObjectURLSpy = vi.fn(() => 'blob:mock-doc-preview');
    const revokeObjectURLSpy = vi.fn();
    URL.createObjectURL = createObjectURLSpy;
    URL.revokeObjectURL = revokeObjectURLSpy;

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /Required Actions/i }));

    // Find the supporting doc button in the table
    const docBtn = await screen.findByRole('button', { name: /Tardus_Inc_Agreement\.png/i });
    expect(docBtn).toBeInTheDocument();

    // Clicking does NOT trigger direct save download without preview; it opens the Document Preview modal
    await user.click(docBtn);

    // Modal dialog is opened
    const modal = await screen.findByRole('dialog', { name: /Document Preview: Tardus_Inc_Agreement\.png/i });
    expect(modal).toBeInTheDocument();
    expect(within(modal).getByText(/Employee:/i)).toBeInTheDocument();
    expect(within(modal).getByText('Lilly M')).toBeInTheDocument();

    // Download options are available inside the modal
    const downloadPdfBtn = within(modal).getByRole('button', { name: 'Download as PDF' });
    const downloadOrigBtn = within(modal).getByRole('button', { name: 'Download original' });
    const closeBtn = within(modal).getByText('Close');
    expect(downloadPdfBtn).toBeInTheDocument();
    expect(downloadOrigBtn).toBeInTheDocument();

    // Verify document was fetched from API
    expect(documentApi.download).toHaveBeenCalledWith('doc-file-123');

    // Test clicking Download as PDF
    await user.click(downloadPdfBtn);

    // Test clicking Download original
    await user.click(downloadOrigBtn);

    // Test closing modal
    await user.click(closeBtn);
    expect(screen.queryByRole('dialog', { name: /Document Preview/i })).not.toBeInTheDocument();
  });

  it('renders text document preview and allows downloading as PDF in Document Preview modal', async () => {
    const user = userEvent.setup();
    const pendingWithText = record({
      id: 'ts-doc-2',
      employeeId: 'TD003',
      employeeName: 'Sarah Connor',
      status: 'SUBMITTED',
      supportingDocumentId: 'doc-file-txt',
      supportingDocumentName: 'timesheet_hours.txt',
      periodStartDate: '2026-09-01',
      periodEndDate: '2026-09-07',
      totalHours: 35,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [pendingWithText] } });
    documentApi.download.mockResolvedValue({
      data: new Blob(['Mon: 8h, Tue: 8h, Wed: 8h'], { type: 'text/plain' }),
    });

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /Required Actions/i }));

    const docBtn = await screen.findByRole('button', { name: /timesheet_hours\.txt/i });
    await user.click(docBtn);

    const modal = await screen.findByRole('dialog', { name: /Document Preview: timesheet_hours\.txt/i });
    expect(modal).toBeInTheDocument();
    expect(await within(modal).findByText('Mon: 8h, Tue: 8h, Wed: 8h')).toBeInTheDocument();

    const downloadPdfBtn = within(modal).getByRole('button', { name: 'Download as PDF' });
    await user.click(downloadPdfBtn);
  });

  it('tests the functionality of all buttons in DocumentViewerModal for PDF preview', async () => {
    const user = userEvent.setup();
    const pendingWithPdf = record({
      id: 'ts-doc-pdf',
      employeeId: 'TD002',
      employeeName: 'Lilly M',
      status: 'SUBMITTED',
      supportingDocumentId: 'doc-pdf-123',
      supportingDocumentName: 'Timesheet_Signoff_Lilly_W36.pdf',
      periodStartDate: '2026-09-07',
      periodEndDate: '2026-09-13',
      totalHours: 40,
    });
    timesheetApi.list.mockResolvedValue({ data: { data: [pendingWithPdf] } });
    documentApi.download.mockResolvedValue({
      data: new Blob(['%PDF-1.4 test bytes'], { type: 'application/pdf' }),
    });

    URL.createObjectURL = vi.fn(() => 'blob:mock-pdf-url');
    URL.revokeObjectURL = vi.fn();
    const windowOpenSpy = vi.spyOn(window, 'open').mockImplementation(() => {});

    renderPage('HR', 'hr-1', '/myhr/hr/timesheets');
    await user.click(await screen.findByRole('button', { name: /Required Actions/i }));

    const docBtn = await screen.findByRole('button', { name: /Timesheet_Signoff_Lilly_W36\.pdf/i });
    await user.click(docBtn);

    const modal = await screen.findByRole('dialog', { name: /Document Preview: Timesheet_Signoff_Lilly_W36\.pdf/i });
    expect(modal).toBeInTheDocument();

    // 1. Initial State: Fit Width is active by default
    const fitWidthBtn = within(modal).getByRole('button', { name: 'Fit Width' });
    const fitPageBtn = within(modal).getByRole('button', { name: 'Fit Page' });
    const zoom100Btn = within(modal).getByRole('button', { name: '100%' });
    const zoom125Btn = within(modal).getByRole('button', { name: '125%' });
    const zoom150Btn = within(modal).getByRole('button', { name: '150%' });
    const fullWidthBtn = within(modal).getByRole('button', { name: /Full Width/i });
    const thumbnailsBtn = within(modal).getByRole('button', { name: 'Thumbnails' });
    const openInNewTabLink = within(modal).getByRole('link', { name: /Open in new tab/i });

    expect(fitWidthBtn).toHaveAttribute('aria-pressed', 'true');
    expect(within(modal).getByTitle('Document Preview')).toHaveAttribute('src', expect.stringContaining('view=FitH'));

    // 2. Test Fit Page button
    await user.click(fitPageBtn);
    expect(fitPageBtn).toHaveAttribute('aria-pressed', 'true');
    expect(fitWidthBtn).toHaveAttribute('aria-pressed', 'false');
    expect(within(modal).getByTitle('Document Preview')).toHaveAttribute('src', expect.stringContaining('view=Fit'));

    // 3. Test 100% button
    await user.click(zoom100Btn);
    expect(zoom100Btn).toHaveAttribute('aria-pressed', 'true');

    // 4. Test 125% button
    await user.click(zoom125Btn);
    expect(zoom125Btn).toHaveAttribute('aria-pressed', 'true');
    expect(modal.querySelector('.timesheets__doc-zoom-stage')).toHaveStyle({ width: '125%' });
    expect(within(modal).getByTitle('Document Preview')).toHaveAttribute('src', expect.stringContaining('zoom=125'));

    // 5. Test 150% button
    await user.click(zoom150Btn);
    expect(zoom150Btn).toHaveAttribute('aria-pressed', 'true');
    expect(modal.querySelector('.timesheets__doc-zoom-stage')).toHaveStyle({ width: '150%' });
    expect(within(modal).getByTitle('Document Preview')).toHaveAttribute('src', expect.stringContaining('zoom=150'));

    // 6. Test Fit Width restores 100% width
    await user.click(fitWidthBtn);
    expect(fitWidthBtn).toHaveAttribute('aria-pressed', 'true');
    expect(modal.querySelector('.timesheets__doc-zoom-stage')).toHaveStyle({ width: '100%' });

    // 7. Test Full Width toggle button
    expect(modal).not.toHaveClass('timesheets__doc-modal--expanded');
    await user.click(fullWidthBtn);
    expect(modal).toHaveClass('timesheets__doc-modal--expanded');
    expect(within(modal).getByRole('button', { name: /Standard Width/i })).toBeInTheDocument();

    const standardWidthBtn = within(modal).getByRole('button', { name: /Standard Width/i });
    await user.click(standardWidthBtn);
    expect(modal).not.toHaveClass('timesheets__doc-modal--expanded');
    expect(within(modal).getByRole('button', { name: /Full Width/i })).toBeInTheDocument();

    // 8. Test Thumbnails toggle button
    expect(thumbnailsBtn).toHaveAttribute('aria-pressed', 'false');
    await user.click(thumbnailsBtn);
    const hideThumbnailsBtn = within(modal).getByRole('button', { name: 'Hide Thumbnails' });
    expect(hideThumbnailsBtn).toHaveAttribute('aria-pressed', 'true');
    expect(within(modal).getByTitle('Document Preview')).toHaveAttribute('src', expect.stringContaining('navpanes=1'));

    await user.click(hideThumbnailsBtn);
    expect(within(modal).getByRole('button', { name: 'Thumbnails' })).toHaveAttribute('aria-pressed', 'false');
    expect(within(modal).getByTitle('Document Preview')).toHaveAttribute('src', expect.stringContaining('navpanes=0'));

    // 9. Test Open in new tab link
    await user.click(openInNewTabLink);
    expect(windowOpenSpy).toHaveBeenCalledWith('blob:mock-pdf-url', '_blank');

    // 10. Test Download as PDF and Download original buttons
    const downloadPdfBtn = within(modal).getByRole('button', { name: 'Download as PDF' });
    const downloadOrigBtn = within(modal).getByRole('button', { name: 'Download original' });
    await user.click(downloadPdfBtn);
    await user.click(downloadOrigBtn);

    // 11. Test Close button (footer Close and header ×)
    const closeBtn = within(modal.querySelector('.timesheets__doc-modal-footer')).getByRole('button', { name: 'Close' });
    await user.click(closeBtn);
    expect(screen.queryByRole('dialog', { name: /Document Preview/i })).not.toBeInTheDocument();

    windowOpenSpy.mockRestore();
  });
});




async function openTimesheetFilters() {
  const trigger = screen.queryByRole('button', { name: /^Filters/ });
  if (trigger && trigger.getAttribute('aria-expanded') !== 'true') await userEvent.setup().click(trigger);
}

describe('Admin timesheet refinements', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    timesheetApi.getSettings.mockResolvedValue({ data: { data: { payrollPeriod: 'MONTHLY' } } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: null } });
  });

it('keeps the admin review controls compact and preserves employee and review tool views', async () => {
  timesheetApi.list.mockResolvedValue({ data: { data: [record({ id: 'compact-sub', employeeName: 'Compact Reviewer', status: 'SUBMITTED' })] } });
  const user = userEvent.setup();
  renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
  await screen.findByText('Compact Reviewer');
  expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Dashboard/Timesheets');
  expect(screen.queryByRole('button', { name: 'Back' })).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: 'Organization timesheets' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  expect(screen.getByLabelText('Sort')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Pending 1' }));
  expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('View'), 'employees');
  expect(await screen.findByText('Compact Reviewer')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('View'), 'actions');
  expect(await screen.findByRole('button', { name: 'Approve' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'My team 1' }));
  expect(screen.getByLabelText('View')).toHaveValue('submissions');
  expect(screen.getByRole('link', { name: 'Payroll Readiness' })).toHaveAttribute('href', '/myhr/admin/timesheets?view=payroll');
});


it('sorts and exports all matching admin submissions rather than only the visible page', async () => {
  const rows = Array.from({ length: 12 }, (_, index) => record({
    id: `export-${index}`, employeeName: `Export Employee ${index}`, employeeId: `EMP-${index}`,
    status: 'SUBMITTED', totalHours: index + 1,
  }));
  timesheetApi.list.mockResolvedValue({ data: { data: rows } });
  const exportSpy = vi.spyOn(reportExport, 'downloadReportExcel').mockImplementation(() => {});
  try {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
    await screen.findByText('Export Employee 0');
    await user.selectOptions(screen.getByLabelText('Sort'), 'HOURS_DESC');
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(11);
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Export Employee 11');
    await user.click(screen.getByRole('button', { name: 'Export Excel' }));
    expect(exportSpy.mock.calls.at(-1)[0].rows).toHaveLength(12);
    await user.type(screen.getByLabelText('Search submissions'), 'Export Employee 11');
    await user.click(screen.getByRole('button', { name: 'Export Excel' }));
    expect(exportSpy.mock.calls.at(-1)[0].rows).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'My team 12' })).toBeInTheDocument();
  } finally { exportSpy.mockRestore(); }
});


it('shows submission timestamps without sending them through the date-only parser', async () => {
  timesheetApi.list.mockResolvedValue({ data: { data: [
    record({ id: 'date-valid', employeeName: 'Valid timestamp', status: 'SUBMITTED', submittedAt: '2026-09-16T09:05:00' }),
    record({ id: 'date-only', employeeName: 'Calendar date', status: 'SUBMITTED', submittedAt: '2026-09-18' }),
    record({ id: 'date-missing', employeeName: 'Missing timestamp', status: 'SUBMITTED', submittedAt: null }),
    record({ id: 'date-invalid', employeeName: 'Invalid timestamp', status: 'SUBMITTED', submittedAt: 'not-a-date' }),
  ] } });
  renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
  await screen.findByText('Valid timestamp');
  const table = screen.getByRole('table');
  const submittedColumn = within(table).getAllByRole('columnheader').findIndex((header) => header.textContent === 'Submitted On');
  const submittedCell = (name) => within(within(table).getByText(name).closest('tr')).getAllByRole('cell')[submittedColumn];
  expect(submittedCell('Valid timestamp')).toHaveTextContent(/09-16-2026, 9:05\sAM/);
  expect(within(submittedCell('Valid timestamp')).getByText(/09-16-2026/)).toHaveAttribute('datetime', '2026-09-16T09:05:00');
  expect(submittedCell('Calendar date')).toHaveTextContent('09-18-2026');
  expect(submittedCell('Calendar date')).not.toHaveTextContent('AM');
  expect(submittedCell('Missing timestamp')).toHaveTextContent('—');
  expect(submittedCell('Invalid timestamp')).toHaveTextContent('—');
  expect(within(table).queryByText('Invalid Date')).not.toBeInTheDocument();
});

it('filters admin submissions by the heading period selector and opens reminders in Payroll Readiness', async () => {
  const now = new Date();
  const current = toDateKey(new Date(now.getFullYear(), now.getMonth(), 1));
  const previous = toDateKey(new Date(now.getFullYear(), now.getMonth() - 1, 1));
  timesheetApi.list.mockResolvedValue({ data: { data: [
    record({ id: 'period-current', employeeName: 'Current Period Employee', periodStartDate: current, periodEndDate: addDaysKey(current, 6), status: 'SUBMITTED' }),
    record({ id: 'period-previous', employeeName: 'Previous Period Employee', periodStartDate: previous, periodEndDate: addDaysKey(previous, 6), status: 'APPROVED' }),
  ] } });
  const user = userEvent.setup();
  renderPage('COMPANY_ADMIN', 'admin-1', '/myhr/admin/timesheets');
  await screen.findByText('Current Period Employee');
  await user.selectOptions(screen.getByLabelText('Timesheet period'), 'CURRENT_MONTH');
  const table = screen.getByRole('table');
  expect(within(table).getByText('Current Period Employee')).toBeInTheDocument();
  expect(within(table).queryByText('Previous Period Employee')).not.toBeInTheDocument();
  expect(within(table).getByRole('button', { name: /^(View details|Review)$/ })).toHaveTextContent('Review');
  await user.selectOptions(screen.getByLabelText('Timesheet period'), 'PREVIOUS_MONTH');
  expect(within(table).getByText('Previous Period Employee')).toBeInTheDocument();
  expect(within(table).queryByText('Current Period Employee')).not.toBeInTheDocument();
  await user.click(screen.getByRole('link', { name: 'Reminders' }));
  expect(await screen.findByRole('heading', { name: 'Payroll Readiness' })).toBeInTheDocument();
});

});
