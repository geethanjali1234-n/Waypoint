import { describe, expect, it } from 'vitest';
import { conversationalReply, workplaceQuery } from '../../components/chatbot/conversationIntents';
import { askWavi } from '../../components/chatbot/waviAssistant';

const employee = { role: 'EMPLOYEE', route: '/myhr/employee/profile-change-requests' };

describe('Wavy conversational continuity', () => {
  it.each(['how are you?', 'I had a long day', 'can we just chat', 'tell me a joke', "I'm stuck", "you're awesome", 'thanks for your help'])('handles %s without FAQ fallback cards', (query) => {
    const reply = conversationalReply(query);
    expect(reply.social).toBe(true);
    expect(reply.actions).toEqual([]);
  });

  it('varies greetings using recent conversation instead of repeating itself', () => {
    const first = conversationalReply('Hi');
    const second = conversationalReply('Hi', { history: [{ sender: 'assistant', text: first.text }] });
    expect(second.text).not.toBe(first.text);
  });

  it('keeps mixed greetings attached to the actual workplace request', () => {
    expect(conversationalReply('Hi Wavy, how do I update my address?')).toBeNull();
    expect(workplaceQuery('Hi Wavy, how do I update my address?')).toBe('how do I update my address?');
  });

  it('clarifies failures in relation to the task instead of pretending to fix them', () => {
    const reply = conversationalReply("that didn't work", { previousQuery: 'update my address' });
    expect(reply.text).toContain('profile request');
    expect(reply.text).toContain('What happened');
    expect(reply.preserveTask).toBe(true);
    expect(reply.confirmed).toBeUndefined();
  });

  it('retains employee-search clarification through acknowledgments', () => {
    const reply = conversationalReply('okay', { context: { intent: 'employee-search', field: 'employeeId' } });
    expect(reply.text).toContain('employee ID');
    expect(reply.context).toBeUndefined();
  });

  it('walks through existing workflow steps after casual conversation', async () => {
    const initial = await askWavi('How do I update my address?', employee);
    expect(initial.context.intent).toBe('workflow-guide');
    const thanks = await askWavi('thanks', { ...employee, context: initial.context });
    expect(thanks.social).toBe(true);
    expect(thanks.context).toBeUndefined();
    const first = await askWavi('walk me through it', { ...employee, previousQuery: 'How do I update my address?', context: initial.context });
    expect(first.text).toContain('1. Navigate');
    const next = await askWavi('next', { ...employee, context: first.context });
    expect(next.text).toContain('2. Tap');
    expect(next.confirmed).toBeUndefined();
  });

  it('can change topics without retaining an old search', () => {
    const reply = conversationalReply('never mind', { context: { intent: 'employee-search' } });
    expect(reply.context).toBeNull();
    expect(reply.clearTask).toBe(true);
  });

  it('only remembers names actually supplied in the recent conversation', () => {
    const reply = conversationalReply('what is my name?', { history: [{ sender: 'user', text: 'Call me Jamie' }] });
    expect(reply.text).toContain('Jamie');
    expect(conversationalReply('what is my name?').text).toContain("haven't told me");
  });
});
