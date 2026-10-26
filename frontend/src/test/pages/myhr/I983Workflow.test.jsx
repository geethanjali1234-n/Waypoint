import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import EmployeeDocumentRequestForm from '../../../pages/myhr/features/documentRequests/EmployeeDocumentRequestForm';
import I983ReviewPanel from '../../../pages/myhr/features/documentRequests/i983/I983ReviewPanel';
import I983SigningPage from '../../../pages/myhr/features/documentRequests/i983/I983SigningPage';
import { NO_SIGNATURE_MESSAGE } from '../../../pages/myhr/features/documentRequests/i983/i983Workflow';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrEmployeeApi');
// pdf.js needs a real canvas/worker; the signing page only needs a page of known size.
vi.mock('../../../pages/myhr/features/documentRequests/i983/pdfRendering', async (importOriginal) => ({
  ...(await importOriginal()),
  openPdf: vi.fn(async () => ({ numPages: 2, destroy: vi.fn() })),
  startPdfPageRender: vi.fn(async () => ({ size: { width: 600, height: 800 }, task: { promise: Promise.resolve(), cancel: vi.fn() } })),
}));

const pdfFile = () => new File(['%PDF-1.4'], 'my-i983.pdf', { type: 'application/pdf' });

const workflow = (overrides = {}) => ({
  request: { id: 'req-1', status: 'SUBMITTED', documentType: 'I983', originalDocumentId: 'doc-1' },
  originalDocumentId: 'doc-1', currentDocumentId: 'doc-1', signedDocumentId: null, signedIsCurrent: false,
  viewerIsReviewer: true, canReview: true, canResubmit: false, returnReason: null,
  submittedAt: '2026-09-28T10:00:00', versions: [
    { documentId: 'doc-1', version: 1, kind: 'ORIGINAL', fileName: 'my-i983.pdf', uploadedByName: 'John Smith', uploadedAt: '2026-09-28T10:00:00' },
  ],
  history: [{ action: 'SUBMITTED', actorName: 'John Smith', at: '2026-09-28T10:00:00', detail: 'Submitted my-i983.pdf' }],
  ...overrides,
});

function fakeImages() {
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:signature');
  globalThis.URL.revokeObjectURL = vi.fn();
  vi.spyOn(globalThis, 'Image').mockImplementation(function FakeImage() {
    const image = { naturalWidth: 300, naturalHeight: 100 };
    Object.defineProperty(image, 'src', { set() { setTimeout(() => image.onload?.()); } });
    return image;
  });
}

