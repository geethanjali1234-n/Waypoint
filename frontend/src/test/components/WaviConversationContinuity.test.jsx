import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { WaviConversationProvider } from '../../components/chatbot/WaviConversationContext';
import { clearMyHrAuthentication, expireMyHrAuthentication, saveMyHrAuthentication } from '../../auth/myHrSession';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';

vi.mock('../../api/myHrChatApi', () => ({ sendMyHrChatMessage: vi.fn() }));

const EMPLOYEE = { userId: 'employee-1', organizationId: 'org-a', loginId: 'EMP001', role: 'EMPLOYEE' };
const OTHER_ORG_EMPLOYEE = { userId: 'employee-2', organizationId: 'org-b', loginId: 'EMP002', role: 'EMPLOYEE' };

function Page({ title, to, label }) {
  const navigate = useNavigate();
  return <><h1>{title}</h1><button type="button" onClick={() => navigate(to)}>{label}</button><WaypointChatbot /></>;
}

function renderMyHrRoutes(path = '/myhr/employee/dashboard') {
  return render(<WaviConversationProvider><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/myhr/employee/dashboard" element={<Page title="Dashboard" to="/myhr/employee/timesheets" label="Timesheets" />} />
    <Route path="/myhr/employee/timesheets" element={<Page title="Timesheets" to="/myhr/employee/profile-change-requests" label="Profile Change Requests" />} />
    <Route path="/myhr/employee/profile-change-requests" element={<Page title="Profile Change Requests" to="/myhr/employee/document-requests" label="Document Requests" />} />
    <Route path="/myhr/employee/document-requests" element={<Page title="Document Requests" to="/myhr/employee/timesheets" label="Timesheets" />} />
  </Routes></MemoryRouter></WaviConversationProvider>);
}

function openChat() {
  if (!screen.queryByRole('dialog', { name: 'Wavy, Waypoint Assistant Chat' })) {
    fireEvent.click(screen.getByRole('button', { name: /Open Wavy, Waypoint Assistant/i }));
  }
}

function send(question) {
  fireEvent.change(screen.getByRole('textbox', { name: 'Ask assistant a question' }), { target: { value: question } });
  fireEvent.click(screen.getByRole('button', { name: 'Send message' }));
}

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify(EMPLOYEE));
  localStorage.setItem('accessToken', 'test-token');
});

afterEach(() => localStorage.clear());

it('keeps one authenticated conversation through Timesheets, Profile Change Requests, and Document Requests', async () => {
  sendMyHrChatMessage.mockResolvedValue({ message: 'Your most recent timesheet was approved.' });
  renderMyHrRoutes();
  openChat();
  send('What is my timesheet status?');
  expect(await screen.findByText('Your most recent timesheet was approved.')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Timesheets' }));
  expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
  openChat();
  expect(screen.getByText('Your most recent timesheet was approved.')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Close assistant' }));

  fireEvent.click(screen.getByRole('button', { name: 'Profile Change Requests' }));
  expect(await screen.findByRole('heading', { name: 'Profile Change Requests' })).toBeInTheDocument();
  openChat();
  expect(screen.getByRole('button', { name: /View Timesheets/i })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: /View Timesheets/i }));
  expect(await screen.findByRole('heading', { name: 'Timesheets' })).toBeInTheDocument();
  openChat();
  expect(screen.getByText('Your most recent timesheet was approved.')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Close assistant' }));

  fireEvent.click(screen.getByRole('button', { name: 'Profile Change Requests' }));
  expect(await screen.findByRole('heading', { name: 'Profile Change Requests' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Document Requests' }));
  expect(await screen.findByRole('heading', { name: 'Document Requests' })).toBeInTheDocument();
  openChat();
  expect(screen.getByText('Your most recent timesheet was approved.')).toBeInTheDocument();
});

it('clears shared conversation and drops a late answer after account switching or expiry', async () => {
  let resolveReply;
  sendMyHrChatMessage.mockReturnValue(new Promise((resolve) => { resolveReply = resolve; }));
  renderMyHrRoutes();
  openChat();
  send('What is my timesheet status?');

  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify(OTHER_ORG_EMPLOYEE));
  act(() => window.dispatchEvent(new StorageEvent('storage', { key: 'myHrAuthenticatedUser' })));
  await act(async () => resolveReply({ message: 'Old organization answer.' }));
  expect(screen.queryByText('Old organization answer.')).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: /Hey! I'm Wavy/i })).toBeInTheDocument();

  expireMyHrAuthentication();
  expect(screen.getByRole('heading', { name: /Hey! I'm Wavy/i })).toBeInTheDocument();
});

it('clears the conversation on same-tab account switches and logout', async () => {
  sendMyHrChatMessage.mockResolvedValue({ message: 'Private answer.' });
  renderMyHrRoutes();
  openChat();
  send('What is my timesheet status?');
  expect(await screen.findByText('Private answer.')).toBeInTheDocument();

  act(() => saveMyHrAuthentication('other-token', OTHER_ORG_EMPLOYEE));
  expect(screen.queryByText('Private answer.')).not.toBeInTheDocument();

  send('What is my timesheet status?');
  expect(await screen.findByText('Private answer.')).toBeInTheDocument();
  act(() => clearMyHrAuthentication());
  expect(screen.queryByText('Private answer.')).not.toBeInTheDocument();
});

it('sends a question once when it is submitted twice before the first reply renders, then accepts the next one', async () => {
  let resolveReply;
  sendMyHrChatMessage.mockReturnValueOnce(new Promise((resolve) => { resolveReply = resolve; }));
  renderMyHrRoutes();
  openChat();
  const box = screen.getByRole('textbox', { name: 'Ask assistant a question' });
  fireEvent.change(box, { target: { value: 'What is my timesheet status?' } });
  // Both key presses land before React re-renders with isTyping.
  act(() => {
    fireEvent.keyDown(box, { key: 'Enter' });
    fireEvent.keyDown(box, { key: 'Enter' });
  });
  expect(sendMyHrChatMessage).toHaveBeenCalledTimes(1);
  await act(async () => resolveReply({ message: 'Your most recent timesheet was approved.' }));
  expect(await screen.findByText('Your most recent timesheet was approved.')).toBeInTheDocument();
  expect(screen.getAllByText('What is my timesheet status?')).toHaveLength(1);

  sendMyHrChatMessage.mockResolvedValueOnce({ message: 'You have no pending profile change requests.' });
  send('Any pending profile changes?');
  expect(await screen.findByText('You have no pending profile change requests.')).toBeInTheDocument();
  expect(sendMyHrChatMessage).toHaveBeenCalledTimes(2);
});
