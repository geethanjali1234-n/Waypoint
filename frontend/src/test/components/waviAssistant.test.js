import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import payrollReadinessApi from '../../api/payrollReadinessApi';
import timesheetApi from '../../api/timesheetApi';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';
import myHrProfileChangeApi from '../../api/myHrProfileChangeApi';
import { askWavi, permittedActions } from '../../components/chatbot/waviAssistant';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';
import { serverHistory } from '../../components/chatbot/waviAssistant';

vi.mock('../../api/payrollReadinessApi', () => ({ default: { getReadiness: vi.fn() } }));
vi.mock('../../api/timesheetApi', () => ({ default: { list: vi.fn() } }));
vi.mock('../../api/myHrEmployeeApi', () => ({ default: { list: vi.fn(), getMine: vi.fn() } }));
vi.mock('../../api/myHrProfileChangeApi', () => ({ default: { listForAdmin: vi.fn() } }));
vi.mock('../../api/myHrChatApi', () => ({ sendMyHrChatMessage: vi.fn() }));

// By default this deployment has no chat backend (HTTP 404): Wavy says so and reads nothing else.
beforeEach(() => { vi.resetAllMocks(); sendMyHrChatMessage.mockRejectedValue({ response: { status: 404 } }); localStorage.setItem('accessToken', 'test-token'); });
afterEach(() => localStorage.removeItem('accessToken'));
const admin = { role: 'COMPANY_ADMIN', route: '/myhr/admin/payroll-readiness' };
const employee = { role: 'EMPLOYEE', route: '/myhr/employee/timesheets' };
const ok = (data) => ({ data: { success: true, data } });

describe('Wavy trusted application data', () => {


  it('uses the Claude-first chat answer, its verified citations and its stamps instead of the canned conversation', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'A live conversational answer.', knowledgeStatus: 'GROUNDED',
      citations: [{ sourceNumber: 1, title: 'Leave Policy', documentName: 'leave.pdf', hrOnly: false }],
      historyProvenance: { user: 'stamp-u2', assistant: 'stamp-a2' }, actions: ['TIMESHEETS', 'UNKNOWN_PAGE', 'EMPLOYEES'] });
    const reply = await askWavi('Hello, can you help me?', { ...employee, sessionKey: 's1', history: [
      { sender: 'user', text: 'My original task', provenance: 'stamp-u1', sessionKey: 's1' },
      { sender: 'assistant', text: 'Server answer', provenance: 'stamp-a1', sessionKey: 's1' },
      { sender: 'assistant', text: 'Local guidance', sessionKey: 's1' },
      { sender: 'user', text: 'Another account', provenance: 'stamp-x', sessionKey: 'other' },
      { sender: 'assistant', text: 'Failed attempt', error: true, sessionKey: 's1' },
    ] });
    expect(reply.text).toBe('A live conversational answer.');
    expect(reply.server).toBe(true);
    expect(reply.sources).toEqual([{ sourceNumber: 1, title: 'Leave Policy', documentName: 'leave.pdf', hrOnly: false }]);
    expect(reply.source).toBe('Approved company policies');
    expect(reply.knowledgeStatus).toBe('GROUNDED');
    expect(reply.provenance).toEqual({ user: 'stamp-u2', assistant: 'stamp-a2' });
    // the server's page keys become this role's own, permitted routes; unknown and unavailable ones give no button
    expect(reply.actions).toEqual([{ label: 'Timesheets', path: '/myhr/employee/timesheets' }]);
    // only this session's server-stamped turns go back to the server; an unstamped reply is not sent
    expect(sendMyHrChatMessage).toHaveBeenCalledWith('Hello, can you help me?', [
      { role: 'user', content: 'My original task', provenance: 'stamp-u1' },
      { role: 'assistant', content: 'Server answer', provenance: 'stamp-a1' },
    ]);
  });

  it('sends at most the last ten stamped turns and never an over-long, blank or unstamped one', () => {
    const turns = Array.from({ length: 14 }, (_, index) => ({ sender: index % 2 ? 'assistant' : 'user', text: `turn ${index}`, provenance: `s${index}` }));
    const sent = serverHistory([...turns, { sender: 'user', text: '   ', provenance: 'b' }, { sender: 'system', text: 'x', provenance: 'y' },
      { sender: 'user', text: 'unstamped' }], undefined);
    expect(sent).toHaveLength(10);
    expect(sent[0]).toEqual({ role: 'user', content: 'turn 4', provenance: 's4' });
    expect(sent.some((turn) => turn.content === 'unstamped')).toBe(false);
    expect(serverHistory([{ sender: 'user', text: 'x'.repeat(5000), provenance: 'p' }])[0].content).toHaveLength(4000);
  });

  it.each(['Hi', 'Alright', 'Thanks', 'Okay!', 'Thank you Wavy', 'Sounds good'])('sends %s to the single chat backend and shows no navigation cards it did not return', async (query) => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'Happy to help.', actions: [] });
    const response = await askWavi(query, admin);
    expect(sendMyHrChatMessage).toHaveBeenCalledWith(query, []);
    expect(response.text).toBe('Happy to help.');
    expect(response.actions).toEqual([]);
    expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
  });

  // Formerly answered in the browser from separate record APIs; the browser no longer reads records to answer.
  it.each([
    ['Who is my HR?', employee], ['I need to find an employee', admin], ['find an employee named Alice', employee],
    ['Check pending approvals', { ...admin, route: '/myhr/admin/profile-change-requests' }],
    ['Check pending approvals', { ...admin, route: '/myhr/admin/timesheets' }],
    ['When is my timesheet deadline?', employee], ['Check payroll status', admin], ['Check my timesheet status', employee],
  ])('with no chat backend (HTTP 404) says so for %s and reads no records in the browser', async (query, options) => {
    const reply = await askWavi(query, options);
    expect(reply.error).toBe(true);
    expect(reply.text).toBe('Wavy chat is not enabled for this workspace.');
    expect(reply.actions).toEqual([]);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
    expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
    expect(myHrProfileChangeApi.listForAdmin).not.toHaveBeenCalled();
    expect(timesheetApi.list).not.toHaveBeenCalled();
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
  });

  it('does not bypass an authentication or authorization failure with local tools', async () => {
    sendMyHrChatMessage.mockRejectedValue({ response: { status: 403 } });
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.error).toBe(true);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });




  it('displays the authorized backend payroll summary without a separate frontend data read', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'Payroll readiness: 6 of 9 employees are ready for payroll; 2 are awaiting approval.', actions: ['PAYROLL_READINESS'] });
    const response = await askWavi('Check payroll status', admin);
    expect(response.text).toContain('6 of 9');
    expect(response.text).toContain('2 are awaiting approval');
    expect(response.actions).toEqual([{ label: 'Payroll Readiness', path: '/myhr/admin/payroll-readiness' }]);
    expect(response.confirmed).toBeUndefined();
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
    expect(sendMyHrChatMessage).toHaveBeenCalledWith('Check payroll status', []);
  });


  it('uses the cutoff returned by the backend instead of the knowledge-base date', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'The payroll cutoff for the current period is 2026-11-19.', actions: ['PAYROLL_READINESS'] });
    const response = await askWavi('When is payroll cutoff?', admin);
    expect(response.text).toContain('2026-11-19');
    expect(response.text).not.toContain('10th');
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
  });




  it('does not advertise restricted or unimplemented routes to employees', () => {
    expect(permittedActions([
      { label: 'Employees', path: '/myhr/employee/employees' },
      { label: 'Unknown', path: '/myhr/employee/not-implemented' },
      { label: 'Profile', path: '/myhr/employee/profile' },
    ], 'EMPLOYEE')).toEqual([{ label: 'Profile', path: '/myhr/employee/profile' }]);
  });
});

