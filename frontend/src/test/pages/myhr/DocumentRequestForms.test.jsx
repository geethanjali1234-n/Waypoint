import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import RequestDocumentsForm, { DOCUMENT_CATEGORIES } from '../../../pages/myhr/features/documentRequests/RequestDocumentsForm';
import EmployeeDocumentRequestForm, { SELF_SERVICE_DOCUMENT_TYPES } from '../../../pages/myhr/features/documentRequests/EmployeeDocumentRequestForm';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';

vi.mock('../../../api/myHrDocumentRequestApi');
describe('Request Document forms', () => {
  beforeEach(() => { vi.clearAllMocks(); myHrDocumentRequestApi.requestableEmployees.mockResolvedValue({ data: { data: [{ userId: 'emp-1', name: 'John Smith', employeeId: 'TD002' }] } }); myHrDocumentRequestApi.create.mockResolvedValue({}); myHrDocumentRequestApi.createSelfService.mockResolvedValue({}); });
  it('manager form loads requestable employees and creates a request', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><RequestDocumentsForm role="HR" /></MemoryRouter>);

    await user.selectOptions(await screen.findByLabelText(/^Document Category/), 'PRE_EMPLOYMENT_ONBOARDING');
    await user.selectOptions(screen.getByLabelText(/^Employee/), 'emp-1');
    const searchInput = screen.getByPlaceholderText('Type to search documents in this category...');
    await user.click(searchInput);
    await user.click(await screen.findByRole('option', { name: 'Signed offer letter / employment agreement' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(myHrDocumentRequestApi.create).toHaveBeenCalledWith(expect.objectContaining({
      requestedForUserId: 'emp-1',
      category: 'PRE_EMPLOYMENT_ONBOARDING',
      documents: ['Signed offer letter / employment agreement'],
    }));
  });

  it('catalog contains valid document categories and self-service choices', () => {
    expect(DOCUMENT_CATEGORIES).toHaveProperty('PRE_EMPLOYMENT_ONBOARDING');
    expect(DOCUMENT_CATEGORIES).toHaveProperty('TAX_PAYROLL');
    expect(DOCUMENT_CATEGORIES).toHaveProperty('BENEFITS_ENROLLMENT');
    expect(DOCUMENT_CATEGORIES).toHaveProperty('IMMIGRATION_RELATED');
    expect(DOCUMENT_CATEGORIES).toHaveProperty('EXIT_OFFBOARDING');
    expect(SELF_SERVICE_DOCUMENT_TYPES.length).toBeGreaterThanOrEqual(1);
    expect(DOCUMENT_CATEGORIES.IMMIGRATION_RELATED.documents).toContain('I-983');
  });
  it('reopens the picker for remaining options and makes removed choices available again', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><RequestDocumentsForm role="COMPANY_ADMIN" /></MemoryRouter>);
    await user.selectOptions(await screen.findByLabelText(/^Document Category/), 'IMMIGRATION_RELATED');
    const picker = screen.getByPlaceholderText('Type to search documents in this category...');
    await user.click(picker);
    await user.click(await screen.findByRole('option', { name: 'I-983' }));
    expect(screen.getByText('I-983')).toBeInTheDocument();
    await user.click(picker);
    expect(screen.queryByRole('option', { name: 'I-983' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: 'Visa / EAD renewal documents' }));
    expect(screen.getByText('Visa / EAD renewal documents')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove I-983' }));
    await user.click(picker);
    expect(screen.getByRole('option', { name: 'I-983' })).toBeInTheDocument();
  });

  it('offers an Other document option in every document category', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><RequestDocumentsForm role="HR" /></MemoryRouter>);
    await user.selectOptions(await screen.findByLabelText(/^Document Category/), 'TAX_PAYROLL');
    await user.click(screen.getByPlaceholderText('Type to search documents in this category...'));
    expect(screen.getByRole('option', { name: 'Other document' })).toBeInTheDocument();
  });

  it('adds a typed document when the catalog has no match', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><RequestDocumentsForm role="HR" /></MemoryRouter>);
    await user.selectOptions(await screen.findByLabelText(/^Document Category/), 'TAX_PAYROLL');
    const picker = screen.getByPlaceholderText('Type to search documents in this category...');
    await user.type(picker, 'union membership certificate');
    await user.click(screen.getByRole('button', { name: /Add “union membership certificate”/ }));
    expect(screen.getByLabelText('Remove Other document: union membership certificate')).toBeInTheDocument();
  });
  it('self-service form collects only approved request fields, no attachment control when none is configured', async () => {
    const user = userEvent.setup(); render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    expect(screen.queryByLabelText(/SSN|date of birth|marital|passport|bank|tax/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Document title/)).not.toBeInTheDocument();
    // Document type is scoped to the selected category first (dynamic request UX).
    expect(screen.getByLabelText(/^Document type/)).toBeDisabled();
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'EMPLOYMENT_LETTERS');
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'EMPLOYMENT_VERIFICATION');
    expect(screen.getByRole('heading', { name: 'Employment Verification' })).toBeInTheDocument();
    // Employment Verification has no required/optional documents configured - the
    // Requirements section says so, and no attachment control is shown at all.
    expect(screen.getByText('No supporting documents are required for this request.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Supporting attachments')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Reason/), 'Housing verification');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(myHrDocumentRequestApi.createSelfService).toHaveBeenCalledWith(expect.objectContaining({ documentType: 'EMPLOYMENT_VERIFICATION', documentTitle: 'Employment Verification' }), []);
  });

  it('scopes document type options to the selected category and shows the right requirements notice', async () => {
    const user = userEvent.setup(); render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'IMMIGRATION');
    expect(screen.getByRole('option', { name: 'LCA' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Offer Letter' })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'I140');
    expect(screen.getByText(/No signature is required from you/)).toBeInTheDocument();
    expect(screen.getByText('Required Information')).toBeInTheDocument();
    expect(screen.getByText('Employee information (auto-filled)')).toBeInTheDocument();
    expect(screen.getByText('Reason for request')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'LCA');
    // Switching category resets the type selection so a stale value can't be submitted.
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'SEPARATION');
    expect(screen.getByLabelText(/^Document type/)).toHaveValue('');
    expect(screen.queryByRole('option', { name: 'Relieving Letter' })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'EXPERIENCE_LETTER');
    expect(screen.getByText(/No signature is required from you/)).toBeInTheDocument();
  });
});
