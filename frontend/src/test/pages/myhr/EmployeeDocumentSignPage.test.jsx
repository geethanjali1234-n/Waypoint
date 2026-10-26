import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import EmployeeDocumentSignPage from '../../../pages/myhr/features/documentRequests/documentPreparation/EmployeeDocumentSignPage';
import DocumentRequestsPage from '../../../pages/myhr/features/documentRequests/DocumentRequestsPage';
import SignedDocumentReviewPanel from '../../../pages/myhr/features/documentRequests/documentPreparation/SignedDocumentReviewPanel';
import { getWorkflowState, setWorkflowState } from '../../../pages/myhr/features/documentRequests/documentPreparation/documentWorkflowState';
import { extractSignatureFromImage } from '../../../pages/myhr/features/signatureImageProcessing';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../pages/myhr/features/signatureImageProcessing');

const experienceLetterRequest = {
  id: 'req-1', requestTitle: 'Experience Letter', requestDescription: 'Please prepare my experience letter',
  requestedByUserId: 'employee-1', requestedByName: 'John Doe', requestedByLoginId: 'TD001',
  requestedForUserId: 'hr', requestedForName: 'Sarah', status: 'PENDING', priority: 'NORMAL',
  requestDirection: 'TO_MANAGER', requestedAt: '2026-09-17T10:00:00',
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function renderSignPage(userId = 'employee-1') {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId, role: 'EMPLOYEE' }));
  myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: experienceLetterRequest } });
  return render(
    <MemoryRouter initialEntries={['/myhr/employee/document-requests/req-1/sign']}>
      <Location />
      <Routes>
        <Route path="/myhr/employee/document-requests/:requestId" element={<output>request detail</output>} />
        <Route path="/myhr/employee/document-requests/:requestId/sign" element={<EmployeeDocumentSignPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('EmployeeDocumentSignPage', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); extractSignatureFromImage.mockResolvedValue('data:image/png;base64,ZXh0cmFjdGVkLXNpZ25hdHVyZQ=='); myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { signatureUrl: null } } }); });

  it('shows a neutral state when there is nothing awaiting the employee\'s signature', async () => {
    renderSignPage();
    expect(await screen.findByText('There is nothing awaiting your signature for this request right now.')).toBeInTheDocument();
  });

  it('accepts one signature method without requiring a signed-document upload', async () => {
    setWorkflowState('req-1', {
      stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }],
    });
    const user = userEvent.setup();
    renderSignPage();

    await screen.findByText('DOCUMENT TO REVIEW');
    expect(screen.getByText('Experience Letter for John Doe')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeDisabled();

    await user.click(screen.getByRole('tab', { name: 'Upload image' }));
    const uploadedPhoto = new File(['sig'], 'signature.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Signature image'), uploadedPhoto);

    // The extracted signature is staged for confirmation, not submitted
    // straight away - Submit stays disabled until the employee confirms it.
    expect(await screen.findByAltText('Detected signature, pending confirmation'))
      .toHaveAttribute('src', 'data:image/png;base64,ZXh0cmFjdGVkLXNpZ25hdHVyZQ==');
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeDisabled();

    // The uploaded photo is only ever passed through extraction - the raw
    // photo itself must never become the submitted signature.
    expect(extractSignatureFromImage).toHaveBeenCalledWith(uploadedPhoto);

    await user.click(screen.getByRole('button', { name: 'Use This Signature' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeEnabled());
    expect(screen.queryByAltText('Detected signature, pending confirmation')).not.toBeInTheDocument();
    expect(screen.getByAltText('Your captured signature')).toHaveAttribute('src', 'data:image/png;base64,ZXh0cmFjdGVkLXNpZ25hdHVyZQ==');

    myHrDocumentRequestApi.submitSignedResponse.mockResolvedValue({ data: { data: { ...experienceLetterRequest, employeeSignatureDocumentId: 'signature-1', signatureSubmittedAt: '2026-09-22T00:00:00' } } });

    await user.click(screen.getByRole('button', { name: 'Submit to HR' }));
    expect(await screen.findByText(/You submitted your signature response/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit to HR' })).not.toBeInTheDocument();
    expect(getWorkflowState('req-1').stage).toBe('SIGNED_RETURNED');

    const [, submittedSignatureFile] = myHrDocumentRequestApi.submitSignedResponse.mock.calls[0];
    expect(submittedSignatureFile.signature).not.toBeNull();
    expect(await submittedSignatureFile.signature.text()).not.toBe(await uploadedPhoto.text());
  });

  it('preselects the saved profile signature while still allowing another signature method', async () => {
    setWorkflowState('req-1', { stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true, previewBlocks: [] });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { signatureUrl: '/uploads/user-signatures/employee-1/signature.png' } } });
    renderSignPage();

    expect(await screen.findByAltText('Your captured signature')).toHaveAttribute('src', expect.stringContaining('/uploads/user-signatures/employee-1/signature.png'));
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeEnabled();
    expect(screen.getByRole('tab', { name: 'Draw' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Type' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Upload image' })).toBeInTheDocument();
  });

  it('lets the same photo be re-selected after extraction fails, instead of silently doing nothing on retry', async () => {
    extractSignatureFromImage.mockResolvedValueOnce(null).mockResolvedValueOnce('data:image/png;base64,ZXh0cmFjdGVkLXNpZ25hdHVyZQ==');
    setWorkflowState('req-1', {
      stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }],
    });
    const user = userEvent.setup();
    renderSignPage();

    await screen.findByText('DOCUMENT TO REVIEW');
    await user.click(screen.getByRole('tab', { name: 'Upload image' }));
    const samePhoto = new File(['sig'], 'signature.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Signature image'), samePhoto);
    expect(await screen.findByText(/Couldn't detect a signature in that image/)).toBeInTheDocument();

    // Re-selecting the exact same file (a real browser fires no change event
    // for this unless the input was reset after the first selection) must
    // still trigger extraction again, not silently do nothing.
    await user.upload(screen.getByLabelText('Signature image'), samePhoto);
    await waitFor(() => expect(extractSignatureFromImage).toHaveBeenCalledTimes(2));
    expect(await screen.findByAltText('Detected signature, pending confirmation')).toBeInTheDocument();
  });

  it('discards the detected signature and lets the employee pick a different image when they reject it', async () => {
    setWorkflowState('req-1', {
      stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }],
    });
    const user = userEvent.setup();
    renderSignPage();

    await screen.findByText('DOCUMENT TO REVIEW');
    await user.click(screen.getByRole('tab', { name: 'Upload image' }));
    await user.upload(screen.getByLabelText('Signature image'), new File(['sig'], 'signature.png', { type: 'image/png' }));
    await screen.findByAltText('Detected signature, pending confirmation');

    await user.click(screen.getByRole('button', { name: 'Choose a Different Image' }));
    expect(screen.queryByAltText('Detected signature, pending confirmation')).not.toBeInTheDocument();
    expect(screen.queryByAltText('Your captured signature')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeDisabled();
  });

  it("shows an error and never enables submit when no signature can be detected in the uploaded image", async () => {
    extractSignatureFromImage.mockResolvedValue(null);
    setWorkflowState('req-1', {
      stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }],
    });
    const user = userEvent.setup();
    renderSignPage();

    await screen.findByText('DOCUMENT TO REVIEW');
    await user.click(screen.getByRole('tab', { name: 'Upload image' }));
    await user.upload(screen.getByLabelText('Signature image'), new File(['blank'], 'blank.png', { type: 'image/png' }));

    expect(await screen.findByText(/Couldn't detect a signature in that image/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeDisabled();
    expect(myHrDocumentRequestApi.submitSignedResponse).not.toHaveBeenCalled();
  });

  it('accepts an already-signed document without requiring an in-app signature', async () => {
    setWorkflowState('req-1', {
      stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }],
    });
    const user = userEvent.setup();
    renderSignPage();

    await screen.findByText('DOCUMENT TO REVIEW');
    await user.upload(screen.getByLabelText('Signed document'), new File(['doc'], 'signed-letter.pdf', { type: 'application/pdf' }));
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeEnabled();
  });

  it('cannot be submitted twice - remounting after submission shows the read-only state, not the form', async () => {
    setWorkflowState('req-1', {
      stage: 'SIGNED_RETURNED', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }],
      signedDocumentFileName: 'signed-letter.pdf', submittedAt: '2026-01-02T00:00:00.000Z',
    });
    renderSignPage();
    expect(await screen.findByText(/You submitted your signature response/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit to HR' })).not.toBeInTheDocument();
    expect(screen.queryByText('DOCUMENT TO REVIEW')).not.toBeInTheDocument();
  });
});

