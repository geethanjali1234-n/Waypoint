import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useOutletContext } from 'react-router-dom';
import {
  Button,
  Card,
  Dropdown,
  FileUpload,
  Modal,
  StatusTag,
  TextInput,
} from '../components';
import './EmployerDashboardPage.css';
import './JobSeekerDashboardPage.css';
import './MyHrEmployeePage.css';

const REPORTING_MANAGER = 'TD002 - Sarah Williams - HR';
const PAYROLL_PERIOD = 'August 1 - August 15, 2026';

const EMPLOYEE_FEATURE_PAGES = {
  '/employee/profile': {
    id: 'my-profile',
    title: 'My Profile',
    description: 'Review your employee information and request updates when something changes.',
  },
  '/employee/timesheets': {
    id: 'timesheets',
    title: 'Timesheets',
    description: 'Enter your hours for the current payroll period and submit them for review.',
  },
  '/employee/paystubs': {
    id: 'paystubs',
    title: 'Paystubs',
    description: 'View your pay history and download available paystub PDFs.',
  },
};

const EMPLOYEE_DASHBOARD_ACTIONS = [
  {
    title: 'My Profile',
    description: 'View and update your personal information and preferences.',
    href: '/employee/profile',
    icon: 'profile',
  },
  {
    title: 'My Timesheets',
    description: 'Enter and submit your hours and review your time entries.',
    href: '/employee/timesheets',
    icon: 'timesheets',
  },
  {
    title: 'My Paystubs',
    description: 'View and download your paystubs and payment details.',
    href: '/employee/paystubs',
    icon: 'paystubs',
  },
  {
    title: 'Help & Support',
    description: 'Get help and find answers to common questions.',
    href: '/employee/dashboard',
    icon: 'help',
  },
];

const profileFields = [
  { name: 'name', label: 'Name', value: 'John' },
  { name: 'personalEmail', label: 'Personal Email', value: 'Not provided' },
  { name: 'phoneNumber', label: 'Phone Number', value: 'Not provided' },
  { name: 'employeeId', label: 'Employee ID', value: 'TD003' },
  { name: 'state', label: 'State', value: 'Not provided' },
  { name: 'city', label: 'City', value: 'Not provided' },
  { name: 'streetAddress', label: 'Street Address', value: 'Not provided' },
  { name: 'zipCode', label: 'ZIP Code', value: 'Not provided' },
  { name: 'hourlyRate', label: 'Hourly Rate', value: 'Not provided' },
  { name: 'designation', label: 'Designation', value: 'Not provided' },
  { name: 'reportingManager', label: 'Reporting Manager', value: REPORTING_MANAGER },
];

const requestableProfileFields = [
  'Name',
  'Personal Email',
  'Phone Number',
  'State',
  'City',
  'Street Address',
  'ZIP Code',
  'Hourly Rate',
  'Designation',
].map((label) => ({ label, value: label }));

const initialTimesheetRows = [
  { date: '2026-08-11', displayDate: 'August 11, 2026', day: 'Tuesday', hours: '8' },
  { date: '2026-08-12', displayDate: 'August 12, 2026', day: 'Wednesday', hours: '8' },
  { date: '2026-08-13', displayDate: 'August 13, 2026', day: 'Thursday', hours: '8' },
  { date: '2026-08-14', displayDate: 'August 14, 2026', day: 'Friday', hours: '8' },
  { date: '2026-08-15', displayDate: 'August 15, 2026', day: 'Saturday', hours: '8' },
];

const CALENDAR_YEAR = new Date().getFullYear();
const INITIAL_TIMESHEET_MONTH = `${CALENDAR_YEAR}-${String(new Date().getMonth() + 1).padStart(2, '0')}`;
const DAYS_PER_PAGE = 7;
const calendarMonthOptions = Array.from({ length: 12 }, (_, monthIndex) => {
  const date = new Date(2000, monthIndex, 1);
  return {
    value: String(monthIndex + 1).padStart(2, '0'),
    label: date.toLocaleString('en-US', { month: 'long' }),
  };
});
const calendarYearOptions = Array.from({ length: 5 }, (_, yearIndex) => String(CALENDAR_YEAR - 2 + yearIndex));
const calendarWeekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const initialTimesheetHours = initialTimesheetRows.reduce((hoursByDate, row) => ({
  ...hoursByDate,
  [row.date]: row.hours,
}), {});

const initialTimesheetHistory = [
  {
    id: 'august-16-31',
    monthKey: '2026-08',
    periodStart: '2026-08-16',
    periodEnd: '2026-08-31',
    period: 'August 16 - August 31, 2026',
    hours: '80.0',
    submitted: 'September 1, 2026',
    reportingManager: REPORTING_MANAGER,
    status: 'Approved',
  },
  {
    id: 'august-1-15',
    monthKey: '2026-08',
    periodStart: '2026-08-01',
    periodEnd: '2026-08-15',
    period: 'August 1 - August 15, 2026',
    hours: '72.0',
    submitted: 'August 16, 2026',
    reportingManager: REPORTING_MANAGER,
    status: 'Approved',
  },
  {
    id: 'july-16-31',
    monthKey: '2026-07',
    periodStart: '2026-07-16',
    periodEnd: '2026-07-31',
    period: 'July 16 - July 31, 2026',
    hours: '80.0',
    submitted: 'August 1, 2026',
    reportingManager: REPORTING_MANAGER,
    status: 'Approved',
  },
  {
    id: 'july-1-15',
    monthKey: '2026-07',
    periodStart: '2026-07-01',
    periodEnd: '2026-07-15',
    period: 'July 1 - July 15, 2026',
    hours: '76.0',
    submitted: 'July 16, 2026',
    reportingManager: REPORTING_MANAGER,
    status: 'Approved',
  },
];

