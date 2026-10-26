import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import DocumentRequestsPage from '../../../pages/myhr/features/documentRequests/DocumentRequestsPage';
import DocumentPreparationPage from '../../../pages/myhr/features/documentRequests/documentPreparation/DocumentPreparationPage';
import { GENERIC_DOCUMENT_WORKFLOW_TYPES } from '../../../pages/myhr/features/documentRequests/documentWorkflowTypes';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../pages/myhr/features/documentRequests/SignatureField', () => ({ default: ({ onChange }) => <button type="button" onClick={() => onChange('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==')}>Use test signature</button> }));

async function addRequiredSignature(user) {
  const control = screen.queryByRole('button', { name: 'Use test signature' });
  if (control) await user.click(control);
}


function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

function requestFor(requestTitle) {
  return {
    id: 'req-1', requestTitle, requestDescription: 'Please prepare this document',
    requestedByUserId: 'employee-1', requestedByName: 'John Doe', requestedByLoginId: 'TD001',
    requestedForUserId: 'hr', requestedForName: 'Sarah', status: 'PENDING', priority: 'NORMAL',
    requestDirection: 'TO_MANAGER', requestedAt: '2026-09-17T10:00:00',
  };
}

function renderApp(initialPath) {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'hr', role: 'HR' }));
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Location />
      <Routes>
        <Route path="/myhr/hr/document-requests/:requestId" element={<DocumentRequestsPage role="HR" />} />
        {GENERIC_DOCUMENT_WORKFLOW_TYPES.map((type) => (
          <Route key={type.id} path={`/myhr/hr/document-requests/:requestId/${type.routeSegment}`}
            element={<DocumentPreparationPage role="HR" documentTypeId={type.id} />} />
        ))}
      </Routes>
    </MemoryRouter>,
  );
}

// One representative required-field fill per generic document type, plus the
// info needed to drive/verify the flow through the shared shell.
const GENERIC_CASES = [
  {
    id: 'EMPLOYMENT_VERIFICATION', requestTitle: 'Employment Verification', routeSegment: 'employment-verification',
    prepareLabel: 'Prepare Employment Verification', templateName: 'Employment Verification Letter Template',
    fill: async (user) => {
      await user.selectOptions(screen.getByLabelText(/^Salutation/), 'MR');
      await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
      await user.type(screen.getByLabelText(/^Role Description/), 'Software Development and related consulting services');
    },
  },
  {
    id: 'EXPERIENCE_LETTER', requestTitle: 'Experience and Relieving Letter', routeSegment: 'experience-relieving-letter',
    prepareLabel: 'Prepare Experience and Relieving Letter', templateName: 'Experience and Relieving Letter Template',
    fill: async (user) => {
      await user.selectOptions(screen.getByLabelText(/^Salutation/), 'MR');
      await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
      await user.type(screen.getByLabelText(/^Employment Start Date/), '2023-01-01');
      await user.type(screen.getByLabelText(/^Last Working Date/), '2026-01-01');
    },
  },
  {
    id: 'SALARY_VERIFICATION', requestTitle: 'Salary Verification Letter', routeSegment: 'salary-verification',
    prepareLabel: 'Prepare Salary Verification', templateName: 'Salary Verification Letter Template',
    fill: async (user) => {
      await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
      await user.selectOptions(screen.getByLabelText(/^Salutation/), 'MS');
      await user.type(screen.getByLabelText(/^Annual Salary/), '95,000');
      await user.type(screen.getByLabelText(/^Employment Start Date/), '2023-01-01');
    },
  },
  {
    id: 'EMPLOYMENT_LETTER', requestTitle: 'Employment Letter', routeSegment: 'employment-letter',
    prepareLabel: 'Prepare Employment Letter', templateName: 'Standard Employment Letter Template',
    fill: async (user) => {
      await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
      await user.type(screen.getByLabelText(/^Employment Start Date/), '2024-01-15');
      await user.selectOptions(screen.getByLabelText(/^Employment Status/), 'ACTIVE');
      await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
    },
  },
  {
    id: 'I983', requestTitle: 'Signed I-983', routeSegment: 'signed-i983',
    prepareLabel: 'Prepare Signed I-983', templateName: 'Standard Signed I-983 Template',
    fill: async (user) => {
      await user.type(screen.getByLabelText(/^Job Title \/ Designation/), 'Software Engineer');
      await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
      await user.type(screen.getByLabelText(/^Training Start Date/), '2024-01-15');
    },
  },
  {
    id: 'OTHER', requestTitle: 'Other Employment Document', routeSegment: 'other-employment-document',
    prepareLabel: 'Prepare Document', templateName: 'Custom Employment Document Template',
    fill: async (user) => {
      await user.type(screen.getByLabelText(/^Document Title/), 'Address Confirmation Letter');
      await user.type(screen.getByLabelText(/^Document Details/), 'This confirms the employee resides at the address on file.');
    },
  },
];

