import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import EmployeeDocumentRequestForm from '../../../pages/myhr/features/documentRequests/EmployeeDocumentRequestForm';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';

vi.mock('../../../api/myHrDocumentRequestApi');

// Verifies the Requirements section's Required/Optional Documents lists and
// the attachment control actually render once a type's configuration names
// a document - the counterpart to DocumentRequestForms.test.jsx's coverage
// of "nothing configured" (today's real state for all 11 shipped types).
// Mocked at module scope (not via vi.resetModules mid-test) to avoid a
// second React module instance being loaded for a dynamically re-imported
// component tree.
vi.mock('../../../pages/myhr/features/documentRequests/selfServiceDocumentCatalog', async () => {
  const actual = await vi.importActual('../../../pages/myhr/features/documentRequests/selfServiceDocumentCatalog');
  return {
    ...actual,
    SELF_SERVICE_TYPE_CONFIG: {
      ...actual.SELF_SERVICE_TYPE_CONFIG,
      LCA: {
        ...actual.SELF_SERVICE_TYPE_CONFIG.LCA,
        requiredDocuments: ['Approved LCA (ETA-9035)'],
        optionalDocuments: ['Visa approval notice'],
        requiresAttachment: true,
      },
    },
  };
});

describe('EmployeeDocumentRequestForm - Requirements section with configured documents', () => {
  beforeEach(() => { vi.clearAllMocks(); myHrDocumentRequestApi.createSelfService.mockResolvedValue({}); });

  it('shows Required Documents, Optional Documents, and the attachment control once a type configures them', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployeeDocumentRequestForm role="EMPLOYEE" /></MemoryRouter>);
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'IMMIGRATION');
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'LCA');

    expect(screen.getByText('Required Documents')).toBeInTheDocument();
    expect(screen.getByText('Approved LCA (ETA-9035)')).toBeInTheDocument();
    expect(screen.getByText('Optional Documents')).toBeInTheDocument();
    expect(screen.getByText('Visa approval notice')).toBeInTheDocument();
    expect(screen.queryByText('No supporting documents are required for this request.')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Supporting attachments')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^Reason/), 'Visa renewal support');
    await user.upload(screen.getByLabelText('Supporting attachments'), new File(['x'], 'lca.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(myHrDocumentRequestApi.createSelfService).toHaveBeenCalledWith(
      expect.objectContaining({ documentType: 'OTHER', documentTitle: 'LCA' }),
      [expect.any(File)],
    );
  });
});
