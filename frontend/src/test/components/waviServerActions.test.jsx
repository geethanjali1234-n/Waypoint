import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';
import { resolveWaviActions, waviPagePath, WAVI_PAGE_KEYS } from '../../components/chatbot/waviActions';

vi.mock('../../api/myHrChatApi', () => ({ sendMyHrChatMessage: vi.fn() }));

const mockedNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockedNavigate };
});

// Every route declared in AppRouter.jsx, read from the source so a renamed or removed page fails this test.
const ROUTER = readFileSync(resolve(process.cwd(), 'src/routes/AppRouter.jsx'), 'utf8');
const ROUTES = new Set([...ROUTER.matchAll(/path:\s*["'`]([^"'`]+)["'`]/g)].map((match) => match[1]));
const ROLES = ['EMPLOYEE', 'HR', 'COMPANY_ADMIN'];
const BASE = { EMPLOYEE: '/myhr/employee', HR: '/myhr/hr', COMPANY_ADMIN: '/myhr/admin' };

describe('Wavi server page keys', () => {
  it('map only to routes that exist, inside the role\'s own area', () => {
    expect(ROUTES.size).toBeGreaterThan(30);
    for (const key of WAVI_PAGE_KEYS) {
      for (const role of ROLES) {
        const path = waviPagePath(key, role);
        if (path === null) continue;
        expect(ROUTES.has(path), `${key} for ${role}: ${path}`).toBe(true);
        expect(path.startsWith(`${BASE[role]}/`), `${key} for ${role}`).toBe(true);
      }
    }
  });

  it('match the backend page catalogue, with role-restricted pages unavailable to other roles', () => {
    // MyHrConversationIntents.Page: keep in step.
    expect(WAVI_PAGE_KEYS).toEqual(['DOCUMENT_REQUESTS', 'TIMESHEETS', 'MY_PROFILE', 'PROFILE_CHANGE_REQUESTS',
      'DASHBOARD', 'EMPLOYEES', 'REPORTS', 'KNOWLEDGE_BASE', 'PAYROLL_READINESS', 'DOCUMENT_APPROVALS', 'AUDIT_LOG',
      'ORGANIZATION_SETTINGS']);
    for (const key of ['EMPLOYEES', 'REPORTS', 'KNOWLEDGE_BASE', 'PAYROLL_READINESS', 'DOCUMENT_APPROVALS', 'AUDIT_LOG']) {
      expect(waviPagePath(key, 'EMPLOYEE'), key).toBeNull();
    }
    for (const key of ['DOCUMENT_APPROVALS', 'AUDIT_LOG', 'ORGANIZATION_SETTINGS']) {
      expect(waviPagePath(key, 'HR'), key).toBeNull();
    }
  });

  it('drop unknown, unavailable, duplicate and malformed keys and keep at most three', () => {
    expect(resolveWaviActions(['AUDIT_LOG', 'NOT_A_PAGE', 42, 'TIMESHEETS', 'TIMESHEETS'], 'EMPLOYEE'))
      .toEqual([{ label: 'Timesheets', path: '/myhr/employee/timesheets' }]);
    expect(resolveWaviActions(['TIMESHEETS', 'MY_PROFILE', 'DASHBOARD', 'REPORTS'], 'HR')).toHaveLength(3);
    expect(resolveWaviActions(null, 'HR')).toEqual([]);
    expect(resolveWaviActions(['__proto__', 'constructor', 'toString'], 'HR')).toEqual([]);
  });

  it('preserves the review shortcut only when the server supplies the timesheets page', () => {
    const query = 'How does 1-tap express clearance work?';
    expect(resolveWaviActions(['TIMESHEETS'], 'HR', query)).toEqual([
      { label: 'Review & Clear Timesheets', path: '/myhr/hr/timesheets?view=team&subtab=actions' },
    ]);
    expect(resolveWaviActions(['TIMESHEETS'], 'EMPLOYEE', query)).toEqual([
      { label: 'Timesheets', path: '/myhr/employee/timesheets' },
    ]);
    expect(resolveWaviActions([], 'HR', query)).toEqual([]);
  });
});

describe('Wavi buttons follow the server answer', () => {
  const signIn = (user) => {
    localStorage.setItem('accessToken', 'test-token');
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify(user));
  };
  const openChat = () => {
    render(<MemoryRouter><WaypointChatbot /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: /Open Wavy, Waypoint Assistant/i }));
  };
  const ask = async (question, reply) => {
    sendMyHrChatMessage.mockResolvedValueOnce(reply);
    fireEvent.change(screen.getByPlaceholderText(/Ask Wavy about your workday/i), { target: { value: question } });
    fireEvent.click(screen.getByRole('button', { name: /Send message/i }));
    return (await screen.findByText(reply.message)).closest('.wp-chatbot-message');
  };
  const buttons = (message) => within(message).queryAllByRole('button').map((button) => button.textContent.trim());

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('shows the pages the server named, as the signed-in role\'s routes', async () => {
    signIn({ userId: 'admin-1', organizationId: 'org-1', role: 'COMPANY_ADMIN', loginId: 'ADMIN001' });
    openChat();
    const message = await ask('take me to the audit log', {
      message: 'Use the Audit Log button below to open the Audit Log page.', actions: ['AUDIT_LOG'],
    });
    expect(buttons(message)).toEqual(['Audit Log']);
    fireEvent.click(within(message).getByRole('button', { name: 'Audit Log' }));
    expect(mockedNavigate).toHaveBeenCalledWith('/myhr/admin/audit');
  });

  it('shows no button when the server says the answer has none, whatever the question mentions', async () => {
    signIn({ userId: 'emp-1', organizationId: 'org-1', role: 'EMPLOYEE', loginId: 'EMP001' });
    openChat();
    const message = await ask('open document approvals', {
      message: "The Document Approvals page isn't available to Employee accounts in Waypoint.", actions: [],
    });
    expect(buttons(message)).toEqual([]);
  });

  it('opens the employee document page from an authorized letter request', async () => {
    signIn({ userId: 'emp-1', organizationId: 'org-1', role: 'EMPLOYEE', loginId: 'EMP001' });
    openChat();
    const message = await ask('How do I request a letter?', {
      message: 'Open Document Requests to request your letter.', actions: ['DOCUMENT_REQUESTS'],
    });
    fireEvent.click(within(message).getByRole('button', { name: 'Request Letter' }));
    expect(mockedNavigate).toHaveBeenCalledWith('/myhr/employee/document-requests');
  });

  it('keeps the chat UI\'s own shortcut when the server sends no page keys', async () => {
    signIn({ userId: 'emp-1', organizationId: 'org-1', role: 'EMPLOYEE', loginId: 'EMP001' });
    openChat();
    const message = await ask('What is my timesheet status?', { message: 'Your most recent timesheet was approved.' });
    expect(buttons(message)).toEqual(['View Timesheets']);
  });
});
