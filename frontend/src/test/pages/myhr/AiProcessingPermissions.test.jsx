import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import KnowledgeBasePage from '../../../pages/myhr/features/knowledge/KnowledgeBasePage';
import AiProcessingPermissions, { AUTOMATIC_COVERAGE_NOTICE }
  from '../../../pages/myhr/features/knowledge/AiProcessingPermissions';
import api from '../../../api/myHrKnowledgeApi';

const session = { current: null };
vi.mock('../../../auth/myHrSession', () => ({ getMyHrSession: () => session.current }));
vi.mock('../../../pages/myhr/features/MyHrFeaturePage', () => ({
  default: ({ title, children }) => <main><h1>{title}</h1>{children}</main>,
}));
vi.mock('../../../api/myHrKnowledgeApi', () => ({
  default: { getSettings: vi.fn(), listDocuments: vi.fn(), getExternalProcessing: vi.fn(),
    approveClaudeOrganization: vi.fn(), revokeClaudeOrganization: vi.fn(), excludeFromClaude: vi.fn(),
    liftClaudeExclusion: vi.fn(), replaceClaudeOrganizationCoverage: vi.fn() },
}));

const response = (data) => ({ data: { data } });
const REFERENCE = 'DPA-2026-17 signed by Legal';
const documents = [
  { id: 'kd-1', title: 'Leave policy', status: 'LIVE', activeVersion: { stage: 'INDEXED' }, actions: {} },
  { id: 'kd-2', title: 'Travel policy', status: 'LIVE', actions: {} },
  { id: 'kd-3', title: 'Old policy', status: 'REVOKED', actions: {} },
];
const organization = (coverage = 'REVIEWED_VERSIONS', status = 'ACTIVE') => ({ id: 'org-a', provider: 'ANTHROPIC_CLAUDE',
  scope: 'ORGANIZATION', status, effective: status === 'ACTIVE', accessLevels: ['COMPANY_WIDE'], coverage });
const exclusion = (id) => ({ id: `ex-${id}`, provider: 'ANTHROPIC_CLAUDE', scope: 'DOCUMENT_EXCLUSION', status: 'ACTIVE',
  effective: true, knowledgeDocumentId: id });
const givenStatus = (approvals, claudeSwitchEnabled = true) =>
  api.getExternalProcessing.mockResolvedValue(response({ claudeSwitchEnabled, approvals }));
const forbidden = () => Promise.reject({ response: { status: 403, data: { message: 'Only a Company Admin can approve' } } });

beforeEach(() => {
  vi.clearAllMocks();
  session.current = null;
  givenStatus([]);
  api.approveClaudeOrganization.mockResolvedValue(response({}));
  api.revokeClaudeOrganization.mockResolvedValue(response([]));
  api.excludeFromClaude.mockResolvedValue(response({}));
  api.liftClaudeExclusion.mockResolvedValue(response([]));
  api.replaceClaudeOrganizationCoverage.mockResolvedValue(response({}));
});

describe('Knowledge Base page visibility', () => {
  beforeEach(() => {
    api.getSettings.mockResolvedValue(response({ managementEnabled: true, ingestionEnabled: true, activationEnabled: true }));
    api.listDocuments.mockResolvedValue(response(documents));
  });

  it('shows AI Processing Permissions to a Company Admin and keeps the policy list', async () => {
    session.current = { role: 'COMPANY_ADMIN' };
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('AI Processing Permissions')).toBeInTheDocument();
    expect(screen.getByLabelText('Search policies')).toBeInTheDocument();
    await waitFor(() => expect(api.getExternalProcessing).toHaveBeenCalledTimes(1));
  });

  it.each([['HR'], ['EMPLOYEE'], [null]])('hides the section and never requests approvals for role %s', async (role) => {
    session.current = role ? { role } : null;
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('Leave policy')).toBeInTheDocument();
    expect(screen.queryByText('AI Processing Permissions')).not.toBeInTheDocument();
    expect(api.getExternalProcessing).not.toHaveBeenCalled();
  });
});