describe('Document request detail page - sign/return lifecycle', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); });

  it('lets the employee requester navigate to the sign page once HR has sent a signature-required document', async () => {
    setWorkflowState('req-1', { stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, requiresSignedReturn: true });
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'employee-1', role: 'EMPLOYEE' }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: experienceLetterRequest } });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/myhr/employee/document-requests/req-1']}>
        <Location />
        <Routes><Route path="/myhr/employee/document-requests/:requestId" element={<DocumentRequestsPage role="EMPLOYEE" />} /></Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole('button', { name: 'Review & Sign' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/employee/document-requests/req-1/sign');
  });

  it('shows HR the signed-copy review panel and completing the process clears it', async () => {
    setWorkflowState('req-1', {
      stage: 'SIGNED_RETURNED', requiresEmployeeSignature: true, requiresSignedReturn: true,
      previewBlocks: [{ type: 'p', text: 'Experience Letter for John Doe' }], signedDocumentFileName: 'signed-letter.pdf',
    });
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'hr', role: 'HR' }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: experienceLetterRequest } });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/myhr/hr/document-requests/req-1']}>
        <Routes><Route path="/myhr/hr/document-requests/:requestId" element={<DocumentRequestsPage role="HR" />} /></Routes>
      </MemoryRouter>,
    );
    // A pre-merge "Experience Letter" request resolves to the merged type's label.
    expect(await screen.findByText('Experience and Relieving Letter - Signature Response Submitted')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Complete Process' }));
    expect(await screen.findByText('Experience and Relieving Letter completed')).toBeInTheDocument();
    expect(getWorkflowState('req-1').stage).toBe('COMPLETED');
  });
});