describe('Wavy refusal handling', () => {
  it.each([400, 401, 403, 429, 500, 502, 503, 504])('does not invoke another data path after HTTP %s', async (status) => {
    sendMyHrChatMessage.mockRejectedValue({ response: { status } });
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.error).toBe(true);
    // at most a retry of the same question; never a different route to the data
    expect(reply.actions.every((action) => action.query === 'Who is my HR?')).toBe(true);
    expect(reply.notice).toBeUndefined();
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
    expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
    expect(timesheetApi.list).not.toHaveBeenCalled();
    expect(payrollReadinessApi.getReadiness).not.toHaveBeenCalled();
  });

  it('does not invoke another data path when the failure reason is unknown', async () => {
    sendMyHrChatMessage.mockRejectedValue(new Error('network error'));
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.error).toBe(true);
    expect(reply.actions.every((action) => action.query === 'Who is my HR?')).toBe(true);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });

  it('says plainly that the AI usage limit was reached and offers no retry and no other path', async () => {
    sendMyHrChatMessage.mockRejectedValue({ response: { status: 503, data: { message: 'The AI assistant has reached its configured usage limit and cannot answer this request.' } } });
    const reply = await askWavi('Who is my HR?', employee);
    expect(reply.error).toBe(true);
    expect(reply.text).toMatch(/usage limit/i);
    expect(reply.actions).toEqual([]);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });


  it('never sends a message to the server without a signed-in workplace session', async () => {
    localStorage.removeItem('accessToken');
    await askWavi('How do I submit a timesheet?', employee);
    expect(sendMyHrChatMessage).not.toHaveBeenCalled();
  });

  it('treats an empty chat answer as a failure, not as an answer', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: '   ', knowledgeStatus: null, citations: [], historyProvenance: null, actions: null });
    const reply = await askWavi('Hello', employee);
    expect(reply.error).toBe(true);
    expect(reply.text).toBeTruthy();
  });
});
