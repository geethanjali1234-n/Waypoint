import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import EmployeeDocumentRequestForm from '../../../pages/myhr/features/documentRequests/EmployeeDocumentRequestForm';
import { MANDATORY_ONBOARDING_DOCUMENTS } from '../../../pages/myhr/features/documentRequests/selfServiceDocumentCatalog';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';

vi.mock('../../../api/myHrDocumentRequestApi');

// The six onboarding documents are prerequisites/attachments of the Offer
// Letter request itself - there is no separate "Pre-Employment /
// Onboarding Documents" type the employee requests on its own.
async function selectOfferLetter(user) {
  await user.selectOptions(screen.getByLabelText(/^Document Category/), 'PRE_EMPLOYMENT_ONBOARDING_OFFER');
  await user.selectOptions(screen.getByLabelText(/^Document type/), 'OFFER_LETTER');
}

describe('EmployeeDocumentRequestForm - Offer Letter with mandatory onboarding documents', () => {
  beforeEach(() => { vi.clearAllMocks(); myHrDocumentRequestApi.createSelfService.mockResolvedValue({}); });

  it('is the only type under Pre-Employment / Onboarding & Offer, and shows all six mandatory documents', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'PRE_EMPLOYMENT_ONBOARDING_OFFER');
    expect(screen.getByRole('option', { name: 'Offer Letter' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Pre-Employment / Onboarding Documents' })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText(/^Document type/), 'OFFER_LETTER');
    expect(screen.getByRole('heading', { name: 'Offer Letter' })).toBeInTheDocument();
    expect(screen.queryByText(/HR will prepare this and send it to you/)).not.toBeInTheDocument();

    // One unified full-width "Required Documents" section - no separate
    // "Requirements" column, and no redundant bullet list of the same names.
    expect(screen.getByText('Required Documents')).toBeInTheDocument();
    expect(screen.queryByText('Requirements')).not.toBeInTheDocument();
    expect(screen.getByText(/Please upload the following documents to request your Offer Letter/)).toBeInTheDocument();
    expect(screen.queryByText('No supporting documents are required for this request.')).not.toBeInTheDocument();

    // A subtle info line, not a red alert, is shown proactively before submission.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('All 6 required documents must be uploaded before you can submit this request.')).toBeInTheDocument();

    for (const doc of MANDATORY_ONBOARDING_DOCUMENTS) expect(screen.getByText(doc.label)).toBeInTheDocument();
    expect(document.querySelectorAll('.employee-document-request__required-marker')).toHaveLength(MANDATORY_ONBOARDING_DOCUMENTS.length);
    expect(screen.getAllByText('No file selected')).toHaveLength(MANDATORY_ONBOARDING_DOCUMENTS.length);
  });

  it('hides the informational hint once all six documents are uploaded', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await selectOfferLetter(user);
    expect(screen.getByText('All 6 required documents must be uploaded before you can submit this request.')).toBeInTheDocument();

    for (const doc of MANDATORY_ONBOARDING_DOCUMENTS) {
      const input = screen.getByText(doc.label).closest('.employee-document-request__upload').querySelector('input[type="file"]');
      await user.upload(input, new File(['x'], `${doc.id.toLowerCase()}.pdf`, { type: 'application/pdf' }));
    }
    expect(screen.queryByText('All 6 required documents must be uploaded before you can submit this request.')).not.toBeInTheDocument();
  });

  it('blocks the Offer Letter request and names every still-missing document until all six are uploaded', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await selectOfferLetter(user);
    await user.type(screen.getByLabelText(/^Reason/), 'New hire offer letter');

    await user.click(screen.getByRole('button', { name: 'Send request' }));
    const firstError = await screen.findByRole('alert');
    for (const doc of MANDATORY_ONBOARDING_DOCUMENTS) expect(firstError).toHaveTextContent(doc.label);
    expect(myHrDocumentRequestApi.createSelfService).not.toHaveBeenCalled();

    // Upload all but one - the error should narrow to just the missing one.
    for (let i = 0; i < MANDATORY_ONBOARDING_DOCUMENTS.length - 1; i += 1) {
      const upload = screen.getAllByText('Upload')[0];
      await user.upload(upload.parentElement.querySelector('input[type="file"]'), new File(['x'], 'file.pdf', { type: 'application/pdf' }));
    }
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    const lastDoc = MANDATORY_ONBOARDING_DOCUMENTS[MANDATORY_ONBOARDING_DOCUMENTS.length - 1];
    expect(await screen.findByRole('alert')).toHaveTextContent(lastDoc.label);
    expect(myHrDocumentRequestApi.createSelfService).not.toHaveBeenCalled();
  });

  it('lets the employee replace and remove an uploaded file before submitting', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await selectOfferLetter(user);

    const resumeUpload = screen.getByText('Resume').closest('.employee-document-request__upload').querySelector('input[type="file"]');
    await user.upload(resumeUpload, new File(['x'], 'resume-v1.pdf', { type: 'application/pdf' }));
    expect(screen.getByText('resume-v1.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove Resume' })).toBeInTheDocument();

    // Replace with a different file.
    const replaceInput = screen.getByText('Resume').closest('.employee-document-request__upload').querySelector('input[type="file"]');
    await user.upload(replaceInput, new File(['y'], 'resume-v2.pdf', { type: 'application/pdf' }));
    expect(screen.getByText('resume-v2.pdf')).toBeInTheDocument();
    expect(screen.queryByText('resume-v1.pdf')).not.toBeInTheDocument();

    // Remove it entirely.
    await user.click(screen.getByRole('button', { name: 'Remove Resume' }));
    expect(screen.queryByText('resume-v2.pdf')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove Resume' })).not.toBeInTheDocument();
  });

  it('submits a real Offer Letter request (not a generic/OTHER request) with all six documents attached and labeled', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await selectOfferLetter(user);
    await user.type(screen.getByLabelText(/^Reason/), 'New hire offer letter');

    for (const doc of MANDATORY_ONBOARDING_DOCUMENTS) {
      const input = screen.getByText(doc.label).closest('.employee-document-request__upload').querySelector('input[type="file"]');
      await user.upload(input, new File(['x'], `${doc.id.toLowerCase()}.pdf`, { type: 'application/pdf' }));
    }

    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(myHrDocumentRequestApi.createSelfService).toHaveBeenCalledTimes(1);
    const [payload, attachments] = myHrDocumentRequestApi.createSelfService.mock.calls[0];
    // documentType is the real OFFER_LETTER enum value - HR receives an Offer
    // Letter request, not a separate onboarding-documents request routed as OTHER.
    expect(payload).toMatchObject({ documentType: 'OFFER_LETTER', documentTitle: 'Offer Letter' });
    expect(attachments).toHaveLength(6);
    expect(attachments.map((file) => file.name)).toEqual(MANDATORY_ONBOARDING_DOCUMENTS.map((doc) => `${doc.label}.pdf`));
  });
});