describe('SignedDocumentReviewPanel', () => {
  it('offers HR the completed signed offer letter', async () => {
    const user = userEvent.setup();
    const onViewSignature = vi.fn();
    render(<SignedDocumentReviewPanel
      request={{ id: 'req-no-blob', requestedByName: 'John Doe', signedResponseDocumentId: 'signed-document-1' }}
      workflowType={{ label: 'Experience Letter' }}
      workflowState={{ previewBlocks: [] }}
      onComplete={() => {}}
      onViewOriginal={onViewSignature}
    />);
    await user.click(screen.getByRole('button', { name: 'View Signed Document' }));
    expect(onViewSignature).toHaveBeenCalledOnce();
  });
});


describe('Persisted offer letter lifecycle', () => {
  const offer = { ...experienceLetterRequest, documentType: 'OFFER_LETTER', requestTitle: 'Offer Letter', status: 'FULFILLED', fulfilledDocumentId: 'original' };
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); });
  it('lets an employee sign an offer in a fresh browser without workflow storage', async () => {
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'employee-1', role: 'EMPLOYEE' }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: offer } });
    render(<MemoryRouter initialEntries={['/myhr/employee/document-requests/req-1/sign']}><Routes>
      <Route path="/myhr/employee/document-requests/:requestId/sign" element={<EmployeeDocumentSignPage />} />
    </Routes></MemoryRouter>);
    expect(await screen.findByRole('button', { name: 'View Offer Letter' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit to HR' })).toBeDisabled();
    expect(screen.queryByLabelText('Signed document')).not.toBeInTheDocument();
  });
  it('lets an HR requester review and sign an offer sent by their reporting manager', async () => {
    const hrOffer = { ...offer, requestedByUserId: 'hr', requestedByName: 'Sarah', requestedForUserId: 'admin', requestedForName: 'Admin' };
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'hr', role: 'HR' }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: hrOffer } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/myhr/hr/document-requests/req-1']}><Location /><Routes>
      <Route path="/myhr/hr/document-requests/:requestId" element={<DocumentRequestsPage role="HR" />} />
      <Route path="/myhr/hr/document-requests/:requestId/sign" element={<EmployeeDocumentSignPage role="HR" />} />
    </Routes></MemoryRouter>);

    expect(await screen.findByText('Pending Signature')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Review & Sign' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/hr/document-requests/req-1/sign');
    expect(await screen.findByRole('button', { name: 'Submit to your reporting manager' })).toBeDisabled();
  });
  it('lets a Company Admin requester reach the same offer signing flow', async () => {
    const adminOffer = { ...offer, requestedByUserId: 'admin', requestedByName: 'Admin', requestedForUserId: 'hr', requestedForName: 'Sarah' };
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'admin', role: 'COMPANY_ADMIN' }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: adminOffer } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/myhr/admin/document-requests/req-1']}><Location /><Routes>
      <Route path="/myhr/admin/document-requests/:requestId" element={<DocumentRequestsPage role="COMPANY_ADMIN" />} />
      <Route path="/myhr/admin/document-requests/:requestId/sign" element={<EmployeeDocumentSignPage role="COMPANY_ADMIN" />} />
    </Routes></MemoryRouter>);

    expect(await screen.findByRole('button', { name: 'Review & Sign' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review & Sign' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/document-requests/req-1/sign');
    expect(await screen.findByRole('button', { name: 'Submit to HR' })).toBeDisabled();
  });
  it('does not expose signing controls to a user who did not request the offer', async () => {
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'other-hr', role: 'HR' }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: offer } });
    render(<MemoryRouter initialEntries={['/myhr/hr/document-requests/req-1/sign']}><Routes>
      <Route path="/myhr/hr/document-requests/:requestId/sign" element={<EmployeeDocumentSignPage role="HR" />} />
    </Routes></MemoryRouter>);

    expect(await screen.findByText('Only the user who requested this document can sign it.')).toBeInTheDocument();
    expect(screen.queryByText('DOCUMENT TO REVIEW')).not.toBeInTheDocument();
  });
  it.each(['HR', 'EMPLOYEE'])('opens the complete signed PDF for %s despite stale local state', async (role) => {
    setWorkflowState('req-1', { stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true });
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: role === 'HR' ? 'hr' : 'employee-1', role }));
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...offer, signedResponseDocumentId: 'complete-offer' } } });
    myHrDocumentRequestApi.getDocument.mockResolvedValue({ data: new Blob(['pdf'], { type: 'application/pdf' }) });
    vi.stubGlobal('open', vi.fn());
    URL.createObjectURL = vi.fn(() => 'blob:complete');
    URL.revokeObjectURL = vi.fn();
    render(<MemoryRouter initialEntries={['/myhr/employee/document-requests/req-1']}><Routes>
      <Route path="/myhr/employee/document-requests/:requestId" element={<DocumentRequestsPage role={role} />} />
    </Routes></MemoryRouter>);
    await userEvent.click(await screen.findByRole('button', { name: 'Preview' }));
    expect(myHrDocumentRequestApi.getDocument).toHaveBeenCalledWith('req-1', 'complete-offer', false);
    expect(screen.queryByRole('button', { name: 'Review & Sign' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /View.*Signature/ })).not.toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});
