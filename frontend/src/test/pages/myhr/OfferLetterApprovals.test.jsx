import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { UiVariantContext } from '../../../context/uiVariant';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import { getMyHrSession, saveMyHrAuthentication } from '../../../auth/myHrSession';
import MyHrRoleLandingPage from '../../../pages/myhr/MyHrRoleLandingPage';
import MyHrNavigation from '../../../pages/myhr/components/MyHrNavigation';
import OfferLetterApprovalCard from '../../../pages/myhr/components/OfferLetterApprovalCard';
import OfferLetterApprovalsPage from '../../../pages/myhr/features/documentRequests/OfferLetterApprovalsPage';
import { CATEGORIES, ROLES, navigationRegistry } from '../../../services/navigation/navigationRegistry';
import { documentRequestStatus } from '../../../pages/myhr/features/documentRequests/offerLetterState';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../pages/myhr/MyHrShell', () => ({ default: ({ children }) => <div data-testid="shell"><MyHrNavigation role={getMyHrSession()?.role} name="Workspace" />{children}</div> }));

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

const item = (overrides = {}) => ({
  requestId: 'req-1', approvalStatus: 'PENDING_APPROVAL', employeeName: 'Venkata Naga Sai Vasavi Anna', designation: 'Software Developer',
  offerType: 'Paid', employmentCategory: 'Full Time', joiningDate: '2026-10-15', issueDate: '2026-10-01', payRate: '95000 per hour',
  workLocation: 'Austin, TX', rolesAndResponsibilities: 'Build and maintain software.', submittedByUserId: 'hr-1', submittedByName: 'Sarah HR',
  submittedAt: '2026-10-02T09:30:00', reviewedByName: null, reviewedAt: null, rejectionReason: null, documentId: 'approval-doc',
  canSubmit: false, canApprove: true, canSend: false, ...overrides,
});

const listResponse = (items, counts = {}) => ({
  data: { data: { pending: items.filter((entry) => entry.approvalStatus === 'PENDING_APPROVAL').length, approved: 12, rejected: 2, items, ...counts } },
});

function renderApprovals(path = '/myhr/admin/offer-letter-approvals') {
  saveMyHrAuthentication('signed-jwt', { loginId: 'TDADMIN', role: 'COMPANY_ADMIN' });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <Routes>
        <Route path="/myhr/admin/offer-letter-approvals" element={<OfferLetterApprovalsPage />} />
        <Route path="/myhr/admin/offer-letter-approvals/:requestId" element={<OfferLetterApprovalsPage />} />
        <Route path="/myhr/admin/dashboard" element={<p>Admin dashboard</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { organizationName: 'Tardus' } } });
  myHrDocumentRequestApi.getDocument.mockResolvedValue({ data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }) });
});

describe('Admin dashboard approval card', () => {
  const renderCard = () => render(<MemoryRouter><Location /><OfferLetterApprovalCard /></MemoryRouter>);

  it('shows Document Approvals with the pending count and a Review Approvals action', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue({ data: { data: { pending: 3, approved: 12, rejected: 2, items: [] } } });
    const user = userEvent.setup();
    renderCard();

    const card = await screen.findByLabelText('Document Approvals');
    expect(within(card).getByText('3 Pending Approval')).toBeInTheDocument();
    expect(within(card).getByText(/3 documents require your approval/)).toBeInTheDocument();
    expect(card).toHaveClass('offer-approval-card--attention');
    await user.click(within(card).getByRole('button', { name: 'Review Approvals' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/offer-letter-approvals');
  });

  it('uses the singular for one pending approval', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue({ data: { data: { pending: 1, approved: 0, rejected: 0, items: [] } } });
    renderCard();

    expect(await screen.findByText('1 Pending Approval')).toBeInTheDocument();
    expect(screen.getByText(/1 document requires your approval/)).toBeInTheDocument();
  });

  it('says nothing is waiting, and offers the approval history, when there are no pending approvals', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue({ data: { data: { pending: 0, approved: 4, rejected: 0, items: [] } } });
    renderCard();

    expect(await screen.findByText('No documents are currently waiting for your approval.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View Approval History' })).toBeInTheDocument();
    expect(screen.queryByText(/Pending Approval/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Document Approvals')).not.toHaveClass('offer-approval-card--attention');
  });

  it('stays hidden when the count cannot be loaded', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockRejectedValue({ response: { status: 403 } });
    renderCard();

    await waitFor(() => expect(myHrDocumentRequestApi.listOfferLetterApprovals).toHaveBeenCalled());
    expect(screen.queryByLabelText('Document Approvals')).not.toBeInTheDocument();
  });
});

