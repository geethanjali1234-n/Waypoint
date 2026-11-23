import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from '../../api/apiClient';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';

vi.mock('../../api/apiClient');

describe('sendMyHrChatMessage history provenance', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('posts the history it is given, stamps included, and returns the new stamps', async () => {
    apiClient.post.mockResolvedValue({ data: { data: {
      message: 'Hi', historyProvenance: { user: 'v1.P.u', assistant: 'v1.P.a' },
    } } });
    const history = [{ role: 'user', content: 'hello', provenance: 'v1.P.x' }];

    const reply = await sendMyHrChatMessage('thanks', history);

    expect(apiClient.post).toHaveBeenCalledWith('/myhr/chat', { message: 'thanks', history }, { timeout: 100000 });
    expect(reply.historyProvenance).toEqual({ user: 'v1.P.u', assistant: 'v1.P.a' });
  });

  it.each([
    [undefined],
    [null],
    ['v1.P.u'],
    [{ user: 'v1.P.u' }],
    [{ user: 'v1.P.u', assistant: 42 }],
    [{ user: '', assistant: 'v1.P.a' }],
    [{ user: 'x'.repeat(201), assistant: 'v1.P.a' }],
  ])('ignores missing or malformed stamps (%j)', async (historyProvenance) => {
    apiClient.post.mockResolvedValue({ data: { data: { message: 'Hi', historyProvenance } } });

    const reply = await sendMyHrChatMessage('hello');

    expect(reply.historyProvenance).toBeNull();
    expect(reply.message).toBe('Hi');
  });

  it('returns the server page keys, null when absent, keeping only strings', async () => {
    apiClient.post.mockResolvedValueOnce({ data: { data: { message: 'Hi', actions: ['TIMESHEETS', 7, null] } } });
    expect((await sendMyHrChatMessage('open timesheets')).actions).toEqual(['TIMESHEETS']);
    apiClient.post.mockResolvedValueOnce({ data: { data: { message: 'Hi', actions: [] } } });
    expect((await sendMyHrChatMessage('open audit log')).actions).toEqual([]);
    apiClient.post.mockResolvedValueOnce({ data: { data: { message: 'Hi' } } });
    expect((await sendMyHrChatMessage('hello')).actions).toBeNull();
  });

  it('does not display an error envelope as a chatbot answer', async () => {
    apiClient.post.mockResolvedValue({ data: { success: false, message: 'Permission denied', data: null } });
    await expect(sendMyHrChatMessage('hello')).rejects.toThrow('Chat request was not successful');
  });
});
