import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import DocumentRequestsPage from '../../../pages/myhr/features/documentRequests/DocumentRequestsPage';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import { renderAsync } from 'docx-preview';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('docx-preview', () => ({ renderAsync: vi.fn().mockResolvedValue(undefined) }));
const pending = { id: 'req-1', requestTitle: 'Employment letter', requestDescription: 'Needed', requestedByUserId: 'employee', requestedByName: 'John', requestedForUserId: 'hr', requestedForName: 'Sarah', status: 'PENDING', priority: 'NORMAL', requestDirection: 'TO_MANAGER', requestedAt: '2026-09-01T10:00:00' };
const prefix = (role) => role === 'COMPANY_ADMIN' ? 'admin' : role.toLowerCase();
function Location() { const location = useLocation(); return <output data-testid="location">{`${location.pathname}${location.search}`}</output>; }
function renderPage(role = 'HR', session = { userId: 'hr', role: 'HR' }, detail = false, search = '') {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify(session));
  const base = `/myhr/${prefix(role)}/document-requests`;
  return render(<MemoryRouter initialEntries={[`${detail ? `${base}/req-1` : base}${search}`]}><Location /><Routes>
    <Route path={`${base}`} element={<DocumentRequestsPage role={role} />} />
    <Route path={`${base}/:requestId`} element={<DocumentRequestsPage role={role} />} />
  </Routes></MemoryRouter>);
}

