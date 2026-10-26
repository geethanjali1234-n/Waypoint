import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { MemoryRouter } from 'react-router-dom';
import AuditHistoryPage from '../../../pages/myhr/features/admin/AuditHistoryPage';
import myHrAuditApi from '../../../api/myHrAuditApi';

vi.mock('../../../api/myHrAuditApi');

const catalog = [
  { value: 'ACCOUNT_STATUS_CHANGED', label: 'Account status changed', category: 'ACCOUNT_MANAGEMENT' },
  { value: 'REPORTING_MANAGER_ASSIGNED', label: 'Reporting manager assigned', category: 'REPORTING_STRUCTURE' },
];
const categories = ['ACCOUNT_MANAGEMENT', 'REPORTING_STRUCTURE'];

function page(overrides = {}) {
  return {
    items: [{
      id: 'log-1', performedAt: '2026-01-15T10:30:00', actionType: 'ACCOUNT_STATUS_CHANGED',
      actionLabel: 'Account status changed', category: 'ACCOUNT_MANAGEMENT',
      description: 'John Doe account deactivated', actorUserId: 'hr-1', actorLoginId: 'TD001',
      actorName: 'Sarah Jones', actorRole: 'HR', entityType: 'USER', entityId: 'emp-1',
      entityDisplayName: 'John Doe', previousValue: { accountStatus: 'ACTIVE' },
      updatedValue: { accountStatus: 'INACTIVE' }, outcome: 'SUCCESS', source: 'MY_HR',
    }],
    page: 0, size: 25, totalElements: 1, totalPages: 1, actionCatalog: catalog, categories,
    ...overrides,
  };
}

function renderPage() {
  return render(<MemoryRouter><AuditHistoryPage /></MemoryRouter>);
}