describe('Document Approvals are Company Admin only', () => {
  const renderDashboard = (role) => {
    saveMyHrAuthentication('signed-jwt', { loginId: 'TD001', role });
    return render(<UiVariantContext.Provider value={{ activeVariant: 'v1' }}><MemoryRouter><MyHrRoleLandingPage role={role} /></MemoryRouter></UiVariantContext.Provider>);
  };

  it('shows pending document approvals on the dashboard alongside existing navigation', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue({ data: { data: { pending: 2, approved: 0, rejected: 0, items: [] } } });
    renderDashboard('COMPANY_ADMIN');

    expect(await screen.findByRole('link', { name: 'Review documents' })).toHaveAttribute('href', '/myhr/admin/offer-letter-approvals?status=PENDING_APPROVAL');
    expect(within(screen.getByText('Documents awaiting approval').closest('li')).getByText('2')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Needs you today' })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Organization Settings' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'Employee Management' })).toBeInTheDocument();
  });

  it.each(['HR', 'EMPLOYEE'])('never shows or loads the approval card for %s', async (role) => {
    renderDashboard(role);

    await waitFor(() => expect(myHrEmployeeApi.getMine).toHaveBeenCalled());
    expect(screen.queryByLabelText('Document Approvals')).not.toBeInTheDocument();
    expect(screen.queryByText(/Document Approvals/)).not.toBeInTheDocument();
    expect(myHrDocumentRequestApi.listOfferLetterApprovals).not.toHaveBeenCalled();
  });

  it('offers Document Approvals in navigation to a Company Admin only', () => {
    const adminAction = navigationRegistry.getActions(ROLES.COMPANY_ADMIN).find((action) => action.id === 'offer-letter-approvals');
    expect(adminAction.path).toBe('/myhr/admin/offer-letter-approvals');
    expect(adminAction.category).toBe(CATEGORIES.ADMINISTRATION);
    expect(navigationRegistry.getActions(ROLES.HR).map((action) => action.id)).not.toContain('offer-letter-approvals');
    expect(navigationRegistry.getActions(ROLES.EMPLOYEE).map((action) => action.id)).not.toContain('offer-letter-approvals');
  });
});

describe('Document Approvals page', () => {
  it('shows the summary, and pending letters first with a clear PENDING APPROVAL status and a Review action', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue(listResponse([
      item(), item({ requestId: 'req-2', approvalStatus: 'APPROVED', employeeName: 'Already Approved', canApprove: false }),
    ]));
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item() } });
    const user = userEvent.setup();
    renderApprovals();

    expect(await screen.findByRole('heading', { name: 'Document Approvals' })).toBeInTheDocument();
    expect(screen.getByText('Review and approve documents submitted by HR before they are sent to employees.')).toBeInTheDocument();
    const filters = screen.getByRole('group', { name: 'Filter approvals' });
    expect(within(filters).getByRole('button', { name: 'Pending approval 1' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(filters).getByRole('button', { name: 'Approved 12' })).toBeInTheDocument();
    expect(within(filters).getByRole('button', { name: 'Rejected 2' })).toBeInTheDocument();

    // Pending is the default view, so only the letter that needs a decision is listed.
    const pending = screen.getByRole('heading', { name: 'Venkata Naga Sai Vasavi Anna' }).closest('article');
    expect(within(pending).getByText('Software Developer')).toBeInTheDocument();
    expect(within(pending).getByText('Paid · Full Time')).toBeInTheDocument();
    expect(within(pending).getByText(/Submitted by: Sarah HR/)).toBeInTheDocument();
    expect(within(pending).getByText(/Joining Date:/)).toBeInTheDocument();
    expect(within(pending).getByText('PENDING APPROVAL')).toBeInTheDocument();
    expect(screen.queryByText('Already Approved')).not.toBeInTheDocument();

    await user.click(within(pending).getByRole('button', { name: 'Review Offer Letter' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/offer-letter-approvals/req-1');
  });

  it('shows the empty state when nothing is waiting and lets the Admin see the history', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue(listResponse([
      item({ approvalStatus: 'APPROVED', canApprove: false }),
    ], { pending: 0 }));
    const user = userEvent.setup();
    renderApprovals();

    await waitFor(() => expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true'));
    expect(screen.getByRole('heading', { name: 'Venkata Naga Sai Vasavi Anna' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Pending approval/ }));
    expect(screen.getByText('No documents are currently waiting for your approval.')).toBeInTheDocument();
  });

  it('shows a clear error when the approvals cannot be loaded', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockRejectedValue({ response: { data: { message: 'You do not have access to this resource' } } });
    renderApprovals();

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have access to this resource');
  });
});

