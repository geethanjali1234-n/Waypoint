import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import KnowledgeBasePage from '../../../pages/myhr/features/knowledge/KnowledgeBasePage';
import api from '../../../api/myHrKnowledgeApi';

vi.mock('../../../pages/myhr/features/MyHrFeaturePage', () => ({
  default: ({ title, description, children }) => <main><h1>{title}</h1><p>{description}</p>{children}</main>,
}));
vi.mock('../../../api/myHrKnowledgeApi', () => ({
  default: { getSettings: vi.fn(), listDocuments: vi.fn(), addPolicy: vi.fn(), replacePolicy: vi.fn(),
    reviewContent: vi.fn(), previewPendingContent: vi.fn(), acceptIncompleteText: vi.fn(), ingest: vi.fn(),
    activate: vi.fn(), revoke: vi.fn(), restoreHistoricalVersion: vi.fn() },
}));

const settings = { managementEnabled: true, ingestionEnabled: true, activationEnabled: true, retrievalEnabled: true, chatGroundingEnabled: true,
  acceptedFileExtensions: ['txt'], maxFileSizeBytes: 1024 };
const version = { fileName: 'leave.txt', accessLevel: 'COMPANY_WIDE', stage: 'INDEXED',
  indexedSectionCount: 2, ingestionId: 'version-1', sourceDocumentId: 'source-1' };
const published = { id: 'one', title: 'Leave policy', status: 'LIVE', activeVersion: version, actions: { revoke: true } };
const pending = { id: 'two', title: 'Travel policy', status: 'LIVE', pendingVersion: version,
  actions: { activate: true, replace: true } };
const retired = { id: 'three', title: 'Old policy', status: 'REVOKED', actions: {} };
const response = (data) => ({ data: { data } });

beforeEach(() => {
  vi.clearAllMocks();
  api.getSettings.mockResolvedValue(response(settings));
  api.listDocuments.mockResolvedValue(response([published, pending, retired]));
  api.addPolicy.mockResolvedValue(response({}));
  api.activate.mockResolvedValue(response({}));
  api.revoke.mockResolvedValue(response({}));
});