describe('Generic document preparation workflow', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { organizationName: 'Tardus Inc.' } } });
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: { reportingManager: null } } });
    myHrDocumentRequestApi.getFulfillmentEmailDraft.mockResolvedValue({ data: { data: {
      recipientEmail: 'john.doe@example.com', subject: 'Your document from Waypoint', message: 'Please find your document attached.',
    } } });
    myHrDocumentRequestApi.fulfill.mockResolvedValue({ data: { data: {} } });
    myHrDocumentRequestApi.getDocumentApproval.mockResolvedValue({ data: { data: { approvalStatus: 'NONE' } } });
    myHrDocumentRequestApi.submitDocumentForApproval.mockResolvedValue({ data: { data: { approvalStatus: 'PENDING_APPROVAL' } } });
    myHrDocumentRequestApi.deliverApprovedDocument.mockResolvedValue({ data: { data: {} } });
    myHrDocumentRequestApi.getDocument.mockResolvedValue({ data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }) });
  });

  /** Opens a document in the given approval state, as the server would report it. */
  function openInApprovalState(approvalStatus, routeSegment, title, extra = {}) {
    const requestStatus = { PENDING_APPROVAL: 'SUBMITTED', APPROVED: 'APPROVED', REJECTED: 'RETURNED_FOR_CORRECTION' }[approvalStatus];
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...requestFor(title), status: requestStatus } } });
    myHrDocumentRequestApi.getDocumentApproval.mockResolvedValue({ data: { data: { approvalStatus, documentId: 'approval-doc', contentType: 'application/pdf', ...extra } } });
    return renderApp(`/myhr/hr/document-requests/req-1/${routeSegment}`);
  }

  it.each(GENERIC_CASES)(
    'runs $requestTitle through request -> prepare -> details -> template -> submit for Admin approval, delivering nothing',
    async ({ requestTitle, routeSegment, prepareLabel, templateName, fill }) => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor(requestTitle) } });
      renderApp('/myhr/hr/document-requests/req-1');

      await screen.findByRole('heading', { name: requestTitle });
      await user.click(screen.getByRole('button', { name: prepareLabel }));
      expect(screen.getByTestId('location')).toHaveTextContent(`/myhr/hr/document-requests/req-1/${routeSegment}`);
      await waitFor(() => expect(myHrDocumentRequestApi.get).toHaveBeenCalledWith('req-1'));

      // Request context is reloaded from the real API, not re-entered by HR.
      expect(await screen.findByText('TD001 - John Doe')).toBeInTheDocument();
      expect(screen.getByLabelText(/^Employee Name/)).toHaveValue('John Doe');

      await fill(user);
      await user.type(screen.getByLabelText(/^Employee Email/), 'john.doe@example.com');
      await addRequiredSignature(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

      expect(await screen.findByText('SELECTED TEMPLATE')).toBeInTheDocument();
      expect(screen.getByText(templateName)).toBeInTheDocument();

      // HR can never send directly: the generated PDF goes to the Company Admin for approval.
      expect(screen.queryByRole('button', { name: /^Send/ })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: /^Submit for (Admin Approval|Signature)$/ }));

      expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
      expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledWith('req-1',
        expect.objectContaining({ employeeName: 'John Doe', formJson: expect.any(String) }), expect.any(File));
      expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
      expect(myHrDocumentRequestApi.deliverApprovedDocument).not.toHaveBeenCalled();
    },
  );

  it.each(['LCA', 'PERM', 'I-140'])(
    '%s is upload-only: HR uploads the existing document, which goes to the Admin for approval',
    async (requestTitle) => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor(requestTitle) } });
      renderApp('/myhr/hr/document-requests/req-1');

      await screen.findByRole('heading', { name: requestTitle });
      expect(screen.queryByRole('button', { name: `Prepare ${requestTitle}` })).not.toBeInTheDocument();
      expect(GENERIC_DOCUMENT_WORKFLOW_TYPES.some((type) => type.requestTitle === requestTitle)).toBe(false);

      expect(screen.queryByRole('button', { name: 'Upload & Share' })).not.toBeInTheDocument();
      const submitButton = screen.getByRole('button', { name: /^Submit for (Admin Approval|Signature)$/ });
      expect(submitButton).toBeDisabled();
      const file = new File(['pdf'], `${requestTitle}.pdf`, { type: 'application/pdf' });
      await user.upload(screen.getByLabelText(`${requestTitle} document`), file);
      await user.click(submitButton);

      // The upload goes to the Company Admin for approval; nothing is shared with the employee yet.
      await waitFor(() => expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledWith('req-1', { employeeName: 'John Doe' }, file));
      expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
    },
  );

  it('shows the employee a waiting message on a pending upload-only request', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...requestFor('LCA'), requestedByUserId: 'hr', requestedForUserId: 'manager' } } });
    renderApp('/myhr/hr/document-requests/req-1');

    expect(await screen.findByText(/Your HR or company admin will upload your LCA here/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Upload & Share' })).not.toBeInTheDocument();
  });

  it('preserves entered Employment Verification details when HR goes back to details from the template step', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor('Employment Verification') } });
    renderApp('/myhr/hr/document-requests/req-1/employment-verification');

    await screen.findByText('TD001 - John Doe');
    await user.selectOptions(screen.getByLabelText(/^Salutation/), 'MR');
    await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
    await user.type(screen.getByLabelText(/^Role Description/), 'Software Development and related consulting services');
    await user.type(screen.getByLabelText(/^Employee Email/), 'john.doe@example.com');
    await addRequiredSignature(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByText('SELECTED TEMPLATE');

    await user.click(screen.getByRole('button', { name: 'Back to Details' }));
    expect(screen.getByLabelText(/^Designation/)).toHaveValue('Software Engineer');
    expect(screen.getByLabelText(/^Role Description/)).toHaveValue('Software Development and related consulting services');
    expect(screen.getByLabelText(/^Employee Email/)).toHaveValue('john.doe@example.com');
  });

  it('previews a document that requires an employee signature, then waits for the Admin before anything is sent', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      organizationName: 'Tardus Inc.', organizationAdminName: 'Jane Admin', organizationAdminEmail: 'jane.admin@tardus.test',
      organizationAdminPhoneNumber: '+19726936993',
    } } });
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor('Experience and Relieving Letter') } });
    renderApp('/myhr/hr/document-requests/req-1/experience-relieving-letter');

    await screen.findByText('TD001 - John Doe');
    await user.selectOptions(screen.getByLabelText(/^Salutation/), 'MR');
    await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
    await user.type(screen.getByLabelText(/^Employment Start Date/), '2023-01-01');
    await user.type(screen.getByLabelText(/^Last Working Date/), '2026-01-01');
    await user.type(screen.getByLabelText(/^Employee Email/), 'john.doe@example.com');
    await addRequiredSignature(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText(/This letter is to certify that Mr\. John Doe was employed with Tardus Inc\. as a Software Engineer/)).toBeInTheDocument();
    expect(screen.getByText(/During his employment, he performed his duties sincerely and responsibly/)).toBeInTheDocument();
    expect(screen.getByText(/We wish him all the very best in his future endeavors\./)).toBeInTheDocument();
    expect(screen.getByText('Name: Jane Admin')).toBeInTheDocument();
    expect(screen.getByText('Email: jane.admin@tardus.test')).toBeInTheDocument();
    expect(screen.getByText('Mobile: +1 (972) 693-6993')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Submit for (Admin Approval|Signature)$/ }));

    expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
    expect(screen.queryByText(/awaits their signature/)).not.toBeInTheDocument();
    expect(myHrDocumentRequestApi.deliverApprovedDocument).not.toHaveBeenCalled();
  });

  it('sends an Admin-approved document that requires an employee signature, delivering and emailing it', async () => {
    const user = userEvent.setup();
    openInApprovalState('APPROVED', 'experience-relieving-letter', 'Experience and Relieving Letter', {
      formJson: JSON.stringify({ employeeName: 'John Doe', employeeEmail: 'john.doe@example.com' }),
    });

    expect(await screen.findByText('APPROVED')).toBeInTheDocument();
    expect(await screen.findByText(/approved by the company admin and is ready to be sent to the employee/)).toBeInTheDocument();
    expect(await screen.findByLabelText(/Recipient Email/)).toHaveValue('john.doe@example.com');
    expect(screen.getByLabelText(/Subject/)).toHaveValue('Your document from Waypoint');
    await user.click(screen.getByRole('button', { name: 'Send Experience and Relieving Letter' }));

    expect(await screen.findByText(/completed, but email was not delivered/)).toBeInTheDocument();
    expect(myHrDocumentRequestApi.deliverApprovedDocument).toHaveBeenCalledWith('req-1', {
      recipientEmail: 'john.doe@example.com', subject: 'Your document from Waypoint', message: 'Please find your document attached.',
    });
    expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
  });

  it('shows PENDING ADMIN APPROVAL with no send control while the Admin has not decided', async () => {
    openInApprovalState('PENDING_APPROVAL', 'employment-verification', 'Employment Verification');

    expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Send|Submit for Admin Approval|Resubmit/ })).not.toBeInTheDocument();
  });

  it('shows the Admin rejection reason, restores the submitted details and allows a resubmission', async () => {
    const user = userEvent.setup();
    openInApprovalState('REJECTED', 'employment-verification', 'Employment Verification', {
      rejectionReason: 'The designation is wrong.',
      formJson: JSON.stringify({ salutation: 'MR', designation: 'Software Engineer', roleDescription: 'Software Development and related consulting services', employeeEmail: 'john.doe@example.com', employeeName: 'John Doe', issueDate: '2026-10-01' }),
    });

    expect(await screen.findByText('ADMIN APPROVAL REJECTED')).toBeInTheDocument();
    expect(await screen.findByText(/The designation is wrong\./)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/^Designation/)).toHaveValue('Software Engineer'));
    await addRequiredSignature(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByText('SELECTED TEMPLATE');
    await user.click(screen.getByRole('button', { name: 'Resubmit for Approval' }));

    await waitFor(() => expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledTimes(1));
    expect(myHrDocumentRequestApi.deliverApprovedDocument).not.toHaveBeenCalled();
  });

  it('a Company Admin still prepares and sends a document directly', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { organizationName: 'Tardus Inc.', organizationAdminEmail: 'admin@tardus.test', signatureUrl: '/uploads/test-signature.png' } } });
    myHrDocumentRequestApi.submitDocumentForApproval.mockResolvedValue({ data: { data: { approvalStatus: 'APPROVED', documentId: 'signed-document' } } });
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor('Employment Verification') } });
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'admin', role: 'COMPANY_ADMIN', email: 'admin@tardus.test' }));
    render(
      <MemoryRouter initialEntries={['/myhr/admin/document-requests/req-1/employment-verification']}>
        <Routes>
          {GENERIC_DOCUMENT_WORKFLOW_TYPES.map((type) => (
            <Route key={type.id} path={`/myhr/admin/document-requests/:requestId/${type.routeSegment}`}
              element={<DocumentPreparationPage role="COMPANY_ADMIN" documentTypeId={type.id} />} />
          ))}
        </Routes>
      </MemoryRouter>,
    );

    await screen.findByText('TD001 - John Doe');
    await user.selectOptions(screen.getByLabelText(/^Salutation/), 'MR');
    await user.type(screen.getByLabelText(/^Designation/), 'Software Engineer');
    await user.type(screen.getByLabelText(/^Role Description/), 'Software Development and related consulting services');
    await user.type(screen.getByLabelText(/^Employee Email/), 'john.doe@example.com');
    await addRequiredSignature(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByText('SELECTED TEMPLATE');
    expect(screen.queryByRole('button', { name: /^Submit for (Admin Approval|Signature)$/ })).not.toBeInTheDocument();
    expect(await screen.findByLabelText(/Recipient Email/)).toHaveValue('john.doe@example.com');
    await user.click(screen.getByRole('button', { name: 'Verify & Send' }));

    expect(await screen.findByText(/completed, but email was not delivered/)).toBeInTheDocument();
    expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledWith('req-1', expect.any(Object), expect.any(File));
    expect(myHrDocumentRequestApi.deliverApprovedDocument).toHaveBeenCalledWith('req-1', {
      recipientEmail: 'john.doe@example.com', subject: 'Your document from Waypoint', message: 'Please find your document attached.',
    });
    expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
  });

  it.each(['Experience Letter', 'Relieving Letter'])(
    'routes a pre-merge "%s" request to the merged Experience and Relieving Letter page',
    async (legacyTitle) => {
      myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor(legacyTitle) } });
      renderApp('/myhr/hr/document-requests/req-1/experience-relieving-letter');

      expect(await screen.findByText('TD001 - John Doe')).toBeInTheDocument();
      expect(screen.getByLabelText(/^Last Working Date/)).toBeInTheDocument();
    },
  );

  it('auto-fills employee email, designation and start date from the employee profile', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: {
      reportingManager: null, workEmail: 'john.doe@tardus.com', designation: 'Software Engineer', startDate: '2023-01-02',
    } } });
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor('Experience and Relieving Letter') } });
    renderApp('/myhr/hr/document-requests/req-1/experience-relieving-letter');

    await screen.findByText('TD001 - John Doe');
    await waitFor(() => expect(screen.getByLabelText(/^Employee Email/)).toHaveValue('john.doe@tardus.com'));
    expect(screen.getByLabelText(/^Designation/)).toHaveValue('Software Engineer');
    expect(screen.getByLabelText(/^Employment Start Date/)).toHaveValue('2023-01-02');
    expect(screen.queryByLabelText(/^Work Location/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Roles & Responsibilities/)).not.toBeInTheDocument();
  });

  it('OTHER lets HR configure per-request whether the employee must sign and return a signed copy', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor('Other Employment Document') } });
    renderApp('/myhr/hr/document-requests/req-1/other-employment-document');

    await screen.findByText('TD001 - John Doe');
    // The signed-return checkbox is only meaningful once signature is required.
    expect(screen.getByLabelText('Employee must return a signed copy')).toBeDisabled();
    await user.click(screen.getByLabelText('Employee must sign this document'));
    expect(screen.getByLabelText('Employee must return a signed copy')).toBeEnabled();
    await user.click(screen.getByLabelText('Employee must return a signed copy'));

    await user.type(screen.getByLabelText(/^Document Title/), 'Address Confirmation Letter');
    await user.type(screen.getByLabelText(/^Document Details/), 'This confirms the address on file.');
    await user.type(screen.getByLabelText(/^Employee Email/), 'john.doe@example.com');
    await addRequiredSignature(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: /^Submit for (Admin Approval|Signature)$/ }));

    // The per-request signature choices are submitted with the document, so they survive until it is sent.
    await waitFor(() => expect(myHrDocumentRequestApi.submitDocumentForApproval).toHaveBeenCalledTimes(1));
    const details = myHrDocumentRequestApi.submitDocumentForApproval.mock.calls[0][1];
    expect(JSON.parse(details.formJson)).toMatchObject({ otherRequiresSignature: true, otherRequiresSignedReturn: true });
    expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
  });

  it('sends an approved OTHER document with the signature choices HR made, awaiting the employee', async () => {
    const user = userEvent.setup();
    openInApprovalState('APPROVED', 'other-employment-document', 'Other Employment Document', {
      formJson: JSON.stringify({ otherRequiresSignature: true, otherRequiresSignedReturn: true, employeeName: 'John Doe', employeeEmail: 'john.doe@example.com' }),
    });

    await screen.findByText('APPROVED');
    await screen.findByLabelText(/Recipient Email/);
    await user.click(screen.getByRole('button', { name: /^Send/ }));

    expect(await screen.findByText(/completed, but email was not delivered/)).toBeInTheDocument();
  });

  it('unchecking sign-required for OTHER clears the now-irrelevant signed-return requirement', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: requestFor('Other Employment Document') } });
    renderApp('/myhr/hr/document-requests/req-1/other-employment-document');

    await screen.findByText('TD001 - John Doe');
    await user.click(screen.getByLabelText('Employee must sign this document'));
    await user.click(screen.getByLabelText('Employee must return a signed copy'));
    await user.click(screen.getByLabelText('Employee must sign this document'));
    expect(screen.getByLabelText('Employee must return a signed copy')).not.toBeChecked();
    expect(screen.getByLabelText('Employee must return a signed copy')).toBeDisabled();
  });
});
