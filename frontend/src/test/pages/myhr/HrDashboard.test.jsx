import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MyHrRoleLandingPage from '../../../pages/myhr/MyHrRoleLandingPage';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import timesheetApi from '../../../api/timesheetApi';
import payrollReadinessApi from '../../../api/payrollReadinessApi';
import { saveMyHrAuthentication } from '../../../auth/myHrSession';

vi.mock('../../../api/myHrEmployeeApi');
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

it('shows real work queues and excludes the HR user own submission from team review', async () => {
  show();
  expect(await screen.findByRole('heading', { name: 'Welcome, Vasavi' })).toBeInTheDocument();
  const queues = await screen.findByRole('region', { name: 'Priority action queues' });
  await waitFor(() => expect(within(queues).getAllByRole('listitem')).toHaveLength(3));
  expect(within(queues).getByText('Timesheets awaiting review').closest('li')).toHaveTextContent(/^1/);
  expect(within(queues).getByText('Profile change requests').closest('li')).toHaveTextContent(/^1/);
  expect(within(queues).getByText('Payroll follow-ups').closest('li')).toHaveTextContent(/^1/);
  expect(within(queues).getByRole('link', { name: 'Review timesheets' })).toHaveAttribute('href', '/myhr/hr/timesheets?view=team&subtab=submissions&status=SUBMITTED');
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

it.each([['HR', '/myhr/hr'], ['COMPANY_ADMIN', '/myhr/admin']])('keeps the %s dashboard focused on work with an employee directory shortcut', async (role, basePath) => {
  saveMyHrAuthentication('test-token', { userId: 'hr-1', role, loginId: 'HR001' });
  render(<MemoryRouter><MyHrRoleLandingPage role={role} /></MemoryRouter>);
  await screen.findByRole('progressbar', { name: 'Payroll ready' });
  expect(screen.getByRole('link', { name: 'View employees' })).toHaveAttribute('href', basePath + '/employees');
  expect(screen.queryByRole('table', { name: 'Dashboard people' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Search people')).not.toBeInTheDocument();
  expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
  expect(screen.getByText(/Payroll run status is not available/)).toBeInTheDocument();
});
