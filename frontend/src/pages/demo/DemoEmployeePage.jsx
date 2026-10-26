import { useState } from 'react';
import { DemoWorkspaceRoute } from './DemoWorkspaceShell';
import './DemoEmployeePage.css';

const initialTimesheetRows = [
  { date: 'Aug 15', day: 'Friday', hours: '8' },
  { date: 'Aug 14', day: 'Thursday', hours: '8' },
  { date: 'Aug 13', day: 'Wednesday', hours: '8' },
  { date: 'Aug 12', day: 'Tuesday', hours: '8' },
  { date: 'Aug 11', day: 'Monday', hours: '8' },
];

const timesheetHistory = [
  { period: 'Aug 1 - 15, 2026', hours: '72.0', submitted: 'Aug 16, 2026', routed: 'TD002 - Sarah Williams', status: 'Approved', tone: 'success' },
  { period: 'Jul 16 - 31, 2026', hours: '80.0', submitted: 'Aug 1, 2026', routed: 'TD002 - Sarah Williams', status: 'Approved', tone: 'success' },
  { period: 'Jul 1 - 15, 2026', hours: '76.0', submitted: 'Jul 16, 2026', routed: 'TD002 - Sarah Williams', status: 'Approved', tone: 'success' },
];

const paystubs = [
  { id: 'TD003-2026-08-01', period: 'Aug 1 - 15, 2026', paid: 'Aug 19, 2026', hours: '72.0 hrs', net: '$1,971.44', status: 'Current' },
  { id: 'TD003-2026-07-16', period: 'Jul 16 - 31, 2026', paid: 'Aug 5, 2026', hours: '80.0 hrs', net: '$2,189.20', status: 'Current' },
  { id: 'TD003-2026-07-01', period: 'Jul 1 - 15, 2026', paid: 'Jul 19, 2026', hours: '76.0 hrs', net: '$2,074.62', status: 'Current' },
];

const initialNotifications = [
  { id: 'manager-update', type: 'Manager update', title: 'Sarah Williams approved your timesheet', description: 'Your Aug 1 - 15 submission is included in the current payroll period.', time: 'Today, 9:18 AM', unread: true },
  { id: 'paystub-ready', type: 'Paystub', title: 'Your paystub is ready to view', description: 'The latest paystub for Aug 1 - 15, 2026 is available as a PDF.', time: 'Yesterday', unread: true },
  { id: 'profile-reminder', type: 'Profile', title: 'Keep your profile up to date', description: 'Submit a profile change request if any controlled details need attention.', time: 'Aug 12, 2026', unread: false },
];

function Arrow() {
  return <span aria-hidden="true">-&gt;</span>;
}

function DownloadIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3v9M6.5 9.5 10 13l3.5-3.5M4 16.5h12" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" /></svg>;
}

function EyeIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M2.8 10s2.5-4 7.2-4 7.2 4 7.2 4-2.5 4-7.2 4-7.2-4-7.2-4Z" fill="none" stroke="currentColor" strokeWidth="1.4" /><circle cx="10" cy="10" r="1.8" fill="none" stroke="currentColor" strokeWidth="1.4" /></svg>;
}

function PanelHeading({ eyebrow, title, action }) {
  return (
    <div className="employee-page__panel-heading">
      <div>
        <p className="employee-page__eyebrow">{eyebrow}</p>
        <h2>{title}</h2>
      </div>
      {action}
    </div>
  );
}

function StatusPill({ children, tone = 'neutral' }) {
  return <span className={`employee-page__status employee-page__status--${tone}`}>{children}</span>;
}

function ProfileField({ label, value }) {
  return (
    <label className="employee-page__profile-field">
      <span>{label}</span>
      <input value={value} readOnly aria-label={label} />
    </label>
  );
}