describe('Signed I-983 - employee request', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fakeImages();
    myHrDocumentRequestApi.submitI983.mockResolvedValue({ data: { data: { request: { id: 'req-1' } } } });
  });

  it('requires an uploaded PDF and submits it through the I-983 endpoint', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/myhr/employee/document-requests/new']}><Routes>
      <Route path="/myhr/employee/document-requests/new" element={<EmployeeDocumentRequestForm />} />
      <Route path="/myhr/employee/document-requests/:requestId" element={<p>Request detail</p>} />
    </Routes></MemoryRouter>);

    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'IMMIGRATION');
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'I983');
    expect(screen.getByRole('heading', { name: 'Upload Document' })).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Reason/), 'STEM OPT extension');
    await user.click(screen.getByRole('button', { name: 'Submit' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Upload the I-983 Document before submitting.');
    expect(myHrDocumentRequestApi.submitI983).not.toHaveBeenCalled();

    await user.upload(document.getElementById('document-I983'), pdfFile());
    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Submit' }));

    expect(myHrDocumentRequestApi.submitI983).toHaveBeenCalledWith(
      expect.objectContaining({ documentType: 'I983', documentTitle: 'Signed I-983', reason: 'STEM OPT extension' }),
      expect.objectContaining({ name: 'my-i983.pdf' }),
      null,
    );
    expect(await screen.findByText('Request detail')).toBeInTheDocument();
  });

  const openI983Form = async (user) => {
    render(<MemoryRouter initialEntries={['/myhr/employee/document-requests/new']}><Routes>
      <Route path="/myhr/employee/document-requests/new" element={<EmployeeDocumentRequestForm />} />
      <Route path="/myhr/employee/document-requests/:requestId" element={<p>Request detail</p>} />
    </Routes></MemoryRouter>);
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'IMMIGRATION');
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'I983');
    await user.type(screen.getByLabelText(/^Reason/), 'STEM OPT extension');
    await user.upload(document.getElementById('document-I983'), pdfFile());
  };

  it('lets the employee place their Profile signature exactly where they choose and submits that placement', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { signatureUrl: '/uploads/gridfs/sig/signature.png' } } });
    const user = userEvent.setup();
    await openI983Form(user);

    await user.click(screen.getByRole('button', { name: 'Sign Document' }));
    expect(await screen.findByAltText('Your signature')).toBeInTheDocument();
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument();
    const surface = screen.getByTestId('i983-page-surface');
    surface.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600, height: 800, right: 600, bottom: 800 });
    await waitFor(() => expect(surface.style.width).toBe('600px'));
    fireEvent.pointerDown(surface, { clientX: 150, clientY: 700 });
    expect(await screen.findByRole('button', { name: /Signature 1 on page 1/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.getByText(/Your signature is placed once \(page 1\)/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit' }));
    const [, , signatures] = myHrDocumentRequestApi.submitI983.mock.calls[0];
    expect(signatures).toHaveLength(1);
    const [signature] = signatures;
    expect(signature).toMatchObject({ page: 1 });
    expect(signature.x).toBeCloseTo((150 - 75) / 600);
    expect(signature.y).toBeCloseTo((700 - 25) / 800);
    expect(signature.width).toBeCloseTo(0.25);
  });

  it('lets the employee place their signature in several places, on different pages, and remove one', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { signatureUrl: '/uploads/gridfs/sig/signature.png' } } });
    const user = userEvent.setup();
    await openI983Form(user);

    await user.click(screen.getByRole('button', { name: 'Sign Document' }));
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument();
    const surface = screen.getByTestId('i983-page-surface');
    surface.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600, height: 800, right: 600, bottom: 800 });
    await waitFor(() => expect(surface.style.width).toBe('600px'));
    fireEvent.pointerDown(surface, { clientX: 150, clientY: 200 });
    fireEvent.pointerDown(surface, { clientX: 300, clientY: 600 });
    expect(await screen.findByRole('button', { name: /Signature 2 on page 1/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(surface.style.width).toBe('600px'));
    fireEvent.pointerDown(surface, { clientX: 300, clientY: 400 });
    expect(await screen.findByRole('button', { name: /Signature 1 on page 2/ })).toBeInTheDocument();
    expect(screen.getByText(/3 signatures placed/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '1' }));
    await user.click(await screen.findByRole('button', { name: 'Remove signature 1 on page 1' }));
    expect(screen.getByText(/2 signatures placed/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.getByText(/Your signature is placed 2 times \(pages 1, 2\)/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit' }));
    const [, , signatures] = myHrDocumentRequestApi.submitI983.mock.calls[0];
    expect(signatures.map((item) => item.page)).toEqual([1, 2]);
    expect(signatures[0].y).toBeCloseTo((600 - 25) / 800);
  });

  it('asks the employee to add a Profile signature before they can sign', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { signatureUrl: null } } });
    const user = userEvent.setup();
    await openI983Form(user);
    await user.click(screen.getByRole('button', { name: 'Sign Document' }));
    expect(await screen.findByText(/haven't uploaded a signature yet/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to My HR Profile' })).toHaveAttribute('href', '/myhr/employee/profile');
    expect(screen.getByRole('button', { name: 'Done' })).toBeDisabled();
  });

  it('rejects files that are not PDFs', async () => {
    const user = userEvent.setup({ applyAccept: false });
    render(<MemoryRouter><EmployeeDocumentRequestForm /></MemoryRouter>);
    await user.selectOptions(screen.getByLabelText(/^Document Category/), 'IMMIGRATION');
    await user.selectOptions(screen.getByLabelText(/^Document type/), 'I983');
    await user.upload(document.getElementById('document-I983'), new File(['x'], 'photo.png', { type: 'image/png' }));
    expect(screen.getByRole('alert')).toHaveTextContent('PDF');
  });
});

