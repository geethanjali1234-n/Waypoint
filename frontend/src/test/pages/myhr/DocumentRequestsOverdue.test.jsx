import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import DocumentRequestsPage from '../../../pages/myhr/features/documentRequests/DocumentRequestsPage';
import StatusBadge from '../../../components/global/StatusBadge';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('docx-preview', () => ({ renderAsync: vi.fn().mockResolvedValue(undefined) }));
// MyHrShell loads the profile and notifications; mock them so the test never reaches a real backend.
vi.mock('../../../api/myHrEmployeeApi', () => ({ default: { getMine: vi.fn(() => Promise.resolve({ data: { data: {} } })) } }));
vi.mock('../../../api/myHrNotificationApi', () => ({ default: {
  listNotifications: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getUnreadCount: vi.fn(() => Promise.resolve({ data: { data: 0 } })),
  markAsRead: vi.fn(() => Promise.resolve({ data: { data: null } })),
  markAllAsRead: vi.fn(() => Promise.resolve({ data: { data: null } })),
} }));

const base = {
  requestDescription: 'Needed', requestedByUserId: 'hr', requestedByName: 'Sarah', requestedForUserId: 'employee',
  requestedForName: 'John', priority: 'NORMAL', requestDirection: 'TO_EMPLOYEE', requestedAt: '2026-09-01T10:00:00',
};
const overdue = { ...base, id: 'req-overdue', requestTitle: 'Passport copy', status: 'PENDING', requiredBy: '2026-10-01', overdue: true };
const onTime = { ...base, id: 'req-on-time', requestTitle: 'Visa copy', status: 'PENDING', requiredBy: '2026-12-01', overdue: false };
// Past its due date but the backend says not overdue: the UI must never decide overdue on its own.
const notFlagged = { ...base, id: 'req-not-flagged', requestTitle: 'Address proof', status: 'PENDING', requiredBy: '2026-01-01', requestDirection: 'TO_MANAGER', overdue: false };
const completed = { ...base, id: 'req-done', requestTitle: 'Degree certificate', status: 'FULFILLED', requiredBy: '2026-01-01', overdue: false };
const olderServer = { ...base, id: 'req-old', requestTitle: 'Bank letter', status: 'PENDING', requiredBy: '2026-01-01' };

function renderPage(path, role = 'HR', userId = 'hr') {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId, role }));
  const segment = role === 'EMPLOYEE' ? 'employee' : 'hr';
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path={`/myhr/${segment}/document-requests`} element={<DocumentRequestsPage role={role} />} />
    <Route path={`/myhr/${segment}/document-requests/:requestId`} element={<DocumentRequestsPage role={role} />} />
  </Routes></MemoryRouter>);
}

// Employee-to-HR requests and Signed I-983s the backend marks overdue (the UI never decides this itself).
const employeeToHrOverdue = { ...base, id: 'req-emp-letter', requestTitle: 'Employment letter', requestedByUserId: 'employee', requestedByName: 'John',
  requestedForUserId: 'hr', requestedForName: 'Sarah', requestDirection: 'TO_MANAGER', documentType: 'EMPLOYMENT_LETTER', status: 'PENDING', requiredBy: '2026-10-01', overdue: true };
const i983Overdue = { ...base, id: 'req-i983', requestTitle: 'Signed I-983', requestedByUserId: 'employee', requestedByName: 'John',
  requestedForUserId: 'hr', requestedForName: 'Sarah', requestDirection: 'TO_MANAGER', documentType: 'I983', status: 'SUBMITTED', requiredBy: '2026-10-01', overdue: true };
const i983Approved = { ...i983Overdue, id: 'req-i983-done', requestTitle: 'Signed I-983 (approved)', status: 'APPROVED', overdue: false };
const badgesFor = (title) => [...screen.getByText(title).closest('tr, .document-requests__card').querySelectorAll('.wp-status-badge')]
  .map((badge) => badge.textContent.trim());

const page = (items) => ({ data: { data: { items, page: 0, size: 10, totalItems: items.length, totalPages: 1 } } });
const overdueBadges = () => document.querySelectorAll('.wp-status-badge--overdue');