function PaystubPreview({ paystub, onClose, onDownload }) {
  return (
    <div className="employee-page__modal-backdrop" role="presentation" onClick={onClose}>
      <section className="employee-page__modal" role="dialog" aria-modal="true" aria-labelledby="paystub-preview-title" onClick={(event) => event.stopPropagation()}>
        <div className="employee-page__modal-heading">
          <div>
            <p className="employee-page__eyebrow">PDF preview</p>
            <h2 id="paystub-preview-title">Paystub {paystub.period}</h2>
          </div>
          <button type="button" className="employee-page__close-button" onClick={onClose} aria-label="Close paystub preview">x</button>
        </div>

        <div className="employee-page__paystub-paper">
          <div className="employee-page__paper-brand"><span>W</span><strong>Waypoint</strong><small>Tardus Inc payroll</small></div>
          <div className="employee-page__paper-title"><strong>Employee paystub</strong><span>{paystub.status}</span></div>
          <div className="employee-page__paper-meta"><span><b>Employee</b> John Carter (TD003)</span><span><b>Pay period</b> {paystub.period}</span><span><b>Paid</b> {paystub.paid}</span></div>
          <div className="employee-page__paper-summary"><div><span>Hours</span><strong>{paystub.hours}</strong></div><div><span>Gross pay</span><strong>$2,340.00</strong></div><div><span>Net pay</span><strong>{paystub.net}</strong></div></div>
          <p className="employee-page__paper-note">Demo preview. Payroll calculations and tax details are represented with sample data for this prototype.</p>
        </div>

        <div className="employee-page__modal-actions">
          <button type="button" className="employee-page__quiet-button" onClick={onClose}>Close preview</button>
          <button type="button" className="employee-page__primary-button" onClick={() => onDownload(paystub)}><DownloadIcon /> Download PDF</button>
        </div>
      </section>
    </div>
  );
}

