import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import payrollReadinessApi from '../../api/payrollReadinessApi';
import timesheetApi from '../../api/timesheetApi';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';
import myHrProfileChangeApi from '../../api/myHrProfileChangeApi';
import { askWavi, permittedActions } from '../../components/chatbot/waviAssistant';
import wavyApi from '../../api/wavyApi';

vi.mock('../../api/payrollReadinessApi', () => ({ default: { getReadiness: vi.fn() } }));
vi.mock('../../api/timesheetApi', () => ({ default: { list: vi.fn() } }));
vi.mock('../../api/myHrEmployeeApi', () => ({ default: { list: vi.fn(), getMine: vi.fn() } }));
vi.mock('../../api/myHrProfileChangeApi', () => ({ default: { listForAdmin: vi.fn() } }));
vi.mock('../../api/wavyApi', () => ({ default: { chat: vi.fn() } }));

beforeEach(() => { vi.resetAllMocks(); wavyApi.chat.mockResolvedValue({ data: { success: true, data: { mode: 'limited', notice: 'Workflow guidance' } } }); localStorage.setItem('accessToken', 'test-token'); });
afterEach(() => localStorage.removeItem('accessToken'));
const admin = { role: 'COMPANY_ADMIN', route: '/myhr/admin/payroll-readiness' };
const employee = { role: 'EMPLOYEE', route: '/myhr/employee/timesheets' };
const ok = (data) => ({ data: { success: true, data } });