describe('Reviewing a document that is not an offer letter', () => {
  const verification = (overrides = {}) => item({
    documentType: 'EMPLOYMENT_VERIFICATION', documentTitle: 'Employment Verification', designation: null, offerType: null, employmentCategory: null,
    payRate: null, rolesAndResponsibilities: null, fileName: 'verification.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    ...overrides,
  });

  it('lists it with its own name and a Review Document action', async () => {
    myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue(listResponse([verification()]));
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: verification() } });
    const user = userEvent.setup();
    renderApprovals();

    const entry = (await screen.findByRole('heading', { name: 'Venkata Naga Sai Vasavi Anna' })).closest('article');
    expect(within(entry).getByText('Employment Verification')).toBeInTheDocument();
    await user.click(within(entry).getByRole('button', { name: 'Review Document' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/offer-letter-approvals/req-1');
  });

  it('shows a download link instead of an embedded PDF for a Word document, and approves it', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: verification() } });
    myHrDocumentRequestApi.approveOfferLetter.mockResolvedValue({ data: { data: verification({ approvalStatus: 'APPROVED', canApprove: false }) } });
    const user = userEvent.setup();
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    expect(await screen.findByRole('heading', { name: 'Review Document' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /Download verification\.docx/ })).toBeInTheDocument();
    expect(screen.queryByTitle(/PDF/)).not.toBeInTheDocument();
    expect(screen.getByText('Employment Verification', { selector: 'dd' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Approve Document' }));
    expect(myHrDocumentRequestApi.approveOfferLetter).toHaveBeenCalledWith('req-1');
  });

  it('asks for a reason before rejecting a document', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: verification() } });
    const user = userEvent.setup();
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    await user.click(await screen.findByRole('button', { name: /Reject (Offer Letter|Document)/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button', { name: /Reject (Offer Letter|Document)/ })).toBeDisabled();
  });
});