export default function DemoEmployeePage() {
  const [timesheetRows, setTimesheetRows] = useState(initialTimesheetRows);
  const [timesheetStatus, setTimesheetStatus] = useState('Draft');
  const [uploadedFile, setUploadedFile] = useState('');
  const [notifications, setNotifications] = useState(initialNotifications);
  const [profileRequestOpen, setProfileRequestOpen] = useState(false);
  const [profileRequestField, setProfileRequestField] = useState('Phone number');
  const [profileRequestValue, setProfileRequestValue] = useState('');
  const [profileRequestStatus, setProfileRequestStatus] = useState('');
  const [selectedPaystub, setSelectedPaystub] = useState(null);

  const totalHours = timesheetRows.reduce((total, row) => total + Number(row.hours || 0), 0);
  const unreadCount = notifications.filter((notification) => notification.unread).length;

  const updateHours = (date, value) => {
    setTimesheetRows((rows) => rows.map((row) => (row.date === date ? { ...row, hours: value } : row)));
    if (timesheetStatus !== 'Draft') setTimesheetStatus('Draft');
  };

  const submitTimesheet = (event) => {
    event.preventDefault();
    setTimesheetStatus('Pending manager review');
    setNotifications((current) => current.some((notification) => notification.id === 'timesheet-submitted')
      ? current
      : [{ id: 'timesheet-submitted', type: 'Timesheet', title: 'Timesheet submitted to TD002', description: 'Sarah Williams will review your Aug 1 - 15 hours.', time: 'Just now', unread: true }, ...current]);
  };

  const submitProfileRequest = (event) => {
    event.preventDefault();
    setProfileRequestOpen(false);
    setProfileRequestStatus(`Pending review - ${profileRequestField} change requested`);
    setNotifications((current) => current.some((notification) => notification.id === 'profile-request')
      ? current
      : [{ id: 'profile-request', type: 'Profile', title: 'Profile change request sent', description: 'Your request was routed to Sarah Williams for review.', time: 'Just now', unread: true }, ...current]);
  };

  const markAllRead = () => {
    setNotifications((current) => current.map((notification) => ({ ...notification, unread: false })));
  };

  const downloadPaystub = (paystub) => {
    const demoPdf = `Waypoint demo paystub\nEmployee: John Carter (TD003)\nPeriod: ${paystub.period}\nNet pay: ${paystub.net}`;
    const url = URL.createObjectURL(new Blob([demoPdf], { type: 'application/pdf' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${paystub.id}.pdf`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  };

  return (
    <DemoWorkspaceRoute persona="employee">
      <div className="employee-page">
        <section className="employee-page__heading" id="overview">
          <div>
            <p className="employee-page__eyebrow">MY HR / EMPLOYEE / TUESDAY, AUGUST 19</p>
            <h1>Good morning, John.</h1>
            <p className="employee-page__heading-description">Your workday, hours, and payroll details in one clear place.</p>
          </div>
          <div className="employee-page__identity-card">
            <span className="employee-page__avatar">JC</span>
            <div><strong>TD003</strong><span>Software Engineer</span></div>
            <StatusPill tone="success">Active</StatusPill>
          </div>
        </section>

        <div className="employee-page__metrics">
          <article><p>Profile completion</p><strong>92%</strong><span className="is-positive">Almost there</span></article>
          <article><p>Current pay period</p><strong>{totalHours}h</strong><span>Aug 1 - 15, 2026</span></article>
          <article><p>Submitted timesheets</p><strong>03</strong><span className="is-positive">All approved</span></article>
          <article><p>Unread notifications</p><strong>{unreadCount.toString().padStart(2, '0')}</strong><span>Needs your attention</span></article>
        </div>

        <section className="employee-page__manager-banner" aria-label="Reporting manager">
          <div>
            <p className="employee-page__banner-label">YOUR REPORTING MANAGER</p>
            <h2>Sarah Williams is your active reporting manager.</h2>
            <p>Timesheet submissions and profile-change requests route to the assigned manager saved with your account.</p>
          </div>
          <div className="employee-page__manager-person"><span className="employee-page__avatar employee-page__avatar--light">SW</span><div><strong>TD002 / Sarah Williams</strong><span>HR / Tardus Inc</span></div><StatusPill tone="light">Primary</StatusPill></div>
        </section>

        <div className="employee-page__split employee-page__split--main">
          <section className="employee-page__card" id="timesheets">
            <PanelHeading
              eyebrow="Record your hours"
              title="Timesheet entry"
              action={<div className="employee-page__panel-action"><a className="employee-page__text-button" href="#history">View history</a><StatusPill tone={timesheetStatus === 'Draft' ? 'warning' : 'success'}>{timesheetStatus}</StatusPill></div>}
            />
            <form onSubmit={submitTimesheet}>
              <div className="employee-page__period-row"><div><strong>Aug 1 - 15, 2026</strong><span>Submit daily, weekly, biweekly, or monthly</span></div><span className="employee-page__period-label">Current payroll period</span></div>
              <div className="employee-page__timesheet-table" role="table" aria-label="Timesheet hours">
                <div className="employee-page__timesheet-row employee-page__timesheet-row--header" role="row"><span>Date</span><span>Day</span><span>Hours</span></div>
                {timesheetRows.map((row) => (
                  <label className="employee-page__timesheet-row" role="row" key={row.date}>
                    <span>{row.date}, 2026</span><span>{row.day}</span><input aria-label={`${row.date} hours`} type="number" min="0" max="24" step="0.5" value={row.hours} onChange={(event) => updateHours(row.date, event.target.value)} />
                  </label>
                ))}
              </div>
              <div className="employee-page__timesheet-total"><span>Total hours</span><strong>{totalHours.toFixed(1)} hrs</strong></div>
              <label className="employee-page__upload"><span><strong>Optional approved timesheet</strong><small>{uploadedFile || 'Upload a supporting document'}</small></span><input type="file" accept=".pdf,.png,.jpg" onChange={(event) => setUploadedFile(event.target.files?.[0]?.name || '')} /><em>Choose file</em></label>
              <div className="employee-page__form-actions"><span>Routes to TD002 / Sarah Williams</span><button type="submit" className="employee-page__primary-button">Submit timesheet <Arrow /></button></div>
            </form>
          </section>

          <section className="employee-page__card" id="notifications">
            <PanelHeading eyebrow="Stay informed" title="Notifications" action={<button type="button" className="employee-page__text-button" onClick={markAllRead}>Mark all read</button>} />
            <div className="employee-page__notification-list">
              {notifications.slice(0, 3).map((notification) => (
                <article className={`employee-page__notification ${notification.unread ? 'is-unread' : ''}`} key={notification.id}>
                  <span className="employee-page__notification-dot" aria-hidden="true" />
                  <div><div className="employee-page__notification-meta"><span>{notification.type}</span><small>{notification.time}</small></div><strong>{notification.title}</strong><p>{notification.description}</p></div>
                </article>
              ))}
            </div>
            <a className="employee-page__card-link" href="#notifications">View notification history <Arrow /></a>
          </section>
        </div>

        <div className="employee-page__split">
          <section className="employee-page__card" id="profile">
            <PanelHeading eyebrow="Your details" title="My profile" action={<StatusPill>View only</StatusPill>} />
            <p className="employee-page__card-intro">Controlled profile information is managed by your organization. Submit a request when something needs to change.</p>
            <div className="employee-page__profile-grid">
              <ProfileField label="Name" value="John Carter" />
              <ProfileField label="Employee ID" value="TD003" />
              <ProfileField label="Personal email" value="john.carter@example.com" />
              <ProfileField label="Phone number" value="(616) 555-0142" />
              <ProfileField label="Designation" value="Software Engineer" />
              <ProfileField label="Hourly rate" value="$32.50 / hour" />
              <ProfileField label="Work location" value="Grand Rapids, MI" />
              <ProfileField label="Reporting manager" value="TD002 - Sarah Williams" />
            </div>
            {profileRequestStatus && <div className="employee-page__request-status" role="status">{profileRequestStatus}</div>}
            {profileRequestOpen ? (
              <form className="employee-page__request-form" onSubmit={submitProfileRequest}>
                <div className="employee-page__request-form-heading"><strong>Request a profile change</strong><button type="button" className="employee-page__close-button" onClick={() => setProfileRequestOpen(false)} aria-label="Close profile change request">x</button></div>
                <label>Field to update<select value={profileRequestField} onChange={(event) => setProfileRequestField(event.target.value)}><option>Phone number</option><option>Personal email</option><option>Street address</option><option>City</option><option>ZIP code</option></select></label>
                <label>Requested value<input required value={profileRequestValue} onChange={(event) => setProfileRequestValue(event.target.value)} placeholder="Enter the corrected value" /></label>
                <div className="employee-page__form-actions"><span>Assigned to TD002</span><button type="submit" className="employee-page__primary-button">Send request <Arrow /></button></div>
              </form>
            ) : (
              <button type="button" className="employee-page__secondary-button" onClick={() => setProfileRequestOpen(true)}>Submit profile change request <Arrow /></button>
            )}
          </section>

          <section className="employee-page__card" id="paystubs">
            <PanelHeading eyebrow="Payroll history" title="Paystubs" action={<span className="employee-page__muted-label">PDF available</span>} />
            <p className="employee-page__card-intro">View the latest paystub on screen or download a PDF copy for your records.</p>
            <div className="employee-page__paystub-list">
              {paystubs.map((paystub) => (
                <article className="employee-page__paystub-row" key={paystub.id}>
                  <span className="employee-page__paystub-icon">$</span><div><strong>{paystub.period}</strong><span>Paid {paystub.paid} / {paystub.hours}</span></div><strong className="employee-page__paystub-net">{paystub.net}</strong><div className="employee-page__paystub-actions"><button type="button" onClick={() => setSelectedPaystub(paystub)} aria-label={`Preview paystub for ${paystub.period}`}><EyeIcon /> Preview</button><button type="button" onClick={() => downloadPaystub(paystub)} aria-label={`Download paystub for ${paystub.period}`}><DownloadIcon /> Download</button></div>
                </article>
              ))}
            </div>
          </section>
        </div>

        <section className="employee-page__card employee-page__history-card" id="history">
          <PanelHeading eyebrow="Past submissions" title="Timesheet history" action={<a className="employee-page__text-button" href="#timesheets">Enter new hours <Arrow /></a>} />
          <div className="employee-page__history-table" role="table" aria-label="Timesheet history">
            <div className="employee-page__history-row employee-page__history-row--header" role="row"><span>Pay period</span><span>Hours</span><span>Submitted</span><span>Reporting manager</span><span>Status</span></div>
            {timesheetHistory.map((item) => <div className="employee-page__history-row" role="row" key={item.period}><span><strong>{item.period}</strong></span><span>{item.hours} hrs</span><span>{item.submitted}</span><span>{item.routed}</span><span><StatusPill tone={item.tone}>{item.status}</StatusPill></span></div>)}
          </div>
        </section>
      </div>
      {selectedPaystub && <PaystubPreview paystub={selectedPaystub} onClose={() => setSelectedPaystub(null)} onDownload={downloadPaystub} />}
    </DemoWorkspaceRoute>
  );
}