describe('Signed I-983 - review panel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrDocumentRequestApi.getI983Signature.mockResolvedValue({ data: { data: { available: true, signatoryName: 'Jane Admin' } } });
  });
  const renderPanel = () => render(<MemoryRouter><I983ReviewPanel requestId="req-1" base="/myhr/hr" /></MemoryRouter>);

  it('does not manufacture an organization signature when an approved response omits the signed version', async () => {
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow({ request: { status: 'APPROVED' }, signedDocumentId: null, signedIsCurrent: false, canReview: false }) } });
    renderPanel();
    const route = await screen.findByRole('list', { name: 'I-983 workflow' });
    expect(within(route).queryByText('Organization signed')).not.toBeInTheDocument();
    expect(within(route).queryByText('Review and sign')).not.toBeInTheDocument();
    expect(within(route).getByText('Approved')).toBeInTheDocument();
  });

  it('shows the HR/Admin action bar and keeps Download Signed and Approve disabled until a signed version exists', async () => {
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow() } });
    renderPanel();
    const toolbar = await screen.findByRole('toolbar', { name: 'I-983 actions' });
    for (const name of ['View', 'Download Original', 'Upload/Replace', 'Add Signature', 'Preview', 'Download Signed', 'Approve', 'Return', 'Reject'])
      expect(within(toolbar).getByRole('button', { name })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download Signed' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
    expect(screen.getByText('Apply the organization signature before approving.')).toBeInTheDocument();
    expect(screen.getByText('Submitted', { selector: '.wp-status-badge' })).toBeInTheDocument();
  });

  it('approves once the current version is signed', async () => {
    const signed = workflow({ signedDocumentId: 'doc-2', signedIsCurrent: true });
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: signed } });
    myHrDocumentRequestApi.approveI983.mockResolvedValue({ data: { data: workflow({ ...signed, canReview: false, request: { ...signed.request, status: 'APPROVED' } }) } });
    const user = userEvent.setup();
    renderPanel();
    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(screen.getByRole('button', { name: 'Download Signed' })).toBeEnabled();
    await user.click(approve);
    expect(myHrDocumentRequestApi.approveI983).toHaveBeenCalledWith('req-1');
    expect(await screen.findByText(/was approved/)).toBeInTheDocument();
  });

  it('returns the request with a required reason', async () => {
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow() } });
    myHrDocumentRequestApi.returnI983.mockResolvedValue({ data: { data: workflow({ canReview: false, request: { id: 'req-1', status: 'RETURNED_FOR_CORRECTION' } }) } });
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole('button', { name: 'Return' }));
    const confirm = screen.getByRole('button', { name: 'Return to employee' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText(/What needs to be corrected/), 'Section 5 is incomplete');
    await user.click(confirm);
    expect(myHrDocumentRequestApi.returnI983).toHaveBeenCalledWith('req-1', 'Section 5 is incomplete');
  });

  it('warns reviewers when no authorized signature is configured', async () => {
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow() } });
    myHrDocumentRequestApi.getI983Signature.mockResolvedValue({ data: { data: { available: false, message: NO_SIGNATURE_MESSAGE } } });
    renderPanel();
    expect(await screen.findByText(NO_SIGNATURE_MESSAGE)).toBeInTheDocument();
  });

  it('lets the employee resubmit after a return, and shows the signed copy only once approved', async () => {
    const employee = { viewerIsReviewer: false, canReview: false };
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow({ ...employee, canResubmit: true, returnReason: 'Sign section 4', request: { id: 'req-1', status: 'RETURNED_FOR_CORRECTION' } }) } });
    myHrDocumentRequestApi.resubmitI983.mockResolvedValue({ data: { data: workflow({ ...employee, request: { id: 'req-1', status: 'SUBMITTED' } }) } });
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    expect(await screen.findByText('Sign section 4')).toBeInTheDocument();
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
    await user.upload(document.getElementById('i983-corrected'), pdfFile());
    await user.click(screen.getByRole('button', { name: 'Resubmit' }));
    expect(myHrDocumentRequestApi.resubmitI983).toHaveBeenCalledWith('req-1', expect.objectContaining({ name: 'my-i983.pdf' }), null);
    unmount();

    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow({ ...employee, signedDocumentId: 'doc-2', request: { id: 'req-1', status: 'APPROVED' } }) } });
    renderPanel();
    expect(await screen.findByRole('button', { name: 'Download Signed I-983' })).toBeInTheDocument();
  });
});