describe('AI Processing Permissions', () => {
  it('displays each coverage mode', async () => {
    const view = render(<AiProcessingPermissions documents={documents} />);
    expect(await screen.findByText('Not approved')).toBeInTheDocument();
    view.unmount();

    givenStatus([organization()]);
    const second = render(<AiProcessingPermissions documents={documents} />);
    expect(await screen.findByText('Per-version approval')).toBeInTheDocument();
    second.unmount();

    givenStatus([organization('ALL_PUBLISHED_COMPANY_WIDE')]);
    render(<AiProcessingPermissions documents={documents} />);
    expect(await screen.findByText('Automatic coverage of published company-wide policies')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enable automatic coverage' })).not.toBeInTheDocument();
  });

  it('a revoked organization approval is shown as not approved', async () => {
    givenStatus([organization('ALL_PUBLISHED_COMPANY_WIDE', 'REVOKED')]);
    render(<AiProcessingPermissions documents={documents} />);
    expect(await screen.findByText('Not approved')).toBeInTheDocument();
  });

  it('warns when Claude policy sharing is switched off for the environment', async () => {
    givenStatus([], false);
    render(<AiProcessingPermissions documents={documents} />);
    expect(await screen.findByText(/Claude policy sharing is turned off/)).toBeInTheDocument();
  });

  it('requires a genuine reference and acknowledgement, then confirmation, before enabling automatic coverage', async () => {
    const user = userEvent.setup();
    render(<AiProcessingPermissions documents={documents} />);
    const enable = await screen.findByRole('button', { name: 'Enable automatic coverage' });
    expect(enable).toBeDisabled();

    await user.type(screen.getByLabelText(/Approval reference/), REFERENCE);
    expect(enable).toBeDisabled();
    await user.click(screen.getByRole('checkbox'));
    expect(enable).toBeEnabled();

    await user.click(enable);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(AUTOMATIC_COVERAGE_NOTICE)).toBeInTheDocument();
    expect(api.approveClaudeOrganization).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(api.approveClaudeOrganization).not.toHaveBeenCalled();

    await user.click(enable);
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Enable automatic coverage' }));
    await waitFor(() => expect(api.approveClaudeOrganization).toHaveBeenCalledWith(
      { approvalReference: REFERENCE, coverage: 'ALL_PUBLISHED_COMPANY_WIDE' }));
    expect(api.revokeClaudeOrganization).not.toHaveBeenCalled();
    expect(await screen.findByText(/Automatic coverage is on/)).toBeInTheDocument();
    expect(api.getExternalProcessing).toHaveBeenCalledTimes(2);
  });

  it('replaces a per-version approval with one atomic request bound to the approval shown', async () => {
    const user = userEvent.setup();
    givenStatus([organization()]);
    render(<AiProcessingPermissions documents={documents} />);
    await user.type(await screen.findByLabelText(/Approval reference/), REFERENCE);
    await user.click(screen.getByRole('checkbox'));
    const enable = screen.getByRole('button', { name: 'Enable automatic coverage' });
    expect(enable).toBeDisabled();
    await user.type(screen.getByLabelText(/Reason for replacing/), 'Moving to automatic coverage');

    await user.click(enable);
    expect(within(screen.getByRole('dialog')).getByText(/replaced in a single step/)).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Enable automatic coverage' }));

    await waitFor(() => expect(api.replaceClaudeOrganizationCoverage).toHaveBeenCalledWith({ expectedApprovalId: 'org-a',
      coverage: 'ALL_PUBLISHED_COMPANY_WIDE', approvalReference: REFERENCE, reason: 'Moving to automatic coverage' }));
    expect(api.revokeClaudeOrganization).not.toHaveBeenCalled();
    expect(api.approveClaudeOrganization).not.toHaveBeenCalled();
    expect(await screen.findByText(/Automatic coverage is on/)).toBeInTheDocument();
  });

  it('keeps showing the previous approval when the replacement fails or was changed concurrently', async () => {
    const user = userEvent.setup();
    givenStatus([organization()]);
    api.replaceClaudeOrganizationCoverage.mockRejectedValueOnce({ response: { status: 400, data: {
      message: 'The organization approval changed while you were editing it; reload and try again' } } });
    render(<AiProcessingPermissions documents={documents} />);
    await user.type(await screen.findByLabelText(/Approval reference/), REFERENCE);
    await user.click(screen.getByRole('checkbox'));
    await user.type(screen.getByLabelText(/Reason for replacing/), 'Moving to automatic coverage');
    await user.click(screen.getByRole('button', { name: 'Enable automatic coverage' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Enable automatic coverage' }));

    expect(await screen.findByText(/changed while you were editing it/)).toBeInTheDocument();
    expect(screen.getByText('Per-version approval')).toBeInTheDocument();
    expect(api.getExternalProcessing).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/receives no policy excerpts/)).not.toBeInTheDocument();
  });

  it('sends only one replacement while a request is in progress', async () => {
    const user = userEvent.setup();
    givenStatus([organization()]);
    let finish;
    api.replaceClaudeOrganizationCoverage.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    render(<AiProcessingPermissions documents={documents} />);
    await user.type(await screen.findByLabelText(/Approval reference/), REFERENCE);
    await user.click(screen.getByRole('checkbox'));
    await user.type(screen.getByLabelText(/Reason for replacing/), 'Moving to automatic coverage');
    const enable = screen.getByRole('button', { name: 'Enable automatic coverage' });
    await user.click(enable);
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Enable automatic coverage' }));
    await waitFor(() => expect(enable).toBeDisabled());
    expect(api.replaceClaudeOrganizationCoverage).toHaveBeenCalledTimes(1);
    finish(response({}));
    await screen.findByText(/Automatic coverage is on/);
    expect(api.replaceClaudeOrganizationCoverage).toHaveBeenCalledTimes(1);
  });

  it('revokes the organization approval only with a reason and confirmation', async () => {
    const user = userEvent.setup();
    givenStatus([organization('ALL_PUBLISHED_COMPANY_WIDE')]);
    render(<AiProcessingPermissions documents={documents} />);
    const revoke = await screen.findByRole('button', { name: 'Revoke organization approval' });
    expect(revoke).toBeDisabled();
    await user.type(screen.getByLabelText(/Reason for revoking/), 'Contract ended');
    await user.click(revoke);
    expect(api.revokeClaudeOrganization).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke approval' }));
    await waitFor(() => expect(api.revokeClaudeOrganization).toHaveBeenCalledWith('Contract ended'));
    expect(await screen.findByText(/Claude no longer receives policy excerpts/)).toBeInTheDocument();
  });

  it('excludes a policy and removes an existing exclusion, each with a reason', async () => {
    const user = userEvent.setup();
    givenStatus([organization('ALL_PUBLISHED_COMPANY_WIDE'), exclusion('kd-2')]);
    render(<AiProcessingPermissions documents={documents} />);

    const travel = (await screen.findByText('Travel policy')).closest('li');
    expect(within(travel).getByText('Excluded from Claude')).toBeInTheDocument();
    expect(screen.queryByText('Old policy')).not.toBeInTheDocument();
    const exclude = screen.getByRole('button', { name: 'Exclude Leave policy' });
    expect(exclude).toBeDisabled();

    await user.type(screen.getByLabelText(/Reason for excluding/), 'Legal review pending');
    await user.click(exclude);
    await waitFor(() => expect(api.excludeFromClaude).toHaveBeenCalledWith('kd-1', 'Legal review pending'));

    await user.type(screen.getByLabelText(/Reason for excluding/), 'Review complete');
    await user.click(screen.getByRole('button', { name: 'Remove exclusion for Travel policy' }));
    await waitFor(() => expect(api.liftClaudeExclusion).toHaveBeenCalledWith('kd-2', 'Review complete'));
  });

  it('shows friendly messages for permission and lookup failures', async () => {
    const user = userEvent.setup();
    api.excludeFromClaude.mockRejectedValueOnce({ response: { status: 403 } })
      .mockRejectedValueOnce({ response: { status: 404 } })
      .mockRejectedValueOnce({ response: { status: 400, data: { message: 'This policy is already excluded from Claude' } } });
    render(<AiProcessingPermissions documents={documents} />);
    await user.type(await screen.findByLabelText(/Reason for excluding/), 'Legal review pending');
    const exclude = screen.getByRole('button', { name: 'Exclude Leave policy' });

    await user.click(exclude);
    expect(await screen.findByText('Only a Company Admin can change AI processing permissions.')).toBeInTheDocument();
    await user.click(exclude);
    expect(await screen.findByText('That policy was not found in your organization.')).toBeInTheDocument();
    await user.click(exclude);
    expect(await screen.findByText('This policy is already excluded from Claude')).toBeInTheDocument();
  });

  it('shows a friendly message when the approvals cannot be loaded', async () => {
    api.getExternalProcessing.mockImplementation(forbidden);
    render(<AiProcessingPermissions documents={documents} />);
    expect(await screen.findByText('Only a Company Admin can change AI processing permissions.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enable automatic coverage' })).not.toBeInTheDocument();
  });
});