describe('Wavy trusted application data', () => {
  it('answers who is my HR using the actual profile assignment', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue(ok({ reportingManager: { name: 'Riley Chen', roleLabel: 'HR' } }));
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.text).toContain('Riley Chen');
    expect(reply.text).toContain('HR** role');
    expect(reply.source).toBe('Your authenticated profile');
  });

  it('does not label an administrator as HR', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue(ok({ reportingManager: { name: 'Alex', roleLabel: 'COMPANY_ADMIN' } }));
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.text).toContain("won't assume");
    expect(reply.text).not.toContain('HR** role');
  });

  it('uses a real backend model response instead of the canned conversation', async () => {
    wavyApi.chat.mockResolvedValue(ok({ mode: 'ai', text: 'A live conversational answer.', actions: [], sources: [{ id: 'guide', title: 'Waypoint guide' }] }));
    const reply = await askWavi('Hello, can you help me?', { ...employee, history: [{ sender: 'user', text: 'My original task' }] });
    expect(reply.text).toBe('A live conversational answer.');
    expect(reply.source).toBe('Waypoint guide');
    expect(wavyApi.chat).toHaveBeenCalledWith(expect.objectContaining({ history: [{ role: 'user', text: 'My original task' }] }));
  });

  it('does not bypass an authentication or authorization failure with local tools', async () => {
    wavyApi.chat.mockRejectedValue({ response: { status: 403 } });
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.error).toBe(true);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });
  it.each(['Hi', 'Alright', 'Thanks', 'Okay!', 'Thank you Wavy', 'Sounds good'])('handles %s conversationally without API calls or navigation cards', async (query) => {
    const response = await askWavi(query, admin);
    expect(response.social).toBe(true);
    expect(response.actions).toEqual([]);
    expect(response.text).not.toContain('exact match');
    expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
  });

  it('clarifies an employee search and filters only authorized API results', async () => {
    const start = await askWavi('I need to find an employee', admin);
    expect(start.text).toContain('name, employee ID, or designation');
    const field = await askWavi('by name', { ...admin, context: start.context });
    expect(field.text).toBe("What's the name?");
    myHrEmployeeApi.list.mockResolvedValue(ok([
      { name: 'Alice Chen', employeeId: 'E12', designation: 'Engineer', personalEmail: 'private@example.com' },
      { name: 'Bob Jones', employeeId: 'E13', designation: 'Designer' },
    ]));
    const found = await askWavi('Alice', { ...admin, context: field.context });
    expect(found.text).toContain('Alice Chen');
    expect(found.text).not.toContain('Bob Jones');
    expect(found.text).not.toContain('private@example.com');
    expect(found.source).toBe('Employee Management API');
  });

  it('does not query the employee roster for an employee role', async () => {
    const response = await askWavi('find an employee named Alice', employee);
    expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
    expect(response.text).toContain("isn't available for your role");
  });

  it('uses the current profile-request route to resolve an approval status question', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(ok([{ status: 'PENDING' }, { status: 'APPROVED' }]));
    const response = await askWavi('Check pending approvals', { ...admin, route: '/myhr/admin/profile-change-requests' });
    expect(response.text).toContain('**1** pending request');
    expect(response.source).toBe('Profile requests API');
  });

  it('checks submitted timesheets from the API on the timesheet route', async () => {
    timesheetApi.list.mockResolvedValue(ok([{ status: 'SUBMITTED' }, { status: 'APPROVED' }]));
    const response = await askWavi('Check pending approvals', { ...admin, route: '/myhr/admin/timesheets' });
    expect(response.text).toContain('**1** submitted timesheet');
    expect(response.source).toBe('Timesheets API');
  });
  it('reports only returned payroll counts and confirms a successful read', async () => {
    payrollReadinessApi.getReadiness.mockResolvedValue(ok({ summary: { totalEmployees: 9, readyForPayroll: 6, awaitingApproval: 2 } }));
    const response = await askWavi('Check payroll status', admin);
    expect(response.text).toContain('**6 of 9**');
    expect(response.text).toContain('**2**');
    expect(response.confirmed).toBe(true);
    expect(response.source).toBe('Payroll readiness API');
  });

  it('does not query payroll for employees or invent a deadline', async () => {
    const response = await askWavi('When is my timesheet deadline?', employee);
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
    expect(response.text).toContain("don't have a verified deadline");
    expect(response.confirmed).toBeUndefined();
  });

  it('uses the actual API cutoff instead of the knowledge-base date', async () => {
    payrollReadinessApi.getReadiness.mockResolvedValue(ok({ period: { payrollCutoffDate: '2026-11-19' } }));
    const response = await askWavi('When is payroll cutoff?', admin);
    expect(response.text).toContain('2026-11-19');
    expect(response.text).not.toContain('10th');
  });

  it('offers recovery without inventing counts if the payload is incomplete', async () => {
    payrollReadinessApi.getReadiness.mockResolvedValue(ok({ summary: {} }));
    const response = await askWavi('Check payroll status', admin);
    expect(response.error).toBe(true);
    expect(response.confirmed).toBeUndefined();
    expect(response.actions[0]).toEqual({ label: 'Try again', query: 'Check payroll status' });
  });

  it('respects backend denial without claiming success or encouraging retries', async () => {
    payrollReadinessApi.getReadiness.mockRejectedValue({ response: { status: 403 } });
    const response = await askWavi('Check payroll status', admin);
    expect(response.text).toContain("isn't available for your account");
    expect(response.confirmed).toBeUndefined();
    expect(response.actions.some((action) => action.label === 'Try again')).toBe(false);
  });

  it('reads own timesheet status through the authenticated API', async () => {
    timesheetApi.list.mockResolvedValue(ok([{ status: 'APPROVED' }, { status: 'SUBMITTED' }]));
    const response = await askWavi('Check my timesheet status', employee);
    expect(response.text).toContain('**2** timesheets');
    expect(response.text).toContain('**1** approved');
    expect(response.confirmed).toBe(true);
    expect(response.actions[0].path).toBe('/myhr/employee/timesheets');
  });

  it('does not advertise restricted or unimplemented routes to employees', () => {
    expect(permittedActions([
      { label: 'Employees', path: '/myhr/employee/employees' },
      { label: 'Unknown', path: '/myhr/employee/not-implemented' },
      { label: 'Profile', path: '/myhr/employee/profile' },
    ], 'EMPLOYEE')).toEqual([{ label: 'Profile', path: '/myhr/employee/profile' }]);
  });
});
