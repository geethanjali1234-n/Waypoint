import { Component } from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';

// Step 9.3 T4: chatbot responses are untrusted. Mocked API only; nothing reaches a backend or model.
vi.mock('../../api/myHrChatApi', () => ({ sendMyHrChatMessage: vi.fn() }));
vi.mock('react-router-dom', async () => ({ ...(await vi.importActual('react-router-dom')), useNavigate: () => vi.fn() }));

class CrashBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { crashed: false };
  }

  static getDerivedStateFromError() {
    return { crashed: true };
  }

  render() {
    return this.state.crashed ? <p>CHAT CRASHED</p> : this.props.children;
  }
}

const MALFORMED_NOTICE = "The assistant's response couldn't be displayed. Please try again.";

const renderChat = () => render(<CrashBoundary><MemoryRouter><WaypointChatbot /></MemoryRouter></CrashBoundary>);

// Selectors follow the Wavy chat window's accessible names (dialog "Wavy, Waypoint Assistant Chat").
const INPUT_PLACEHOLDER = /Ask Wavy about your workday/i;

const send = (question) => {
  fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: question } });
  fireEvent.click(screen.getByRole('button', { name: /Send message/i }));
};

const openChat = () => {
  renderChat();
  fireEvent.click(screen.getByRole('button', { name: /Open Wavy, Waypoint Assistant/i }));
  return screen.getByRole('dialog', { name: 'Wavy, Waypoint Assistant Chat' });
};

