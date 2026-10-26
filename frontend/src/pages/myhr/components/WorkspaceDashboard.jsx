import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import timesheetApi from '../../../api/timesheetApi';
import payrollReadinessApi from '../../../api/payrollReadinessApi';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import { Button, DataTable, NeedsYouList, PageHeader, RouteLine } from '../../../components';
import { getMyHrSession } from '../../../auth/myHrSession';
import './workspaceDashboard.css';

export default function WorkspaceDashboard({ role, name, basePath }) {
  const sessionUserId = getMyHrSession()?.userId;
  const isReviewableSubmission = (row) => row.status === 'SUBMITTED' && (role === 'COMPANY_ADMIN' || row.employeeUserId !== sessionUserId);
  const [data, setData] = useState({});
  const [search, setSearch] = useState('');
  const [peoplePage, setPeoplePage] = useState(1);
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
      if (key === 'documents' ? typeof value?.pending !== 'number' : !Array.isArray(value)) throw new Error('Invalid response');
      if (active) setData((previous) => ({ ...previous, [key]: { value } }));
    }).catch(() => { if (active) setData((previous) => ({ ...previous, [key]: { error: true } })); });
    load('employees', () => myHrEmployeeApi.list());
    load('profiles', () => myHrProfileChangeApi.listForAdmin());
    load('timesheets', () => timesheetApi.list());
    if (role === 'COMPANY_ADMIN') load('documents', () => myHrDocumentRequestApi.listOfferLetterApprovals());
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
  const employees = data.employees?.value;
  const visible = (employees || []).filter((employee) => [employee.name, employee.employeeId, employee.designation].filter(Boolean).join(' ').toLowerCase().includes(search.toLowerCase()));
  const page = Math.min(peoplePage, Math.max(1, Math.ceil(visible.length / 5)));
  const summary = data.payroll?.value;
  const hasReadiness = ['totalEmployees', 'readyForPayroll', 'awaitingApproval'].every((key) => Number.isFinite(summary?.[key]) && summary[key] >= 0)
    && summary.readyForPayroll + summary.awaitingApproval <= summary.totalEmployees;
  const followup = hasReadiness ? summary.totalEmployees - summary.readyForPayroll - summary.awaitingApproval : null;
  const readiness = hasReadiness && summary.totalEmployees > 0 ? Math.round(summary.readyForPayroll / summary.totalEmployees * 100) : null;
  const payrollPath = basePath + '/timesheets?view=payroll&payrollMonth=' + month;
  const followupPath = payrollPath + '&status=NEEDS_FOLLOW_UP';
  const period = data.payroll?.period;
  const urgentPayroll = Number.isFinite(period?.daysUntilCutoff) && period.daysUntilCutoff >= 0 && period.daysUntilCutoff <= 3;
  const reviewCount = data.timesheets?.value?.filter(isReviewableSubmission).length;
  const profileCount = data.profiles?.value?.filter((row) => row.status === 'PENDING').length;
  const queues = [
    { id: 'timesheets', title: 'Timesheets awaiting review', count: reviewCount, context: 'Submitted hours waiting for a decision.', to: basePath + '/timesheets?view=team&subtab=submissions&status=SUBMITTED', actionLabel: 'Review timesheets', priority: 1 },
    { id: 'profiles', title: 'Profile change requests', count: profileCount, context: 'Employee information changes waiting for review.', to: basePath + '/profile-change-requests?view=assigned&status=PENDING', actionLabel: 'Review changes', priority: 2 },
    ...(role === 'COMPANY_ADMIN' ? [{ id: 'documents', title: 'Documents awaiting approval', count: data.documents?.value?.pending, context: 'Prepared documents waiting for your approval.', to: '/myhr/admin/offer-letter-approvals?status=PENDING_APPROVAL', actionLabel: 'Review documents', priority: 3 }] : []),
    { id: 'payroll', title: 'Payroll follow-ups', count: followup, context: 'Missing submissions and corrections for the selected payroll month.', to: followupPath, actionLabel: 'Review follow-ups', priority: urgentPayroll ? 0 : 4 },
  ];
  const knownQueues = queues.filter((queue) => Number.isFinite(queue.count) && queue.count >= 0);
  const pendingQueues = queues.filter((queue) => !Number.isFinite(queue.count) || queue.count < 0);
  const shortDate = (value) => {
    if (!value) return null;
    const date = new Date(/^\d{4}-\d{2}-\d{2}$/.test(value) ? value + 'T00:00:00' : value);
    return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  };
  const startDate = shortDate(period?.requiredPeriodStart);
  const endDate = shortDate(period?.requiredPeriodEnd);
  const cutoffDate = shortDate(period?.payrollCutoffDate);
  const opened = startDate && new Date(period.requiredPeriodStart + 'T00:00:00') <= new Date();
  const payrollSteps = startDate ? [
    { label: 'Opened', sublabel: startDate, state: opened ? 'done' : 'current' },
    { label: 'Review', sublabel: hasReadiness && summary.totalEmployees === 0 ? 'No employees in this payroll period.' : 'Review hours and resolve follow-ups.', state: opened ? 'current' : 'upcoming' },
    ...(cutoffDate ? [{ label: 'Run', sublabel: `Cutoff: ${cutoffDate}. Payroll run status is not available.`, state: 'upcoming' }] : []),
  ] : [];
  const statusMessage = (key) => data[key]?.error ? 'Unable to load. Try Refresh.' : data[key] ? 'Data unavailable for this queue.' : 'Loading...';

  return <div className="workspace-dashboard">
    <PageHeader eyebrow="Workspace overview" title={'Welcome' + (name ? ', ' + name : '')} summary="Your people and the next steps that need your attention."
      primaryAction={<Button variant="secondary" onClick={() => setRefresh((value) => value + 1)}>Refresh</Button>} />
    <div className="workspace-dashboard__columns">
      <section className="workspace-dashboard__card workspace-dashboard__attention" aria-labelledby="dashboard-attention-title">
        <p className="workspace-dashboard__eyebrow">Next stops</p><h2 id="dashboard-attention-title">Needs you today</h2>
        <NeedsYouList items={knownQueues} label="Priority action queues" emptyMessage={pendingQueues.length ? 'Pending actions appear as the queues load.' : 'No pending actions in the loaded queues.'} />
        {pendingQueues.length > 0 && <ul className="workspace-dashboard__queue-status" aria-live="polite">{pendingQueues.map((queue) => <li key={queue.id}><strong>{queue.title}</strong><span>{statusMessage(queue.id)}</span></li>)}</ul>}
        {role === 'COMPANY_ADMIN' && data.documents?.value?.pending === 0 && <Link className="workspace-dashboard__text-link" to="/myhr/admin/offer-letter-approvals">View approval history</Link>}
      </section>
      <section className="workspace-dashboard__card workspace-dashboard__payroll" aria-labelledby="payroll-window-title">
        <p className="workspace-dashboard__eyebrow">Time &amp; pay</p><h2 id="payroll-window-title">Payroll window</h2>
        <label className="workspace-dashboard__month">Payroll month<input type="month" value={month} onChange={(event) => { if (/^\d{4}-\d{2}$/.test(event.target.value)) setMonth(event.target.value); }} /></label>
        {payrollSteps.length > 0 && <RouteLine steps={payrollSteps} label="Payroll window progress" />}
        {startDate && endDate && <p className="workspace-dashboard__period">Timesheet period: {startDate} to {endDate}</p>}
        {cutoffDate && <p className="workspace-dashboard__period">Cutoff: {cutoffDate}</p>}
        {readiness !== null ? <div className="workspace-dashboard__readiness"><p><strong>{readiness}%</strong> ready for payroll</p><div role="progressbar" aria-label="Payroll ready" aria-valuemin={0} aria-valuemax={100} aria-valuenow={readiness} className="workspace-dashboard__readiness-track"><span style={{ width: readiness + '%' }} /></div></div>
          : <p role="status">{hasReadiness ? 'No employees in this payroll period.' : statusMessage('payroll')}</p>}
        {hasReadiness && <p className="workspace-dashboard__payroll-note">{summary.totalEmployees === 0 ? 'Choose another payroll month to review its readiness.' : followup > 0 ? 'Resolve missing submissions and corrections before payroll.' : summary.awaitingApproval > 0 ? 'Submitted hours are waiting for approval.' : 'All employees in this period are ready for payroll.'}</p>}
        <div className="workspace-dashboard__payroll-actions"><Link to={payrollPath}>Open report</Link><Link to={followupPath}>Review employees requiring follow-up</Link></div>
      </section>
    </div>
    <section className="workspace-dashboard__people" aria-labelledby="dashboard-employees-title">
      <div className="workspace-dashboard__people-heading"><div><h2 id="dashboard-employees-title">People</h2>{employees?.every((employee) => typeof employee.accountStatus === 'string') && <p>{employees.filter((employee) => employee.accountStatus === 'ACTIVE').length} active employees</p>}</div>
        <label>Search people<input type="search" placeholder="Name, ID or job title" value={search} onChange={(event) => { setSearch(event.target.value); setPeoplePage(1); }} /></label>
      </div>
      {data.employees?.error ? <p role="status">{statusMessage('employees')}</p> : <DataTable label="Dashboard people" loading={!data.employees} columns={[
        { key: 'person', header: 'Person', type: 'person' }, { key: 'designation', header: 'Job title' }, { key: 'reportingManagerName', header: 'Manager' }, { key: 'accountStatus', header: 'Status', type: 'status' },
      ]} rows={visible.slice((page - 1) * 5, page * 5).map((employee, index) => ({ ...employee, id: employee.userId || employee.employeeId || String((page - 1) * 5 + index), person: { name: employee.name || 'Name unavailable', id: employee.employeeId } }))}
        pagination={employees ? { page, pageSize: 5, total: visible.length, onPageChange: setPeoplePage } : undefined}
        emptyMessage={search ? 'No people match your search.' : 'No people to display.'} />}
      <Link className="workspace-dashboard__text-link" to={basePath + '/employees'}>View all people</Link>
    </section>
  </div>;
}
