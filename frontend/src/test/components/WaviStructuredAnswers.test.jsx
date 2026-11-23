import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';
import { structureAnswer, emphasisParts, chatErrorMessage } from '../../components/chatbot/waviAnswerFormat';

vi.mock('../../api/myHrChatApi', () => ({ sendMyHrChatMessage: vi.fn() }));

const mockedNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockedNavigate };
});

const USERS = {
  EMPLOYEE: { userId: 'emp-1', organizationId: 'org-1', role: 'EMPLOYEE', loginId: 'E001' },
  HR: { userId: 'hr-1', organizationId: 'org-1', role: 'HR', loginId: 'H001' },
  COMPANY_ADMIN: { userId: 'admin-1', organizationId: 'org-1', role: 'COMPANY_ADMIN', loginId: 'A001' },
};

// Replies exactly as the backend composes them (MyHrChatService), including the two shapes from the real app.
const ONE_DAY = '8 hours recorded on Tue Nov 10, 2026.\n'
  + 'By submission:\n'
  + '- Timesheet Mon Nov 9, 2026 to Sun Nov 15, 2026 (approved): 8 hours on 1 day';
const ONE_DAY_TWO_SUBMISSIONS = '8 hours recorded on Mon Oct 12, 2026. This date is on 2 separate submissions; '
  + 'Hours Overview counts it once, from the latest submission.\n'
  + 'By submission:\n'
  + '- Timesheet Mon Oct 12, 2026 to Sun Oct 18, 2026 (rejected): 8 hours on 1 day\n'
  + '- Timesheet Mon Oct 12, 2026 to Wed Oct 14, 2026 (submitted): 8 hours on 1 day\n'
  + "1 day appears on more than one of your timesheets, so I haven't added them together.";
const OCTOBER_LIST = 'Your timesheets covering October 2026 (7):\n'
  + '- Wed Oct 28, 2026 to Fri Oct 30, 2026: is saved as a draft and has not been submitted yet\n'
  + '- Mon Oct 26, 2026 to Sun Nov 1, 2026: is approved (submitted Mon Nov 2, 2026 at 09:00)\n'
  + '- Mon Oct 19, 2026 to Sun Oct 25, 2026: is submitted and waiting for approval (submitted Mon Oct 26, 2026 at 09:00)\n'
  + '- Mon Oct 12, 2026 to Sun Oct 18, 2026: is approved (submitted Mon Oct 19, 2026 at 09:00)\n'
  + '- Mon Oct 5, 2026 to Sun Oct 11, 2026: is approved (submitted Tue Oct 13, 2026 at 11:00) [submission 1 of 2 for this period, most recent]\n'
  + '- Mon Oct 5, 2026 to Sun Oct 11, 2026: was rejected and needs to be corrected and resubmitted (submitted Mon Oct 12, 2026 at 09:00); no reviewer comment was recorded [submission 2 of 2 for this period]\n'
  + '- Mon Sep 28, 2026 to Sun Oct 4, 2026: is approved (submitted Mon Oct 5, 2026 at 09:00)\n'
  + 'A timesheet whose work period only partly falls in October 2026 is included.';

const signIn = (role) => {
  localStorage.setItem('accessToken', 'test-session');
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify(USERS[role]));
};
const openChat = () => {
  render(<MemoryRouter><WaypointChatbot /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: /Open Wavy, Waypoint Assistant/i }));
};
const send = (question) => {
  fireEvent.change(screen.getByPlaceholderText(/Ask Wavy about your workday/i), { target: { value: question } });
  fireEvent.click(screen.getByRole('button', { name: /Send message/i }));
};
/** The assistant message whose text starts with the reply's first line. */
const reply = async (text) => {
  const firstLine = text.split('\n')[0];
  const lead = await screen.findByText((_, element) => element?.classList?.contains('wp-wavi-answer__lead')
    && element.textContent === firstLine);
  return lead.closest('.wp-chatbot-message');
};
/** Everything the bubble shows, as the user reads it. */
const bubbleText = (message) => message.querySelector('.wp-chatbot-bubble').textContent;