describe('Offer Letter review screen', () => {
  it('opens from the notification link and shows the real letter details and PDF for review', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item() } });
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    expect(await screen.findByRole('heading', { name: 'Venkata Naga Sai Vasavi Anna' })).toBeInTheDocument();
    expect(myHrDocumentRequestApi.getOfferLetterApproval).toHaveBeenCalledWith('req-1');
    for (const text of ['Software Developer', 'Paid', 'Full Time', 'Austin, TX', '95000 per hour', 'Sarah HR', 'Build and maintain software.']) {
      expect(screen.getByText(text)).toBeInTheDocument();
    }
    expect(screen.getByText('PENDING APPROVAL')).toBeInTheDocument();
    // The exact submitted PDF is shown - reviewing never regenerates it.
    await waitFor(() => expect(screen.getByTitle('Offer letter PDF')).toBeInTheDocument());
    expect(myHrDocumentRequestApi.getDocument).toHaveBeenCalledWith('req-1', 'approval-doc');
    expect(screen.getByRole('button', { name: 'Approve Offer Letter' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Reject (Offer Letter|Document)/ })).toBeInTheDocument();
  });

  it('approves the letter and shows it as approved, without sending anything to the employee', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item() } });
    myHrDocumentRequestApi.approveOfferLetter.mockResolvedValue({ data: { data: item({ approvalStatus: 'APPROVED', canApprove: false, reviewedByName: 'Tardus Admin' }) } });
    const user = userEvent.setup();
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    await user.click(await screen.findByRole('button', { name: 'Approve Offer Letter' }));

    expect(myHrDocumentRequestApi.approveOfferLetter).toHaveBeenCalledWith('req-1');
    expect(await screen.findByText(/Approved by Tardus Admin\. HR can now send it to the employee\./)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve Offer Letter' })).not.toBeInTheDocument();
    expect(myHrDocumentRequestApi.deliverOfferLetter).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.emailOfferLetter).not.toHaveBeenCalled();
  });

  it('requires a rejection reason, then rejects with it and shows the rejection', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item() } });
    myHrDocumentRequestApi.rejectOfferLetter.mockResolvedValue({
      data: { data: item({ approvalStatus: 'REJECTED', canApprove: false, rejectionReason: 'The compensation is wrong.' }) },
    });
    const user = userEvent.setup();
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    await user.click(await screen.findByRole('button', { name: /Reject (Offer Letter|Document)/ }));
    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: /Reject (Offer Letter|Document)/ });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Reason for rejection/), '   ');
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Reason for rejection/), 'The compensation is wrong.');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => expect(myHrDocumentRequestApi.rejectOfferLetter).toHaveBeenCalledWith('req-1', 'The compensation is wrong.'));
    expect(await screen.findByText(/The compensation is wrong\./)).toBeInTheDocument();
    expect(screen.getAllByText('REJECTED').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Approve Offer Letter' })).not.toBeInTheDocument();
  });

  it('cancelling the rejection dialog rejects nothing', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item() } });
    const user = userEvent.setup();
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    await user.click(await screen.findByRole('button', { name: /Reject (Offer Letter|Document)/ }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));

    expect(myHrDocumentRequestApi.rejectOfferLetter).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.approveOfferLetter).not.toHaveBeenCalled();
  });

  it('shows no approve or reject actions for a letter that is not waiting for approval', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item({ approvalStatus: 'APPROVED', canApprove: false }) } });
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    expect(await screen.findByRole('heading', { name: 'Venkata Naga Sai Vasavi Anna' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve Offer Letter' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reject (Offer Letter|Document)/ })).not.toBeInTheDocument();
  });

  it('shows the server message and reloads when the decision is refused (for example it was already decided)', async () => {
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue({ data: { data: item() } });
    myHrDocumentRequestApi.approveOfferLetter.mockRejectedValue({ response: { data: { message: 'This offer letter has already been approved, so it cannot be approved again.' } } });
    const user = userEvent.setup();
    renderApprovals('/myhr/admin/offer-letter-approvals/req-1');

    await user.click(await screen.findByRole('button', { name: 'Approve Offer Letter' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This offer letter has already been approved, so it cannot be approved again.');
    await waitFor(() => expect(myHrDocumentRequestApi.getOfferLetterApproval).toHaveBeenCalledTimes(2));
  });
});

describe('Offer letter status labels', () => {
  const offer = (status, extra = {}) => ({ documentType: 'OFFER_LETTER', status, requestedByUserId: 'employee-1', viewerUserId: 'hr-1', ...extra });

  it('shows HR and the Admin the approval workflow status', () => {
    expect(documentRequestStatus(offer('SUBMITTED'), 'HR')).toBe('Pending Admin Approval');
    expect(documentRequestStatus(offer('APPROVED'), 'HR')).toBe('Approved');
    expect(documentRequestStatus(offer('RETURNED_FOR_CORRECTION'), 'COMPANY_ADMIN')).toBe('Approval Rejected');
  });

  it('never shows the employee any approval state - their request just stays Pending', () => {
    for (const status of ['SUBMITTED', 'APPROVED', 'RETURNED_FOR_CORRECTION']) {
      expect(documentRequestStatus(offer(status, { viewerUserId: 'employee-1' }), 'EMPLOYEE')).toBe('Pending');
    }
  });
});


it('keeps the pending filter from a dashboard attention link even when its count is zero', async () => {
  myHrDocumentRequestApi.listOfferLetterApprovals.mockResolvedValue(listResponse([item({ approvalStatus: 'APPROVED' })]));
  renderApprovals('/myhr/admin/offer-letter-approvals?status=PENDING_APPROVAL');
  expect(await screen.findByText('No documents are currently waiting for your approval.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'View Offer Letter' })).not.toBeInTheDocument();
});