describe('Document request Overdue badge and filter', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear();
    myHrDocumentRequestApi.list.mockResolvedValue(page([overdue, onTime, notFlagged, completed, olderServer]));
  });

  it('shows a red Overdue badge only for requests the backend marks overdue, keeping the status badge', async () => {
    renderPage('/myhr/hr/document-requests?view=employees');
    await screen.findByText('Passport copy');

    expect(overdueBadges()).toHaveLength(1);
    const badge = overdueBadges()[0];
    expect(badge).toHaveTextContent('Overdue');
    // The existing Pending status badge is still shown next to it.
    expect(within(badge.parentElement).getByText('Pending')).toBeInTheDocument();
    // On-time, past-due-but-not-flagged, completed and older-server responses get no Overdue badge.
    ['Visa copy', 'Address proof', 'Degree certificate', 'Bank letter'].forEach((title) => {
      const row = screen.getByText(title).closest('tr, .document-requests__card');
      expect(row.querySelector('.wp-status-badge--overdue')).toBeNull();
    });
  });

  it('offers Overdue in the status filter and sends status=OVERDUE to the backend', async () => {
    const user = userEvent.setup();
    renderPage('/myhr/hr/document-requests?view=employees');
    await screen.findByText('Passport copy');

    const status = screen.getByLabelText('Status');
    expect(within(status).getByRole('option', { name: 'Overdue' })).toHaveValue('OVERDUE');
    // Existing statuses are all still there.
    ['All', 'Pending', 'Submitted', 'Returned for Correction', 'Approved', 'Completed', 'Rejected']
      .forEach((name) => expect(within(status).getByRole('option', { name })).toBeInTheDocument());

    myHrDocumentRequestApi.list.mockResolvedValue(page([overdue]));
    await user.selectOptions(status, 'OVERDUE');

    await waitFor(() => expect(myHrDocumentRequestApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OVERDUE', page: 0 })));
    await waitFor(() => expect(screen.queryByText('Visa copy')).not.toBeInTheDocument());
    expect(screen.getByText('Passport copy')).toBeInTheDocument();
  });

  it('shows the Overdue badge on the request detail only when overdue', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: overdue } });
    const { unmount } = renderPage('/myhr/hr/document-requests/req-overdue');
    await screen.findByRole('heading', { name: 'Passport copy' });
    expect(overdueBadges()).toHaveLength(1);
    unmount();

    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: onTime } });
    renderPage('/myhr/hr/document-requests/req-on-time');
    await screen.findByRole('heading', { name: 'Visa copy' });
    expect(overdueBadges()).toHaveLength(0);
  });
});

describe('Overdue badge for employee-to-HR requests and Signed I-983', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear();
    myHrDocumentRequestApi.list.mockResolvedValue(page([overdue, employeeToHrOverdue, i983Overdue, i983Approved]));
  });

  it('HR view: shows Overdue next to the existing status of each overdue workflow', async () => {
    renderPage('/myhr/hr/document-requests');
    await screen.findByText('Employment letter');

    expect(badgesFor('Employment letter')).toEqual(['Pending', 'Overdue']);
    expect(badgesFor('Signed I-983')).toEqual(['Submitted', 'Overdue']);
    expect(badgesFor('Signed I-983 (approved)')).toEqual(['Approved']);
    expect(badgesFor('Passport copy')).toEqual(['Pending', 'Overdue']);
  });

  it('employee view: shows the same badges on the employee’s own requests and filters with status=OVERDUE', async () => {
    const user = userEvent.setup();
    renderPage('/myhr/employee/document-requests', 'EMPLOYEE', 'employee');
    await screen.findByText('Employment letter');

    expect(badgesFor('Employment letter')).toEqual(['Pending', 'Overdue']);
    expect(badgesFor('Signed I-983')).toEqual(['Submitted', 'Overdue']);
    expect(badgesFor('Signed I-983 (approved)')).toEqual(['Approved']);

    myHrDocumentRequestApi.list.mockResolvedValue(page([employeeToHrOverdue, i983Overdue]));
    await user.selectOptions(screen.getByLabelText('Status'), 'OVERDUE');
    await waitFor(() => expect(myHrDocumentRequestApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OVERDUE' })));
    await waitFor(() => expect(screen.queryByText('Signed I-983 (approved)')).not.toBeInTheDocument());
  });

  it('employee view: the detail of an overdue employee-to-HR request shows the badge', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: employeeToHrOverdue } });
    renderPage('/myhr/employee/document-requests/req-emp-letter', 'EMPLOYEE', 'employee');
    await screen.findByRole('heading', { name: 'Employment letter' });
    expect(overdueBadges()).toHaveLength(1);
  });
});

describe('StatusBadge overdue variant', () => {
  it('renders overdue with the error (red) variant and leaves other variants unchanged', () => {
    const { container } = render(<>
      <StatusBadge status="overdue" label="Overdue" />
      <StatusBadge status="rejected" />
      <StatusBadge status="pending" />
    </>);
    const [overdueBadge, rejected, pendingBadge] = container.querySelectorAll('.wp-status-badge');
    expect(overdueBadge.className).toContain('wp-status-badge--overdue');
    expect(overdueBadge).toHaveTextContent('Overdue');
    expect(rejected.className).toContain('wp-status-badge--rejected');
    expect(pendingBadge.className).toContain('wp-status-badge--pending');
  });
});
