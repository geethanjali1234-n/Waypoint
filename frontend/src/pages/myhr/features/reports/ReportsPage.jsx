import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, DetailPanel, Dropdown, FilterPills, StatusBadge as StatusTag } from '../../../../components';
import MyHrFeaturePage from '../MyHrFeaturePage';
import myHrReportApi from '../../../../api/myHrReportApi';
import timesheetApi from '../../../../api/timesheetApi';
import { formatDateOnly, formatDateTime } from '../../../../utils/dateFormat';
import { downloadReportCsv, downloadReportExcel, downloadReportPdf } from './reportExport';
import './reports.css';
import Table from '../../components/WorkspaceDataTable';
import StrokeIcon from '../../../../components/global/StrokeIcon';

const REPORTS = [
  { id: 'workforce', title: 'Workforce Overview', description: 'Headcount, designations, and reporting structure for your scope.', icon: ['M4 17V9h3v8M9 17V4h3v13M14 17v-6h3v6', 'M3 17h15'] },
  { id: 'timesheets', title: 'Timesheet Report', description: 'Generate scalable timesheet reports by scope, period, status, and issue.', icon: ['M3 5h14v12H3Z', 'M6 3v4M14 3v4M3 9h14M7 12h3v3H7Z'] },
];

const ISSUE_LABELS = { REJECTED: 'Rejected', SUBMITTED_CORRECTION: 'Submitted correction', PENDING_APPROVAL_3_DAYS: 'Pending approval > 3 days', ZERO_RECORDED_HOURS: 'Zero recorded hours' };
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function toDateKey(date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function parseDateKey(value) { return new Date(`${value}T00:00:00`); }
function addDays(dateKey, amount) { const date = parseDateKey(dateKey); date.setDate(date.getDate() + amount); return toDateKey(date); }
function monthRange(year, month) { return { start: toDateKey(new Date(year, month, 1)), end: toDateKey(new Date(year, month + 1, 0)) }; }
function currentWeek() { const today = new Date(); const offset = today.getDay() === 0 ? -6 : 1 - today.getDay(); today.setDate(today.getDate() + offset); return { start: toDateKey(today), end: addDays(toDateKey(today), 6) }; }
function formatPeriod(start, end) {
  if (!start || !end) return 'Select period';
  const s = parseDateKey(start); const e = parseDateKey(end);
  if (s.getFullYear() === e.getFullYear() && s.getMonth() === e.getMonth()) return `${s.toLocaleDateString('en-US', { month: 'short' })} ${s.getDate()} - ${e.getDate()}, ${e.getFullYear()}`;
  return `${s.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} - ${e.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}`;
}
function Icon({ kind }) { return <span className="reports__icon"><StrokeIcon name={kind === "timesheets" ? "time" : "people"} /></span>; }
function EmptyCell() { return <span className="reports__empty-cell">-</span>; }
function excelMonthName(start, end) {
  if (!start && !end) return 'Selected_Period';
  const s = start ? parseDateKey(start) : null;
  const e = end ? parseDateKey(end) : s;
  if (s && e && s.getFullYear() === e.getFullYear() && s.getMonth() === e.getMonth()) {
    return `${s.toLocaleDateString('en-US', { month: 'long' })}_${s.getFullYear()}`;
  }
  const startLabel = s ? s.toLocaleDateString('en-US', { month: 'short', year: 'numeric' }).replace(/ /g, '_') : 'Start';
  const endLabel = e ? e.toLocaleDateString('en-US', { month: 'short', year: 'numeric' }).replace(/ /g, '_') : 'End';
  return `${startLabel}_to_${endLabel}`;
}
function renderStatusCell(value) { return value ? <StatusTag status={value === 'SUBMITTED' ? 'pending' : value} label={value === 'SUBMITTED' ? 'Pending review' : undefined} /> : <EmptyCell />; }

function DownloadMenu({ menuRef, open, onToggle, onDownload, disabled }) {
  return (
    <div className="reports__download" ref={menuRef}>
      <Button type="button" variant="secondary" className="reports__download-trigger" disabled={disabled} aria-haspopup="menu" aria-expanded={open} onClick={onToggle}>
        Download
        <svg className="reports__download-caret" viewBox="0 0 12 8" aria-hidden="true"><path d="M1 1.5 6 6.5 11 1.5" /></svg>
      </Button>
      {open && (
        <div className="reports__download-menu" role="menu">
          <button type="button" role="menuitem" onClick={() => onDownload('pdf')}>
            <span className="reports__download-icon">PDF</span>Download as PDF
          </button>
          <button type="button" role="menuitem" onClick={() => onDownload('csv')}>
            <span className="reports__download-icon">CSV</span>Download as CSV
          </button>
        </div>
      )}
    </div>
  );
}

function SearchWithRecommendations({
  value,
  onChange,
  onSelectEmployee,
  label = 'Search',
  placeholder = 'Employee name or ID',
  managerOnly = false,
  scopePerson = null,
  onClearPerson = null,
}) {
  const [query, setQuery] = useState(value || '');
  const [recommendations, setRecommendations] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef(null);
  const requestRef = useRef(0);

  useEffect(() => {
    setQuery(value || '');
  }, [value]);

  const fetchRecommendations = useCallback((searchTerm = '') => {
    const requestId = requestRef.current + 1;
    requestRef.current = requestId;
    setLoading(true);
    myHrReportApi
      .searchEmployees(searchTerm, { managerOnly, limit: 8 })
      .then((res) => {
        if (requestRef.current === requestId) {
          setRecommendations(res.data.data || []);
        }
      })
      .catch(() => {
        if (requestRef.current === requestId) {
          setRecommendations([]);
        }
      })
      .finally(() => {
        if (requestRef.current === requestId) {
          setLoading(false);
        }
      });
  }, [managerOnly]);

  const handleInputChange = (event) => {
    const nextVal = event.target.value;
    setQuery(nextVal);
    onChange(nextVal);
    onClearPerson?.();
    setIsOpen(true);
    fetchRecommendations(nextVal.trim());
  };

  const handleFocus = () => {
    setIsOpen(true);
    fetchRecommendations(query.trim());
  };

  const handleSelect = (emp) => {
    const selectedText = emp.name || emp.employeeId || '';
    setQuery(selectedText);
    onChange(selectedText);
    onSelectEmployee?.(emp);
    setIsOpen(false);
  };

  const handleClear = () => {
    setQuery('');
    onChange('');
    onClearPerson?.();
    setIsOpen(false);
  };

  useEffect(() => {
    function handleClickOutside(event) {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const displayVal = scopePerson
    ? `${scopePerson.name || 'Unnamed'} (${scopePerson.employeeId || 'ID'})`
    : query;

  return (
    <div className="reports__search-box" ref={containerRef}>
      <label className="wp-field__label" htmlFor="reportSearchInput">
        {label}
      </label>
      <div className="reports__search-input-wrap">
        <span className="reports__search-icon" aria-hidden="true"><StrokeIcon name="search" size={20} /></span>
        <input
          id="reportSearchInput"
          name="reportSearch"
          className="wp-input reports__search-field"
          type="text"
          value={displayVal}
          onChange={handleInputChange}
          onFocus={handleFocus}
          placeholder={placeholder}
          autoComplete="off"
          role="combobox"
          aria-expanded={isOpen}
          aria-autocomplete="list"
        />
        {(query || scopePerson) && (
          <button
            type="button"
            className="reports__search-clear-btn"
            onClick={handleClear}
            aria-label="Clear employee search"
            title="Clear search"
          >
            ✕
          </button>
        )}
      </div>

      {isOpen && (
        <div className="reports__search-dropdown" role="listbox">
          <div className="reports__search-dropdown-header">
            <span>{query.trim().length >= 2 ? 'Search Results' : 'Recommended Employees (Assigned / In Scope)'}</span>
            {loading && <span className="reports__search-spinner">Loading...</span>}
          </div>

          {!loading && recommendations.length === 0 && (
            <div className="reports__search-empty">
              <p>{query.trim() ? `No employees found matching "${query}".` : 'No accessible employees found.'}</p>
            </div>
          )}

          {!loading && recommendations.length > 0 && (
            <div className="reports__search-list">
              {recommendations.map((emp) => (
                <button
                  key={emp.userId || emp.employeeId}
                  type="button"
                  className="reports__search-item"
                  role="option"
                  aria-label={`Select ${emp.name || emp.employeeId}`}
                  onClick={() => handleSelect(emp)}
                >
                  <div className="reports__search-avatar" aria-hidden="true">
                    {(emp.name || emp.employeeId || 'E').charAt(0).toUpperCase()}
                  </div>
                  <div className="reports__search-item-info">
                    <div className="reports__search-item-name-row">
                      <strong>{emp.name || 'Unnamed'}</strong>
                      {emp.employeeId && <span className="reports__search-id-pill">{emp.employeeId}</span>}
                      {emp.role && <span className="reports__search-role-pill">{emp.role}</span>}
                    </div>
                    <div className="reports__search-item-sub">
                      {emp.designation && <span>{emp.designation}</span>}
                      {emp.reportingManagerName && (
                        <span className="reports__search-manager">
                          Manager: <b>{emp.reportingManagerName}</b>
                        </span>
                      )}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function PeriodSelector({ value, onChange }) {
  const today = toDateKey(new Date());
  const base = parseDateKey(value.start || today);
  const [year, setYear] = useState(base.getFullYear());
  const [month, setMonth] = useState(base.getMonth());
  const [isCalendarOpen, setIsCalendarOpen] = useState(false);
  const [activePreset, setActivePreset] = useState('THIS_MONTH');

  const years = Array.from({ length: 8 }, (_, index) => new Date().getFullYear() - 5 + index);
  const days = [];
  let day = monthRange(year, month).start;
  const end = monthRange(year, month).end;
  while (day <= end) {
    days.push(day);
    day = addDays(day, 1);
  }

  function preset(name) {
    setActivePreset(name);
    const now = new Date();
    if (name === 'THIS_WEEK') onChange(currentWeek());
    if (name === 'LAST_WEEK') {
      const w = currentWeek();
      onChange({ start: addDays(w.start, -7), end: addDays(w.end, -7) });
    }
    if (name === 'THIS_MONTH') onChange(monthRange(now.getFullYear(), now.getMonth()));
    if (name === 'LAST_MONTH') onChange(monthRange(now.getFullYear(), now.getMonth() - 1));
    if (name === 'LAST_30_DAYS') onChange({ start: addDays(today, -29), end: today });
  }

  function selectDay(nextDay) {
    setActivePreset('CUSTOM');
    if (!value.start || value.end) onChange({ start: nextDay, end: '' });
    else onChange(nextDay < value.start ? { start: nextDay, end: value.start } : { start: value.start, end: nextDay });
  }

  return (
    <div className="reports__period-picker">
      <div className="reports__period-header">
        <div className="reports__period-badge">
          <span className="reports__period-icon" aria-hidden="true">📅</span>
          <div>
            <span className="reports__period-label">Period</span>
            <strong>{formatPeriod(value.start, value.end || value.start)}</strong>
          </div>
        </div>

        <div className="reports__period-presets">
          {[
            ['THIS_WEEK', 'This Week'],
            ['LAST_WEEK', 'Last Week'],
            ['THIS_MONTH', 'This Month'],
            ['LAST_MONTH', 'Last Month'],
            ['LAST_30_DAYS', 'Last 30 Days'],
          ].map(([id, text]) => (
            <button
              type="button"
              key={id}
              className={`reports__preset-btn ${activePreset === id ? 'is-active' : ''}`}
              onClick={() => preset(id)}
            >
              {text}
            </button>
          ))}
          <button
            type="button"
            className={`reports__preset-btn reports__preset-btn--calendar ${isCalendarOpen ? 'is-open' : ''}`}
            onClick={() => setIsCalendarOpen((prev) => !prev)}
            aria-expanded={isCalendarOpen}
          >
            {isCalendarOpen ? 'Hide Calendar ▴' : 'Custom Calendar ▾'}
          </button>
        </div>
      </div>

      {isCalendarOpen && (
        <div className="reports__period-expanded">
          <div className="reports__period-controls">
            <label>
              Year
              <select value={year} onChange={(event) => setYear(Number(event.target.value))}>
                {years.map((item) => (
                  <option key={item} value={item}>{item}</option>
                ))}
              </select>
            </label>
            <label>
              Month
              <select
                value={month}
                onChange={(event) => {
                  const next = Number(event.target.value);
                  setMonth(next);
                  onChange(monthRange(year, next));
                }}
              >
                {MONTHS.map((name, index) => (
                  <option key={name} value={index}>{name}</option>
                ))}
              </select>
            </label>
          </div>
          <div className="reports__date-grid" aria-label="Select report dates">
            {days.map((item) => {
              const selected = item === value.start || item === value.end;
              const inRange = value.start && value.end && item >= value.start && item <= value.end;
              return (
                <button
                  type="button"
                  key={item}
                  className={[selected ? 'is-selected' : '', inRange ? 'is-in-range' : ''].filter(Boolean).join(' ')}
                  onClick={() => selectDay(item)}
                >
                  {parseDateKey(item).getDate()}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

export default function ReportsPage({ role }) {
  const navigate = useNavigate();
  const [selected, setSelected] = useState(null);
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [downloadOpen, setDownloadOpen] = useState(false);
  const downloadMenuRef = useRef(null);
  const [scope, setScope] = useState(role === 'COMPANY_ADMIN' ? 'ORGANIZATION' : 'ACCESSIBLE');
  const [scopePerson, setScopePerson] = useState(null);
  const [period, setPeriod] = useState(() => monthRange(new Date().getFullYear(), new Date().getMonth()));
  const [status, setStatus] = useState('ALL');
  const [issue, setIssue] = useState('ALL');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState('PERIOD_DESC');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [timesheetReport, setTimesheetReport] = useState(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportError, setReportError] = useState(null);
  const [reviewRecord, setReviewRecord] = useState(null);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [reviewError, setReviewError] = useState(null);

  useEffect(() => {
    if (!downloadOpen) return undefined;
    function outside(event) {
      if (downloadMenuRef.current && !downloadMenuRef.current.contains(event.target)) setDownloadOpen(false);
    }
    document.addEventListener('mousedown', outside);
    return () => document.removeEventListener('mousedown', outside);
  }, [downloadOpen]);

  const fetchReport = useCallback(() => {
    setLoading(true);
    setError(null);
    myHrReportApi
      .getWorkforceReport()
      .then((response) => setReport(response.data.data))
      .catch((fetchError) => {
        setReport(null);
        setError(fetchError?.response?.data?.message || 'Unable to load report data.');
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const activeItem = REPORTS.find((item) => item.id === selected);
  const employees = report?.employees || [];
  const managerSummary = report?.managerSummary || [];
  const totalEmployees = Number.isFinite(report?.totalEmployees) ? report.totalEmployees : undefined;
  const activeEmployees = Number.isFinite(report?.activeEmployees) ? report.activeEmployees : undefined;
  const inactiveEmployees = Number.isFinite(report?.inactiveEmployees) ? report.inactiveEmployees : undefined;
  const totalManagers = Number.isFinite(report?.totalManagers) ? report.totalManagers : undefined;
  const isOrgWide = report?.scope === 'ORGANIZATION';
  const distinctDesignations = Array.isArray(report?.employees) ? new Set(employees.map((employee) => employee.designation).filter(Boolean)).size : undefined;
  const tableColumns = [
    { key: 'name', header: 'Employee' },
    { key: 'employeeId', header: 'Employee ID' },
    { key: 'designation', header: 'Designation' },
    { key: 'reportingManagerName', header: 'Reporting Manager' },
    { key: 'accountStatus', header: 'Status' },
  ];

  function handleDownload(format) {
    const item = activeItem;
    if (!item) return;
    const options = {
      filename: `myhr-${item.id}-report-${new Date().toISOString().slice(0, 10)}.${format}`,
      title: `${item.title} - ${role === 'COMPANY_ADMIN' ? 'Organization' : 'Direct Reports'}`,
      subtitle: `Generated ${formatDateTime(new Date())}`,
      columns: tableColumns,
      rows: employees,
      resolveValue: (column, row) => row[column.key] || '-',
    };
    if (format === 'csv') downloadReportCsv(options);
    else downloadReportPdf(options);
    setDownloadOpen(false);
  }

  const runTimesheetReport = useCallback((nextPage = page, nextSize = size, overrides = {}) => {
    const params = {
      scope: overrides.scope || scope,
      scopeId: (overrides.scopePerson !== undefined ? overrides.scopePerson : scopePerson)?.userId || '',
      startDate: overrides.period?.start || period.start,
      endDate: overrides.period?.end || overrides.period?.start || period.end || period.start,
      search: overrides.search !== undefined ? overrides.search : search,
      status: overrides.status || status,
      issue: overrides.issue || issue,
      sort: overrides.sort || sort,
      page: nextPage,
      size: nextSize,
    };
    if ((params.scope === 'EMPLOYEE' || params.scope === 'REPORTING_MANAGER') && !params.scopeId) {
      setReportError(`Select a ${params.scope === 'EMPLOYEE' ? 'employee' : 'reporting manager'} before generating the report.`);
      return;
    }
    setReportLoading(true);
    setReportError(null);
    myHrReportApi
      .getTimesheetReport(params)
      .then((response) => {
        setTimesheetReport(response.data.data);
        setPage(nextPage);
        setSize(nextSize);
      })
      .catch((fetchError) => {
        setTimesheetReport(null);
        setReportError(fetchError?.response?.data?.message || 'Unable to generate report.');
      })
      .finally(() => setReportLoading(false));
  }, [issue, page, period, scope, scopePerson, search, size, sort, status]);

  function clearTimesheetFilters() {
    setScope(role === 'COMPANY_ADMIN' ? 'ORGANIZATION' : 'ACCESSIBLE');
    setScopePerson(null);
    setPeriod(monthRange(new Date().getFullYear(), new Date().getMonth()));
    setStatus('ALL');
    setIssue('ALL');
    setSearch('');
    setSort('PERIOD_DESC');
    setPage(0);
    setTimesheetReport(null);
    setReportError(null);
  }

  function openReview(row) {
    setReviewRecord(row);
    setReviewLoading(true);
    setReviewError(null);
    timesheetApi
      .get(row.id)
      .then((response) => setReviewRecord(response.data.data))
      .catch((fetchError) => setReviewError(fetchError?.response?.data?.message || 'Unable to load timesheet details.'))
      .finally(() => setReviewLoading(false));
  }

  const summary = timesheetReport?.summary || {};
  const rows = timesheetReport?.content || [];
  const totalElements = timesheetReport?.totalElements || 0;
  const scopeOptions = role === 'COMPANY_ADMIN'
    ? [{ value: 'ORGANIZATION', label: 'Organization' }, { value: 'REPORTING_MANAGER', label: 'Reporting Manager' }, { value: 'EMPLOYEE', label: 'Individual Employee' }]
    : [{ value: 'ACCESSIBLE', label: 'My HR Scope' }, { value: 'REPORTING_MANAGER', label: 'My Reporting Assignments' }, { value: 'EMPLOYEE', label: 'Individual Employee' }];

  const resultColumns = [
    { key: 'employee', header: 'Employee' },
    { key: 'employeeId', header: 'Employee ID' },
    { key: 'role', header: 'Role' },
    { key: 'manager', header: 'Reporting Manager' },
    { key: 'period', header: 'Pay Period' },
    { key: 'hours', header: 'Worked Hours' },
    { key: 'status', header: 'Status' },
    { key: 'issues', header: 'Issues' },
    { key: 'actions', header: 'Actions' },
  ];
  const timesheetExcelColumns = resultColumns.filter((column) => column.key !== 'actions');

  function timesheetExportValue(column, row) {
    if (column.key === 'employee') return row.employeeName || 'Unnamed employee';
    if (column.key === 'role') return row.employeeRole || '';
    if (column.key === 'manager') return row.reportingManagerName || '';
    if (column.key === 'period') return formatPeriod(row.periodStartDate, row.periodEndDate);
    if (column.key === 'hours') return row.totalHours != null ? `${row.totalHours}h` : '';
    if (column.key === 'status') return row.status || '';
    if (column.key === 'issues') return row.issues?.length ? row.issues.map((item) => ISSUE_LABELS[item] || item).join(', ') : '';
    return row[column.key] || '';
  }

  function downloadTimesheetExcel() {
    const selectedMonth = excelMonthName(period.start, period.end || period.start);
    downloadReportExcel({
      filename: `${selectedMonth}_Employees_Timesheet.xls`,
      title: `Employees Timesheet - ${formatPeriod(period.start, period.end || period.start)}`,
      subtitle: `Generated ${formatDateTime(new Date())}`,
      columns: timesheetExcelColumns,
      rows,
      resolveValue: timesheetExportValue,
    });
  }

  return (
    <MyHrFeaturePage hideBack className="phase-six"
      title="Reports"
      description={role === 'COMPANY_ADMIN' ? 'Organization-wide workforce and timesheet reporting.' : 'Workforce and timesheet reporting for your direct reports.'}
      onBack={activeItem ? () => setSelected(null) : undefined}
      backLabel={activeItem ? 'Back to Reports' : 'Back'}
    >
      {activeItem && <FilterPills label="Report type" value={selected || 'ALL'} onChange={(value) => setSelected(value === 'ALL' ? null : value)} options={[{ value: 'ALL', label: 'All reports' }, ...REPORTS.map((item) => ({ value: item.id, label: item.id === 'timesheets' ? 'Timesheet analysis' : 'Workforce' }))]} />}
      {!activeItem ? (
        <div className="reports__catalog">
          {REPORTS.map((item) => (
            <article className="reports__card" key={item.id}>
              <Icon kind={item.id} />
              <strong>{item.title}</strong>
              <p>{item.description}</p>
              <div>
                <Button
                  type="button"
                  variant="primary"
                  onClick={() => {
                    setSelected(item.id);
                    setDownloadOpen(false);
                  }}
                >
                  Run Report
                </Button>
                <small>PDF - CSV</small>
              </div>
            </article>
          ))}
        </div>
      ) : activeItem.id === 'timesheets' ? (
        <div className="reports__detail reports__workspace">
          <section className="reports__hero">
            <div>
              <p>MY HR - REPORT WORKSPACE</p>
              <h2>Timesheet Report</h2>
              <span>Choose scope, period, optional filters, then generate a bounded report.</span>
            </div>
          </section>

          <FilterPills label="Quick Timesheet Filters" value={issue !== 'ALL' ? issue : status} options={[
            { value: 'ALL', label: 'All statuses' },
            { value: 'SUBMITTED', label: 'Awaiting Approval' },
            { value: 'PENDING_APPROVAL_3_DAYS', label: 'Overdue > 3 Days' },
            { value: 'SUBMITTED_CORRECTION', label: 'Submitted Corrections' },
            { value: 'REJECTED', label: 'Rejected' },
            { value: 'APPROVED', label: 'Approved' },
          ]} onChange={(value) => {
            const next = ['PENDING_APPROVAL_3_DAYS', 'SUBMITTED_CORRECTION'].includes(value)
              ? { status: 'ALL', issue: value } : { status: value, issue: 'ALL' };
            setStatus(next.status); setIssue(next.issue);
            runTimesheetReport(0, size, next);
          }} />

          {/* Structured Enterprise Filter Panel */}
          <section className="reports__enterprise-filters" aria-label="Timesheet report filters">
            <div className="reports__filter-grid">
              <Dropdown
                name="reportScope"
                label="Report Scope"
                options={scopeOptions}
                value={scope}
                onChange={(event) => {
                  setScope(event.target.value);
                  setScopePerson(null);
                  setPage(0);
                }}
              />

              <SearchWithRecommendations
                label={scope === 'EMPLOYEE' ? 'Individual Employee' : scope === 'REPORTING_MANAGER' ? 'Reporting Manager' : 'Search Employee'}
                placeholder="Employee name or ID"
                value={search}
                onChange={(val) => {
                  setSearch(val);
                }}
                scopePerson={scopePerson}
                managerOnly={scope === 'REPORTING_MANAGER'}
                onSelectEmployee={(emp) => {
                  if (scope === 'EMPLOYEE' || scope === 'REPORTING_MANAGER') {
                    setScopePerson(emp);
                  }
                  setSearch(emp.name || emp.employeeId || '');
                }}
                onClearPerson={() => {
                  setScopePerson(null);
                }}
              />

              <Dropdown
                name="reportStatus"
                label="Status"
                options={[
                  { value: 'ALL', label: 'All Statuses' },
                  { value: 'SUBMITTED', label: 'Submitted' },
                  { value: 'APPROVED', label: 'Approved' },
                  { value: 'REJECTED', label: 'Rejected' },
                  { value: 'DRAFT', label: 'Draft' },
                ]}
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value);
                  setPage(0);
                }}
              />

              <Dropdown
                name="reportIssue"
                label="Issue / Exception"
                options={[
                  { value: 'ALL', label: 'All Issues' },
                  { value: 'REJECTED', label: 'Rejected' },
                  { value: 'SUBMITTED_CORRECTION', label: 'Submitted corrections' },
                  { value: 'PENDING_APPROVAL_3_DAYS', label: 'Pending approval > 3 days' },
                  { value: 'ZERO_RECORDED_HOURS', label: 'Zero recorded hours' },
                ]}
                value={issue}
                onChange={(event) => {
                  setIssue(event.target.value);
                  setPage(0);
                }}
              />

              <Dropdown
                name="reportSort"
                label="Sort Order"
                options={[
                  { value: 'PERIOD_DESC', label: 'Newest period' },
                  { value: 'PERIOD_ASC', label: 'Oldest period' },
                  { value: 'SUBMITTED_DESC', label: 'Newest submission' },
                  { value: 'SUBMITTED_ASC', label: 'Oldest submission' },
                  { value: 'HOURS_DESC', label: 'Worked hours high-low' },
                  { value: 'HOURS_ASC', label: 'Worked hours low-high' },
                ]}
                value={sort}
                onChange={(event) => {
                  setSort(event.target.value);
                  setPage(0);
                }}
              />
            </div>

            {/* Dedicated Period Bar & Action Row */}
            <div className="reports__period-actions-row">
              <PeriodSelector
                value={period}
                onChange={(next) => {
                  setPeriod(next);
                  setPage(0);
                }}
              />

              <div className="reports__filter-actions">
                <Button
                  type="button"
                  variant="primary"
                  disabled={reportLoading || !period.start || !(period.end || period.start)}
                  onClick={() => runTimesheetReport(0, size)}
                >
                  {reportLoading ? 'Generating...' : 'Generate Report'}
                </Button>
                <Button type="button" variant="secondary" onClick={clearTimesheetFilters}>
                  Clear Filters
                </Button>
              </div>
            </div>
          </section>

          {reportError && (
            <div role="alert" className="reports__status reports__status--error">
              <p>{reportError}</p>
              <Button type="button" variant="secondary" onClick={() => runTimesheetReport(page, size)}>
                Retry
              </Button>
            </div>
          )}

          {!timesheetReport && !reportLoading && !reportError && (
            <p className="reports__status">
              Choose the people and period you want to review, then generate the report.
            </p>
          )}

          {reportLoading && <p role="status" className="reports__status">Generating report...</p>}

          {timesheetReport && !reportLoading && (
            <>
              <div className="reports__kpis reports__kpis--wide">
                {[
                  ['Employees', summary.employees],
                  ['Timesheets', summary.timesheets],
                  ['Total Worked Hours', Number.isFinite(summary.totalWorkedHours) ? `${summary.totalWorkedHours}h` : undefined],
                  ['Exceptions', summary.exceptions],
                ].filter(([, value]) => value !== undefined && value !== null).filter(([, value]) => value !== undefined).map(([label, value]) => (
                  <article key={label}>
                    <span>{label}</span>
                    <strong>{value}</strong>
                  </article>
                ))}
              </div>

              <section className="reports__attention">
                <header>
                  <h3>Attention Required</h3>
                  <p>Calculated from existing timesheet data only.</p>
                </header>
                {(timesheetReport.attentionRequired || []).map((item) => (
                  <div className={item.count === 0 ? 'is-muted' : ''} key={item.issue}>
                    <span>{item.label}</span>
                    <strong>{item.count}</strong>
                    <Button
                      type="button"
                      variant="secondary"
                      disabled={item.count === 0}
                      onClick={() => {
                        setIssue(item.issue);
                        runTimesheetReport(0, size, { issue: item.issue });
                      }}
                    >
                      Review
                    </Button>
                  </div>
                ))}
              </section>

              <section className="reports__results">
                <header>
                  <div>
                    <h3>Detailed Records</h3>
                    <p>
                      {totalElements === 0 ? 'No records matched the selected report.' : 'Review hours, status, and reported exceptions.'}
                    </p>
                  </div>
                  <div className="reports__results-actions">
                    <Button type="button" variant="secondary" disabled={rows.length === 0} onClick={downloadTimesheetExcel}>
                      Download Excel
                    </Button>
                    <label>
                      Rows per page
                      <select value={size} onChange={(event) => runTimesheetReport(0, Number(event.target.value))}>
                        <option value="25">25</option>
                        <option value="50">50</option>
                        <option value="100">100</option>
                      </select>
                    </label>
                  </div>
                </header>

                <Table
                  label="Timesheet report records" pagination={{ page: page + 1, pageSize: size, total: totalElements, onPageChange: (next) => runTimesheetReport(next - 1, size) }}
                  columns={resultColumns}
                  rows={rows}
                  emptyMessage="No timesheets matched the selected period and filters."
                  renderCell={(column, row) => {
                    if (column.key === 'employee') return <strong>{row.employeeName || 'Unnamed employee'}</strong>;
                    if (column.key === 'role') return row.employeeRole || <EmptyCell />;
                    if (column.key === 'manager') return row.reportingManagerName || <EmptyCell />;
                    if (column.key === 'period') return formatPeriod(row.periodStartDate, row.periodEndDate);
                    if (column.key === 'hours') return row.totalHours != null ? `${row.totalHours}h` : <EmptyCell />;
                    if (column.key === 'status') return renderStatusCell(row.status);
                    if (column.key === 'issues') return row.issues?.length ? row.issues.map((item) => ISSUE_LABELS[item] || item).join(', ') : <EmptyCell />;
                    if (column.key === 'actions') return <Button type="button" variant="secondary" onClick={() => openReview(row)}>Review</Button>;
                    return row[column.key] || <EmptyCell />;
                  }}
                />


              </section>
            </>
          )}

          {/* Slide-out Review Drawer */}
          <DetailPanel open={Boolean(reviewRecord)} title={reviewRecord?.employeeName || 'Timesheet'} onClose={() => setReviewRecord(null)} className="reports__detail-panel">
            {reviewRecord && <>
                {reviewLoading ? (
                  <p className="reports__status">Loading timesheet details...</p>
                ) : reviewError ? (
                  <div role="alert" className="reports__status reports__status--error">
                    <p>{reviewError}</p>
                    <Button type="button" variant="secondary" onClick={() => openReview(reviewRecord)}>Retry</Button>
                  </div>
                ) : (
                  <div className="reports__drawer-body">
                    <dl>
                      <dt>Employee ID</dt>
                      <dd>{reviewRecord.employeeId || '-'}</dd>
                      <dt>Status</dt>
                      <dd>{renderStatusCell(reviewRecord.status)}</dd>
                      <dt>Reporting Manager</dt>
                      <dd>{reviewRecord.reportingManagerName || '-'}</dd>
                      <dt>Pay Period</dt>
                      <dd>{formatPeriod(reviewRecord.periodStartDate, reviewRecord.periodEndDate)}</dd>
                      <dt>Total Worked</dt>
                      <dd>{Number.isFinite(reviewRecord.totalHours) ? `${reviewRecord.totalHours}h` : '—'}</dd>
                      <dt>Submitted</dt>
                      <dd>{reviewRecord.submittedAt ? formatDateTime(reviewRecord.submittedAt) : 'Not submitted'}</dd>
                    </dl>
                    <h4>Daily Hours</h4>
                    <div className="reports__daily-hours">
                      {(reviewRecord.entries || []).length === 0 ? (
                        <p>No daily entries available.</p>
                      ) : (
                        reviewRecord.entries.map((entry) => (
                          <div key={entry.workDate}>
                            <span>{entry.dayOfWeek || formatDateOnly(entry.workDate)}</span>
                            <strong>{entry.hoursWorked}h</strong>
                          </div>
                        ))
                      )}
                    </div>
                    <h4>Approval Information</h4>
                    <p>{reviewRecord.reviewComment || reviewRecord.changeReason || 'No review note recorded.'}</p>

                    <div className="reports__drawer-actions">
                      <Button
                        type="button"
                        variant="primary"
                        onClick={() => {
                          const targetPath = role === 'COMPANY_ADMIN' ? '/myhr/admin/timesheets' : '/myhr/hr/timesheets';
                          navigate(`${targetPath}?employeeId=${reviewRecord.employeeId || ''}`);
                        }}
                      >
                        Manage in Timesheets Workspace →
                      </Button>
                    </div>
                  </div>
                )}
            </>}
          </DetailPanel>
        </div>
      ) : (
        <div className="reports__detail">
          <section className="reports__hero">
            <div>
              <p>MY HR - REPORT PREVIEW</p>
              <h2>{activeItem.title}</h2>
              <span>{role === 'COMPANY_ADMIN' ? 'Organization' : 'Your direct reports'} - Current</span>
            </div>
            <div>
              <Button type="button" variant="secondary" className="reports__download-trigger" disabled={loading} aria-label="Refresh report data" onClick={fetchReport}>
                {loading ? 'Refreshing...' : 'Refresh'}
              </Button>
              <DownloadMenu menuRef={downloadMenuRef} open={downloadOpen} onToggle={() => setDownloadOpen((open) => !open)} onDownload={handleDownload} disabled={loading || Boolean(error)} />
            </div>
          </section>
          {loading ? (
            <p role="status" className="reports__status">Loading report data...</p>
          ) : error ? (
            <div role="alert" className="reports__status reports__status--error">
              <p>{error}</p>
              <Button type="button" variant="secondary" onClick={fetchReport}>Try Again</Button>
            </div>
          ) : (
            <>
              <div className="reports__kpis">
                {(isOrgWide
                  ? [
                      ['Total Employees', totalEmployees],
                      ['Active Employees', activeEmployees],
                      ['Inactive Employees', inactiveEmployees],
                      ['Distinct Designations', distinctDesignations],
                      ['Reporting Managers', totalManagers],
                      ['Avg Team Size', totalManagers > 0 && Number.isFinite(totalEmployees) ? (totalEmployees / totalManagers).toFixed(1) : undefined],
                    ]
                  : [
                      ['Total Employees', totalEmployees],
                      ['Active Employees', activeEmployees],
                      ['Inactive Employees', inactiveEmployees],
                      ['Distinct Designations', distinctDesignations],
                    ]
                ).filter(([, value]) => value !== undefined).map(([label, value]) => (
                  <article key={label}>                    <span>{label}</span>
                    <strong>{value}</strong>
                  </article>
                ))}
              </div>
              <div className="reports__panels">
                <section>
                  <header>
                    <h3>Employee Details</h3>
                    <p>{employees.length === 0 ? 'No employee data available yet for this scope.' : `Live data for ${role === 'COMPANY_ADMIN' ? 'the organization' : 'your direct reports'}.`}</p>
                  </header>
                  <Table columns={tableColumns} rows={employees} emptyMessage="No employee data available yet." renderCell={(column, row) => column.key === 'accountStatus' ? renderStatusCell(row[column.key]) : (row[column.key] || <EmptyCell />)} />
                </section>
                {Number.isFinite(activeEmployees) && Number.isFinite(inactiveEmployees) && (
                <section>
                  <header>
                    <h3>Workforce Status</h3>
                    <p>{totalEmployees === 0 ? 'No data yet for this scope.' : 'Live distribution for this scope.'}</p>
                  </header>
                  <div className="reports__legend">
                    <span><i />Active<b>{activeEmployees}</b></span>
                    <span><i className="muted" />Inactive<b>{inactiveEmployees}</b></span>
                  </div>
                </section>
                )}
              </div>
              <div className="reports__panels">
                <section>
                  <header>
                    <h3>Employees by Designation</h3>
                  </header>
                  <p className="reports__status">{employees.length ? 'Designation summary is calculated from the loaded workforce scope.' : 'No designation data yet.'}</p>
                </section>
                <section>
                  <header>
                    <h3>Reporting Structure</h3>
                  </header>
                  {isOrgWide && (
                    <Table columns={[{ key: 'managerName', header: 'Reporting Manager' }, { key: 'directReportCount', header: 'Direct Reports' }]} rows={managerSummary} emptyMessage="No active reporting assignments yet." renderCell={(column, row) => row[column.key] || <EmptyCell />} />
                  )}
                </section>
              </div>
            </>
          )}
        </div>
      )}
    </MyHrFeaturePage>
  );
}
