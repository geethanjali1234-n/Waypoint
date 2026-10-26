import { useSearchParams } from 'react-router-dom';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Modal, DetailPanel, FilterPills, Dropdown, FormLabel, StatusBadge as StatusTag, TextInput } from '../../../../components';
import myHrReportApi from '../../../../api/myHrReportApi';
import payrollReadinessApi from '../../../../api/payrollReadinessApi';
import { downloadReportExcel } from '../reports/reportExport';
import { isMobileDevice } from '../../../../utils/deviceDetection';
import MyHrFeaturePage from '../MyHrFeaturePage';
import MyHrPagination from '../MyHrPagination';
import './payrollReadiness.css';
import Table from '../../components/WorkspaceDataTable';

const EXPORT_COLUMNS = [
  { key: 'employeeId', header: 'Employee ID' },
  { key: 'employee', header: 'Employee Name' },
  { key: 'period', header: 'Required Period' },
  { key: 'workedHours', header: 'Total Hours' },
  { key: 'readiness', header: 'Readiness' },
];

const MONTHS = [
  ['1', 'January'], ['2', 'February'], ['3', 'March'], ['4', 'April'], ['5', 'May'], ['6', 'June'],
  ['7', 'July'], ['8', 'August'], ['9', 'September'], ['10', 'October'], ['11', 'November'], ['12', 'December'],
];

const READINESS_OPTIONS = [
  { value: 'ALL', label: 'All' },
  { value: 'READY', label: 'Ready' },
  { value: 'NEEDS_FOLLOW_UP', label: 'Needs Follow-up' },
  { value: 'MONTH_SUBMITTED', label: 'Month Submitted' },
  { value: 'PARTIALLY_APPROVED', label: 'Partially Approved' },
  { value: 'MISSING_TIMESHEET', label: 'Missing' },
  { value: 'INCOMPLETE_TIMESHEET', label: 'Incomplete' },
  { value: 'AWAITING_APPROVAL', label: 'Awaiting Approval' },
  { value: 'CORRECTION_REQUIRED', label: 'Correction Required' },
];

const SORT_OPTIONS = [
  { value: 'EMPLOYEE_ASC', label: 'Employee A-Z' },
  { value: 'EMPLOYEE_DESC', label: 'Employee Z-A' },
  { value: 'STATE', label: 'Readiness' },
  { value: 'LAST_REMINDER_DESC', label: 'Recent reminder' },
];

const STATUS_STATES = {
  READY: 'approved', PARTIALLY_APPROVED: 'pending', MISSING_TIMESHEET: 'pending',
  INCOMPLETE_TIMESHEET: 'pending', AWAITING_APPROVAL: 'pending', CORRECTION_REQUIRED: 'rejected',
};

function currentPayrollMonth() {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1 };
}

// Bounds for the Payroll Month picker's year navigation (its native
// month/year popup lets you click the year to jump between years within this
// range). Generous on both sides since this report is meant for long-term,
// ongoing use - not just the last few years.
function yearsAroundCurrent() {
  const year = new Date().getFullYear();
  return Array.from({ length: 36 }, (_, index) => year - 20 + index);
}

function humanize(value) {
  if (!value) return '-';
  return value.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (match) => match.toUpperCase());
}

function parseDate(value) { return value ? new Date(`${value}T00:00:00`) : null; }

function formatFriendlyDate(value) {
  const date = parseDate(value);
  if (!date) return '-';
  return date.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
}

function formatPeriod(start, end) {
  if (!start || !end) return '-';
  return `${formatFriendlyDate(start)} - ${formatFriendlyDate(end)}`;
}

function formatHours(value) {
  if (value == null) return '-';
  return `${Number(value).toFixed(2)}h`;
}

function coverageLabel(row) {
  return Number.isFinite(row.coveredDays) && Number.isFinite(row.requiredDays) ? `${row.coveredDays}/${row.requiredDays} days` : 'Coverage unavailable';
}

function payrollMonthParam(year, month) {
  return `${year}-${String(month).padStart(2, '0')}-01`;
}