describe('waviAnswerFormat: layout only, never new words', () => {
  it('keeps every character of the reply and adds nothing', () => {
    for (const text of [ONE_DAY, ONE_DAY_TWO_SUBMISSIONS, OCTOBER_LIST]) {
      const { lead, groups, notes } = structureAnswer(text);
      const rebuilt = [...lead, ...groups.flatMap((group) => [group.title ? `${group.title}:` : null, ...group.rows.map((row) => `- ${row}`)]),
        ...notes].filter(Boolean);
      const original = text.split('\n').filter((line) => line.trim());
      // Same lines, in the same order; a heading only loses its trailing colon.
      expect(rebuilt).toEqual(original);
    }
  });

  it('treats a plain sentence as plain text', () => {
    expect(structureAnswer('Your employee ID is WPT-1001.').groups).toEqual([]);
  });

  it('renders paired ** as bold and drops a stray marker instead of showing it', () => {
    expect(emphasisParts('Status: **Approved** today')).toEqual([
      { text: 'Status: ', bold: false }, { text: 'Approved', bold: true }, { text: ' today', bold: false }]);
    expect(emphasisParts('[submission 1 of 2]**')).toEqual([{ text: '[submission 1 of 2]', bold: false }]);
  });

  it('gives error messages from the HTTP status, with retry only where it can help', () => {
    expect(chatErrorMessage({ response: { status: 401 } }).retry).toBe(false);
    expect(chatErrorMessage({ response: { status: 503 } }).text).toMatch(/temporarily unavailable/);
    expect(chatErrorMessage(new Error('offline')).retry).toBe(true);
  });
});