describe('AuditHistoryPage', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    myHrAuditApi.history.mockResolvedValue({ data: { data: page() } });
  });

  it('shows a loader then the audit rows with a summary count', async () => {
    renderPage();
    expect(screen.getByRole('status')).toBeInTheDocument();

    expect((await screen.findAllByText('John Doe')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Account status changed').length).toBeGreaterThan(0);
    expect(screen.getByText('Showing 1 of 1 event')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Audit event timeline' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Category')).not.toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Audit event timeline' }).tagName).toBe('OL');
  });

  it('shows an empty state when there is no history yet', async () => {
    myHrAuditApi.history.mockResolvedValue({ data: { data: page({ items: [], totalElements: 0, totalPages: 0 }) } });
    renderPage();

    expect(await screen.findByText('No audit history yet')).toBeInTheDocument();
  });

  it('shows a filtered empty state and lets the user reset filters', async () => {
    myHrAuditApi.history.mockResolvedValue({ data: { data: page({ items: [], totalElements: 0, totalPages: 0 }) } });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(myHrAuditApi.history).toHaveBeenCalled());

    await user.type(screen.getByPlaceholderText('Search by person, record, or description'), 'nothing-matches');
    expect(await screen.findByText('No events match these filters')).toBeInTheDocument();

    const resetButton = screen.getByRole('button', { name: 'Clear filters' });
    expect(resetButton).toBeEnabled();
    await user.click(resetButton);
    expect(screen.getByPlaceholderText('Search by person, record, or description')).toHaveValue('');
  });

  it('disables Clear filters when no filters are active', async () => {
    renderPage();
    await waitFor(() => expect(myHrAuditApi.history).toHaveBeenCalled());

    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDisabled();
  });

  it('shows an error state and reloads on retry', async () => {
    myHrAuditApi.history.mockRejectedValueOnce({ response: { data: { message: 'Server unavailable' } } });
    renderPage();

    expect(await screen.findByText('Could not load audit history')).toBeInTheDocument();
    expect(screen.getByText('Server unavailable')).toBeInTheDocument();

    myHrAuditApi.history.mockResolvedValueOnce({ data: { data: page() } });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('John Doe')).toBeInTheDocument();
  });

  it('sends the search text, category, and date range filters to the API and resets to page 1', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    myHrAuditApi.history.mockClear();

    await user.type(screen.getByPlaceholderText('Search by person, record, or description'), 'Sarah');
    await user.click(within(screen.getByRole('group', { name: 'Audit categories' })).getByRole('button', { name: 'Account Management' }));
    await user.type(screen.getByLabelText('From'), '2026-01-01');
    await user.type(screen.getByLabelText('To'), '2026-01-31');

    await waitFor(() => expect(myHrAuditApi.history).toHaveBeenCalledWith(expect.objectContaining({
      page: 0, search: 'Sarah', category: 'ACCOUNT_MANAGEMENT', from: '2026-01-01', to: '2026-01-31',
    })));
  });

  it('scopes the action dropdown to the selected category and resets action to ALL on category change', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.click(within(screen.getByRole('group', { name: 'Audit categories' })).getByRole('button', { name: 'Account Management' }));
    const actionSelect = screen.getByLabelText('Action');
    expect(within(actionSelect).getByRole('option', { name: 'Account status changed' })).toBeInTheDocument();
    expect(within(actionSelect).queryByRole('option', { name: 'Reporting manager assigned' })).not.toBeInTheDocument();
    expect(actionSelect).toHaveValue('ALL');
  });

  it('opens the detail modal for a row and shows the before/after diff', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.click(screen.getByRole('button', { name: /Account status changed/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Account status changed' });

    expect(within(dialog).getByText('John Doe account deactivated')).toBeInTheDocument();
    expect(within(dialog).getByText('Active')).toBeInTheDocument();
    expect(within(dialog).getByText('Inactive')).toBeInTheDocument();

    await user.click(within(dialog).getByText('Close'));
    expect(screen.queryByRole('dialog', { name: 'Account status changed' })).not.toBeInTheDocument();
  });

  it('shows System for events with no actor', async () => {
    myHrAuditApi.history.mockResolvedValue({ data: { data: page({
      items: [{
        id: 'log-3', performedAt: '2026-01-10T09:00:00', actionType: 'ACCOUNT_STATUS_CHANGED',
        actionLabel: 'Account status changed', category: 'ACCOUNT_MANAGEMENT', description: 'System update',
        actorUserId: null, actorLoginId: null, actorName: null, actorRole: null,
        entityType: 'USER', entityId: 'emp-1', entityDisplayName: 'John Doe',
        previousValue: null, updatedValue: null, outcome: 'SUCCESS', source: 'MY_HR',
      }],
    }) } });
    renderPage();

    expect((await screen.findAllByText('System')).length).toBeGreaterThan(0);
  });

  it('shows document-request participants in context without adding universal columns', async () => {
    myHrAuditApi.history.mockResolvedValue({ data: { data: page({
      items: [{
        id: 'log-doc', performedAt: '2026-01-10T09:00:00', actionType: 'DOCUMENT_REQUEST_CREATED',
        actionLabel: 'Document request created', category: 'DOCUMENT_REQUEST', description: 'Document request created',
        actorUserId: 'admin', actorLoginId: 'TDADMIN', actorName: null, actorRole: 'COMPANY_ADMIN',
        entityType: 'DOCUMENT_REQUEST', entityId: 'request-1', entityDisplayName: 'I-983', previousValue: null,
        updatedValue: { requestedBy: 'TDADMIN', requestedFor: 'John Doe (EMP-001)', status: 'PENDING' },
        outcome: 'SUCCESS', source: 'MY_HR',
      }],
    }) } });
    renderPage();

    // Rendered as compact context inline with the row's description, not a
    // dedicated column - matches AuditHistoryPage's participantContext().
    expect(await screen.findByText(/Requested by TDADMIN/)).toBeInTheDocument();
    expect(screen.getByText(/Requested for John Doe \(EMP-001\)/)).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /Requested By/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /Requested For/i })).not.toBeInTheDocument();
  });

  it('marks only the selected category pill as pressed and the chosen view as active, and keeps contrast rules for hover', async () => {
    const user = userEvent.setup();
    const { container } = renderPage();
    await screen.findByText('John Doe');
    const group = screen.getByRole('group', { name: 'Audit categories' });
    expect(within(group).getByRole('button', { name: 'All categories' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(group).getByRole('button', { name: 'Account Management' }));
    expect(within(group).getByRole('button', { name: 'Account Management' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(group).getByRole('button', { name: 'All categories' })).toHaveAttribute('aria-pressed', 'false');
    expect(container.querySelectorAll('.audit-history__view-toggle-btn.is-active')).toHaveLength(1);

    // jsdom does not apply stylesheets, so guard the page-scoped rules that keep a hovered selected pill readable.
    const css = readFileSync(resolve(__dirname, '../../../pages/myhr/features/admin/auditHistory.css'), 'utf8');
    expect(css).toMatch(/\.audit-history-page \.wp-filter-pills button\[aria-pressed='true'\]:hover:not\(:disabled\)\s*\{[^}]*background:var\(--wp-color-primary-deep\);[^}]*color:#FBF9F3/);
    expect(css).toMatch(/button:hover:not\(:disabled\):not\(\[aria-pressed='true'\]\)\s*\{[^}]*color:var\(--wp-color-text\)/);
  });
});