describe('Knowledge Base dashboard', () => {
  it('loads with reads only, maps policy states, and supports search and filtering', async () => {
    const user = userEvent.setup();
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('Leave policy')).toBeInTheDocument();
    expect(screen.getByText('Published and indexed; Wavy availability depends on search readiness and access')).toBeInTheDocument();
    expect(screen.getByText('Ready for publication')).toBeInTheDocument();
    expect(within(screen.getByText('Old policy').closest('li')).getByText('Retired')).toBeInTheDocument();
    expect(api.addPolicy).not.toHaveBeenCalled();
    expect(api.ingest).not.toHaveBeenCalled();
    expect(api.activate).not.toHaveBeenCalled();
    expect(api.revoke).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText('Search policies'), 'travel');
    expect(screen.queryByText('Leave policy')).not.toBeInTheDocument();
    expect(screen.getByText('Travel policy')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Status'), 'retired');
    expect(screen.getByText('No matching policies')).toBeInTheDocument();
  });

  it('does not claim Wavy availability when grounding is disabled or indexing is unverified', async () => {
    api.getSettings.mockResolvedValue(response({ ...settings, chatGroundingEnabled: false }));
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('Published; Wavy policy answers are unavailable')).toBeInTheDocument();
  });

  it('validates an upload and sends it only after an explicit submission', async () => {
    const user = userEvent.setup();
    render(<KnowledgeBasePage />);
    await user.click(await screen.findByRole('button', { name: 'Add policy' }));
    await user.click(screen.getByRole('button', { name: 'Add policy' }));
    expect(screen.getByText('Choose a policy file.')).toBeInTheDocument();
    expect(api.addPolicy).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText(/Policy title/), 'Demo');
    await user.upload(screen.getByLabelText('Policy file'), new File(['demo'], 'demo.txt', { type: 'text/plain' }));
    await user.click(screen.getByRole('button', { name: 'Add policy' }));
    await waitFor(() => expect(api.addPolicy).toHaveBeenCalledTimes(1));
    expect(api.addPolicy).toHaveBeenCalledWith(expect.objectContaining({ title: 'Demo', accessLevel: 'COMPANY_WIDE' }));
  });

  it('requires confirmation and passes the exact pending version to publication', async () => {
    const user = userEvent.setup();
    render(<KnowledgeBasePage />);
    const item = (await screen.findByText('Travel policy')).closest('li');
    await user.click(within(item).getByRole('button', { name: 'Publish policy' }));
    expect(api.activate).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Publish policy' }));
    await waitFor(() => expect(api.activate).toHaveBeenCalledWith('two', 'version-1'));
  });

  it('does not send duplicate uploads while a request is pending', async () => {
    const user = userEvent.setup();
    let finish;
    api.addPolicy.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    render(<KnowledgeBasePage />);
    await user.click(await screen.findByRole('button', { name: 'Add policy' }));
    await user.type(screen.getByLabelText(/Policy title/), 'Demo');
    await user.upload(screen.getByLabelText('Policy file'), new File(['demo'], 'demo.txt', { type: 'text/plain' }));
    const form = screen.getByRole('form', { name: 'Add a company policy' });
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(api.addPolicy).toHaveBeenCalledTimes(1);
    finish(response({}));
    await waitFor(() => expect(screen.getByLabelText(/Policy title/)).toHaveValue(''));
  });

  it('opens content review only when the server permits it', async () => {
    const user = userEvent.setup();
    api.listDocuments.mockResolvedValue(response([{ ...pending, pendingVersion: { ...version, stage: 'NEEDS_REVIEW' },
      actions: { reviewContent: true } }]));
    api.previewPendingContent.mockRejectedValue({ response: { data: { message: 'Review unavailable' } } });
    render(<KnowledgeBasePage />);
    await user.click(await screen.findByRole('button', { name: 'Review extracted content' }));
    expect(await screen.findByText('Review unavailable')).toBeInTheDocument();
    expect(api.previewPendingContent).toHaveBeenCalledWith('two');
    expect(api.reviewContent).not.toHaveBeenCalled();
  });

  it('honors server-provided action permissions and management availability', async () => {
    api.getSettings.mockResolvedValue(response({ ...settings, managementEnabled: false }));
    api.listDocuments.mockResolvedValue(response([{ ...pending, actions: {} }]));
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('Travel policy')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add policy' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Publish policy' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retire policy' })).not.toBeInTheDocument();
  });

  it('presents the actual processing and review stages without writing on load', async () => {
    api.listDocuments.mockResolvedValue(response([
      { ...pending, pendingVersion: { ...version, stage: 'NEEDS_REVIEW', pagesWithoutText: 1 }, actions: {} },
      { ...pending, id: 'processing', title: 'Processing policy', pendingVersion: { ...version, stage: 'INGESTING' }, actions: {} },
      { ...pending, id: 'failed', title: 'Failed policy', pendingVersion: { ...version, stage: 'FAILED' }, actions: { ingest: true } },
    ]));
    const user = userEvent.setup();
    api.ingest.mockResolvedValue(response({}));
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('Awaiting review · some pages have no text')).toBeInTheDocument();
    expect(screen.getByText('Processing')).toBeInTheDocument();
    expect(screen.getByText('Processing failed')).toBeInTheDocument();
    expect(api.ingest).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Retry processing' }));
    expect(api.ingest).toHaveBeenCalledWith('failed');
  });

  it('opens replacement without submitting and requires confirmation to retire', async () => {
    const user = userEvent.setup();
    render(<KnowledgeBasePage />);
    const travel = (await screen.findByText('Travel policy')).closest('li');
    await user.click(within(travel).getByRole('button', { name: 'Replace file' }));
    expect(within(travel).getByRole('form', { name: 'Replace Travel policy' })).toBeInTheDocument();
    expect(api.replacePolicy).not.toHaveBeenCalled();
    const leave = screen.getByText('Leave policy').closest('li');
    await user.click(within(leave).getByRole('button', { name: 'Retire policy' }));
    expect(api.revoke).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Retire policy' }));
    await waitFor(() => expect(api.revoke).toHaveBeenCalledWith('one'));
  });

  it('shows no switch notices when processing and publishing are enabled', async () => {
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('Leave policy')).toBeInTheDocument();
    expect(screen.queryByText(/Processing for AI Search is currently turned off/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Publishing is currently turned off/)).not.toBeInTheDocument();
  });

  it('explains why processing or publishing is unavailable when those switches are off', async () => {
    api.getSettings.mockResolvedValue(response({ ...settings, ingestionEnabled: false }));
    const view = render(<KnowledgeBasePage />);
    expect(await screen.findByText(/Processing for AI Search is currently turned off/)).toBeInTheDocument();
    expect(screen.queryByText(/Publishing is currently turned off/)).not.toBeInTheDocument();
    view.unmount();
    api.getSettings.mockResolvedValue(response({ ...settings, activationEnabled: false }));
    render(<KnowledgeBasePage />);
    expect(await screen.findByText(/Publishing is currently turned off/)).toBeInTheDocument();
    expect(screen.queryByText(/Processing for AI Search is currently turned off/)).not.toBeInTheDocument();
    expect(api.ingest).not.toHaveBeenCalled();
    expect(api.activate).not.toHaveBeenCalled();
  });

  it('does not show switch notices when management itself is disabled', async () => {
    api.getSettings.mockResolvedValue(response({ ...settings, managementEnabled: false, ingestionEnabled: false, activationEnabled: false }));
    render(<KnowledgeBasePage />);
    expect(await screen.findByText(/Adding policies is currently unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/currently turned off/)).not.toBeInTheDocument();
  });

  it('shows loading errors and an empty state without writing', async () => {
    api.listDocuments.mockRejectedValueOnce({ response: { data: { message: 'Cannot load policies' } } });
    const view = render(<KnowledgeBasePage />);
    expect(await screen.findByText('Cannot load policies')).toBeInTheDocument();
    view.unmount();
    api.listDocuments.mockResolvedValue(response([]));
    render(<KnowledgeBasePage />);
    expect(await screen.findByText('No policies yet')).toBeInTheDocument();
    expect(api.revoke).not.toHaveBeenCalled();
  });
});
