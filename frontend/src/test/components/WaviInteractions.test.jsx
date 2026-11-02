import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { askWavi } from '../../components/chatbot/waviAssistant';

vi.mock('../../components/chatbot/waviAssistant', async () => ({
  ...(await vi.importActual('../../components/chatbot/waviAssistant')),
  askWavi: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ role: 'EMPLOYEE' }));
});
afterEach(() => localStorage.removeItem('myHrAuthenticatedUser'));

function openAndSend(text) {
  render(<MemoryRouter><WaypointChatbot /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: 'Open Wavy, Waypoint Assistant' }));
  const input = screen.getByRole('textbox');
  fireEvent.change(input, { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Send message' }));
}

it('ignores an in-flight API reply after restarting the conversation', async () => {
  let finish;
  askWavi.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  openAndSend('Check my timesheet status');
  await waitFor(() => expect(askWavi).toHaveBeenCalled());
  fireEvent.click(screen.getByRole('button', { name: 'Conversation options' }));
  fireEvent.click(screen.getByRole('button', { name: 'Restart conversation' }));
  await act(async () => finish({ text: 'Old sensitive response', confirmed: true }));
  expect(screen.queryByText('Old sensitive response')).not.toBeInTheDocument();
  expect(screen.queryByText(/All checked/i)).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: /Hey! I'm Wavy/i })).toBeInTheDocument();
});

it('celebrates a confirmed read and labels the trusted source', async () => {
  askWavi.mockResolvedValue({ text: 'Your latest status.', confirmed: true, source: 'Timesheets API' });
  openAndSend('Check my timesheet status');
  await waitFor(() => expect(screen.getByText(/All checked/i)).toBeInTheDocument());
  expect(screen.getByText(/Timesheets API/)).toBeInTheDocument();
});

it('keeps the task context through consecutive follow-up questions', async () => {
  askWavi.mockResolvedValue({ text: 'Here is the next step.' });
  openAndSend('How do I update my address?');
  await waitFor(() => expect(screen.queryByLabelText('Assistant is typing')).not.toBeInTheDocument());
  for (const query of ['what next', 'and then']) {
    fireEvent.change(screen.getByRole('textbox'), { target: { value: query } });
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }));
    await waitFor(() => expect(askWavi).toHaveBeenLastCalledWith(query, expect.objectContaining({ previousQuery: 'How do I update my address?' })));
    await waitFor(() => expect(screen.queryByLabelText('Assistant is typing')).not.toBeInTheDocument());
  }
});