describe('Signed I-983 - signing page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrDocumentRequestApi.getI983.mockResolvedValue({ data: { data: workflow() } });
    myHrDocumentRequestApi.getI983Document.mockResolvedValue({ data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }) });
    fakeImages();
  });

  const renderPage = () => render(<MemoryRouter initialEntries={['/myhr/hr/document-requests/req-1/i983-sign']}><Routes>
    <Route path="/myhr/hr/document-requests/:requestId/i983-sign" element={<I983SigningPage role="HR" />} />
    <Route path="/myhr/hr/document-requests/:requestId" element={<p>Request detail</p>} />
  </Routes></MemoryRouter>);

  it('blocks signing when there is no active organization signature', async () => {
    myHrDocumentRequestApi.getI983Signature.mockResolvedValue({ data: { data: { available: false, message: NO_SIGNATURE_MESSAGE } } });
    renderPage();
    expect(await screen.findByText(NO_SIGNATURE_MESSAGE)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Apply Signature' })).toBeDisabled();
    expect(myHrDocumentRequestApi.getI983SignatureImage).not.toHaveBeenCalled();
  });

  it('places the organization signature where the reviewer clicks, previews it and applies it after confirmation', async () => {
    myHrDocumentRequestApi.getI983Signature.mockResolvedValue({ data: { data: { available: true, signatoryName: 'Jane Admin', signatoryTitle: 'President', effectiveDate: '2026-01-01' } } });
    myHrDocumentRequestApi.getI983SignatureImage.mockResolvedValue({ data: new Blob(['png'], { type: 'image/png' }) });
    myHrDocumentRequestApi.signI983.mockResolvedValue({ data: { data: workflow() } });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('Jane Admin')).toBeInTheDocument();
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();

    const surface = screen.getByTestId('i983-page-surface');
    surface.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600, height: 800, right: 600, bottom: 800 });
    await waitFor(() => expect(surface.style.width).toBe('600px'));
    fireEvent.pointerDown(surface, { clientX: 300, clientY: 400 });
    const box = await screen.findByRole('button', { name: /Signature 1 on page 2/ });
    fireEvent.keyDown(box, { key: 'ArrowRight', shiftKey: true });

    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByRole('button', { name: 'Edit placement' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Apply Signature' }));
    const dialogApply = screen.getAllByRole('button', { name: 'Apply Signature' }).at(-1);
    await user.click(dialogApply);

    await waitFor(() => expect(myHrDocumentRequestApi.signI983).toHaveBeenCalled());
    const [requestId, { documentId, placements }] = myHrDocumentRequestApi.signI983.mock.calls[0];
    expect(requestId).toBe('req-1');
    expect(documentId).toBe('doc-1');
    expect(placements).toHaveLength(1);
    const [placement] = placements;
    expect(placement).toMatchObject({ page: 2 });
    // 25% of the page wide, centred on the click and then nudged 10px right; height follows the 3:1 signature.
    expect(placement.width).toBeCloseTo(0.25);
    expect(placement.x).toBeCloseTo((300 - 75 + 10) / 600);
    expect(placement.height).toBeCloseTo(50 / 800);
    expect(placement.y).toBeCloseTo((400 - 25) / 800);
    expect(await screen.findByText('Request detail')).toBeInTheDocument();
  });
});