describe('WaypointChatbot treats responses as untrusted text', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'test-session');
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'user-1', organizationId: 'org-1', role: 'EMPLOYEE' }));
    sendMyHrChatMessage.mockReset();
    delete window.__wpXss;
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('myHrAuthenticatedUser');
    console.error.mockRestore();
    delete window.__wpXss;
  });

  it('renders HTML and script-like answers and citation fields as inert text', async () => {
    sendMyHrChatMessage.mockResolvedValue({
      message: '<img src=x onerror="window.__wpXss=1"><script>window.__wpXss=2</script> **<b>bold</b>** done [1]',
      knowledgeStatus: 'GROUNDED',
      citations: [{
        sourceNumber: 1,
        title: '<img src=x onerror="window.__wpXss=3">',
        documentName: '<script>window.__wpXss=4</script>',
        hrOnly: false,
      }],
    });
    const chat = openChat();

    send('What is the leave policy?');

    await waitFor(() => expect(chat).toHaveTextContent('done [1]'));
    expect(chat).toHaveTextContent('<img src=x onerror="window.__wpXss=1"><script>window.__wpXss=2</script>');
    expect(chat.querySelectorAll('img[src="x"], script, b')).toHaveLength(0);
    const bold = within(chat).getByText('<b>bold</b>');
    expect(bold.tagName).toBe('STRONG');
    const sources = within(chat).getByLabelText('Sources');
    expect(sources).toHaveTextContent('<img src=x onerror="window.__wpXss=3">');
    expect(sources).toHaveTextContent('<script>window.__wpXss=4</script>');
    expect(sources.querySelectorAll('img, script')).toHaveLength(0);
    expect(window.__wpXss).toBeUndefined();
  });

  it('shows only well-formed citations', async () => {
    sendMyHrChatMessage.mockResolvedValue({
      message: 'Answer with sources [4].',
      knowledgeStatus: 'GROUNDED',
      citations: [
        { sourceNumber: '1', title: 'String number' },
        { sourceNumber: 2, title: { html: '<b>object title</b>' } },
        { sourceNumber: 3.5, title: 'Fractional number' },
        null,
        'plain string citation',
        { title: 'No number at all' },
        { sourceNumber: 4, title: 'Valid source', documentName: 'valid.pdf', hrOnly: false },
      ],
    });
    const chat = openChat();

    send('What applies?');

    await waitFor(() => expect(chat).toHaveTextContent('Answer with sources [4].'));
    const sources = within(chat).getByLabelText('Sources');
    expect(within(sources).getAllByRole('listitem')).toHaveLength(1);
    expect(sources).toHaveTextContent('Valid source');
    for (const rejected of ['String number', 'object title', 'Fractional number', 'plain string citation', 'No number at all']) {
      expect(sources).not.toHaveTextContent(rejected);
    }
  });

  it('caps the number of displayed citations', async () => {
    sendMyHrChatMessage.mockResolvedValue({
      message: 'Many sources.',
      knowledgeStatus: 'GROUNDED',
      citations: Array.from({ length: 25 }, (_, index) => ({ sourceNumber: index + 1, title: `Source ${index + 1}` })),
    });
    const chat = openChat();

    send('List everything');

    await waitFor(() => expect(chat).toHaveTextContent('Many sources.'));
    expect(within(within(chat).getByLabelText('Sources')).getAllByRole('listitem')).toHaveLength(10);
  });

  // G4b (fixed): a malformed reply, which the current backend cannot produce, is shown as a fixed
  // notice instead of crashing the chat, and the conversation carries on normally afterwards.
  it('G4b regression: a non-string message is shown as a safe notice and the chat stays usable', async () => {
    sendMyHrChatMessage
      .mockResolvedValueOnce({ message: 12345, knowledgeStatus: null, citations: [] })
      .mockResolvedValueOnce({ message: 'Valid **answer** after the notice.', knowledgeStatus: null, citations: [] });
    const chat = openChat();

    send('hello there');

    await waitFor(() => expect(chat).toHaveTextContent(MALFORMED_NOTICE));
    expect(chat).not.toHaveTextContent('12345');
    expect(screen.queryByText('CHAT CRASHED')).not.toBeInTheDocument();

    send('second question');

    await waitFor(() => expect(chat).toHaveTextContent('Valid answer after the notice.'));
    expect(within(chat).getByText('answer').tagName).toBe('STRONG');
    expect(screen.queryByText('CHAT CRASHED')).not.toBeInTheDocument();
  });

  it.each([
    ['an object', { html: '<img src=x onerror="window.__wpXss=5">', text: 'object text' }],
    ['an array', ['first part', '<script>window.__wpXss=6</script>']],
    ['a boolean', true],
    ['a nested reply', { message: 'nested text' }],
  ])('G4b regression: a message that is %s never crashes or renders, and its citations are not shown', async (_, message) => {
    sendMyHrChatMessage.mockResolvedValue({
      message,
      knowledgeStatus: 'GROUNDED',
      citations: [{ sourceNumber: 1, title: 'Attached source', documentName: 'attached.pdf', hrOnly: true }],
    });
    const chat = openChat();

    send('What applies?');

    await waitFor(() => expect(chat).toHaveTextContent(MALFORMED_NOTICE));
    expect(screen.queryByText('CHAT CRASHED')).not.toBeInTheDocument();
    for (const leaked of ['object text', 'first part', 'nested text', 'true', 'Attached source']) {
      expect(chat).not.toHaveTextContent(leaked);
    }
    expect(within(chat).queryByLabelText('Sources')).not.toBeInTheDocument();
    // Wavi renders its own mascot image beside replies; nothing from the reply may become an element.
    expect(chat.querySelectorAll('img[src="x"], img[onerror], script')).toHaveLength(0);
    expect(window.__wpXss).toBeUndefined();
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toBeEnabled();
  });

  it.each([
    ['a number', 42],
    ['an array', [{ message: 'array item' }]],
    ['a boolean', false],
  ])('G4b regression: a whole reply that is %s is shown as the safe notice', async (_, reply) => {
    sendMyHrChatMessage.mockResolvedValue(reply);
    const chat = openChat();

    send('hello there');

    await waitFor(() => expect(chat).toHaveTextContent(MALFORMED_NOTICE));
    expect(chat).not.toHaveTextContent('array item');
    expect(screen.queryByText('CHAT CRASHED')).not.toBeInTheDocument();
  });

  it.each([null, undefined])('G4b regression: a %s message keeps the existing empty-response notice', async (message) => {
    sendMyHrChatMessage.mockResolvedValue({ message, knowledgeStatus: null, citations: [{ sourceNumber: 1, title: 'Unused' }] });
    const chat = openChat();

    send('hello there');

    await waitFor(() => expect(chat).toHaveTextContent('The assistant returned an empty response. Please try again.'));
    expect(within(chat).queryByLabelText('Sources')).not.toBeInTheDocument();
    expect(screen.queryByText('CHAT CRASHED')).not.toBeInTheDocument();
  });

  it('G4b regression: malformed citation fields are dropped instead of crashing the sources list', async () => {
    sendMyHrChatMessage.mockResolvedValue({
      message: 'Grounded answer [1] [2].',
      knowledgeStatus: { status: 'GROUNDED' },
      citations: [
        { sourceNumber: 1, title: 'Leave Policy', documentName: { html: '<b>object name</b>' }, hrOnly: 0 },
        { sourceNumber: 2, title: 'Recruitment Policy', documentName: 'recruitment.pdf', hrOnly: 'yes' },
      ],
    });
    const chat = openChat();

    send('What applies?');

    await waitFor(() => expect(chat).toHaveTextContent('Grounded answer [1] [2].'));
    const items = within(within(chat).getByLabelText('Sources')).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Leave Policy');
    expect(items[0]).not.toHaveTextContent('object name');
    expect(items[0]).not.toHaveTextContent('HR only');
    expect(items[1]).toHaveTextContent('recruitment.pdf');
    expect(items[1]).toHaveTextContent('HR only');
    expect(screen.queryByText('CHAT CRASHED')).not.toBeInTheDocument();
  });

  it('G4b regression: unstamped questions and local error notices are excluded from later history', async () => {
    sendMyHrChatMessage
      .mockResolvedValueOnce({ message: { unexpected: true }, knowledgeStatus: null, citations: [] })
      .mockResolvedValueOnce({ message: 'Next answer.', knowledgeStatus: null, citations: [] });
    const chat = openChat();

    send('first question');
    await waitFor(() => expect(chat).toHaveTextContent(MALFORMED_NOTICE));
    send('second question');
    await waitFor(() => expect(chat).toHaveTextContent('Next answer.'));

    const [, history] = sendMyHrChatMessage.mock.calls[1];
    expect(history).toEqual([]);
  });
});