const paystubs = [
  {
    id: 'TD003-2026-08-01',
    number: 'Paystub 001',
    period: PAYROLL_PERIOD,
    paid: 'August 19, 2026',
    hours: '72.0',
    gross: '$2,340.00',
    net: '$1,971.44',
    status: 'CURRENT',
  },
  {
    id: 'TD003-2026-07-16',
    number: 'Paystub 002',
    period: 'July 16 - July 31, 2026',
    paid: 'August 5, 2026',
    hours: '80.0',
    gross: '$2,600.00',
    net: '$2,189.20',
    status: 'SUPERSEDED',
  },
  {
    id: 'TD003-2026-07-01',
    number: 'Paystub 003',
    period: 'July 1 - July 15, 2026',
    paid: 'July 19, 2026',
    hours: '76.0',
    gross: '$2,470.00',
    net: '$2,074.62',
    status: 'SUPERSEDED',
  },
];

const initialNotifications = [
  {
    id: 'timesheet-submission',
    title: 'Timesheet Submission',
    message: 'Your submitted hours are available to your Reporting Manager.',
    timestamp: 'Today',
    read: false,
  },
  {
    id: 'paystub-generation',
    title: 'Paystub Generation',
    message: 'Your paystub is available to view and download as a PDF.',
    timestamp: 'Yesterday',
    read: false,
  },
  {
    id: 'profile-change-request',
    title: 'Profile Change Request',
    message: 'Your request is assigned to your Reporting Manager.',
    timestamp: 'August 12',
    read: true,
  },
];

function ProfileIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="6.5" r="2.7" fill="none" />
      <path d="M4.5 16a5.5 5.5 0 0 1 11 0" fill="none" strokeLinecap="round" />
    </svg>
  );
}

function CalendarIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <rect x="3" y="4.5" width="14" height="12" rx="2" fill="none" />
      <path d="M6.5 3v3M13.5 3v3M3 8h14" fill="none" strokeLinecap="round" />
      <path d="M6.5 11h.01M10 11h.01M13.5 11h.01M6.5 14h.01M10 14h.01" fill="none" strokeLinecap="round" strokeWidth="2" />
    </svg>
  );
}

function PaystubIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="M5 2.8h6l4 4v10.4H5z" fill="none" strokeLinejoin="round" />
      <path d="M11 2.8v4h4M7.5 10h5M7.5 13h5" fill="none" strokeLinecap="round" />
    </svg>
  );
}

function HelpIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="10" r="7" fill="none" />
      <path d="M8.3 7.6a1.8 1.8 0 1 1 3.1 1.2c-.8.7-1.4 1-1.4 2.1M10 14h.01" fill="none" strokeLinecap="round" strokeWidth="1.5" />
    </svg>
  );
}

function dashboardActionIcon(icon) {
  if (icon === 'profile') return <ProfileIcon />;
  if (icon === 'timesheets') return <CalendarIcon />;
  if (icon === 'paystubs') return <PaystubIcon />;
  return <HelpIcon />;
}

function ArrowIcon({ direction = 'right' }) {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" className={`my-hr-employee__inline-icon my-hr-employee__inline-icon--${direction}`}>
      <path d={direction === 'left' ? 'M16 10H4M9 5l-5 5 5 5' : 'M4 10h12M11 5l5 5-5 5'} fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" className="my-hr-employee__inline-icon">
      <path d="M10 3v9M6.5 9.5 10 13l3.5-3.5M4 16.5h12" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function EyeIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" className="my-hr-employee__inline-icon">
      <path d="M2.8 10s2.5-4 7.2-4 7.2 4 7.2 4-2.5 4-7.2 4-7.2-4-7.2-4Z" fill="none" />
      <circle cx="10" cy="10" r="1.8" fill="none" />
    </svg>
  );
}

function getStatusTone(status) {
  if (status === 'Approved' || status === 'CURRENT') return 'success';
  if (status === 'Open') return 'success';
  if (status === 'Pending manager review' || status === 'Pending') return 'warning';
  if (status === 'Not open') return 'warning';
  return 'neutral';
}

function getCalendarDateParts(date) {
  const calendarDate = new Date(`${date}T00:00:00`);
  return {
    month: calendarDate.toLocaleString('en-US', { month: 'short' }).toUpperCase(),
    dayNumber: calendarDate.getDate(),
  };
}

