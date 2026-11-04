import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MyHrRoleLandingPage from '../../../pages/myhr/MyHrRoleLandingPage';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrAuditApi from '../../../api/myHrAuditApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import timesheetApi from '../../../api/timesheetApi';
import payrollReadinessApi from '../../../api/payrollReadinessApi';
import { saveMyHrAuthentication } from '../../../auth/myHrSession';

vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrAuditApi');
vi.mock('../../../api/myHrProfileChangeApi');
vi.mock('../../../api/timesheetApi');
vi.mock('../../../api/payrollReadinessApi');
vi.mock('../../../pages/myhr/MyHrShell', () => ({ default: ({ children }) => <main>{children}</main> }));

const response = (data) => ({ data: { data } });
function show() {
  saveMyHrAuthentication('test-token', { userId: 'hr-1', role: 'HR', loginId: 'HR001' });
  return render(<MemoryRouter><MyHrRoleLandingPage role="HR" /></MemoryRouter>);
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.setSystemTime(new Date('2026-11-04T18:00:00Z'));
  myHrDocumentRequestApi.dashboardSummary.mockResolvedValue(response({ pending: 3, oldestRequestedAt: '2026-10-31T10:00:00' }));
  myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue(response({ pending: 0, items: [] }));
  myHrAuditApi.history.mockResolvedValue(response({ items: [{ id: 'a1', actionLabel: 'Profile change approved', actorName: 'HR teammate', performedAt: '2026-11-04T10:00:00', outcome: 'SUCCESS' }] }));
  localStorage.clear();
  myHrEmployeeApi.getMine.mockResolvedValue(response({ firstName: 'Vasavi' }));
  myHrEmployeeApi.list.mockResolvedValue(response([{ userId: 'emp-1', employeeId: 'EMP001', name: 'Alex Rivera', designation: 'Engineer', accountStatus: 'ACTIVE' }]));
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(response([{ id: 'change-1', status: 'PENDING' }]));
  timesheetApi.list.mockResolvedValue(response([
    { id: 'own', employeeUserId: 'hr-1', status: 'SUBMITTED' },
    { id: 'team', employeeUserId: 'emp-1', status: 'SUBMITTED' },
    { id: 'done', employeeUserId: 'emp-2', status: 'APPROVED' },
  ]));
  payrollReadinessApi.getReadiness.mockResolvedValue(response({
    summary: { totalEmployees: 4, readyForPayroll: 2, awaitingApproval: 1 },
    period: { requiredPeriodStart: '2026-10-01', requiredPeriodEnd: '2026-10-31', payrollCutoffDate: '2026-11-05' },
  }));
});

afterEach(() => vi.useRealTimers());

it('shows real work queues and excludes the HR user own submission from team review', async () => {
  show();
  expect(await screen.findByRole('heading', { name: 'Welcome, Vasavi' })).toBeInTheDocument();
  const queues = await screen.findByRole('region', { name: 'Priority action queues' });
  await waitFor(() => expect(within(queues).getAllByRole('listitem')).toHaveLength(4));
  expect(within(queues).getByText('Timesheets awaiting review').closest('li')).toHaveTextContent(/^1/);
  expect(within(queues).getByText('Profile change requests').closest('li')).toHaveTextContent(/^1/);
  expect(within(queues).getByText('Payroll follow-ups').closest('li')).toHaveTextContent(/^1/);
  expect(within(queues).getByRole('link', { name: 'Review timesheets' })).toHaveAttribute('href', '/myhr/hr/timesheets?view=team&subtab=actions&status=SUBMITTED');
  expect(within(queues).getByRole('link', { name: 'Timesheets awaiting review' })).toHaveAttribute('href', '/myhr/hr/timesheets?view=team&subtab=actions&status=SUBMITTED');
  expect(within(queues).getByRole('link', { name: 'Profile change requests' })).toHaveAttribute('href', '/myhr/hr/profile-change-requests?view=assigned&status=PENDING');
  expect(screen.queryByRole('heading', { name: 'HR Actions' })).not.toBeInTheDocument();
  expect(screen.getByRole('progressbar', { name: 'Payroll ready' })).toHaveAttribute('aria-valuenow', '50');
});

it('does not invent counts when a queue fails and can recover with Refresh', async () => {
  timesheetApi.list.mockRejectedValueOnce(new Error('Unavailable'));
  show();
  await screen.findByText('Unable to load. Try Refresh.');
  const pending = screen.getByText('Timesheets awaiting review').closest('li');
  expect(pending).not.toHaveTextContent(/^0/);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Refresh' }));
  await waitFor(() => expect(screen.getByRole('link', { name: 'Review timesheets' })).toBeInTheDocument());
  expect(timesheetApi.list).toHaveBeenCalledTimes(2);
});

it('requests the chosen payroll month and keeps the report links in that period', async () => {
  show();
  await screen.findByRole('progressbar', { name: 'Payroll ready' });
  fireEvent.change(screen.getByLabelText('Payroll month'), { target: { value: '2026-09' } });
  await waitFor(() => expect(payrollReadinessApi.getReadiness).toHaveBeenLastCalledWith(expect.objectContaining({ payrollYear: 2026, payrollMonthNumber: 9 })));
  expect(screen.getByRole('link', { name: 'Open report' })).toHaveAttribute('href', '/myhr/hr/payroll-readiness?payrollMonth=2026-09');
});