function formatDateTime(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function submissionStatus(row) {
  if (!row) return 'Unknown';
  if (row.monthSubmissionComplete) return 'Full month submitted';
  const missing = row.missingDays ?? 0;
  if (missing > 0) return `${missing} required day${missing === 1 ? '' : 's'} missing`;
  return 'Submission incomplete';
}

function reminderMessage(result) {
  if (!result) return '';
  if (result.sent > 0) return `${result.sent} reminder${result.sent === 1 ? '' : 's'} sent.`;
  if (result.skippedDuplicate > 0) return 'A reminder was already sent recently.';
  if (result.skippedReady > 0) return 'The selected employee is already ready for payroll.';
  if (result.skippedNoRecipient > 0) return 'No valid notification recipient was found.';
  if (result.failed > 0) return 'Unable to send the reminder.';
  return 'No reminders were sent.';
}

function EmployeeTypeahead({ label, value, onChange, onQueryChange, queryValue, managerOnly = false }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  useEffect(() => { if (queryValue !== undefined) setQuery(queryValue); }, [queryValue]);

  useEffect(() => {
    if (value || query.trim().length < 2) { setResults([]); setOpen(false); return undefined; }
    let active = true;
    const timer = window.setTimeout(() => {
      setLoading(true);
      myHrReportApi.searchEmployees(query, { managerOnly, limit: 12 })
        .then((response) => {
          if (!active) return;
          setResults(response.data?.data || []);
          setOpen(true);
        })
        .catch(() => {
          if (active) { setResults([]); setOpen(true); }
        })
        .finally(() => { if (active) setLoading(false); });
    }, 300);
    return () => { active = false; window.clearTimeout(timer); };
  }, [managerOnly, query, value]);

  return <div className="payroll-readiness__typeahead">
    <TextInput
      name={`${label.replace(/\s+/g, '-')}-search`}
      label={label}
      value={value ? `${value.name || 'Unnamed'} - ${value.employeeId || value.userId}` : query}
      onChange={(event) => { onChange(null); setQuery(event.target.value); onQueryChange?.(event.target.value); }}
      placeholder={managerOnly ? 'Search manager name or ID' : 'Search employee name or ID'}
      hint={value ? `${value.role || 'Employee'}${value.designation ? ` - ${value.designation}` : ''}` : 'Type at least 2 characters'}
    />
    {(value || query) && <button type="button" className="payroll-readiness__clear-inline" onClick={() => { onChange(null); setQuery(''); onQueryChange?.(''); setOpen(false); }}>Clear</button>}
    {loading && <p className="payroll-readiness__search-state">Searching...</p>}
    {open && !loading && <div className="payroll-readiness__search-results" role="listbox" aria-label={`${label} suggestions`}>
      {results.length === 0 ? <p>No employees found.</p> : results.map((emp) => <button type="button" role="option" key={emp.userId} onClick={() => { onChange(emp); setQuery(''); setOpen(false); }}>
        <strong>{emp.name || 'Unnamed employee'}</strong>
        <span>{emp.employeeId || 'No employee ID'} - {emp.role || 'Employee'}</span>
        {emp.reportingManagerName && <small>Manager: {emp.reportingManagerName}</small>}
      </button>)}
    </div>}
  </div>;
}

// The content-only workspace, with no MyHrFeaturePage/MyHrShell wrapper of its
// own, so it can be embedded as a tab inside another feature page (the
// Timesheets page's "Payroll Readiness" toggle) as well as rendered
// standalone below via the default-exported, fully-wrapped page.
export function PayrollReadinessWorkspace() {
  const [searchParams] = useSearchParams();
  const requestedMonth = searchParams.get('payrollMonth');
  const initialMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(requestedMonth || '')
    ? { year: Number(requestedMonth.slice(0, 4)), month: Number(requestedMonth.slice(5)) }
    : currentPayrollMonth();
  const [payrollYear, setPayrollYear] = useState(initialMonth.year);
  const [payrollMonth, setPayrollMonth] = useState(initialMonth.month);
  const [employee, setEmployee] = useState(null);
  const [searchText, setSearchText] = useState('');
  const [manager, setManager] = useState(null);
  const [status, setStatus] = useState(() => READINESS_OPTIONS.some(({ value }) => value === searchParams.get('status')) ? searchParams.get('status') : 'ALL');
  const [sort, setSort] = useState('EMPLOYEE_ASC');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [feedback, setFeedback] = useState('');
  const [selectedIds, setSelectedIds] = useState(() => new Set());
  const [reminderBusy, setReminderBusy] = useState('');
  const [bulkMode, setBulkMode] = useState(null);
  const [drawerRow, setDrawerRow] = useState(null);
  const [viewMode, setViewMode] = useState(() => {
    if (typeof window !== 'undefined') {
      if (isMobileDevice() || window.innerWidth <= 768) {
        return 'cards';
      }
    }
    return 'table';
  });
  const hasLoadedInitialReport = useRef(false);

  const years = useMemo(() => yearsAroundCurrent(), []);
  const search = employee ? employee.employeeId || employee.name || '' : searchText;

  const load = useCallback((nextPage = page, nextSize = size, overrides = {}) => {
    setLoading(true);
    setError('');
    const params = {
      payrollYear: overrides.payrollYear ?? payrollYear,
      payrollMonthNumber: overrides.payrollMonth ?? payrollMonth,
      search: overrides.search ?? search,
      status: overrides.status ?? status,
      managerId: (Object.hasOwn(overrides, 'manager') ? overrides.manager : manager)?.userId || '',
      sort: overrides.sort ?? sort,
      page: nextPage,
      size: nextSize,
    };
    payrollReadinessApi.getReadiness(params)
      .then((response) => {
        setData(response.data?.data);
        setPage(nextPage);
        setSize(nextSize);
      })
      .catch((fetchError) => {
        setData(null);
        setError(fetchError?.response?.data?.message || 'Unable to load payroll readiness.');
      })
      .finally(() => setLoading(false));
  }, [manager, page, payrollMonth, payrollYear, search, size, sort, status]);

  useEffect(() => {
    if (hasLoadedInitialReport.current) return;
    hasLoadedInitialReport.current = true;
    load(0, size);
  }, [load, size]); // initial page load only; filters apply via Generate

  const rows = data?.content || [];
  const summary = data?.summary || {};
  const period = data?.period;
  const totalElements = data?.totalElements || 0;
  const totalPages = data?.totalPages || 0;
  const showingStart = totalElements === 0 ? 0 : page * size + 1;
  const showingEnd = Math.min(totalElements, page * size + rows.length);
  const eligibleRows = rows.filter((row) => row.reminderEligible);
  const selectedRows = rows.filter((row) => selectedIds.has(row.employeeUserId));
  const selectedEligibleRows = selectedRows.filter((row) => row.reminderEligible);
  const allEligibleSelected = eligibleRows.length > 0 && eligibleRows.every((row) => selectedIds.has(row.employeeUserId));

  function applyFilters(nextStatus = status) {
    setStatus(nextStatus);
    setSelectedIds(new Set());
    load(0, size, { status: nextStatus });
  }

  function clearFilters() {
    const current = currentPayrollMonth();
    setPayrollYear(current.year);
    setPayrollMonth(current.month);
    setEmployee(null);
    setSearchText('');
    setManager(null);
    setStatus('ALL');
    setSort('EMPLOYEE_ASC');
    setPage(0);
    setSelectedIds(new Set());
    load(0, size, { payrollYear: current.year, payrollMonth: current.month, search: '', status: 'ALL', manager: null, sort: 'EMPLOYEE_ASC' });
  }

  function toggleRow(row) {
    if (!row.reminderEligible) return;
    setSelectedIds((previous) => {
      const next = new Set(previous);
      if (next.has(row.employeeUserId)) next.delete(row.employeeUserId);
      else next.add(row.employeeUserId);
      return next;
    });
  }

  function toggleCurrentPageEligible() {
    setSelectedIds((previous) => {
      const next = new Set(previous);
      if (allEligibleSelected) eligibleRows.forEach((row) => next.delete(row.employeeUserId));
      else eligibleRows.forEach((row) => next.add(row.employeeUserId));
      return next;
    });
  }

  function refreshCurrentPage() {
    load(page, size);
  }

  function handleSingleReminder(row) {
    setFeedback('');
    setReminderBusy(row.employeeUserId);
    payrollReadinessApi.sendReminder(row.employeeUserId, payrollMonthParam(payrollYear, payrollMonth))
      .then((response) => {
        setFeedback(reminderMessage(response.data?.data));
        setSelectedIds((previous) => {
          const next = new Set(previous);
          next.delete(row.employeeUserId);
          return next;
        });
        refreshCurrentPage();
      })
      .catch((reminderError) => setFeedback(reminderError?.response?.data?.message || 'Unable to send reminder.'))
      .finally(() => setReminderBusy(''));
  }

  function sendBulkReminder() {
    if (!bulkMode) return;
    setFeedback('');
    setReminderBusy('bulk');
    const payload = bulkMode === 'selected'
      ? { employeeUserIds: selectedEligibleRows.map((row) => row.employeeUserId) }
      : { employeeUserIds: [], search, readiness: status, responsibility: 'ALL', managerId: manager?.userId || '', sort };
    payrollReadinessApi.sendBulkReminders(payload, payrollMonthParam(payrollYear, payrollMonth))
      .then((response) => {
        const result = response.data?.data;
        setFeedback(`Payroll reminders completed. ${result?.sent || 0} sent, ${result?.skippedDuplicate || 0} already reminded recently, ${result?.skippedNoRecipient || 0} without notification recipient, ${result?.failed || 0} failed.`);
        setSelectedIds(new Set());
        setBulkMode(null);
        refreshCurrentPage();
      })
      .catch((bulkError) => setFeedback(bulkError?.response?.data?.message || 'Unable to send payroll reminders.'))
      .finally(() => setReminderBusy(''));
  }

  function payrollReadinessExportValue(column, row) {
    if (column.key === 'employee') return row.employeeName || 'Unnamed employee';
    if (column.key === 'period') return formatPeriod(row.requiredPeriodStart, row.requiredPeriodEnd);
    if (column.key === 'workedHours') {
      return row[column.key] == null ? '' : Number(Number(row[column.key]).toFixed(2));
    }
    if (column.key === 'readiness') return humanize(row.readinessState);
    return row[column.key] ?? '';
  }

  // Excel export always reflects the currently loaded page (same "what you
  // see is what you get" behavior as the Reports page's own Download Excel),
  // so it honors whatever filters and payroll month are already applied.
  function downloadPayrollReadinessExcel() {
    const monthLabel = period?.payrollMonthLabel || `${MONTHS.find(([id]) => Number(id) === payrollMonth)?.[1]} ${payrollYear}`;
    downloadReportExcel({
      filename: `Payroll_Readiness_${monthLabel.replace(/\s+/g, '_')}.xls`,
      title: `Payroll Readiness - ${monthLabel}`,
      subtitle: `Generated ${formatDateTime(new Date())}`,
      columns: EXPORT_COLUMNS,
      rows,
      resolveValue: payrollReadinessExportValue,
    });
  }

  const knownCount = (key) => Number.isFinite(summary[key]) ? summary[key] : undefined;
  const canDeriveFollowup = ['totalEmployees', 'readyForPayroll', 'awaitingApproval'].every((key) => Number.isFinite(summary[key])) && summary.readyForPayroll + summary.awaitingApproval <= summary.totalEmployees;
  const readinessPills = [
    { value: 'ALL', label: 'Total employees', count: knownCount('totalEmployees') },
    { value: 'READY', label: 'Ready', count: knownCount('readyForPayroll') },
    { value: 'NEEDS_FOLLOW_UP', label: 'Needs Follow-up', count: canDeriveFollowup ? summary.totalEmployees - summary.readyForPayroll - summary.awaitingApproval : undefined },
    { value: 'AWAITING_APPROVAL', label: 'Awaiting Approval', count: knownCount('awaitingApproval') },
    { value: 'MONTH_SUBMITTED', label: 'Month Submitted', count: knownCount('monthSubmitted') },
    { value: 'PARTIALLY_APPROVED', label: 'Partially Approved', count: knownCount('partiallyApproved') },
    { value: 'MISSING_TIMESHEET', label: 'Missing', count: knownCount('missingTimesheet') },
    { value: 'INCOMPLETE_TIMESHEET', label: 'Incomplete', count: knownCount('incompleteTimesheet') },
    { value: 'CORRECTION_REQUIRED', label: 'Correction Required', count: knownCount('correctionRequired') },
  ].map((option) => ({ ...option, disabled: loading, count: loading || error ? undefined : option.count }));

  const columns = [
    { key: 'select', header: <label className="wp-data-table__check"><input type="checkbox" aria-label="Select eligible reminders on current page" checked={allEligibleSelected} disabled={eligibleRows.length === 0} onChange={toggleCurrentPageEligible} /></label>, width: '3%' },
    { key: 'employeeId', header: 'Employee ID', width: '8%' },
    { key: 'employee', header: 'Employee Name', width: '14%' },
    { key: 'manager', header: 'Reporting Manager', width: '14%' },
    { key: 'coverage', header: 'Coverage', width: '11%' },
    { key: 'approvedHours', header: 'Approved Hours', width: '9%' },
    { key: 'totalHours', header: 'Total Hours (Incl. OT)', width: '10%' },
    { key: 'readiness', header: 'Readiness', width: '15%' },
    { key: 'actions', header: 'Actions', width: '16%' },
  ];

  return <div className="payroll-readiness__workspace">
    <section className="payroll-readiness__hero">
      <div>
        <h2>Payroll Readiness</h2>
        <span>Track employee timesheets, follow up on missing submissions, and download readiness reports.</span>
      </div>
      <dl>
        <div><dt>Payroll Month</dt><dd>{period?.payrollMonthLabel || '-'}</dd></div>
        <div><dt>Timesheet Period</dt><dd>{period ? formatPeriod(period.requiredPeriodStart, period.requiredPeriodEnd) : '-'}</dd></div>
        <div><dt>Cutoff</dt><dd>{period ? formatFriendlyDate(period.payrollCutoffDate) : '-'}</dd></div>
      </dl>
      <ReadinessFilterPopover active={status !== 'ALL' || Boolean(search || manager) || sort !== 'EMPLOYEE_ASC'}>
    <section className="payroll-readiness__filters" aria-label="Payroll readiness filters">
      <div className="wp-field payroll-readiness__month-field">
        <FormLabel htmlFor="payrollReadinessMonth">Payroll Month</FormLabel><small className="payroll-readiness__applied-month">{period?.payrollMonthLabel}</small>
        <div className="payroll-readiness__month-controls">
          <select id="payrollReadinessMonth" name="payrollReadinessMonth" value={payrollMonth} onChange={(event) => setPayrollMonth(Number(event.target.value))}>
            {MONTHS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          <select aria-label="Payroll Year" name="payrollReadinessYear" value={payrollYear} onChange={(event) => setPayrollYear(Number(event.target.value))}>
            {years.map((year) => <option key={year} value={year}>{year}</option>)}
          </select>
        </div>
      </div>


      <EmployeeTypeahead label="Employee" queryValue={searchText} onQueryChange={setSearchText} value={employee} onChange={(value) => { setEmployee(value); setSearchText(''); }} />
      <Dropdown name="payrollReadinessState" label="Readiness" options={READINESS_OPTIONS} value={status} onChange={(event) => setStatus(event.target.value)} />
      <EmployeeTypeahead label="Reporting Manager" value={manager} onChange={setManager} managerOnly />
      <Dropdown name="payrollReadinessSort" label="Sort" options={SORT_OPTIONS} value={sort} onChange={(event) => setSort(event.target.value)} />
      <div className="payroll-readiness__filter-actions">
        {/* Invisible - reserves the same label-row height as every other field
            so the buttons below line up with sibling input controls, not the
            page's leftover align-items:end quirk this replaces. */}
        <label aria-hidden="true">&nbsp;</label>
        <div className="payroll-readiness__filter-actions-buttons">
          <Button type="button" variant="primary" disabled={loading} onClick={() => load(0, size)}>{loading ? 'Loading...' : 'Generate'}</Button>
          <Button type="button" variant="secondary" onClick={clearFilters}>Clear Filters</Button>
        </div>
      </div>
    </section>

      </ReadinessFilterPopover>
    </section>

<FilterPills label="Payroll readiness filters" options={readinessPills} value={status} onChange={applyFilters} />

    {error && <div className="payroll-readiness__error" role="alert"><p>{error}</p><Button type="button" variant="secondary" onClick={() => load(page, size)}>Retry</Button></div>}
    {feedback && <div className="payroll-readiness__feedback" role="status"><p>{feedback}</p></div>}
    {loading && <p className="payroll-readiness__status" role="status">Loading payroll readiness...</p>}

    <section className="payroll-readiness__table">
      {(selectedEligibleRows.length > 0 || totalElements > 0) && <div className="payroll-readiness__bulk-bar">
        <strong>{selectedEligibleRows.length} selected for reminders</strong>
        <span>{eligibleRows.length} eligible on this page</span>
        <Button type="button" variant="secondary" disabled={selectedEligibleRows.length === 0 || reminderBusy === 'bulk'} onClick={() => setBulkMode('selected')}>Send to selected</Button>
        <Button type="button" variant="secondary" disabled={loading || reminderBusy === 'bulk'} onClick={() => setBulkMode('matching')}>Send to matching filters</Button>
        {selectedIds.size > 0 && <Button type="button" variant="ghost" onClick={() => setSelectedIds(new Set())}>Clear selection</Button>}
      </div>}
      <header>
        <div><h3>Employee Readiness</h3><p>Review coverage and resolve payroll exceptions.</p></div>
        <div className="payroll-readiness__table-actions">
          <div className="payroll-readiness__view-toggle" role="group" aria-label="View format">
            <button
              type="button"
              className={`payroll-readiness__view-toggle-btn ${viewMode === 'table' ? 'is-active' : ''}`}
              onClick={() => setViewMode('table')}
              aria-pressed={viewMode === 'table'}
              title="Table View"
            >
              Table
            </button>
            <button
              type="button"
              className={`payroll-readiness__view-toggle-btn ${viewMode === 'cards' ? 'is-active' : ''}`}
              onClick={() => setViewMode('cards')}
              aria-pressed={viewMode === 'cards'}
              title="Cards View (Best for mobile screens)"
            >
              Cards
            </button>
          </div>
          <Button type="button" variant="secondary" disabled={rows.length === 0} title="Download the current page using the applied filters" onClick={downloadPayrollReadinessExcel}>Download</Button>
          <label>Rows per page<select value={size} onChange={(event) => { setSelectedIds(new Set()); load(0, Number(event.target.value)); }}><option value="25">25</option><option value="50">50</option><option value="100">100</option></select></label>
        </div>
      </header>
      {viewMode === 'cards' ? (
        <div className="payroll-readiness__cards-grid">
          {rows.length === 0 ? (
            <p className="payroll-readiness__empty">No employees match the selected payroll readiness filters.</p>
          ) : (
            rows.map((row) => (
              <div key={row.employeeUserId} className={`payroll-readiness__card ${selectedIds.has(row.employeeUserId) ? 'is-selected' : ''}`}>
                <div className="payroll-readiness__card-header">
                  <div className="payroll-readiness__card-identity">
                    {row.reminderEligible && (
                      <input
                        type="checkbox"
                        aria-label={`Select ${row.employeeName || 'employee'} for reminder`}
                        checked={selectedIds.has(row.employeeUserId)}
                        onChange={() => toggleRow(row)}
                      />
                    )}
                    <div>
                      <strong>{row.employeeName || 'Unnamed employee'}</strong>
                      <span className="payroll-readiness__card-sub">
                        <span>{row.employeeId || 'No ID'}</span> • <span>{row.role || 'Employee'}</span>
                      </span>
                    </div>
                  </div>
                  <StatusTag status={STATUS_STATES[row.readinessState] || 'inactive'} label={humanize(row.readinessState)} />
                </div>
                <div className="payroll-readiness__card-body">
                  <div className="payroll-readiness__card-row">
                    <span className="payroll-readiness__card-label">Manager</span>
                    <span>{row.reportingManagerName || '-'}</span>
                  </div>
                  <div className="payroll-readiness__card-row">
                    <span className="payroll-readiness__card-label">Coverage</span>
                    <span>{coverageLabel(row)}{Number.isFinite(row.coveragePercentage) ? ` (${row.coveragePercentage}%)` : ''}</span>
                  </div>
                  <div className="payroll-readiness__card-row">
                    <span className="payroll-readiness__card-label">Approved / Total Hours (Incl. OT)</span>
                    <span>{formatHours(row.approvedHours)} / {formatHours(row.workedHours)}</span>
                  </div>
                  {row.lastReminderAt && (
                    <div className="payroll-readiness__card-row payroll-readiness__card-row--reminder">
                      <span className="payroll-readiness__card-label">Last Reminder</span>
                      <span>{formatDateTime(row.lastReminderAt)}</span>
                    </div>
                  )}
                </div>
                <div className="payroll-readiness__card-footer">
                  <Button type="button" variant="secondary" onClick={() => setDrawerRow(row)}>View Details</Button>
                  {row.reminderEligible ? (
                    <Button type="button" variant="primary" disabled={reminderBusy === row.employeeUserId} onClick={() => handleSingleReminder(row)}>
                      {reminderBusy === row.employeeUserId ? 'Sending...' : 'Send Reminder'}
                    </Button>
                  ) : (
                    <span className="payroll-readiness__card-ready">Ready for payroll</span>
                  )}
                </div>
              </div>
            ))
          )}
        </div>
      ) : (
        <Table
          label="Employee payroll readiness" selectedRowIds={[...selectedIds]} getRowId={(row) => row.employeeUserId}
          pagination={{ page: page + 1, pageSize: size, total: totalElements, onPageChange: (next) => { setSelectedIds(new Set()); load(next - 1, size); } }}
          showScrollControls={false}
          className="payroll-readiness__data-table"
          columns={columns}
          rows={rows}
          emptyMessage="No employees match the selected payroll readiness filters."
          renderCell={(column, row) => {
            if (column.key === 'select') return <input type="checkbox" aria-label={`Select ${row.employeeName || 'employee'} for reminder`} checked={selectedIds.has(row.employeeUserId)} disabled={!row.reminderEligible} onChange={() => toggleRow(row)} />;
            if (column.key === 'employee') return <strong>{row.employeeName || 'Unnamed employee'}</strong>;
            if (column.key === 'employeeId') return row.employeeId || '-';
            if (column.key === 'manager') return row.reportingManagerName || '-';
            if (column.key === 'period') return formatPeriod(row.requiredPeriodStart, row.requiredPeriodEnd);
            if (column.key === 'submission') return <span className={row.monthSubmissionComplete ? 'payroll-readiness__submission-complete' : 'payroll-readiness__submission-missing'}>{submissionStatus(row)}</span>;
            if (column.key === 'coverage') return <div className="payroll-readiness__coverage"><span>{coverageLabel(row)}</span>{Number.isFinite(row.coveragePercentage) && <progress aria-label={`Timesheet coverage for ${row.employeeName}`} max="100" value={Math.max(0, Math.min(100, row.coveragePercentage))} />}</div>;
            if (column.key === 'approvedHours') return formatHours(row.approvedHours);
            if (column.key === 'totalHours') return formatHours(row.workedHours);
            if (column.key === 'readiness') return <StatusTag status={STATUS_STATES[row.readinessState] || 'inactive'} label={humanize(row.readinessState)} />;
            if (column.key === 'actions') return <div className="payroll-readiness__row-actions">
              <Button type="button" variant="secondary" className="payroll-readiness__action-btn" onClick={() => setDrawerRow(row)}>View</Button>
              {row.reminderEligible ? <Button type="button" variant="ghost" className="payroll-readiness__action-btn" disabled={reminderBusy === row.employeeUserId} onClick={() => handleSingleReminder(row)}>{reminderBusy === row.employeeUserId ? 'Sending...' : 'Send Reminder'}</Button> : <small>No reminder needed</small>}
            </div>;
            return row[column.key] || '-';
          }}
        />
      )}
      {viewMode === 'cards' && <div className="payroll-readiness__pagination-bar">
        <span className="payroll-readiness__pagination-info">
          {totalElements === 0 ? '0 results' : `Showing ${showingStart}–${showingEnd} of ${totalElements}`}
        </span>
        {totalPages > 1 && (
          <MyHrPagination
            currentPage={page + 1}
            totalPages={totalPages}
            onPageChange={(nextPage) => {
              setSelectedIds(new Set());
              load(nextPage - 1, size);
            }}
          />
        )}
      </div>}
    </section>

    <Modal isOpen={Boolean(bulkMode)} title="Send timesheet reminders?" onClose={() => { if (reminderBusy !== 'bulk') setBulkMode(null); }}
      footer={<><Button type="button" variant="secondary" disabled={reminderBusy === 'bulk'} onClick={() => setBulkMode(null)}>Cancel</Button><Button type="button" variant="primary" disabled={reminderBusy === 'bulk'} onClick={sendBulkReminder}>{reminderBusy === 'bulk' ? 'Sending...' : 'Send Reminders'}</Button></>}>
      <p>{bulkMode === 'selected' ? `${selectedEligibleRows.length} selected employees will be evaluated for payroll reminders.` : `${totalElements} matching employees will be evaluated by the backend for payroll reminders.`}</p>
      <p>Employees who are ready or were reminded recently will be skipped.</p>
    </Modal>

    <DetailPanel open={Boolean(drawerRow)} title={drawerRow?.employeeName || 'Employee'} onClose={() => setDrawerRow(null)} className="payroll-readiness__detail-panel">
      {drawerRow && <>
        <div className="payroll-readiness__drawer-body">
          <StatusTag status={STATUS_STATES[drawerRow.readinessState] || 'inactive'} label={humanize(drawerRow.readinessState)} />
          <dl>
            <dt>Employee ID</dt><dd>{drawerRow.employeeId || '-'}</dd>
            <dt>Role / Designation</dt><dd>{drawerRow.role || '-'}{drawerRow.designation ? ` / ${drawerRow.designation}` : ''}</dd>
            <dt>Reporting Manager</dt><dd>{drawerRow.reportingManagerName || '-'}</dd>
            <dt>Payroll Month</dt><dd>{period?.payrollMonthLabel || '-'}</dd>
            <dt>Required Period</dt><dd>{formatPeriod(drawerRow.requiredPeriodStart, drawerRow.requiredPeriodEnd)}</dd>
            <dt>Month Submission</dt><dd>{submissionStatus(drawerRow)}</dd>
            <dt>Coverage</dt><dd>{coverageLabel(drawerRow)}{Number.isFinite(drawerRow.missingDays) ? `, ${drawerRow.missingDays} missing` : ''}</dd>
            <dt>Approved / Total Hours (Including OT)</dt><dd>{formatHours(drawerRow.approvedHours)} / {formatHours(drawerRow.workedHours)}</dd>
            <dt>Leave</dt><dd>{formatHours(drawerRow.leaveHours)}</dd>
            <dt>Timesheet statuses</dt><dd>{drawerRow.existingTimesheetStatuses?.length ? drawerRow.existingTimesheetStatuses.join(', ') : 'No record'}</dd>
            <dt>Last Reminder</dt><dd>{drawerRow.lastReminderAt ? formatDateTime(drawerRow.lastReminderAt) : 'No reminder sent for this payroll month'}</dd>
          </dl>
          <section><h4>Issue / Reason</h4><p>{drawerRow.reason || 'No additional issue details available.'}</p></section>
          <section><h4>Submission monitoring</h4><p>This status tells the vendor whether the employee has submitted timesheet entries covering the required payroll month. Approval readiness remains separate and is shown in the Readiness status.</p></section>
        </div>
      </>}
    </DetailPanel>
  </div>;
}

// The workspace above wrapped in the ordinary feature-page chrome (title,
// back button, shell). No route renders this directly anymore - Payroll
// Readiness now lives as a tab on the Timesheets page, and the old
// /payroll-readiness URLs (still linked from reminder notifications) redirect
// there (see AppRouter). Kept exported for direct, standalone use/testing.
export default function PayrollReadinessPage({ role }) {
  return <MyHrFeaturePage hideBack className="phase-six" title="Payroll Readiness" fullWidth>
    <PayrollReadinessWorkspace role={role} />
  </MyHrFeaturePage>;
}

function ReadinessFilterPopover({ children, active }) {
  const [open, setOpen] = useState(false);
  const root = useRef(null);
  const trigger = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const dismiss = (event) => {
      if (event.type === 'keydown') {
        if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); }
      } else if (!root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', dismiss);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', dismiss); };
  }, [open]);
  return <div className="payroll-readiness__filter-popover" ref={root} onMouseEnter={() => setOpen(true)} onMouseLeave={() => { if (!root.current?.contains(document.activeElement)) setOpen(false); }}>
    <Button ref={trigger} type="button" variant="secondary" aria-expanded={open} aria-controls="readiness-filter-panel" onClick={() => setOpen(true)}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M3 4h18l-7 8v7l-4 2V12L3 4Z" /></svg> Filters {active && <span aria-label="Filters applied">•</span>}
    </Button>
    {open && <div id="readiness-filter-panel" className="payroll-readiness__filter-panel" role="region" aria-label="Payroll filters">
      <header><strong>Filter payroll readiness</strong><button type="button" onClick={() => { setOpen(false); trigger.current?.focus(); }} aria-label="Close filters">Close</button></header>
      {children}
    </div>}
  </div>;
}
