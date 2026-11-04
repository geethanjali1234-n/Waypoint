import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import myHrAuditApi from '../../../api/myHrAuditApi';
import { formatDateTime } from '../../../utils/dateFormat';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import timesheetApi from '../../../api/timesheetApi';
import payrollReadinessApi from '../../../api/payrollReadinessApi';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import { Button, NeedsYouList, PageHeader } from '../../../components';
import { getMyHrSession } from '../../../auth/myHrSession';
import './workspaceDashboard.css';

export default function WorkspaceDashboard({ role, name, basePath }) {
  const sessionUserId = getMyHrSession()?.userId;
  const isReviewableSubmission = (row) => row.status === 'SUBMITTED' && (role === 'COMPANY_ADMIN' || row.employeeUserId !== sessionUserId);
  const [data, setData] = useState({});
  const [month, setMonth] = useState(() => {
    const date = new Date();
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  });
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let active = true;
    setData((previous) => ({ payroll: previous.payroll }));
    const load = (key, request) => Promise.resolve().then(request).then((response) => {
      const value = response.data?.data;
      if (['documents', 'approvals'].includes(key) ? !Number.isInteger(value?.pending) || value.pending < 0 : key === 'activity' ? !Array.isArray(value?.items) : !Array.isArray(value)) throw new Error('Invalid response');
      if (active) setData((previous) => ({ ...previous, [key]: { value } }));
    }).catch(() => { if (active) setData((previous) => ({ ...previous, [key]: { error: true } })); });
    load('profiles', () => myHrProfileChangeApi.listForAdmin());
    load('timesheets', () => timesheetApi.list());
    load('documents', () => myHrDocumentRequestApi.dashboardSummary());
    if (role === 'COMPANY_ADMIN') load('activity', () => myHrAuditApi.history({ page: 0, size: 5 }));
    if (role === 'COMPANY_ADMIN') load('approvals', () => myHrDocumentRequestApi.listOfferLetterApprovals());
    return () => { active = false; };
  }, [role, refresh]);
  useEffect(() => {
    let active = true;
    setData((previous) => ({ ...previous, payroll: undefined }));
    const [year, monthNumber] = month.split('-').map(Number);
    Promise.resolve().then(() => payrollReadinessApi.getReadiness({ payrollYear: year, payrollMonthNumber: monthNumber, status: 'ALL', page: 0, size: 1 }))
      .then((response) => {
        if (!response.data?.data?.summary) throw new Error('Missing summary');
        if (active) setData((previous) => ({ ...previous, payroll: { value: response.data.data.summary, period: response.data.data.period } }));
      }).catch(() => { if (active) setData((previous) => ({ ...previous, payroll: { error: true } })); });
    return () => { active = false; };
  }, [role, month, refresh]);
  const summary = data.payroll?.value;
  const hasReadiness = ['totalEmployees', 'readyForPayroll', 'awaitingApproval'].every((key) => Number.isFinite(summary?.[key]) && summary[key] >= 0)
    && summary.readyForPayroll + summary.awaitingApproval <= summary.totalEmployees;
  const followup = hasReadiness ? summary.totalEmployees - summary.readyForPayroll - summary.awaitingApproval : null;
  const readiness = hasReadiness && summary.totalEmployees > 0 ? Math.round(summary.readyForPayroll / summary.totalEmployees * 100) : null;
  const payrollPath = basePath + '/payroll-readiness?payrollMonth=' + month;
  const followupPath = payrollPath + '&status=NEEDS_FOLLOW_UP';
  const period = data.payroll?.period;
  const urgentPayroll = Number.isFinite(period?.daysUntilCutoff) && period.daysUntilCutoff >= 0 && period.daysUntilCutoff <= 3;
  const reviewCount = data.timesheets?.value?.filter(isReviewableSubmission).length;
  const profileCount = data.profiles?.value?.filter((row) => row.status === 'PENDING').length;
  // Request timestamps and payroll dates are business dates in America/Chicago.
  const todayParts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/Chicago', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type) => todayParts.find((item) => item.type === type).value;
  const todayKey = `${part('year')}-${part('month')}-${part('day')}`;
  const oldestKey = data.documents?.value?.oldestRequestedAt?.slice(0, 10);
  const oldestAge = oldestKey ? Math.floor((Date.parse(todayKey) - Date.parse(oldestKey)) / 86400000) : null;
  const documentContext = Number.isFinite(oldestAge) && oldestAge >= 0
    ? `Oldest request: ${oldestAge === 0 ? 'today' : `${oldestAge} ${oldestAge === 1 ? 'day' : 'days'}`}.`
    : 'Requests waiting on your action.';
  const queues = [
    { id: 'timesheets', title: 'Timesheets awaiting review', count: reviewCount, context: 'Submitted hours waiting for a decision.', to: basePath + '/timesheets?view=team&subtab=actions&status=SUBMITTED', actionLabel: 'Review timesheets', priority: 1 },
    { id: 'profiles', title: 'Profile change requests', count: profileCount, context: 'Employee information changes waiting for review.', to: basePath + '/profile-change-requests?view=assigned&status=PENDING', actionLabel: 'Review changes', priority: 2 },
    { id: 'documents', title: 'Document requests', count: data.documents?.value?.pending, context: documentContext, to: basePath + '/document-requests?view=action-required', actionLabel: 'Review requests', priority: 3 },
    ...(role === 'COMPANY_ADMIN' ? [{ id: 'approvals', title: 'Documents awaiting approval', count: data.approvals?.value?.pending, context: 'Prepared documents waiting for your approval.', to: '/myhr/admin/offer-letter-approvals?status=PENDING_APPROVAL', actionLabel: 'Review documents', priority: 3 }] : []),
    { id: 'payroll', title: 'Payroll follow-ups', count: followup, context: 'Missing submissions and corrections for the selected payroll month.', to: followupPath, actionLabel: 'Review follow-ups', priority: urgentPayroll ? 0 : 4 },
  ];
  const knownQueues = queues.filter((queue) => Number.isFinite(queue.count) && queue.count >= 0).map((queue) => queue.count === 0 ? { ...queue, context: 'All clear' } : queue);
  const pendingQueues = queues.filter((queue) => !Number.isFinite(queue.count) || queue.count < 0);
  const shortDate = (value) => {
    if (!value) return null;
    const date = new Date(/^\d{4}-\d{2}-\d{2}$/.test(value) ? value + 'T00:00:00' : value);
    return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  };
  const startDate = shortDate(period?.requiredPeriodStart);
  const endDate = shortDate(period?.requiredPeriodEnd);
  const cutoffDate = shortDate(period?.payrollCutoffDate);
  const statusMessage = (key) => data[key]?.error ? 'Unable to load. Try Refresh.' : data[key] ? 'Data unavailable for this queue.' : 'Loading...';

  return <div className="workspace-dashboard">
    <PageHeader title={'Welcome' + (name ? ', ' + name : '')}
      primaryAction={<Button variant="secondary" onClick={() => setRefresh((value) => value + 1)}>Refresh</Button>} />
    <div className="workspace-dashboard__columns">
      <section className="workspace-dashboard__card workspace-dashboard__attention" aria-labelledby="dashboard-attention-title">
        <p className="workspace-dashboard__eyebrow">Next stops</p><h2 id="dashboard-attention-title">Needs you today</h2>
        <NeedsYouList hideZeroCounts={false} items={knownQueues} label="Priority action queues" emptyMessage={pendingQueues.length ? 'Pending actions appear as the queues load.' : 'No pending actions in the loaded queues.'} />
        {pendingQueues.length > 0 && <ul className="workspace-dashboard__queue-status" aria-live="polite">{pendingQueues.map((queue) => <li key={queue.id}><strong>{queue.title}</strong><span>{statusMessage(queue.id)}</span></li>)}</ul>}
      </section>
      <div className="workspace-dashboard__rail">
      <section className="workspace-dashboard__card workspace-dashboard__payroll" aria-labelledby="payroll-window-title">
        <h2 id="payroll-window-title">Payroll window</h2>
        <label className="workspace-dashboard__month">Payroll month<input type="month" value={month} onChange={(event) => { if (/^\d{4}-\d{2}$/.test(event.target.value)) setMonth(event.target.value); }} /></label>
        {startDate && endDate && <p className="workspace-dashboard__period">Timesheet period: {startDate} to {endDate}</p>}
        {cutoffDate && period.payrollCutoffDate < todayKey && <p className="workspace-dashboard__period">Cutoff: {cutoffDate}</p>}
        {readiness !== null ? <div className="workspace-dashboard__readiness"><p><strong>{readiness}%</strong> ready for payroll</p><div role="progressbar" aria-label="Payroll ready" aria-valuemin={0} aria-valuemax={100} aria-valuenow={readiness} className="workspace-dashboard__readiness-track"><span style={{ width: readiness + '%' }} /></div></div>
          : <p role="status">{hasReadiness ? 'No employees in this payroll period.' : statusMessage('payroll')}</p>}
        {hasReadiness && summary.totalEmployees === 0 && <p className="workspace-dashboard__payroll-note">Choose another payroll month to review its readiness.</p>}
        <div className="workspace-dashboard__payroll-actions"><Link to={payrollPath}>Open report</Link><Link to={followupPath}>Review follow-ups</Link></div>
      </section>
    {cutoffDate && period.payrollCutoffDate >= todayKey && <section className="workspace-dashboard__coming-up" aria-labelledby="dashboard-coming-up-title">
      <h2 id="dashboard-coming-up-title">Coming up</h2>
      <Link to={payrollPath}><time dateTime={period.payrollCutoffDate}>{new Date(period.payrollCutoffDate + 'T00:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}</time>: Payroll cutoff</Link>
    </section>}
    {role === 'COMPANY_ADMIN' && <section className="workspace-dashboard__card workspace-dashboard__activity" aria-labelledby="dashboard-activity-title">
      {data.activity?.value?.items.length > 0 ? <>
        <details className="workspace-dashboard__activity-more">
          <summary><h2 id="dashboard-activity-title">Recent activity</h2><span>Show latest {Math.min(data.activity.value.items.length, 5)}</span></summary>
          <ul>{data.activity.value.items.slice(0, 5).map((item) => <ActivityItem key={item.id} item={item} />)}</ul>
          <Link className="workspace-dashboard__text-link" to={basePath + '/audit'}>View audit history</Link>
        </details>
        <ul className="workspace-dashboard__latest-event">{data.activity.value.items.slice(0, 1).map((item) => <ActivityItem key={item.id} item={item} />)}</ul>
      </> : <>
        <h2 id="dashboard-activity-title">Recent activity</h2>
        <p role="status">{data.activity?.error ? 'Unable to load activity. Try Refresh.' : !data.activity ? 'Loading activity...' : 'No recent activity.'}</p>
      </>}
    </section>}
      </div>
    </div>
  </div>;
}

function ActivityItem({ item }) {
  return <li>
    <div><strong>{item.actionLabel}</strong>{item.actorName && <span>{item.actorName}</span>}{item.outcome && item.outcome !== 'SUCCESS' && <span>{item.outcome}</span>}</div>
    {item.performedAt && <time dateTime={item.performedAt}>{formatDateTime(item.performedAt)}</time>}
  </li>;
}