it.each(['HR', 'COMPANY_ADMIN'])('keeps the %s dashboard focused on work without the employee directory', async (role) => {
  saveMyHrAuthentication('test-token', { userId: 'hr-1', role, loginId: 'HR001' });
  render(<MemoryRouter><MyHrRoleLandingPage role={role} /></MemoryRouter>);
  await screen.findByRole('progressbar', { name: 'Payroll ready' });
  expect(screen.queryByRole('link', { name: 'View employees' })).not.toBeInTheDocument();
  expect(screen.queryByRole('table', { name: 'Dashboard people' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Search people')).not.toBeInTheDocument();
  expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
  expect(screen.queryByText(/Payroll run.*complete/i)).not.toBeInTheDocument();
});

it('shows the document count and oldest age, keeping cleared queues visible', async () => {
  timesheetApi.list.mockResolvedValue(response([]));
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(response([]));
  show();
  const documents = (await screen.findByText('Oldest request: 4 days.')).closest('li');
  expect(documents).toHaveTextContent('3');
  expect(within(documents).getByRole('link', { name: 'Review requests' })).toHaveAttribute('href', '/myhr/hr/document-requests?view=action-required');
  for (const title of ['Timesheets awaiting review', 'Profile change requests']) {
    expect(screen.getByText(title).closest('li')).toHaveTextContent('All clear');
  }
  expect(screen.getByRole('region', { name: 'Coming up' })).toHaveTextContent('Payroll cutoff');
  expect(myHrAuditApi.history).not.toHaveBeenCalled();
  expect(screen.queryByRole('heading', { name: 'Recent activity' })).not.toBeInTheDocument();
});

it('does not treat failed document data as zero or invent an age', async () => {
  myHrDocumentRequestApi.dashboardSummary.mockRejectedValue(new Error('Unavailable'));
  show();
  await screen.findByText('Unable to load. Try Refresh.');
  expect(screen.getByText('Document requests').closest('li')).not.toHaveTextContent('All clear');
  expect(screen.queryByText(/Oldest request:/)).not.toBeInTheDocument();
});

it('omits the coming-up strip when a cutoff is absent or past', async () => {
  payrollReadinessApi.getReadiness.mockResolvedValue(response({ summary: { totalEmployees: 0, readyForPayroll: 0, awaitingApproval: 0 }, period: { payrollCutoffDate: '2026-11-01' } }));
  show();
  await screen.findByText('No employees in this payroll period.');
  expect(screen.queryByRole('region', { name: 'Coming up' })).not.toBeInTheDocument();
});

it('shows the latest audit events for Admin without expanding HR permissions', async () => {
  saveMyHrAuthentication('test-token', { userId: 'admin-1', role: 'COMPANY_ADMIN' });
  render(<MemoryRouter><MyHrRoleLandingPage role="COMPANY_ADMIN" /></MemoryRouter>);
  await screen.findByText('Show latest 1');
  expect(screen.getAllByText('Profile change approved')).toHaveLength(2);
  expect(myHrAuditApi.history).toHaveBeenCalledWith({ page: 0, size: 5 });
  await userEvent.setup().click(screen.getByText('Show latest 1'));
  expect(screen.getByRole('link', { name: 'View audit history' })).toHaveAttribute('href', '/myhr/admin/audit');
});

it('shows All clear for an empty document queue without inventing an oldest age', async () => {
  myHrDocumentRequestApi.dashboardSummary.mockResolvedValue(response({ pending: 0, oldestRequestedAt: null }));
  show();
  await screen.findByRole('progressbar', { name: 'Payroll ready' });
  expect(screen.getByText('Document requests').closest('li')).toHaveTextContent('All clear');
  expect(screen.queryByText(/Oldest request:/)).not.toBeInTheDocument();
});

it('omits the coming-up strip when no cutoff is stored', async () => {
  payrollReadinessApi.getReadiness.mockResolvedValue(response({ summary: { totalEmployees: 0, readyForPayroll: 0, awaitingApproval: 0 }, period: {} }));
  show();
  await screen.findByText('No employees in this payroll period.');
  expect(screen.queryByRole('region', { name: 'Coming up' })).not.toBeInTheDocument();
});

it('keeps the latest activity visible and lets the user expand the remaining four events', async () => {
  myHrAuditApi.history.mockResolvedValue(response({ items: Array.from({ length: 5 }, (_, index) => ({ id: String(index), actionLabel: `Activity ${index + 1}`, performedAt: '2026-11-04T10:00:00' })) }));
  saveMyHrAuthentication('test-token', { role: 'COMPANY_ADMIN' });
  render(<MemoryRouter><MyHrRoleLandingPage role="COMPANY_ADMIN" /></MemoryRouter>);
  await screen.findByText('Show latest 5');
  expect(screen.getAllByText('Activity 1').some((element) => !element.closest('details'))).toBe(true);
  const details = screen.getByText('Show latest 5').closest('details');
  expect(details).not.toHaveAttribute('open');
  expect(screen.getByText('Activity 5')).not.toBeVisible();
  await userEvent.setup().click(screen.getByText('Show latest 5'));
  expect(details).toHaveAttribute('open');
  expect(screen.getByText('Activity 5')).toBeVisible();
});