describe('DocumentRequestsPage detail flow', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear();
    myHrDocumentRequestApi.list.mockResolvedValue({ data: { data: [pending] } });
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: pending } });
    myHrDocumentRequestApi.getFulfillmentEmailDraft.mockResolvedValue({ data: { data: { recipientEmail: 'john@example.com', subject: 'Your Employment Letter', message: 'Hi John,' } } });
  });

  it('requests one server-side page at a time and resets filters to page one', async () => {
    const user = userEvent.setup();
    const requests = Array.from({ length: 12 }, (_, index) => ({
      ...pending,
      id: `req-${index + 1}`,
      requestTitle: `Document ${index + 1}`,
      status: index === 10 ? 'REJECTED' : 'PENDING',
    }));
    myHrDocumentRequestApi.list.mockImplementation(({ page = 0, status = 'ALL' } = {}) => {
      const filtered = status === 'REJECTED' ? requests.filter((item) => item.status === 'REJECTED') : requests;
      return Promise.resolve({ data: { data: {
        items: filtered.slice(page * 10, (page + 1) * 10), page, size: 10,
        totalItems: filtered.length, totalPages: Math.ceil(filtered.length / 10),
      } } });
    });
    renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=employees');
    expect(await screen.findByText('Document 1')).toBeInTheDocument();
    expect(screen.queryByText('Document 11')).not.toBeInTheDocument();
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Document 11')).toBeInTheDocument();
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Status'), 'REJECTED');
    expect(await screen.findByText('Document 11')).toBeInTheDocument();
    // Only one REJECTED request matches - a single page of results shows no
    // pagination control at all (same >1 gate as before this change).
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
  });

  it('calls a sent offer Pending Signature for the employee and Awaiting Employee Signature for HR', async () => {
    const sentOffer = { ...pending, documentType: 'OFFER_LETTER', status: 'FULFILLED', fulfilledDocumentId: 'offer-pdf' };
    myHrDocumentRequestApi.list.mockResolvedValue({ data: { data: [sentOffer] } });
    const employee = renderPage('EMPLOYEE', { userId: 'employee', role: 'EMPLOYEE' });
    expect(await screen.findByText('Pending Signature')).toBeInTheDocument();
    employee.unmount();

    renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=employees');
    expect(await screen.findByText('Awaiting Employee Signature')).toBeInTheDocument();
  });

  it('recognizes a sent offer by its backend document type even when its display title changes', async () => {
    const sentOffer = { ...pending, requestTitle: 'Employment Offer - John Doe', documentType: 'OFFER_LETTER', status: 'FULFILLED', fulfilledDocumentId: 'offer-pdf' };
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: sentOffer } });
    renderPage('EMPLOYEE', { userId: 'employee', role: 'EMPLOYEE' }, true);
    expect(await screen.findByRole('button', { name: 'Review & Sign' })).toBeInTheDocument();
  });

  it('renders filter selects through the shared light-theme select styling hook', async () => {
    renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=employees');
    await screen.findByText('Employment letter');

    // The shared .myhr-shell select/option light-theme CSS targets selects by
    // this class + DOM nesting, not by page - this is the structural contract
    // every My HR dropdown (Status, Priority, Category, and all the others)
    // relies on to render a light native option popup instead of the OS
    // dark-theme default.
    const status = screen.getByLabelText('Status');
    expect(status.tagName).toBe('SELECT');
    expect(status.className).toContain('wp-field__control');
    expect(status.closest('.myhr-shell')).not.toBeNull();
    ['Priority', 'Category'].forEach((label) => {
      const select = screen.getByLabelText(label);
      expect(select.className).toContain('wp-field__control');
    });
  });

  it('opens the HR request workspaces from the workspace controls', async () => {
    const user = userEvent.setup();
    renderPage('HR', { userId: 'hr', role: 'HR' });
    await screen.findByText('Employment letter');
    expect(myHrDocumentRequestApi.list).toHaveBeenCalledWith(expect.objectContaining({ workspace: 'received' }));
    await user.click(screen.getByRole('button', { name: 'My requests' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/hr/document-requests?view=requested');
    expect(screen.getByRole('button', { name: 'Request for myself' })).toBeInTheDocument();
  });

  it('does not show a failed load as an empty document-request list', async () => {
    myHrDocumentRequestApi.list.mockRejectedValue({ response: { status: 500 } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=employees');

    expect(await screen.findByRole('alert')).toHaveTextContent('Document requests could not be loaded.');
    expect(screen.queryByText('No document requests available.')).not.toBeInTheDocument();
  });

  it('leaves an expired-session response for the protected route to redirect', async () => {
    myHrDocumentRequestApi.list.mockRejectedValue({ response: { status: 401 } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=employees');

    await waitFor(() => expect(myHrDocumentRequestApi.list).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.queryByText('Loading document requests…')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('No document requests available.')).not.toBeInTheDocument();
  });

  it('separates HR self requests from employee request traffic', async () => {
    const ownRequest = { ...pending, id: 'mine', requestTitle: 'My request', requestedByUserId: 'hr', requestedForUserId: 'manager', requestDirection: 'TO_MANAGER' };
    const sentToEmployee = { ...pending, id: 'sent', requestTitle: 'Sent to employee', requestedByUserId: 'hr', requestedForUserId: 'employee-2', requestDirection: 'TO_EMPLOYEE' };
    const receivedFromEmployee = { ...pending, id: 'received', requestTitle: 'Employee request', requestedByUserId: 'employee-2', requestedForUserId: 'hr', requestDirection: 'TO_MANAGER' };
    myHrDocumentRequestApi.list.mockImplementation(({ workspace } = {}) => Promise.resolve({ data: { data: {
      items: workspace === 'mine' ? [ownRequest] : [sentToEmployee, receivedFromEmployee],
      page: 0, size: 10, totalItems: workspace === 'mine' ? 1 : 2, totalPages: 1,
    } } }));

    const mine = renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=mine');
    expect(await screen.findByText('My request')).toBeInTheDocument();
    expect(screen.queryByText('Sent to employee')).not.toBeInTheDocument();
    expect(screen.queryByText('Employee request')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request for myself' })).toBeInTheDocument();
    mine.unmount();

    renderPage('HR', { userId: 'hr', role: 'HR' }, false, '?view=employees');
    expect(await screen.findByText('Sent to employee')).toBeInTheDocument();
    expect(screen.getByText('Employee request')).toBeInTheDocument();
    expect(screen.queryByText('My request')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request from employee' })).toBeInTheDocument();
  });

  it.each([
    ['COMPANY_ADMIN', 'admin'], ['HR', 'hr'], ['EMPLOYEE', 'employee'],
  ])('%s View navigates to its valid role-specific detail route and loads the ID', async (role, pathRole) => {
    const user = userEvent.setup();
    renderPage(role, { userId: role === 'EMPLOYEE' ? 'employee' : role === 'HR' ? 'hr' : 'admin', role }, false, role === 'HR' ? '?view=employees' : '');
    await user.click(await screen.findByRole('button', { name: 'View' }));
    expect(screen.getByTestId('location')).toHaveTextContent(`/myhr/${pathRole}/document-requests/req-1`);
    await waitFor(() => expect(myHrDocumentRequestApi.get).toHaveBeenCalledWith('req-1'));
    expect(await screen.findByRole('heading', { name: 'Employment letter' })).toBeInTheDocument();
  });

  it('gives every Company Admin a direct path to the organization approval queue', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', { userId: 'itap-admin', role: 'COMPANY_ADMIN' });

    await user.click(await screen.findByRole('button', { name: 'Document Approvals' }));

    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/offer-letter-approvals');
  });

  it('Company Admin can fulfill or reject a request assigned to HR', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', { userId: 'admin', role: 'COMPANY_ADMIN' }, true);
    await screen.findByRole('heading', { name: 'Employment letter' });
    await user.upload(await screen.findByLabelText('Completed document'), new File(['pdf'], 'completed.pdf', { type: 'application/pdf' }));
    expect(screen.getByRole('button', { name: /Fulfill request|Send/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject request' })).toBeInTheDocument();
  });

  it('shows existing separation requests without acknowledgement in the title', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, requestTitle: 'Separation acknowledgement' } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    expect(await screen.findByRole('heading', { name: 'Separation' })).toBeInTheDocument();
  });

  it('assigned HR sees Submit for Admin Approval and Reject - never a direct Send', async () => {
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    expect(await screen.findByLabelText('Completed document')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit for Admin Approval' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Fulfill request|^Send$/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject request' })).toBeInTheDocument();
  });

  it('a Company Admin reviews the email draft before sending a completed document', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', { userId: 'admin', role: 'COMPANY_ADMIN' }, true);
    const file = new File(['document'], 'employment-letter.pdf', { type: 'application/pdf' });
    await user.upload(await screen.findByLabelText('Completed document'), file);
    expect(await screen.findByLabelText(/Recipient Email/)).toHaveValue('john@example.com');
    expect(screen.getByLabelText(/Subject/)).toHaveValue('Your Employment Letter');
    expect(screen.queryByRole('button', { name: 'Submit for Admin Approval' })).not.toBeInTheDocument();
  });

  it('assigned Employee sees Fulfill but never administrative Reject', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, requestedByUserId: 'admin', requestedForUserId: 'employee' } } });
    const user = userEvent.setup();
    renderPage('EMPLOYEE', { userId: 'employee', role: 'EMPLOYEE' }, true);
    await user.upload(await screen.findByLabelText('Completed document'), new File(['pdf'], 'completed.pdf', { type: 'application/pdf' }));
    expect(await screen.findByRole('button', { name: /Fulfill request|Send/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject request' })).not.toBeInTheDocument();
  });

  it('unassigned HR viewer gets detail but no transition controls', async () => {
    renderPage('HR', { userId: 'other-hr', role: 'HR' }, true);
    await screen.findByRole('heading', { name: 'Employment letter' });
    expect(screen.queryByRole('button', { name: /Fulfill request|Send/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject request' })).not.toBeInTheDocument();
  });

  it('assigned HR submits the completed document for Admin approval instead of fulfilling it', async () => {
    const user = userEvent.setup();
    const file = new File(['pdf'], 'letter.pdf', { type: 'application/pdf' });
    myHrDocumentRequestApi.submitDocumentForApproval.mockResolvedValue({ data: { data: { approvalStatus: 'PENDING_APPROVAL' } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    await user.upload(await screen.findByLabelText('Completed document'), file);
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

    await waitFor(() => expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledWith('req-1', { employeeName: 'John' }, file));
    expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
    expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.deliverApprovedDocument).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Completed document')).not.toBeInTheDocument();
  });

  it('shows HR the Admin rejection reason and lets them resubmit a corrected document', async () => {
    const user = userEvent.setup();
    const file = new File(['pdf'], 'fixed.pdf', { type: 'application/pdf' });
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, status: 'RETURNED_FOR_CORRECTION' } } });
    myHrDocumentRequestApi.getDocumentApproval.mockResolvedValue({ data: { data: { approvalStatus: 'REJECTED', rejectionReason: 'Wrong salary shown.' } } });
    myHrDocumentRequestApi.submitDocumentForApproval.mockResolvedValue({ data: { data: { approvalStatus: 'PENDING_APPROVAL' } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);

    expect(await screen.findByText('ADMIN APPROVAL REJECTED')).toBeInTheDocument();
    expect(await screen.findByText(/Wrong salary shown\./)).toBeInTheDocument();
    await user.upload(screen.getByLabelText('Completed document'), file);
    await user.click(screen.getByRole('button', { name: 'Resubmit for Approval' }));
    await waitFor(() => expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledWith('req-1', { employeeName: 'John' }, file));
  });

  it('only lets HR send the document once the Admin approved it, delivering the approved file', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, status: 'APPROVED' } } });
    myHrDocumentRequestApi.getDocumentApproval.mockResolvedValue({ data: { data: { approvalStatus: 'APPROVED' } } });
    myHrDocumentRequestApi.deliverApprovedDocument.mockResolvedValue({ data: { data: { ...pending, status: 'FULFILLED' } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);

    expect(await screen.findByText('APPROVED')).toBeInTheDocument();
    expect(screen.queryByLabelText('Completed document')).not.toBeInTheDocument();
    expect(await screen.findByLabelText(/Recipient Email/)).toHaveValue('john@example.com');
    await user.click(screen.getByRole('button', { name: 'Send to Employee' }));

    await waitFor(() => expect(myHrDocumentRequestApi.deliverApprovedDocument).toHaveBeenCalledWith('req-1', {
      recipientEmail: 'john@example.com', subject: 'Your Employment Letter', message: 'Hi John,',
    }));
    expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
  });

  it('shows HR no send control while the document waits for the Admin', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, status: 'SUBMITTED' } } });
    myHrDocumentRequestApi.getDocumentApproval.mockResolvedValue({ data: { data: { approvalStatus: 'PENDING_APPROVAL' } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);

    expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Send|Submit for Admin Approval|Resubmit/ })).not.toBeInTheDocument();
  });

  it('points the Company Admin to the approval when HR submitted a document, and never to a send', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, status: 'SUBMITTED' } } });
    renderPage('COMPANY_ADMIN', { userId: 'admin', role: 'COMPANY_ADMIN' }, true);

    expect(await screen.findByText('Waiting for your approval')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review for Approval' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/offer-letter-approvals/req-1');
  });

  it('never shows the employee an approval state or control on their own request', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, requestedByUserId: 'employee', status: 'SUBMITTED' } } });
    renderPage('EMPLOYEE', { userId: 'employee', role: 'EMPLOYEE' }, true);

    await screen.findByRole('heading', { name: 'Employment letter' });
    expect(screen.queryByText(/ADMIN APPROVAL|Waiting for your approval|Submit for Admin Approval/)).not.toBeInTheDocument();
    expect(myHrDocumentRequestApi.getDocumentApproval).not.toHaveBeenCalled();
  });

  it('assigned HR can reject from the loaded detail', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.reject.mockResolvedValue({ data: { data: { ...pending, status: 'REJECTED', rejectionReason: 'Unavailable' } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    await user.click(await screen.findByRole('button', { name: 'Reject request' }));
    await user.type(await screen.findByLabelText(/Reason for rejection/i), 'Unavailable');
    await user.click(screen.getAllByRole('button', { name: 'Reject request' }).at(-1));
    expect(myHrDocumentRequestApi.reject).toHaveBeenCalledWith('req-1', 'Unavailable');
  });

  it('returns from the detail page to the document request list', async () => {
    const user = userEvent.setup();
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    await user.click(await screen.findByRole('button', { name: 'Back' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/hr/document-requests');
  });

  it('uses request and document IDs for authorized preview', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, attachmentDocumentIds: ['doc-1'] } } });
    myHrDocumentRequestApi.getDocument.mockResolvedValue({ data: new Blob(['x']) }); window.open = vi.fn();
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    expect(myHrDocumentRequestApi.getDocument).toHaveBeenCalledWith('req-1', 'doc-1', false);
  });

  it('shows each attachment by its real file name when the backend provides one, falling back to a generic label otherwise', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: {
      ...pending, attachmentDocumentIds: ['doc-1', 'doc-2'], attachmentFileNames: ['Resume.pdf', null],
    } } });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    expect(await screen.findByText(/Resume\.pdf/)).toBeInTheDocument();
    expect(screen.getByText(/Supporting attachment 2/)).toBeInTheDocument();
  });

  it('renders DOCX preview without initiating a download', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...pending, fulfilledDocumentId: 'docx-1' } } });
    myHrDocumentRequestApi.getDocument.mockResolvedValue({
      data: new Blob(['docx'], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }),
      headers: { 'content-disposition': 'inline; filename="letter.docx"' },
    });
    renderPage('HR', { userId: 'hr', role: 'HR' }, true);
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    expect(await screen.findByLabelText('DOCX preview')).toBeInTheDocument();
    await waitFor(() => expect(renderAsync).toHaveBeenCalled());
  });
});
