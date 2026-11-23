import { beforeEach, describe, expect, it, vi } from 'vitest';
import { askWavi } from '../../components/chatbot/waviAssistant';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';
import { getAccessToken } from '../../auth/myHrSession';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';

vi.mock('../../api/myHrChatApi', () => ({ sendMyHrChatMessage: vi.fn() }));
vi.mock('../../auth/myHrSession', () => ({ getAccessToken: vi.fn() }));
vi.mock('../../api/myHrEmployeeApi', () => ({ default: { getMine: vi.fn(), list: vi.fn() } }));

describe('single Wavy backend', () => {
  beforeEach(() => { vi.clearAllMocks(); getAccessToken.mockReturnValue('test-token'); });

  it('uses the Claude-first chat API and displays its response', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'Your verified reply.', citations: [], actions: [] });
    const result = await askWavi('Who is my manager?', { role: 'EMPLOYEE', history: [] });
    expect(result.text).toBe('Your verified reply.');
    expect(sendMyHrChatMessage).toHaveBeenCalledOnce();
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });

  it('restores the letter request action only when the server authorizes document navigation', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'Open your document requests.', actions: ['DOCUMENT_REQUESTS'] });
    const result = await askWavi('How do I request a letter?', { role: 'EMPLOYEE', history: [] });
    expect(result.actions).toEqual([{ label: 'Request Letter', path: '/myhr/employee/document-requests' }]);
    sendMyHrChatMessage.mockResolvedValue({ message: 'I cannot open that.', actions: [] });
    expect((await askWavi('How do I request a letter?', { role: 'EMPLOYEE', history: [] })).actions).toEqual([]);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });

  it('displays the backend payroll summary with an authorized navigation action', async () => {
    sendMyHrChatMessage.mockResolvedValue({ message: 'Payroll readiness: 6 of 9 employees are ready for payroll; 2 are awaiting approval.',
      actions: ['PAYROLL_READINESS'] });
    const result = await askWavi('Check payroll status', { role: 'HR', history: [] });
    expect(result.text).toContain('6 of 9');
    expect(result.actions).toEqual([{ label: 'Payroll Readiness', path: '/myhr/hr/payroll-readiness' }]);
    expect(sendMyHrChatMessage).toHaveBeenCalledWith('Check payroll status', []);
    expect(myHrEmployeeApi.list).not.toHaveBeenCalled();
  });

  it('does not replace a disabled backend with local data lookups', async () => {
    sendMyHrChatMessage.mockRejectedValue({ response: { status: 404 } });
    const result = await askWavi('Who is my manager?', { role: 'EMPLOYEE', history: [] });
    expect(result.error).toBe(true);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });

  it('offers a retry when the chat request times out without reading records elsewhere', async () => {
    sendMyHrChatMessage.mockRejectedValue({ code: 'ECONNABORTED' });
    const result = await askWavi('Who is my manager?', { role: 'EMPLOYEE', history: [] });
    expect(result.error).toBe(true);
    expect(result.actions).toEqual([{ label: 'Try again', query: 'Who is my manager?' }]);
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });

  it('does not use a local chatbot when signed out', async () => {
    getAccessToken.mockReturnValue(null);
    const result = await askWavi('Who is my manager?', { role: 'EMPLOYEE', history: [] });
    expect(result.error).toBe(true);
    expect(sendMyHrChatMessage).not.toHaveBeenCalled();
    expect(myHrEmployeeApi.getMine).not.toHaveBeenCalled();
  });
});