describe('Wavy panel rendering', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    signIn('EMPLOYEE');
  });

  it('puts the hours answer first and shows each submission on its own row, with no added labels', async () => {
    openChat();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: ONE_DAY, actions: ['TIMESHEETS'] });
    send('How many hours did I record on November 10, 2026?');
    const message = await reply(ONE_DAY);
    const lead = message.querySelector('.wp-wavi-answer__lead');
    expect(lead).toHaveTextContent('8 hours recorded on Tue Nov 10, 2026.');
    const rows = within(message).getAllByRole('listitem');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent(/^Timesheet Mon Nov 9, 2026 to Sun Nov 15, 2026 \(approved\): 8 hours on 1 day$/);
    // The real-app defect: a badge glued to the row ("8 hours on 1 dayApproved").
    expect(bubbleText(message)).not.toMatch(/dayApproved|day Approved|\*\*/);
    expect(message.querySelector('.wp-wavi-status')).toBeNull();
    // Short answers have no extra controls.
    expect(within(message).queryByRole('button', { name: /Show all|Details/ })).toBeNull();
  });

  it('explains two submissions for one date in a short note, never doubling a status', async () => {
    openChat();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: ONE_DAY_TWO_SUBMISSIONS });
    send('hours on oct 12');
    const message = await reply(ONE_DAY_TWO_SUBMISSIONS);
    const text = bubbleText(message);
    expect(text.match(/\(rejected\)/g)).toHaveLength(1);
    expect(text.match(/\(submitted\)/g)).toHaveLength(1);
    expect(text).not.toMatch(/Approved|Rejected|Awaiting review/); // no inferred badge words
    expect(within(message).getByText(/haven't added them together/)).toHaveClass('wp-wavi-note');
  });

  it('keeps a long timesheet list readable: separate rows, Show all / Show fewer, statuses only as the server wrote them', async () => {
    openChat();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: OCTOBER_LIST, actions: ['TIMESHEETS'] });
    send('Show my timesheets for October');
    const message = await reply(OCTOBER_LIST);
    expect(within(message).getAllByRole('listitem')).toHaveLength(4);
    const showAll = within(message).getByRole('button', { name: 'Show all 7 records' });
    expect(showAll).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(showAll);
    const rows = within(message).getAllByRole('listitem');
    expect(rows).toHaveLength(7);
    // The real-app defect: "...most recent]**Approved**". The row ends with the server's own bracket.
    expect(rows[4]).toHaveTextContent(/\[submission 1 of 2 for this period, most recent\]$/);
    expect(bubbleText(message)).not.toMatch(/\]\*\*|\*\*Approved|\]Approved/);
    // Each status phrase appears exactly as often as the server wrote it.
    expect(bubbleText(message).match(/is approved/g)).toHaveLength(OCTOBER_LIST.match(/is approved/g).length);
    fireEvent.click(within(message).getByRole('button', { name: 'Show fewer' }));
    expect(within(message).getAllByRole('listitem')).toHaveLength(4);
    // Closing note is shown directly, not behind a Details button.
    expect(within(message).getByText(/only partly falls in October 2026/)).toBeVisible();
    expect(within(message).queryByRole('button', { name: 'Details' })).toBeNull();
  });

  it('renders model-written emphasis as bold, not as asterisks', async () => {
    openChat();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: 'Your request is **pending** review.' });
    send('status of my request');
    const bold = await screen.findByText('pending');
    expect(bold.tagName).toBe('STRONG');
    expect(bold.closest('.wp-chatbot-bubble').textContent).toBe('WavyYour request is pending review.');
  });

  it('leaves a one-sentence answer unchanged', async () => {
    openChat();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: 'Your reporting manager is Priya Nair.' });
    send('who is my manager');
    const answer = await screen.findByText('Your reporting manager is Priya Nair.');
    expect(answer.closest('.wp-chatbot-message').querySelector('.wp-wavi-answer')).toBeNull();
  });

  it('retries a failed question without showing it twice', async () => {
    openChat();
    sendMyHrChatMessage.mockRejectedValueOnce({ response: { status: 503 } });
    send('how many hours did I work last week');
    expect(await screen.findByText(/temporarily unavailable/i)).toBeInTheDocument();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: 'Your reporting manager is Priya Nair.' });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Try again' })); });
    expect(await screen.findByText('Your reporting manager is Priya Nair.')).toBeInTheDocument();
    expect(screen.getAllByText('how many hours did I work last week')).toHaveLength(1);
    expect(screen.queryByText(/temporarily unavailable/i)).not.toBeInTheDocument();
  });

  it('explains an expired session without a retry button', async () => {
    openChat();
    sendMyHrChatMessage.mockRejectedValueOnce({ response: { status: 401 } });
    send('what is my timesheet status');
    expect(await screen.findByText(/session has expired/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('says when a reply is taking longer than usual', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      openChat();
      let release;
      sendMyHrChatMessage.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
      send('show my document requests');
      expect(screen.getByText('Checking your Waypoint records…')).toBeInTheDocument();
      await act(async () => { vi.advanceTimersByTime(6500); });
      expect(screen.getByText(/taking longer than usual/)).toBeInTheDocument();
      await act(async () => { release({ message: 'You have 1 open document request.' }); });
      await act(async () => { vi.advanceTimersByTime(300); });
      expect(screen.queryByText(/taking longer than usual/)).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it.each([
    ['EMPLOYEE', 'DOCUMENT_REQUESTS', 'Document Requests', '/myhr/employee/document-requests'],
    ['EMPLOYEE', 'TIMESHEETS', 'Timesheets', '/myhr/employee/timesheets'],
    ['HR', 'PROFILE_CHANGE_REQUESTS', 'Profile Change Requests', '/myhr/hr/profile-change-requests'],
    ['HR', 'TIMESHEETS', 'Timesheets', '/myhr/hr/timesheets'],
    ['COMPANY_ADMIN', 'TIMESHEETS', 'Timesheets', '/myhr/admin/timesheets'],
    ['COMPANY_ADMIN', 'DOCUMENT_REQUESTS', 'Document Requests', '/myhr/admin/document-requests'],
  ])('%s: the %s action opens %s', async (role, key, label, path) => {
    localStorage.clear();
    signIn(role);
    openChat();
    sendMyHrChatMessage.mockResolvedValueOnce({ message: OCTOBER_LIST, actions: [key] });
    send('show me');
    const message = await reply(OCTOBER_LIST);
    fireEvent.click(within(message).getByRole('button', { name: new RegExp(label) }));
    expect(mockedNavigate).toHaveBeenCalledWith(path);
  });
});

describe('mobile layout', () => {
  it('keeps the answer rows, toggle and retry styled for narrow screens without fixed widths', async () => {
    const { readFileSync } = await import('node:fs');
    const { resolve } = await import('node:path');
    const css = readFileSync(resolve(process.cwd(), 'src/components/chatbot/WaypointChatbot.css'), 'utf8');
    const block = css.slice(css.indexOf('/* --- Wavy answers'));
    expect(block).toMatch(/@media \(max-width: 640px\)/);
    // No fixed element width (the media query's own max-width is allowed).
    expect(block).not.toMatch(/(?<![-\w])width:\s*\d+px/);
    expect(block).not.toMatch(/wp-wavi-status|wp-wavi-figure/);
  });
});
