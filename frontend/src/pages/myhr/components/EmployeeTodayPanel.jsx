import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import timesheetApi from '../../../api/timesheetApi';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import { Button, PageHeader, RouteLine, StatusBadge } from '../../../components';
import dashboardDocumentProgress from './dashboardDocumentProgress';
import { documentRequestStatus } from '../features/documentRequests/offerLetterState';
import './employeeTodayPanel.css';

const shortDate = (value) => {
  const date = new Date(/^\d{4}-\d{2}-\d{2}$/.test(value || '') ? `${value}T00:00:00` : value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
};

const dateKey = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

export default function EmployeeTodayPanel({ name, basePath }) {
  const [data, setData] = useState({});
  const [refresh, setRefresh] = useState(0);
  const today = new Date();
  const monday = new Date(today);
  monday.setDate(today.getDate() - (today.getDay() + 6) % 7);
  const weekdays = Array.from({ length: 5 }, (_, index) => {
    const date = new Date(monday);
    date.setDate(monday.getDate() + index);
    return { key: dateKey(date), label: shortDate(dateKey(date)) };
  });

  useEffect(() => {
    let active = true;
    setData({});
    const load = (key, request, resolve) => Promise.resolve().then(request).then((response) => {
      const value = resolve(response.data?.data);
      if (!Array.isArray(value)) throw new Error('Invalid response');
      if (active) setData((current) => ({ ...current, [key]: { value } }));
    }).catch(() => { if (active) setData((current) => ({ ...current, [key]: { error: true } })); });
    load('timesheets', () => timesheetApi.list(), (value) => value);
    load('profiles', () => myHrProfileChangeApi.listMine(), (value) => value);
    load('documents', () => myHrDocumentRequestApi.list({ workspace: 'mine', page: 0, size: 5 }), (value) => Array.isArray(value) ? value : value?.items);
    return () => { active = false; };
  }, [refresh]);

  const days = new Map();
  [...(data.timesheets?.value || [])].sort((a, b) => String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || ''))).forEach((record) => {
    const recordedDays = new Map();
    (record.entries || []).forEach((entry) => recordedDays.set(entry.workDate, recordedDays.get(entry.workDate) || Number(entry.hoursWorked) > 0));
    recordedDays.forEach((recorded, day) => { if (!days.has(day)) days.set(day, { recorded, status: record.status }); });
  });
  const logged = weekdays.filter((day) => days.get(day.key)?.recorded).length;
  const timesheets = data.timesheets?.value || [];
  const drafts = timesheets.filter((record) => record.status === 'DRAFT');
  const submitted = timesheets.filter((record) => record.status === 'SUBMITTED');
  const draftHours = drafts.reduce((sum, record) => sum + (record.entries || []).reduce((total, entry) => total + Number(entry.hoursWorked || 0), 0), 0);
  const submittedHours = submitted.reduce((sum, record) => sum + Number(record.totalHours || 0), 0);
  const pastWeekdaysMissing = weekdays.filter((day) => day.key < dateKey(today) && !days.get(day.key)?.recorded).length;
  const timesheetStatus = drafts.length
    ? { tone: 'warning', title: 'Draft timesheet needs attention', body: `${draftHours.toFixed(2)} hours are saved in a draft and are not sent for approval.`, action: 'Resume or delete draft', href: basePath + '/timesheets' }
    : submitted.length
      ? { tone: 'pending', title: 'Timesheet awaiting approval', body: `${submittedHours.toFixed(2)} submitted hours are waiting for your manager or HR to review.`, action: 'View submitted hours', href: basePath + '/timesheets?mode=history' }
      : pastWeekdaysMissing > 0
        ? { tone: 'warning', title: 'Timesheet needs entries', body: `${pastWeekdaysMissing} past workday${pastWeekdaysMissing === 1 ? '' : 's'} still need hours or a leave entry.`, action: 'Log missing hours', href: basePath + '/timesheets?mode=enter' }
        : data.timesheets?.value
          ? { tone: 'success', title: 'Submitted hours are up to date', body: 'Your current submitted hours are recorded. Approval status will update here after review.', action: 'Review hours', href: basePath + '/timesheets?mode=history' }
          : null;
  const profile = data.profiles?.value?.find((request) => request.status === 'PENDING') || data.profiles?.value?.[0];
  const document = data.documents?.value?.find((request) => dashboardDocumentProgress(request)?.needsAction) || data.documents?.value?.[0];
  const progress = dashboardDocumentProgress(document);
  const processedDate = shortDate(profile?.processedAt);
  const isOldApproval = profile?.status === 'APPROVED' && processedDate && today.getTime() - new Date(profile.processedAt).getTime() > 3 * 86400000;
  const message = (key, empty) => !data[key] ? 'Loading...' : data[key].error ? 'Unable to load. Try Refresh.' : empty;
  // Rejected timesheet days show a red X; a past day without hours says "Not logged" rather than "Upcoming".
  const weekSteps = weekdays.map((day) => {
    const entry = days.get(day.key);
    const isToday = day.key === dateKey(today);
    if (entry?.recorded && entry.status === 'REJECTED') return { label: day.label, state: 'rejected', sublabel: 'Timesheet rejected' };
    return {
      label: day.label,
      state: isToday ? 'current' : entry?.recorded ? 'done' : 'upcoming',
      stateLabel: !isToday && !entry?.recorded && day.key < dateKey(today) ? 'Not logged' : undefined,
      sublabel: entry?.recorded ? 'Hours recorded' : isToday ? 'Not logged' : '-',
    };
  });
  return <div className="employee-today">
    <PageHeader eyebrow={'Your week / ' + shortDate(weekdays[0].key)} title={'Welcome' + (name ? ', ' + name : '')}
      summary="Your hours and the requests that need your attention." primaryAction={<Button variant="secondary" onClick={() => setRefresh((value) => value + 1)}>Refresh</Button>} />
    <section className="employee-today__card employee-today__timesheet" aria-label="Your timesheet this week">
      <p className="employee-today__eyebrow">Time &amp; pay</p>
      <h2>{data.timesheets?.value ? "You're " + logged + ' of 5 days in.' : 'Your timesheet this week'}</h2>
      {data.timesheets?.value ? <RouteLine steps={weekSteps} label="Monday to Friday progress" /> : <p role="status">{message('timesheets', '')}</p>}
      {timesheetStatus && <div className={`employee-today__timesheet-status employee-today__timesheet-status--${timesheetStatus.tone}`} role="status">
        <div><strong>{timesheetStatus.title}</strong><span>{timesheetStatus.body}</span></div>
        <Link to={timesheetStatus.href}>{timesheetStatus.action}</Link>
      </div>}
      <div className="employee-today__actions">
        <Link className="employee-today__primary" to={basePath + '/timesheets?mode=enter'}>Log hours</Link>
        <Link className="employee-today__text-link" to={basePath + '/timesheets?mode=history'}>Review submitted hours and attachments</Link>
      </div>
    </section>
    <div className="employee-today__requests">
      <section className="employee-today__card" aria-label="Document request">
        <p className="employee-today__eyebrow">Document request</p><h2>{document?.requestTitle || 'Your documents'}</h2>
        {document ? <>
          <StatusBadge status={document.status} label={documentRequestStatus(document, 'EMPLOYEE')} />
          {progress.steps.length > 0 && <RouteLine steps={progress.steps} orientation="vertical" label="Document progress" />}
          <p>{progress.context}</p>
          {document.requestedAt && shortDate(document.requestedAt) && <p className="employee-today__date">Requested <time dateTime={document.requestedAt}>{shortDate(document.requestedAt)}</time></p>}
        </> : <p role="status">{message('documents', 'No document requests to display.')}</p>}
        <Link to={document?.id ? basePath + '/document-requests/' + document.id : basePath + '/document-requests'}>{progress?.actionLabel || 'View documents'}</Link>
      </section>
      <section className={'employee-today__card' + (isOldApproval ? ' employee-today__card--past' : '')} aria-label="Profile change request">
        <p className="employee-today__eyebrow">Profile change</p>
        <h2>{profile ? ({ NAME: 'Name update', ADDRESS: 'Address update', PHONE_NUMBER: 'Phone number update', PERSONAL_EMAIL: 'Email update' }[profile.fieldName] || 'Profile update') : 'Your profile requests'}</h2>
        {profile ? <>
          <StatusBadge status={profile.status} />
          {processedDate && <p className="employee-today__date">{profile.status === 'APPROVED' ? 'Approved' : profile.status === 'REJECTED' ? 'Rejected' : 'Reviewed'} <time dateTime={profile.processedAt}>{processedDate}</time></p>}
          <p>{profile.status === 'PENDING' ? 'Your request is waiting for review.' : 'Open your requests to see the decision and details.'}</p>
        </> : <p role="status">{message('profiles', 'No profile change requests to display.')}</p>}
        <Link to={basePath + '/profile-change-requests'}>View or request a change</Link>
      </section>
    </div>
  </div>;
}