function getCalendarMonthDetails(monthKey) {
  const [year, month] = monthKey.split('-').map(Number);
  const firstDate = new Date(year, month - 1, 1);
  const monthName = firstDate.toLocaleString('en-US', { month: 'long' });
  const daysInMonth = new Date(year, month, 0).getDate();

  return {
    label: `${monthName} ${year}`,
    period: `${monthName} 1 - ${monthName} ${daysInMonth}, ${year}`,
    daysInMonth,
  };
}

function shiftCalendarMonth(monthKey, direction) {
  const [year, month] = monthKey.split('-').map(Number);
  const nextDate = new Date(year, month - 1 + direction, 1);
  return `${nextDate.getFullYear()}-${String(nextDate.getMonth() + 1).padStart(2, '0')}`;
}

function getDateKey(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function getCalendarPeriodState(monthKey, today = new Date()) {
  const currentMonthKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;

  if (monthKey < currentMonthKey) {
    return {
      type: 'closed',
      label: 'Closed',
      message: 'This month is closed. Past months can be viewed but cannot be submitted.',
    };
  }

  if (monthKey > currentMonthKey) {
    return {
      type: 'upcoming',
      label: 'Not open',
      message: 'This month has not opened yet. Future dates cannot be submitted.',
    };
  }

  return {
    type: 'open',
    label: 'Open',
    message: 'You can enter hours through today. Future dates remain locked.',
  };
}

function getTimesheetRows(monthKey, hoursByDate) {
  const [year, month] = monthKey.split('-').map(Number);
  const { daysInMonth } = getCalendarMonthDetails(monthKey);

  return Array.from({ length: daysInMonth }, (_, dayIndex) => {
    const date = new Date(year, month - 1, dayIndex + 1);
    const dateKey = getDateKey(date);

    return {
      date: dateKey,
      displayDate: date.toLocaleDateString('en-US', {
        day: 'numeric',
        month: 'long',
        year: 'numeric',
      }),
      day: date.toLocaleDateString('en-US', { weekday: 'long' }),
      hours: hoursByDate[dateKey] ?? '0',
    };
  });
}

function PaystubDetails({ paystub }) {
  return (
    <div className="my-hr-employee__paystub-paper">
      <div className="my-hr-employee__paystub-paper-heading">
        <div>
          <strong>Waypoint</strong>
          <span>Employee paystub</span>
        </div>
        <StatusTag status={paystub.status} tone={getStatusTone(paystub.status)} />
      </div>
      <dl className="my-hr-employee__paystub-details">
        <div><dt>Employee</dt><dd>John (TD003)</dd></div>
        <div><dt>Pay Period</dt><dd>{paystub.period}</dd></div>
        <div><dt>Paid</dt><dd>{paystub.paid}</dd></div>
      </dl>
      <div className="my-hr-employee__paystub-summary">
        <div><span>Hours</span><strong>{paystub.hours}</strong></div>
        <div><span>Gross Pay</span><strong>{paystub.gross}</strong></div>
        <div><span>Net Pay</span><strong>{paystub.net}</strong></div>
      </div>
      <p className="my-hr-employee__paystub-note">
        Paystub details are available after successful payroll processing.
      </p>
    </div>
  );
}

export default function MyHrEmployeePage() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const outletContext = useOutletContext();
  const activeFeature = EMPLOYEE_FEATURE_PAGES[pathname];
  const isDashboardRoute = !activeFeature;
  const [, setLocalNotifications] = useState(initialNotifications);
  const [timesheetMonth, setTimesheetMonth] = useState(INITIAL_TIMESHEET_MONTH);
  const [timesheetHours, setTimesheetHours] = useState(initialTimesheetHours);
  const [selectedDate, setSelectedDate] = useState(`${INITIAL_TIMESHEET_MONTH}-01`);
  const [datePage, setDatePage] = useState(0);
  const [timesheetHistory, setTimesheetHistory] = useState(initialTimesheetHistory);
  const [historyDateFilter, setHistoryDateFilter] = useState('all');
  const [historyCalendarOpen, setHistoryCalendarOpen] = useState(true);
  const [approvedTimesheet, setApprovedTimesheet] = useState('');
  const [timesheetStatus, setTimesheetStatus] = useState('');
  const [timesheetError, setTimesheetError] = useState('');
  const [showHistory, setShowHistory] = useState(false);
  const [showProfileRequest, setShowProfileRequest] = useState(false);
  const [requestField, setRequestField] = useState('');
  const [requestedValue, setRequestedValue] = useState('');
  const [profileRequestStatus, setProfileRequestStatus] = useState('');
  const [profileRequestError, setProfileRequestError] = useState('');
  const [viewedPaystub, setViewedPaystub] = useState(null);
  const [downloadStatus, setDownloadStatus] = useState('');

  const calendarMonth = useMemo(() => getCalendarMonthDetails(timesheetMonth), [timesheetMonth]);
  const selectedYear = timesheetMonth.slice(0, 4);
  const selectedMonthNumber = timesheetMonth.slice(5, 7);
  const todayDateKey = getDateKey(new Date());
  const calendarPeriodState = getCalendarPeriodState(timesheetMonth);
  const canSubmitTimesheet = calendarPeriodState.type === 'open';
  const timesheetRows = useMemo(
    () => getTimesheetRows(timesheetMonth, timesheetHours),
    [timesheetHours, timesheetMonth],
  );
  const datePageCount = Math.ceil(timesheetRows.length / DAYS_PER_PAGE);
  const visibleTimesheetRows = timesheetRows.slice(
    datePage * DAYS_PER_PAGE,
    (datePage + 1) * DAYS_PER_PAGE,
  );
  const selectedDateIndex = timesheetRows.findIndex((row) => row.date === selectedDate);
  const selectedDateRow = selectedDateIndex >= 0 ? timesheetRows[selectedDateIndex] : timesheetRows[0];
  const totalHours = useMemo(
    () => timesheetRows.reduce((total, row) => {
      const hours = Number(row.hours);
      return total + (Number.isFinite(hours) ? hours : 0);
    }, 0),
    [timesheetRows],
  );
  const visibleTimesheetHistory = useMemo(
    () => historyDateFilter === 'all'
      ? timesheetHistory
      : timesheetHistory.filter((item) => (
        historyDateFilter >= item.periodStart && historyDateFilter <= item.periodEnd
      )),
    [historyDateFilter, timesheetHistory],
  );
  const historyDateLabel = historyDateFilter === 'all'
    ? 'All available pay periods'
    : timesheetRows.find((row) => row.date === historyDateFilter)?.displayDate || calendarMonth.label;
  const historyCalendarCells = useMemo(() => {
    const firstDayOfMonth = new Date(`${timesheetMonth}-01T00:00:00`).getDay();
    return [
      ...Array.from({ length: firstDayOfMonth }, () => null),
      ...timesheetRows,
    ];
  }, [timesheetRows, timesheetMonth]);

  useEffect(() => {
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  }, [pathname]);

  const addNotification = (notification) => {
    if (outletContext?.addNotification) {
      outletContext.addNotification(notification);
      return;
    }

    setLocalNotifications((current) => (
      current.some((item) => item.id === notification.id) ? current : [notification, ...current]
    ));
  };

  const updateHours = (date, value) => {
    if (!canSubmitTimesheet || date > todayDateKey) return;
    setTimesheetHours((current) => ({ ...current, [date]: value }));
    setTimesheetStatus('');
    setTimesheetError('');
  };

  const selectCalendarDate = (date) => {
    const nextDateIndex = timesheetRows.findIndex((row) => row.date === date);
    setSelectedDate(date);
    setHistoryDateFilter(date);
    if (nextDateIndex >= 0) setDatePage(Math.floor(nextDateIndex / DAYS_PER_PAGE));
  };

  const selectHistoryCalendarDate = (date) => {
    selectCalendarDate(date);
    setHistoryCalendarOpen(false);
  };

  const changeHistoryCalendarMonth = (direction) => {
    const nextMonth = shiftCalendarMonth(timesheetMonth, direction);
    setTimesheetMonth(nextMonth);
    setSelectedDate(`${nextMonth}-01`);
    setHistoryDateFilter('all');
    setDatePage(0);
    setTimesheetStatus('');
    setTimesheetError('');
  };

  const changeCalendarMonth = (event) => {
    const nextMonth = `${selectedYear}-${event.target.value}`;
    setTimesheetMonth(nextMonth);
    setSelectedDate(`${nextMonth}-01`);
    setHistoryDateFilter('all');
    setDatePage(0);
    setTimesheetStatus('');
    setTimesheetError('');
  };

  const changeCalendarYear = (event) => {
    const nextMonth = `${event.target.value}-${selectedMonthNumber}`;
    setTimesheetMonth(nextMonth);
    setSelectedDate(`${nextMonth}-01`);
    setHistoryDateFilter('all');
    setDatePage(0);
    setTimesheetStatus('');
    setTimesheetError('');
  };

  const moveCalendarPage = (direction) => {
    const nextPage = Math.min(Math.max(datePage + direction, 0), datePageCount - 1);
    const nextPageFirstRow = timesheetRows[nextPage * DAYS_PER_PAGE];
    setDatePage(nextPage);
    if (nextPageFirstRow) {
      setSelectedDate(nextPageFirstRow.date);
      setHistoryDateFilter(nextPageFirstRow.date);
    }
  };

  const submitTimesheet = (event) => {
    event.preventDefault();
    if (!canSubmitTimesheet) {
      setTimesheetError(calendarPeriodState.message);
      return;
    }

    const hasInvalidHours = timesheetRows.some((row) => {
      if (row.hours.trim() === '') return true;
      const hours = Number(row.hours);
      return !Number.isFinite(hours) || hours < 0 || hours > 24;
    });

    if (hasInvalidHours) {
      setTimesheetError('Enter daily hours from 0 to 24 for each calendar date.');
      return;
    }

    setTimesheetError('');
    setTimesheetStatus('Pending manager review');
    setTimesheetHistory((current) => [
      {
        id: `${timesheetMonth}-calendar`,
        monthKey: timesheetMonth,
        periodStart: `${timesheetMonth}-01`,
        periodEnd: `${timesheetMonth}-${String(calendarMonth.daysInMonth).padStart(2, '0')}`,
        period: calendarMonth.period,
        hours: totalHours.toFixed(1),
        submitted: 'Submitted just now',
        reportingManager: REPORTING_MANAGER,
        status: 'Pending manager review',
      },
      ...current.filter((item) => item.id !== `${timesheetMonth}-calendar`),
    ]);
    addNotification({
      id: 'timesheet-submitted',
      title: 'Timesheet Submission',
      message: `Your ${calendarMonth.period} hours were submitted to ${REPORTING_MANAGER}.`,
      timestamp: 'Just now',
      read: false,
    });
  };

  const submitProfileChangeRequest = (event) => {
    event.preventDefault();
    if (!requestField || requestedValue.trim() === '') {
      setProfileRequestError('Select a Field Name and enter a Requested Value.');
      return;
    }

    setProfileRequestError('');
    setProfileRequestStatus(`Profile Change Request submitted to ${REPORTING_MANAGER}.`);
    setShowProfileRequest(false);
    addNotification({
      id: 'profile-request-submitted',
      title: 'Profile Change Request',
      message: `Your ${requestField} request was sent to ${REPORTING_MANAGER}.`,
      timestamp: 'Just now',
      read: false,
    });
  };

  const downloadPaystub = (paystub) => {
    if (
      typeof document === 'undefined'
      || typeof Blob === 'undefined'
      || typeof URL === 'undefined'
      || typeof URL.createObjectURL !== 'function'
    ) {
      setDownloadStatus(`${paystub.number} is ready to download as a PDF.`);
      return;
    }

    const file = new Blob([
      `Waypoint Paystub\nEmployee: John (TD003)\nPay Period: ${paystub.period}\nHours: ${paystub.hours}\nGross Pay: ${paystub.gross}\nNet Pay: ${paystub.net}\nStatus: ${paystub.status}`,
    ], { type: 'application/pdf' });
    const url = URL.createObjectURL(file);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${paystub.id}.pdf`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
    setDownloadStatus(`${paystub.number} downloaded as a PDF.`);
  };

  return (
    <div className="employer-dashboard__container my-hr-employee">
      {!isDashboardRoute && (
        <section className="employer-dashboard__greeting job-seeker-dashboard__greeting" aria-labelledby="employee-dashboard-greeting">
          <div>
            <p className="employer-dashboard__persona">MY HR</p>
            <h1 id="employee-dashboard-greeting">{activeFeature.title}</h1>
            <p>{activeFeature.description}</p>
          </div>
          <div className="my-hr-employee__identity-card">
            <span className="my-hr-employee__avatar">J</span>
            <div>
              <strong>TD003</strong>
              <span>Employee workspace</span>
            </div>
            <StatusTag status="Active" tone="success" />
          </div>
        </section>
      )}

      {isDashboardRoute && (
        <>
          <section className="my-hr-employee__landing-hero" aria-label="Employee summary">
            <div className="my-hr-employee__landing-hero-copy">
              <h1 id="employee-dashboard-greeting">Welcome, John</h1>
              <p>Employee <span aria-hidden="true">·</span> Tardus Inc</p>
            </div>
            <div className="my-hr-employee__landing-stats">
              <div>
                <strong>{totalHours.toFixed(0)}</strong>
                <span>Hours this period</span>
              </div>
              <div>
                <strong>{paystubs.length}</strong>
                <span>Paystubs</span>
              </div>
              <div>
                <strong>1</strong>
                <span>Reporting manager</span>
              </div>
            </div>
          </section>

          <section className="my-hr-employee__dashboard-actions" aria-label="Employee actions">
            {EMPLOYEE_DASHBOARD_ACTIONS.map((action) => (
              <button
                key={action.title}
                type="button"
                className="my-hr-employee__dashboard-action"
                onClick={() => navigate(action.href)}
              >
                <span className="my-hr-employee__dashboard-action-icon">
                  {dashboardActionIcon(action.icon)}
                </span>
                <span className="my-hr-employee__dashboard-action-copy">
                  <span className="my-hr-employee__dashboard-action-title">{action.title}</span>
                  <span className="my-hr-employee__dashboard-action-description">{action.description}</span>
                  <span className="my-hr-employee__dashboard-action-link">Open <ArrowIcon /></span>
                </span>
              </button>
            ))}
          </section>
        </>
      )}

      {!isDashboardRoute && (
        <section
          className="employer-dashboard__feature-grid my-hr-employee__grid my-hr-employee__grid--feature-page"
          aria-label={`${activeFeature.title} workspace`}
        >
        {activeFeature?.id === 'my-profile' && (
          <Card
            id="my-profile"
            title={<span className="my-hr-employee__card-title"><ProfileIcon /> My Profile</span>}
            className="employer-dashboard__feature-card my-hr-employee__profile-card"
            actions={<StatusTag status="View only" />}
          >
          <p className="my-hr-employee__intro">
            Controlled profile fields are view only. Submit a profile change request when information needs to be updated.
          </p>
          <div className="my-hr-employee__profile-grid">
            {profileFields.map((field) => (
              <TextInput
                key={field.name}
                name={field.name}
                label={field.label}
                value={field.value}
                disabled
              />
            ))}
          </div>

          {profileRequestStatus && (
            <p className="my-hr-employee__message" role="status">
              <StatusTag status="Pending" tone="warning" /> {profileRequestStatus}
            </p>
          )}

          {showProfileRequest ? (
            <form className="my-hr-employee__request" onSubmit={submitProfileChangeRequest}>
              <Dropdown
                name="fieldName"
                label="Field Name"
                options={requestableProfileFields}
                value={requestField}
                onChange={(event) => {
                  setRequestField(event.target.value);
                  setProfileRequestError('');
                }}
                placeholder="Select a field"
                required
              />
              <TextInput
                name="requestedValue"
                label="Requested Value"
                value={requestedValue}
                onChange={(event) => {
                  setRequestedValue(event.target.value);
                  setProfileRequestError('');
                }}
                placeholder="Enter the requested value"
                required
              />
              {profileRequestError && <p className="my-hr-employee__error" role="alert">{profileRequestError}</p>}
              <div className="my-hr-employee__actions">
                <Button type="button" variant="secondary" onClick={() => setShowProfileRequest(false)}>Cancel</Button>
                <Button type="submit" variant="primary">Submit Profile Change Request</Button>
              </div>
            </form>
          ) : (
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                setShowProfileRequest(true);
                setProfileRequestError('');
              }}
            >
              Submit Profile Change Request <ArrowIcon />
            </Button>
          )}
          </Card>
        )}

        {activeFeature?.id === 'timesheets' && (
          <Card
            id="timesheets"
            title={<span className="my-hr-employee__card-title"><CalendarIcon /> Timesheets</span>}
            className="employer-dashboard__feature-card"
            actions={timesheetStatus ? <StatusTag status={timesheetStatus} tone={getStatusTone(timesheetStatus)} /> : null}
          >
          <p className="my-hr-employee__intro">
            Enter working hours for any date in the selected calendar month. Use the date controls to move through the month in order.
          </p>
          <form className="my-hr-employee__timesheet" onSubmit={submitTimesheet}>
            <div className="my-hr-employee__payroll-period">
              <div>
                <strong>Selected calendar month</strong>
                <span>{calendarMonth.period}</span>
              </div>
              <div className="my-hr-employee__period-meta">
                <StatusTag status={calendarPeriodState.label} tone={getStatusTone(calendarPeriodState.label)} />
                <small>Hours are saved against each calendar date</small>
              </div>
            </div>

            <div className="my-hr-employee__calendar" aria-label="Timesheet calendar">
              <div className="my-hr-employee__calendar-toolbar">
                <div className="my-hr-employee__calendar-heading">
                  <span className="my-hr-employee__calendar-eyebrow">Calendar month</span>
                  <strong>{calendarMonth.label}</strong>
                  <span className="my-hr-employee__calendar-hint">
                    Showing dates {datePage * DAYS_PER_PAGE + 1}-{Math.min((datePage + 1) * DAYS_PER_PAGE, timesheetRows.length)} of {timesheetRows.length}
                  </span>
                  <span className="my-hr-employee__calendar-selected-date">
                    Selected: {selectedDateRow.displayDate}
                  </span>
                </div>

                <div className="my-hr-employee__calendar-controls">
                  <label className="my-hr-employee__calendar-select">
                    <span>Month</span>
                    <select
                      aria-label="Select month"
                      className="wp-field__control"
                      value={selectedMonthNumber}
                      onChange={changeCalendarMonth}
                    >
                      {calendarMonthOptions.map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  </label>
                  <label className="my-hr-employee__calendar-select my-hr-employee__calendar-select--year">
                    <span>Year</span>
                    <select
                      aria-label="Select year"
                      className="wp-field__control"
                      value={selectedYear}
                      onChange={changeCalendarYear}
                    >
                      {calendarYearOptions.map((year) => (
                        <option key={year} value={year}>{year}</option>
                      ))}
                    </select>
                  </label>
                  <label className="my-hr-employee__calendar-select">
                    <span>Date / day</span>
                    <select
                      aria-label="Select date and day"
                      className="wp-field__control"
                      value={selectedDate}
                      onChange={(event) => selectCalendarDate(event.target.value)}
                    >
                      {timesheetRows.map((row) => (
                        <option key={row.date} value={row.date}>{row.date.slice(-2)} · {row.day}</option>
                      ))}
                    </select>
                  </label>
                </div>
              </div>

              <div className="my-hr-employee__calendar-navigation">
                <button
                  type="button"
                  className="my-hr-employee__calendar-nav"
                  aria-label="Previous dates"
                  onClick={() => moveCalendarPage(-1)}
                  disabled={datePage === 0}
                >
                  <ArrowIcon direction="left" />
                  <span>Previous</span>
                </button>
                <span className="my-hr-employee__calendar-page" aria-live="polite">
                  Dates {datePage * DAYS_PER_PAGE + 1}-{Math.min((datePage + 1) * DAYS_PER_PAGE, timesheetRows.length)}
                  <small>Page {datePage + 1} of {datePageCount}</small>
                </span>
                <button
                  type="button"
                  className="my-hr-employee__calendar-nav"
                  aria-label="Next dates"
                  onClick={() => moveCalendarPage(1)}
                  disabled={datePage === datePageCount - 1}
                >
                  <span>Next</span>
                  <ArrowIcon />
                </button>
              </div>

              <div className={`my-hr-employee__calendar-notice my-hr-employee__calendar-notice--${calendarPeriodState.type}`} role="status">
                <StatusTag status={calendarPeriodState.label} tone={getStatusTone(calendarPeriodState.label)} />
                <span>{calendarPeriodState.message}</span>
              </div>

              <div className="my-hr-employee__calendar-grid">
                {visibleTimesheetRows.map((row) => {
                  const dateParts = getCalendarDateParts(row.date);
                  const isSelected = row.date === selectedDate;
                  const isFutureDate = row.date > todayDateKey;
                  const isDateLocked = !canSubmitTimesheet || isFutureDate;

                  return (
                    <div
                      className={`my-hr-employee__calendar-day${isSelected ? ' my-hr-employee__calendar-day--selected' : ''}`}
                      key={row.date}
                    >
                      <button
                        type="button"
                        className="my-hr-employee__calendar-day-select"
                        aria-label={`Select ${row.displayDate}`}
                        aria-pressed={isSelected}
                        onClick={() => selectCalendarDate(row.date)}
                      >
                        <span className="my-hr-employee__calendar-day-heading">
                          <span className="my-hr-employee__calendar-weekday">{row.day}</span>
                          <span className="my-hr-employee__calendar-date">
                            <small>{dateParts.month}</small>
                            <strong>{dateParts.dayNumber}</strong>
                          </span>
                        </span>
                      </button>
                      <span className="my-hr-employee__calendar-date-label">{row.displayDate}</span>
                      {isDateLocked && (
                        <span className="my-hr-employee__calendar-lock-note">
                          {calendarPeriodState.type === 'closed' ? 'Closed' : calendarPeriodState.type === 'upcoming' ? 'Not open' : 'Future date'}
                        </span>
                      )}
                      <label className="my-hr-employee__calendar-hours">
                        <span>Daily hours</span>
                        <input
                          id={`hours-${row.date}`}
                          name={`hours-${row.date}`}
                          aria-label={`${row.displayDate} hours`}
                          type="number"
                          min="0"
                          max="24"
                          step="0.5"
                          value={row.hours}
                          onChange={(event) => updateHours(row.date, event.target.value)}
                          className="wp-field__control my-hr-employee__hours-input"
                          aria-invalid={Boolean(timesheetError)}
                          disabled={isDateLocked}
                        />
                      </label>
                    </div>
                  );
                })}
              </div>
            </div>

            <div className="my-hr-employee__timesheet-total">
              <span>Total hours</span>
              <strong>{totalHours.toFixed(1)} hours</strong>
            </div>
            {timesheetError && <p className="my-hr-employee__error" role="alert">{timesheetError}</p>}

            <FileUpload
              name="approvedTimesheet"
              label="Upload Optional Approved Timesheet"
              helperText="Accepted file types: PDF, PNG, or JPG."
              fileName={approvedTimesheet}
              accept=".pdf,.png,.jpg"
              disabled={!canSubmitTimesheet}
              onUpload={(event) => setApprovedTimesheet(event.target.files?.[0]?.name || '')}
              onReplace={(event) => setApprovedTimesheet(event.target.files?.[0]?.name || '')}
            />
            <p className="my-hr-employee__routing-note">Submission routes to {REPORTING_MANAGER}.</p>
            <div className="my-hr-employee__actions">
              <Button type="submit" variant="primary" disabled={!canSubmitTimesheet}>Submit <ArrowIcon /></Button>
              <Button
                type="button"
                variant="secondary"
                aria-expanded={showHistory}
                onClick={() => setShowHistory((current) => !current)}
              >
                View History <PaystubIcon />
              </Button>
            </div>
          </form>

          {showHistory && (
            <div className="my-hr-employee__history" role="region" aria-label="Timesheet History">
              <div className="my-hr-employee__history-heading">
                <div className="my-hr-employee__history-heading-copy">
                  <h4>Timesheet History</h4>
                  <span>{historyDateLabel}</span>
                </div>
                <div className="my-hr-employee__history-filter">
                  <span>View history for date</span>
                  <button
                    type="button"
                    className={`my-hr-employee__history-calendar-toggle${historyCalendarOpen ? ' my-hr-employee__history-calendar-toggle--open' : ''}`}
                    aria-label="Toggle history date calendar"
                    aria-expanded={historyCalendarOpen}
                    aria-controls="history-date-calendar"
                    onClick={() => setHistoryCalendarOpen((current) => !current)}
                  >
                    <span>{historyDateFilter === 'all' ? 'All pay periods' : historyDateLabel}</span>
                    <span className="my-hr-employee__history-calendar-chevron" aria-hidden="true">&#9662;</span>
                  </button>
                  {historyCalendarOpen && (
                    <div className="my-hr-employee__history-calendar" id="history-date-calendar" role="group" aria-label="History date calendar">
                      <div className="my-hr-employee__history-calendar-heading">
                        <div className="my-hr-employee__history-calendar-month-navigation">
                          <button
                            type="button"
                            className="my-hr-employee__history-calendar-nav"
                            aria-label="Previous history month"
                            onClick={() => changeHistoryCalendarMonth(-1)}
                          >
                            <ArrowIcon direction="left" />
                          </button>
                          <strong>{calendarMonth.label}</strong>
                          <button
                            type="button"
                            className="my-hr-employee__history-calendar-nav"
                            aria-label="Next history month"
                            onClick={() => changeHistoryCalendarMonth(1)}
                          >
                            <ArrowIcon />
                          </button>
                        </div>
                        <button
                          type="button"
                          className={`my-hr-employee__history-reset${historyDateFilter === 'all' ? ' my-hr-employee__history-reset--active' : ''}`}
                          aria-pressed={historyDateFilter === 'all'}
                          onClick={() => setHistoryDateFilter('all')}
                        >
                          All pay periods
                        </button>
                      </div>
                      <div className="my-hr-employee__history-calendar-weekdays" aria-hidden="true">
                        {calendarWeekdays.map((weekday) => <span key={weekday}>{weekday}</span>)}
                      </div>
                      <div className="my-hr-employee__history-calendar-grid">
                        {historyCalendarCells.map((row, index) => (
                          row ? (
                            <button
                              type="button"
                              className={`my-hr-employee__history-calendar-day${historyDateFilter === row.date ? ' my-hr-employee__history-calendar-day--selected' : ''}`}
                              key={row.date}
                              aria-label={`View history for ${row.displayDate}`}
                              aria-pressed={historyDateFilter === row.date}
                              onClick={() => selectHistoryCalendarDate(row.date)}
                            >
                              {Number(row.date.slice(-2))}
                            </button>
                          ) : (
                            <span className="my-hr-employee__history-calendar-day my-hr-employee__history-calendar-day--empty" key={`empty-${index}`} aria-hidden="true" />
                          )
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
              <div className="my-hr-employee__history-table" role="table" aria-label="Timesheet history">
                <div className="my-hr-employee__history-row my-hr-employee__history-row--header" role="row">
                  <span>Pay period</span>
                  <span>Hours</span>
                  <span>Reporting Manager</span>
                  <span>Status</span>
                </div>
                {visibleTimesheetHistory.length > 0 ? visibleTimesheetHistory.map((item) => (
                  <div className="my-hr-employee__history-row" role="row" key={item.id}>
                    <span>{item.period}</span>
                    <span>{item.hours}</span>
                    <span>{item.reportingManager}</span>
                    <StatusTag status={item.status} tone={getStatusTone(item.status)} />
                  </div>
                )) : (
                  <p className="my-hr-employee__history-empty">
                    {historyDateFilter === 'all'
                      ? 'No timesheet history available yet.'
                      : `No timesheet history for ${historyDateLabel} yet.`}
                  </p>
                )}
              </div>
            </div>
          )}
          </Card>
        )}

        {activeFeature?.id === 'paystubs' && (
          <Card
            id="paystubs"
            title={<span className="my-hr-employee__card-title"><PaystubIcon /> Paystubs</span>}
            className="employer-dashboard__feature-card"
          >
          <p className="my-hr-employee__intro">View your paystubs and download them as PDF files after successful payroll processing.</p>
          <div className="my-hr-employee__list">
            {paystubs.map((paystub) => (
              <div className="my-hr-employee__list-row my-hr-employee__paystub-row" key={paystub.id}>
                <div>
                  <strong>{paystub.number}</strong>
                  <span>{paystub.period} · {paystub.hours} hours</span>
                </div>
                <StatusTag status={paystub.status} tone={getStatusTone(paystub.status)} />
                <div className="my-hr-employee__actions my-hr-employee__paystub-actions">
                  <Button type="button" variant="secondary" aria-label={`View ${paystub.number}`} onClick={() => setViewedPaystub(paystub)}><EyeIcon /> View</Button>
                  <Button type="button" variant="secondary" onClick={() => downloadPaystub(paystub)}><DownloadIcon /> Download PDF</Button>
                </div>
              </div>
            ))}
          </div>
          {downloadStatus && <p className="my-hr-employee__message" role="status">{downloadStatus}</p>}
          </Card>
        )}

        </section>
      )}

      <Modal
        isOpen={Boolean(viewedPaystub)}
        title={viewedPaystub ? viewedPaystub.number : ''}
        onClose={() => setViewedPaystub(null)}
        className="my-hr-employee__paystub-modal"
        footer={viewedPaystub && (
          <>
            <Button type="button" variant="secondary" onClick={() => setViewedPaystub(null)}>Close</Button>
            <Button type="button" variant="primary" onClick={() => downloadPaystub(viewedPaystub)}><DownloadIcon /> Download PDF</Button>
          </>
        )}
      >
        {viewedPaystub && <PaystubDetails paystub={viewedPaystub} />}
      </Modal>
    </div>
  );
}
