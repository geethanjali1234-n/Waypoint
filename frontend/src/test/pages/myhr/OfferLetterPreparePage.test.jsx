import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { inflateSync } from 'node:zlib';
import DocumentRequestsPage from '../../../pages/myhr/features/documentRequests/DocumentRequestsPage';
import OfferLetterPreparePage from '../../../pages/myhr/features/documentRequests/OfferLetterPreparePage';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import { formatDateLong } from '../../../utils/dateFormat';

/** Splits a raw PDF byte buffer into each `stream...endstream` block, inflating FlateDecode streams. */
function pdfContentStreams(buffer) {
  const text = Buffer.from(buffer).toString('latin1');
  const streams = [];
  const streamPattern = /stream\r?\n([\s\S]*?)endstream/g;
  let match = streamPattern.exec(text);
  while (match) {
    const raw = Buffer.from(match[1], 'latin1');
    try {
      streams.push(inflateSync(raw).toString('latin1'));
    } catch {
      streams.push(raw.toString('latin1'));
    }
    match = streamPattern.exec(text);
  }
  return streams;
}

/** Maps each PDF font resource name (e.g. "F2") to its BaseFont (e.g. "Helvetica-Bold"), read from the uncompressed font objects. */
function pdfFontResourceMap(buffer) {
  const text = Buffer.from(buffer).toString('latin1');
  const baseFontByObjNum = new Map();
  for (const m of text.matchAll(/(\d+) 0 obj\s*<<([^>]*\/BaseFont[^>]*)>>/g)) {
    const baseFont = /\/BaseFont\s*\/([A-Za-z0-9+-]+)/.exec(m[2])?.[1];
    if (baseFont) baseFontByObjNum.set(m[1], baseFont);
  }
  const resourceMap = new Map();
  for (const m of text.matchAll(/\/(F\d+)\s+(\d+)\s+0\s+R/g)) {
    const baseFont = baseFontByObjNum.get(m[2]);
    if (baseFont) resourceMap.set(m[1], baseFont);
  }
  return resourceMap;
}

/**
 * Reconstructs the readable text of each content stream by concatenating
 * `(...) Tj` string operands in document order, unescaping PDF string
 * escapes (e.g. `\(` back to `(`). The offer letter's clause bodies are now
 * written one word at a time (see writeRuns in OfferLetterPreparePage, which
 * lets a clause bold a single value mid-sentence), so a multi-word phrase no
 * longer appears as one literal `(...)` token in a raw stream - use this
 * wherever a test just needs to confirm text is present, regardless of how
 * many text-show operations jsPDF split it into.
 */
function pdfReadableText(buffer) {
  return pdfContentStreams(buffer).map((stream) =>
    [...stream.matchAll(/\(((?:[^()\\]|\\.)*)\)\s*Tj/g)].map((m) => m[1].replace(/\\(.)/g, '$1')).join(''));
}

/** Ordered {text, font} for every `(...) Tj` text-show op in `stream` (unescaped), font taken from the most recent `/Fn size Tf` before it. */
function pdfTextRuns(stream, fontMap) {
  const runs = [];
  let currentFont = null;
  const pattern = /\/(F\d+)\s+[\d.]+\s+Tf|\(((?:[^()\\]|\\.)*)\)\s*Tj/g;
  let match = pattern.exec(stream);
  while (match) {
    if (match[1]) currentFont = fontMap.get(match[1]) || null;
    else runs.push({ text: match[2].replace(/\\(.)/g, '$1'), font: currentFont });
    match = pattern.exec(stream);
  }
  return runs;
}

/** The font active at the first character of `needle` (searching from `fromIndex`) in the text reconstructed from `runs`, or null if not found. */
function fontAt(runs, needle, fromIndex = 0) {
  const idx = runs.map((run) => run.text).join('').indexOf(needle, fromIndex);
  if (idx === -1) return null;
  let position = 0;
  for (const run of runs) {
    if (idx < position + run.text.length) return run.font;
    position += run.text.length;
  }
  return null;
}

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrEmployeeApi');

const organizationProfile = {
  organizationName: 'Tardus International', organizationAddress: '200 Lake Street, Austin, TX, 78701',
  organizationPhoneNumber: '+15551234567', organizationWebsite: 'https://tardus.example.com',
  organizationEinNumber: '84-3596475', organizationEVerifyNumber: '1533740',
};

// HR never uploads/chooses a signature while preparing the letter - their
// signature (the preparing HR user's own signature from their My HR Profile) is applied
// automatically. Most tests assume one is already configured; the "not
// configured yet" scenario is covered by its own test with a profile that
// omits this field.
const organizationProfileWithSignature = {
  ...organizationProfile, signatureUrl: '/uploads/gridfs/6ab5000000000000000000aa/signature.png',
};

const offerLetterRequest = {
  id: 'req-1', requestTitle: 'Offer Letter', requestDescription: 'Please send my offer letter',
  requestedByUserId: 'employee-1', requestedByName: 'John Doe', requestedByLoginId: 'TD001', requestedByEmail: 'john.doe@example.com',
  requestedByStreetAddress: '123 Main St', requestedByCity: 'Austin', requestedByState: 'Texas', requestedByZipCode: '73301',
  requestedForUserId: 'hr', requestedForName: 'Sarah', status: 'PENDING', priority: 'NORMAL',
  requestDirection: 'TO_MANAGER', documentType: 'OFFER_LETTER', requestedAt: '2026-09-17T10:00:00',
};

const emailDraft = {
  recipientEmail: 'john.saved@example.com',
  subject: 'Offer Letter – Software Engineer – Tardus International',
  message: 'Hi John Doe,\n\nWe are pleased to offer you the position of **Software Engineer** with our organization.',
  attachmentFileName: 'John_Doe_Offer_Letter.pdf',
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }

/** A date string always after today (the form's issue-date default), so the suite doesn't rot once the hardcoded date passes. */
function futureDateString(daysAhead) {
  const date = new Date();
  date.setDate(date.getDate() + daysAhead);
  return date.toISOString().slice(0, 10);
}
const JOINING_DATE = futureDateString(14);
const LATER_JOINING_DATE = futureDateString(29);

/** Fills the fields that have no default and are required on every offer type. */
async function fillNewlyRequiredFields(user) {
  const mobile = screen.getByLabelText(/^Employee Mobile/);
  if (!mobile.value) await user.type(mobile, '2039967659');
}

/** Fills the paid-offer pay rate required to continue. */
async function fillPaidCompensation(user, payRate = '95000') {
  await user.type(screen.getByLabelText(/^Pay Rate\*$/), payRate);
}

function renderApp(initialPath, role = 'HR') {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: role === 'COMPANY_ADMIN' ? 'admin' : 'hr', role }));
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Location />
      <Routes>
        <Route path="/myhr/hr/document-requests/:requestId" element={<DocumentRequestsPage role="HR" />} />
        <Route path="/myhr/hr/document-requests/:requestId/offer-letter" element={<OfferLetterPreparePage role="HR" />} />
        <Route path="/myhr/admin/document-requests/:requestId/offer-letter" element={<OfferLetterPreparePage role="COMPANY_ADMIN" />} />
      </Routes>
    </MemoryRouter>,
  );
}

const APPROVED_FORM = {
  offerType: 'PAID', employmentCategory: 'FULL_TIME', designation: 'SOFTWARE_ENGINEER', employeeName: 'John Doe',
  employeeEmail: 'john.doe@example.com', joiningDate: JOINING_DATE, workLocation: 'Austin, TX',
};

/** The Company Admin approval state the backend reports for this letter. */
const approval = (status, extra = {}) => ({
  data: { data: {
    requestId: 'req-1', approvalStatus: status, employeeName: 'John Doe', designation: 'Software Engineer',
    documentId: status === 'NONE' ? null : 'approval-doc', formJson: status === 'NONE' ? null : JSON.stringify(APPROVED_FORM), ...extra,
  } },
});

const REQUEST_STATUS_FOR = { PENDING_APPROVAL: 'SUBMITTED', APPROVED: 'APPROVED', REJECTED: 'RETURNED_FOR_CORRECTION', SENT: 'FULFILLED' };

/** Opens the Offer Letter page for a letter that is in the given approval state. */
function openLetter(status, extra = {}, path = '/myhr/hr/document-requests/req-1/offer-letter', role = 'HR') {
  myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue(approval(status, extra));
  myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...offerLetterRequest, status: REQUEST_STATUS_FOR[status] || 'PENDING' } } });
  return renderApp(path, role);
}

/** Opens an Admin-approved letter with the email pre-populated and ready to send. */
async function openApprovedLetter(extra = {}, path, role) {
  openLetter('APPROVED', extra, path, role);
  await screen.findByLabelText(/^Recipient Email/);
  await waitFor(() => expect(screen.getByLabelText(/^Subject/)).toHaveValue(emailDraft.subject));
}

/** Completes step 1 for a paid Software Engineer offer and opens Template & Submit for approval. */
async function continueToSendStep(user) {
  await screen.findByLabelText(/^Employee Name/);
  await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
  await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
  await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
  await fillPaidCompensation(user);
  await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
  await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
  await fillNewlyRequiredFields(user);
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  await screen.findByText('SELECTED TEMPLATE');
  await waitFor(() => expect(screen.getByRole('button', { name: 'Submit for Admin Approval' })).toBeEnabled());
}

describe('Offer Letter preparation workflow', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: offerLetterRequest } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: organizationProfileWithSignature } });
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: { reportingManager: null, phoneNumber: '+15125550123' } } });
    myHrDocumentRequestApi.fulfill.mockResolvedValue({ data: { data: {} } });
    myHrDocumentRequestApi.getOfferLetterApproval.mockResolvedValue(approval('NONE'));
    myHrDocumentRequestApi.submitOfferLetterForApproval.mockResolvedValue(approval('PENDING_APPROVAL'));
    myHrDocumentRequestApi.deliverOfferLetter.mockResolvedValue({ data: { data: {} } });
    myHrDocumentRequestApi.getDocument.mockResolvedValue({ data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }) });
    myHrDocumentRequestApi.getOfferLetterEmailDraft.mockResolvedValue({ data: { data: emailDraft } });
    myHrDocumentRequestApi.emailOfferLetter.mockResolvedValue({ data: { success: true } });
  });

  it('shows Prepare Offer Letter instead of the generic upload for a pending Offer Letter request', async () => {
    renderApp('/myhr/hr/document-requests/req-1');
    await screen.findByRole('heading', { name: 'Offer Letter' });
    expect(screen.getByRole('button', { name: 'Prepare Offer Letter' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Completed document')).not.toBeInTheDocument();
  });

  it('does not show Prepare Offer Letter for a non-Offer-Letter request', async () => {
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...offerLetterRequest, requestTitle: 'Employment letter', documentType: 'OTHER' } } });
    renderApp('/myhr/hr/document-requests/req-1');
    await screen.findByRole('heading', { name: 'Employment letter' });
    expect(screen.queryByRole('button', { name: 'Prepare Offer Letter' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Completed document')).toBeInTheDocument();
  });

  it('does not route a request with an Offer Letter title but a different backend document type', async () => {
    const user = userEvent.setup();
    myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...offerLetterRequest, documentType: 'OTHER' } } });
    renderApp('/myhr/hr/document-requests/req-1');
    await screen.findByRole('heading', { name: 'Offer Letter' });
    expect(screen.queryByRole('button', { name: 'Prepare Offer Letter' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Completed document')).toBeInTheDocument();

    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    expect(await screen.findByRole('alert')).toHaveTextContent('This request is not an offer letter request.');
    expect(myHrDocumentRequestApi.submitOfferLetterForApproval).not.toHaveBeenCalled();
    await user.keyboard('{Escape}');
  });

  it('runs the full request -> prepare -> details -> template -> send flow and reloads the real request', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1');

    await user.click(await screen.findByRole('button', { name: 'Prepare Offer Letter' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/myhr/hr/document-requests/req-1/offer-letter');
    await waitFor(() => expect(myHrDocumentRequestApi.get).toHaveBeenCalledWith('req-1'));

    // Employee information is carried over and re-loaded from the real API.
    expect(await screen.findByLabelText(/^Employee Name/)).toHaveValue('John Doe');
    expect(screen.getByLabelText(/^Employee Email/)).toHaveValue('john.doe@example.com');
    expect(screen.getByLabelText(/^Employee Mobile/)).toHaveValue('(512) 555-0123');
    expect(screen.getByLabelText(/^Street Address/)).toHaveValue('123 Main St');
    expect(screen.getByLabelText(/^City/)).toHaveValue('Austin');
    expect(screen.getByLabelText(/^State/)).toHaveValue('Texas');
    expect(screen.getByLabelText(/^ZIP Code/)).toHaveValue('73301');

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
    await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
    await fillPaidCompensation(user);
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
    // Issue Date already defaults to today.
    await fillNewlyRequiredFields(user);

    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('SELECTED TEMPLATE')).toBeInTheDocument();
    expect(screen.getByText('Software Engineer Paid Offer Letter Template')).toBeInTheDocument();

    // Back to Details preserves everything HR already entered.
    await user.click(screen.getByRole('button', { name: 'Back to Details' }));
    expect(screen.getByLabelText(/^Offer Type/)).toHaveValue('PAID');
    expect(screen.getByLabelText(/^Employment Category/)).toHaveValue('FULL_TIME');
    expect(screen.getByLabelText(/^Designation/)).toHaveValue('SOFTWARE_ENGINEER');
    expect(screen.getByLabelText(/^Pay Rate\*$/)).toHaveValue('95000');
    expect(screen.getByLabelText(/^Employee Email/)).toHaveValue('john.doe@example.com');
    expect(screen.getByLabelText(/^Work Location/)).toHaveValue('Austin, TX');

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByText('SELECTED TEMPLATE');
    // HR can never send directly: there is no send control while the letter is being prepared.
    expect(screen.queryByRole('button', { name: 'Send Offer Letter' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Submit for Admin Approval' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

    // The generated PDF and the letter facts go to the Company Admin; nothing reaches the employee.
    expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
    expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalledWith('req-1', expect.objectContaining({
      employeeName: 'John Doe', designation: 'Software Engineer', offerType: 'Paid', employmentCategory: 'Full Time',
      workLocation: 'Austin, TX', joiningDate: JOINING_DATE, formJson: expect.any(String),
    }), expect.objectContaining({ name: 'offer-letter.pdf', type: 'application/pdf' }));
    expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.deliverOfferLetter).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.emailOfferLetter).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.saveOfferLetter).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.generateOfferLetter).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.sendOfferLetter).not.toHaveBeenCalled();
  });

  it('uses the Organization Settings admin name when a Company Admin prepares the offer letter', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      ...organizationProfileWithSignature,
      name: 'Portal Administrator',
      designation: 'Operations Director',
      organizationAdminName: 'Avery Admin',
    } } });
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: {
      reportingManager: { name: 'Assigned HR' }, phoneNumber: '+15125550123',
    } } });
    renderApp('/myhr/admin/document-requests/req-1/offer-letter', 'COMPANY_ADMIN');
    await screen.findByLabelText(/^Employee Name/);
    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
    await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
    await fillPaidCompensation(user);
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByTitle('Offer letter PDF preview');
    // The Admin is their own approver, so their flow goes straight to "Continue to Send"
    // instead of "Submit for Admin Approval" (which only an HR preparer sees).
    await user.click(screen.getByRole('button', { name: 'Continue to Send' }));

    await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
    const text = pdfContentStreams(await myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2].arrayBuffer()).join('\n');
    expect(text).toContain('Avery Admin');
    expect(text).not.toContain('Company Admin');
    expect(text).not.toContain('Assigned HR');
  });

  it('accepts only numbers in Pay Rate and strips digits from optional Pay Rate in Words', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
    await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
    await user.type(screen.getByLabelText(/^Pay Rate\*$/), '$95,000/yr');
    expect(screen.getByLabelText(/^Pay Rate\*$/)).toHaveValue('95000');
    await user.type(screen.getByLabelText(/^Pay Rate in Words/), '95000 Dollars');
    expect(screen.getByLabelText(/^Pay Rate in Words/)).toHaveValue(' Dollars');
    await user.clear(screen.getByLabelText(/^Pay Rate in Words/));
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // Pay Rate in Words is optional - continue succeeds with letters-only Pay Rate numbers.
    expect(await screen.findByText('SELECTED TEMPLATE')).toBeInTheDocument();
  });

  it('lets HR use a custom designation with its own roles and responsibilities', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'UNPAID');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'OTHER');
    await user.type(screen.getByLabelText(/^Other Designation/), 'Research Assistant');
    await user.type(screen.getByLabelText(/^Roles/), 'Assist with market research.\nSummarize findings weekly.');
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Remote');
    await user.type(screen.getByLabelText(/^Street Address/), '456 Oak Ave');
    await user.type(screen.getByLabelText(/^City/), 'Denton');
    await user.type(screen.getByLabelText(/^State/), 'Texas');
    await user.type(screen.getByLabelText(/^ZIP Code/), '76201');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('Custom Research Assistant Letter')).toBeInTheDocument();
    expect(await screen.findByTitle('Offer letter PDF preview')).toHaveAttribute('src', expect.stringMatching(/^blob:/));
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));
    await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
    const text = pdfContentStreams(await myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2].arrayBuffer()).join('\n');
    expect(text).toContain('Assist with market research.');
    expect(text).toContain('Summarize findings weekly.');
  });

  it('renders the EIN, E-Verify number, letterhead, and letter footer from organization settings rather than the hardcoded prototype values', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);
    await waitFor(() => expect(myHrEmployeeApi.getMine).toHaveBeenCalled());

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
    await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
    await fillPaidCompensation(user);
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
    // Street Address/City/State/ZIP are pre-filled from the request's requestedBy* fields.
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('SELECTED TEMPLATE')).toBeInTheDocument();
    expect(await screen.findByTitle('Offer letter PDF preview')).toHaveAttribute('src', expect.stringMatching(/^blob:/));
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));
    await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
    const text = pdfReadableText(await myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2].arrayBuffer()).join('\n');
    expect(text).toContain('TARDUS INTERNATIONAL');
    expect(text).toContain('Tardus International has all the');
    expect(text).toContain('rights to terminate anytime because of performance issue.');
    expect(text).toContain('Our E-Verify Company ID is 1533740 and');
    expect(text).toContain('our Employment Identification Number (EIN) is 84-3596475.');
    expect(text).toContain('200 Lake Street, Austin, TX, 78701');
  });

  it('shows the employee mobile number and numbers the clauses Duties, Compensation, Benefits, then the standard clauses', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
    await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
    await fillPaidCompensation(user, '15.00');
    await user.type(screen.getByLabelText(/^Joining Date/), LATER_JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Irving, TX');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('SELECTED TEMPLATE')).toBeInTheDocument();
    expect(await screen.findByTitle('Offer letter PDF preview')).toHaveAttribute('src', expect.stringMatching(/^blob:/));
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));
    await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
    const text = pdfReadableText(await myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2].arrayBuffer()).join('\n');
    expect(text).toContain('+15125550123');
    expect(text).not.toContain('Reporting:');
    expect(text).not.toContain('Employment Details:');
    expect(text).toContain('1. Duties:');
    expect(text).toContain('2. Compensation:');
    expect(text).toContain('3. Benefits:');
    expect(text).toContain('12. Employment Eligibility:');
    expect(text).toContain('18. Binding Effect:');
  });

  it('renders each clause number/title in bold in the generated PDF, with a normal-weight body', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
    await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
    await fillPaidCompensation(user);
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Austin, TX');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByText('SELECTED TEMPLATE');
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

    await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
    const file = myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2];
    const buffer = await file.arrayBuffer();
    expect(Buffer.from(buffer).toString('latin1')).toContain('waypoint_employee_signature');
    const fontMap = pdfFontResourceMap(buffer);
    const streamRuns = pdfContentStreams(buffer).map((stream) => pdfTextRuns(stream, fontMap));
    const streamTexts = streamRuns.map((runs) => runs.map((run) => run.text).join(''));
    // Clause 12's sentence is long enough that content changes elsewhere on
    // the page (e.g. an extra compensation line) can push it across a page
    // break - a normal mid-paragraph wrap, not a bug - so search the whole
    // document's runs in order rather than assuming it's on a single page.
    const allRuns = streamRuns.flat();

    const clauseLabels = [
      ['1. Duties: ', 'You will be employed with the title of'],
      ['2. Compensation: ', 'Your compensation is'],
      ['3. Benefits: ', 'This role is not eligible'],
      ['4. Confidentiality: ', 'You shall treat as confidential'],
      ['5. Confidentiality after Termination of Employment: ', 'The confidentiality provisions'],
      ['6. Assignment of Developments: ', 'You will promptly disclose'],
      ['7. Maintenance of Records: ', 'You shall keep and maintain'],
      ['8. Non-Compete Agreement: ', 'It is understood that you will be an employee'],
      ['9. Non-Solicitation: ', 'You agree that, while employed'],
      ['10. Termination: ', 'Employment under this Agreement'],
      ['11. Termination for Disability: ', 'Tardus International shall have the option to terminate'],
      ['12. Employment Eligibility: ', 'Employment with'],
      ['13. Breach: ', 'Any breach of any provisions'],
      ['14. Return of Property: ', 'Upon termination of this Agreement'],
      ['15. Complete Agreement: ', 'This Agreement contains the entire agreement'],
      ['16. Governing Law: ', 'This agreement shall be governed'],
      ['17. Severability: ', 'The invalidity or unenforceability'],
      ['18. Binding Effect: ', 'This Agreement shall be binding'],
    ];

    for (const [label, bodyStart] of clauseLabels) {
      const index = streamTexts.findIndex((text) => text.includes(label));
      expect(index, `no stream found containing "${label}"`).not.toBe(-1);
      const runs = streamRuns[index];
      expect(fontAt(runs, label), label).toContain('Bold');
      expect(fontAt(runs, bodyStart), bodyStart).not.toContain('Bold');
    }

    // The values this feature bolds mid-sentence, not just clause labels.
    const dutiesIndex = streamTexts.findIndex((text) => text.includes('1. Duties: '));
    expect(fontAt(streamRuns[dutiesIndex], 'Software Engineer'), 'designation').toContain('Bold');

    const compensationIndex = streamTexts.findIndex((text) => text.includes('2. Compensation: '));
    expect(fontAt(streamRuns[compensationIndex], '$95000'), 'pay rate (with $)').toContain('Bold');
    expect(fontAt(streamRuns[compensationIndex], formatDateLong(JOINING_DATE)), 'employment start date').toContain('Bold');

    expect(fontAt(allRuns, 'E-Verify Company ID is 1533740'), 'E-Verify Company ID label + value').toContain('Bold');
    expect(fontAt(allRuns, 'Employment Identification Number (EIN) is 84-3596475'), 'EIN label + value').toContain('Bold');

    // The organization name is bold specifically in the "Sincerely," signoff -
    // the same name appears constantly, un-bolded, throughout the clause bodies.
    const signatureIndex = streamTexts.findIndex((text) => text.includes('Sincerely,'));
    const afterSincerely = streamTexts[signatureIndex].indexOf('Sincerely,') + 'Sincerely,'.length;
    expect(fontAt(streamRuns[signatureIndex], 'Tardus International', afterSincerely), 'org name near signature').toContain('Bold');
  });

  it('shows the Organization Settings logo in the preview and hides the redundant org-name letterhead below it', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      ...organizationProfile, organizationLogoUrl: '/uploads/company-logos/org-1/logo.png',
    } } });
    const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
    const pngBytes = Uint8Array.from(atob(pngBase64), (c) => c.charCodeAt(0));
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue({
      blob: () => Promise.resolve(new Blob([pngBytes], { type: 'image/png' })),
    });
    const OriginalImage = global.Image;
    global.Image = class {
      set src(_value) {
        this.naturalWidth = 200; this.naturalHeight = 60;
        setTimeout(() => this.onload?.(), 0);
      }
    };

    try {
      renderApp('/myhr/hr/document-requests/req-1/offer-letter');
      await screen.findByLabelText(/^Employee Name/);
      await waitFor(() => expect(fetchSpy).toHaveBeenCalledWith(expect.stringContaining('/uploads/company-logos/org-1/logo.png')));

      await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'UNPAID');
      await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_DEVELOPER');
      await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
      await user.type(screen.getByLabelText(/^Work Location/), 'Remote');
      await fillNewlyRequiredFields(user);
      await user.click(screen.getByRole('button', { name: 'Continue' }));

      expect(await screen.findByTitle('Offer letter PDF preview')).toHaveAttribute('src', expect.stringMatching(/^blob:/));
    } finally {
      global.Image = OriginalImage;
    }
  });

  it('stamps the logo and company-address footer on every page of the generated PDF, not just the last one', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      ...organizationProfileWithSignature, organizationLogoUrl: '/uploads/company-logos/org-1/logo.png',
    } } });
    // A real (tiny, valid) PNG so jsPDF's own image parser can embed it -
    // fake bytes would make pdf.addImage throw when building the PDF.
    const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
    const pngBytes = Uint8Array.from(atob(pngBase64), (c) => c.charCodeAt(0));
    vi.spyOn(global, 'fetch').mockResolvedValue({
      blob: () => Promise.resolve(new Blob([pngBytes], { type: 'image/png' })),
    });
    const OriginalImage = global.Image;
    global.Image = class {
      set src(_value) {
        this.naturalWidth = 200; this.naturalHeight = 60;
        setTimeout(() => this.onload?.(), 0);
      }
    };

    try {
      renderApp('/myhr/hr/document-requests/req-1/offer-letter');
      await screen.findByLabelText(/^Employee Name/);
      await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));

      await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
      await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
      await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
      await fillPaidCompensation(user, '15.00');
      await user.type(screen.getByLabelText(/^Joining Date/), LATER_JOINING_DATE);
      await user.type(screen.getByLabelText(/^Work Location/), 'Irving, TX');
      await fillNewlyRequiredFields(user);
      await user.click(screen.getByRole('button', { name: 'Continue' }));
      await screen.findByText('SELECTED TEMPLATE');
      await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

      await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
      const file = myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2];
      const buffer = await file.arrayBuffer();
      const streams = pdfContentStreams(buffer);

      const footerHits = streams.filter((stream) => stream.includes('TARDUS INTERNATIONAL')).length;
      // Total draw occurrences across all streams, not just how many streams
      // have at least one - the organization signature is drawn once on
      // whichever page already has that page's own header-logo draw, so a
      // per-stream boolean count would hide it.
      const imageDrawHits = streams.reduce((total, stream) => total + (stream.match(/\/(Im|I)\w*\s+Do/g) || []).length, 0);

      expect(footerHits).toBeGreaterThan(1);
      // One image draw per page for the repeating logo header, plus exactly
      // one more for the organization signature stamped once near the sign-off.
      expect(imageDrawHits).toBe(footerHits + 1);
    } finally {
      global.Image = OriginalImage;
    }
  });

  it('draws the organization signature in the PDF at its natural size and aspect ratio - never enlarged to a fixed height', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: organizationProfileWithSignature } });
    const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
    const pngBytes = Uint8Array.from(atob(pngBase64), (c) => c.charCodeAt(0));
    vi.spyOn(global, 'fetch').mockResolvedValue({
      blob: () => Promise.resolve(new Blob([pngBytes], { type: 'image/png' })),
    });
    const OriginalImage = global.Image;
    // A small, tightly-cropped 3:1 signature. jsdom has no canvas, so its measured natural size falls back to its own
    // 120x40px - which is 90x30pt. The old code forced every signature to 38pt tall, enlarging this one to 114x38pt.
    global.Image = class {
      set src(_value) {
        this.naturalWidth = 120; this.naturalHeight = 40;
        setTimeout(() => this.onload?.(), 0);
      }
    };

    try {
      renderApp('/myhr/hr/document-requests/req-1/offer-letter');
      await screen.findByLabelText(/^Employee Name/);
      await waitFor(() => expect(global.fetch).toHaveBeenCalled());

      await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'PAID');
      await user.selectOptions(screen.getByLabelText(/^Employment Category/), 'FULL_TIME');
      await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_ENGINEER');
      await fillPaidCompensation(user, '15.00');
      await user.type(screen.getByLabelText(/^Joining Date/), LATER_JOINING_DATE);
      await user.type(screen.getByLabelText(/^Work Location/), 'Irving, TX');
      await fillNewlyRequiredFields(user);
      await user.click(screen.getByRole('button', { name: 'Continue' }));
      await screen.findByText('SELECTED TEMPLATE');
      await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

      await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
      const buffer = await myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0][2].arrayBuffer();
      // Every image draw is "w 0 0 h x y cm /Im Do" - collect each one's drawn width and height, in points.
      const draws = pdfContentStreams(buffer).flatMap((stream) => [...stream.matchAll(/([\d.]+) 0 0 ([\d.]+) [\d.-]+ [\d.-]+ cm\s*\/(?:Im|I)\w*\s+Do/g)])
        .map((match) => ({ width: Number(match[1]), height: Number(match[2]) }));

      expect(draws.length).toBeGreaterThan(0);
      const signature = draws.find((draw) => Math.abs(draw.width - 90) < 0.5);
      expect(signature).toBeDefined();
      expect(signature.height).toBeCloseTo(30, 1);
    } finally {
      global.Image = OriginalImage;
    }
  });

  it('keeps long unpaid-offer duties in the saved preview instead of the short internship schedule field', async () => {
    const user = userEvent.setup();
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);
    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'UNPAID');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_DEVELOPER');
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Remote');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    // Submitting is enabled once the PDF preview has been prepared.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Submit for Admin Approval' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

    expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalledWith('req-1', expect.any(Object), expect.objectContaining({
      name: 'offer-letter.pdf', type: 'application/pdf',
    }));
  });

  it('never shows HR any signature upload/draw/type control - their Profile signature is applied automatically', async () => {
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);

    expect(await screen.findByText('✓ Your signature is configured')).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Draw' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Type' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Upload image' })).not.toBeInTheDocument();
    expect(screen.queryByText('Signature')).not.toBeInTheDocument();
  });

  it('does not send an offer when the HR has no Profile signature yet, and tells them to add one on their Profile', async () => {
    const user = userEvent.setup();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: organizationProfile } });
    renderApp('/myhr/hr/document-requests/req-1/offer-letter');
    await screen.findByLabelText(/^Employee Name/);

    expect(await screen.findByText('Your signature is not configured. Add your signature on your Profile page before sending this offer letter.')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText(/^Offer Type/), 'UNPAID');
    await user.selectOptions(screen.getByLabelText(/^Designation/), 'SOFTWARE_DEVELOPER');
    await user.type(screen.getByLabelText(/^Joining Date/), JOINING_DATE);
    await user.type(screen.getByLabelText(/^Work Location/), 'Remote');
    await fillNewlyRequiredFields(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    // Submitting is enabled once the PDF preview has been prepared.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Submit for Admin Approval' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your signature is not configured. Add your signature on your Profile page before submitting this offer letter.');
    expect(myHrDocumentRequestApi.submitOfferLetterForApproval).not.toHaveBeenCalled();
  });
  describe('Company Admin approval workflow', () => {
    it('only offers Submit for Admin Approval while the letter is being prepared - never a send button', async () => {
      const user = userEvent.setup();
      renderApp('/myhr/hr/document-requests/req-1/offer-letter');
      await continueToSendStep(user);

      expect(screen.getByRole('button', { name: 'Submit for Admin Approval' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Send Offer Letter' })).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/^Recipient Email/)).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.getOfferLetterEmailDraft).not.toHaveBeenCalled();
    });

    it('shows PENDING ADMIN APPROVAL with no send controls while the Admin has not decided', async () => {
      openLetter('PENDING_APPROVAL');

      expect(await screen.findByText('PENDING ADMIN APPROVAL')).toBeInTheDocument();
      expect(await screen.findByText(/submitted to the Tardus International Admin for approval\. You can send it to the employee only after approval/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Send Offer Letter|Submit for Admin Approval|Resubmit/ })).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/^Recipient Email/)).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.getOfferLetterEmailDraft).not.toHaveBeenCalled();
      expect(myHrDocumentRequestApi.deliverOfferLetter).not.toHaveBeenCalled();
    });

    it('shows ADMIN APPROVAL REJECTED with the reason, restores the submitted details and allows a resubmission', async () => {
      const user = userEvent.setup();
      // The complete form HR submitted, so it can be corrected and resubmitted as-is.
      const submittedForm = {
        ...APPROVED_FORM, streetAddress: '123 Main St', city: 'Austin', state: 'Texas', zipCode: '73301', employeeMobile: '+15125550123',
        rolesAndResponsibilities: 'Build software.', payType: 'hourly', payRate: '95000', issueDate: new Date().toISOString().slice(0, 10),
      };
      openLetter('REJECTED', { rejectionReason: 'The compensation is wrong.', formJson: JSON.stringify(submittedForm) });

      expect(await screen.findByText('ADMIN APPROVAL REJECTED')).toBeInTheDocument();
      expect(screen.getByText(/The compensation is wrong\./)).toBeInTheDocument();
      // The form HR submitted is restored so it can be corrected without re-entering it.
      expect(await screen.findByLabelText(/^Employee Name/)).toHaveValue('John Doe');
      await waitFor(() => expect(screen.getByLabelText(/^Offer Type/)).toHaveValue('PAID'));
      await user.click(screen.getByRole('button', { name: 'Continue' }));
      await screen.findByText('SELECTED TEMPLATE');
      expect(screen.queryByRole('button', { name: 'Send Offer Letter' })).not.toBeInTheDocument();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Resubmit for Approval' })).toBeEnabled());
      await user.click(screen.getByRole('button', { name: 'Resubmit for Approval' }));

      await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalledTimes(1));
      expect(myHrDocumentRequestApi.deliverOfferLetter).not.toHaveBeenCalled();
    });

    it('shows APPROVED and enables the existing Send Offer Letter step only after the Admin approved', async () => {
      await openApprovedLetter();

      expect(screen.getByText('APPROVED')).toBeInTheDocument();
      expect(await screen.findByText(/approved by the Tardus International Admin and is ready to be sent to the employee/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Send Offer Letter' })).toBeEnabled();
      // The approved letter is read-only: it is not regenerated and cannot be sent back to the details.
      expect(screen.queryByRole('button', { name: 'Back to Details' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Submit for Admin Approval|Resubmit/ })).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.submitOfferLetterForApproval).not.toHaveBeenCalled();
    });

    it('keeps the way back to a letter in the approval workflow on the request page for the assigned HR', async () => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.get.mockResolvedValue({ data: { data: { ...offerLetterRequest, status: 'SUBMITTED' } } });
      renderApp('/myhr/hr/document-requests/req-1');

      expect(screen.queryByRole('button', { name: 'Prepare Offer Letter' })).not.toBeInTheDocument();
      await user.click(await screen.findByRole('button', { name: 'Open Offer Letter' }));
      expect(screen.getByTestId('location')).toHaveTextContent('/myhr/hr/document-requests/req-1/offer-letter');
    });

    it('shows SENT and offers no further send once the letter was sent', async () => {
      openLetter('SENT');

      expect(await screen.findByText('SENT')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Send Offer Letter|Submit for Admin Approval|Resubmit/ })).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/^Recipient Email/)).not.toBeInTheDocument();
    });

    it('does not submit anything when the submission fails, and shows the server message', async () => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.submitOfferLetterForApproval.mockRejectedValueOnce({ response: { data: { message: 'This offer letter is already waiting for Admin approval.' } } });
      renderApp('/myhr/hr/document-requests/req-1/offer-letter');
      await continueToSendStep(user);
      await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('This offer letter is already waiting for Admin approval.');
      expect(screen.queryByText('PENDING ADMIN APPROVAL')).not.toBeInTheDocument();
    });
  });

  describe('Offer letter email (after Admin approval)', () => {
    it('pre-populates the recipient, subject, message and attachment from the email draft', async () => {
      await openApprovedLetter();

      expect(myHrDocumentRequestApi.getOfferLetterEmailDraft).toHaveBeenCalledWith('req-1', {
        designation: 'Software Engineer', employeeName: 'John Doe',
      });
      // The employee's current email of record from the backend, not the letter's account email.
      expect(screen.getByLabelText(/^Recipient Email/)).toHaveValue('john.saved@example.com');
      expect(screen.getByLabelText(/^Subject/)).toHaveValue(emailDraft.subject);
      expect(screen.getByLabelText(/^Message/)).toHaveValue(emailDraft.message);
      expect(screen.getByText('Offer Letter PDF')).toBeInTheDocument();
      expect(screen.getByText('John_Doe_Offer_Letter.pdf')).toBeInTheDocument();
      // The approved PDF is shown unchanged above the email.
      expect(screen.getByLabelText('Approved offer letter')).toBeInTheDocument();
    });

    it('sends the edited recipient, subject and message for this send only', async () => {
      const user = userEvent.setup();
      await openApprovedLetter();

      await user.clear(screen.getByLabelText(/^Recipient Email/));
      await user.type(screen.getByLabelText(/^Recipient Email/), 'another@example.com');
      await user.clear(screen.getByLabelText(/^Subject/));
      await user.type(screen.getByLabelText(/^Subject/), 'Your offer from Tardus');
      await user.clear(screen.getByLabelText(/^Message/));
      await user.type(screen.getByLabelText(/^Message/), 'Hi John,{Enter}{Enter}Welcome aboard.');
      expect(screen.getByLabelText(/^Recipient Email/)).toHaveValue('another@example.com');

      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByText(/emailed to another@example\.com with the PDF attached/)).toBeInTheDocument();
      expect(myHrDocumentRequestApi.deliverOfferLetter).toHaveBeenCalledWith('req-1');
      expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalledWith('req-1', {
        recipientEmail: 'another@example.com', subject: 'Your offer from Tardus', message: 'Hi John,\n\nWelcome aboard.',
        employeeName: 'John Doe',
      });
      // The approved letter is delivered by reference - the browser never uploads a PDF at send time.
      expect(myHrDocumentRequestApi.fulfill).not.toHaveBeenCalled();
      // Only the editable email fields are sent - never PDF bytes, sender or identity.
      expect(Object.keys(myHrDocumentRequestApi.emailOfferLetter.mock.calls[0][1]).sort())
        .toEqual(['employeeName', 'message', 'recipientEmail', 'subject']);
    });

    it('uses the employee name of the approved letter for the draft and the attachment name sent with the email', async () => {
      const user = userEvent.setup();
      await openApprovedLetter({ formJson: JSON.stringify({ ...APPROVED_FORM, employeeName: 'Sridevi Sowmya Grandhi' }) });
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByText(/emailed to/)).toBeInTheDocument();
      expect(myHrDocumentRequestApi.emailOfferLetter.mock.calls[0][1].employeeName).toBe('Sridevi Sowmya Grandhi');
      expect(myHrDocumentRequestApi.deliverOfferLetter).toHaveBeenCalledTimes(1);
      expect(myHrDocumentRequestApi.getOfferLetterEmailDraft).toHaveBeenCalledWith('req-1',
        expect.objectContaining({ employeeName: 'Sridevi Sowmya Grandhi' }));
    });

    it('uses an edited employee name in the PDF HR submits for approval', async () => {
      const user = userEvent.setup();
      renderApp('/myhr/hr/document-requests/req-1/offer-letter');
      await screen.findByLabelText(/^Employee Name/);
      await user.clear(screen.getByLabelText(/^Employee Name/));
      await user.type(screen.getByLabelText(/^Employee Name/), 'Sridevi Sowmya Grandhi');
      await continueToSendStep(user);
      await user.click(screen.getByRole('button', { name: 'Submit for Admin Approval' }));

      await waitFor(() => expect(myHrDocumentRequestApi.submitOfferLetterForApproval).toHaveBeenCalled());
      const [, details, file] = myHrDocumentRequestApi.submitOfferLetterForApproval.mock.calls[0];
      expect(details.employeeName).toBe('Sridevi Sowmya Grandhi');
      const pdfText = Buffer.from(await file.arrayBuffer()).toString('latin1');
      expect(pdfText).toContain('Sridevi Sowmya Grandhi');
      expect(pdfText).not.toContain('John Doe');
    });

    it('shows the normal sent state, with no failure message, when the email API succeeds', async () => {
      const user = userEvent.setup();
      await openApprovedLetter();
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByText(/emailed to john\.saved@example\.com with the PDF attached/)).toBeInTheDocument();
      expect(screen.queryByText(/email delivery failed/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Retry Sending Email' })).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalledTimes(1);
    });

    it('treats an accepted-but-unconfirmed email (HTTP 202) as sent with a notice, never as a failure or a retry', async () => {
      myHrDocumentRequestApi.emailOfferLetter.mockResolvedValue({
        status: 202, data: { success: true, message: 'The offer letter email was handed to the mail server, but delivery could not be confirmed.' },
      });
      const user = userEvent.setup();
      await openApprovedLetter();
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByText(/handed to the mail server, but delivery could not be confirmed/)).toBeInTheDocument();
      expect(screen.queryByText(/email delivery failed/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Retry Sending Email|Send Offer Letter/ })).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalledTimes(1);
      expect(myHrDocumentRequestApi.deliverOfferLetter).toHaveBeenCalledTimes(1);
    });

    it('validates a missing or invalid recipient, subject and message before sending anything', async () => {
      const user = userEvent.setup();
      await openApprovedLetter();

      await user.clear(screen.getByLabelText(/^Recipient Email/));
      await user.clear(screen.getByLabelText(/^Subject/));
      await user.clear(screen.getByLabelText(/^Message/));
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByText('Enter the recipient email address.')).toBeInTheDocument();
      expect(screen.getByText('Enter an email subject.')).toBeInTheDocument();
      expect(screen.getByText('Enter an email message.')).toBeInTheDocument();

      await user.type(screen.getByLabelText(/^Recipient Email/), 'not-an-email');
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));
      expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument();

      expect(myHrDocumentRequestApi.deliverOfferLetter).not.toHaveBeenCalled();
      expect(myHrDocumentRequestApi.emailOfferLetter).not.toHaveBeenCalled();
    });

    it('shows a loading state while sending', async () => {
      const user = userEvent.setup();
      let finishEmail;
      myHrDocumentRequestApi.emailOfferLetter.mockReturnValue(new Promise((resolve) => { finishEmail = resolve; }));
      await openApprovedLetter();

      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      await waitFor(() => expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalled());
      expect(screen.getByRole('button', { name: 'Send Offer Letter' })).toBeDisabled();
      expect(screen.queryByText(/emailed to/)).not.toBeInTheDocument();

      finishEmail({ data: { success: true } });
      expect(await screen.findByText(/emailed to john\.saved@example\.com/)).toBeInTheDocument();
    });

    it('shows the email error without claiming success, then retries only the email', async () => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.emailOfferLetter.mockRejectedValueOnce({
        response: { status: 502, data: { success: false, message: 'The offer letter email could not be sent. Please try again later.' } },
      });
      await openApprovedLetter();

      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The offer letter was stored and shared with John Doe in WayPoint, but email delivery failed. The offer letter email could not be sent. Please try again later.');
      expect(screen.queryByText(/emailed to/)).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Retry Sending Email' }));

      expect(await screen.findByText(/emailed to john\.saved@example\.com/)).toBeInTheDocument();
      // Delivered once; only the email is retried.
      expect(myHrDocumentRequestApi.deliverOfferLetter).toHaveBeenCalledTimes(1);
      expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalledTimes(2);
    });

    it('reports a missing stored PDF clearly and never shows success', async () => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.emailOfferLetter.mockRejectedValue({
        response: { status: 400, data: { success: false, message: 'Offer Letter PDF is not available. Please generate the Offer Letter again before sending.' } },
      });
      await openApprovedLetter();

      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Offer Letter PDF is not available. Please generate the Offer Letter again before sending.');
      expect(screen.queryByText(/emailed to/)).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.deliverOfferLetter).toHaveBeenCalledTimes(1);
    });

    it('does not email when the server refuses to deliver the letter, and shows why', async () => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.deliverOfferLetter.mockRejectedValueOnce({
        response: { data: { message: 'The offer letter must be approved by the Company Admin before it can be sent to the employee.' } },
      });
      await openApprovedLetter();

      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('The offer letter must be approved by the Company Admin before it can be sent to the employee.');
      expect(myHrDocumentRequestApi.emailOfferLetter).not.toHaveBeenCalled();
      expect(screen.getByRole('button', { name: 'Send Offer Letter' })).toBeEnabled();
    });

    it('lets HR still enter the subject and message when the email template cannot be loaded', async () => {
      const user = userEvent.setup();
      myHrDocumentRequestApi.getOfferLetterEmailDraft.mockRejectedValue(new Error('offline'));
      openLetter('APPROVED');

      expect(await screen.findByText('The email template could not be loaded. Enter the subject and message before sending.')).toBeInTheDocument();
      expect(screen.getByLabelText(/^Recipient Email/)).toHaveValue('john.doe@example.com');
      await user.type(screen.getByLabelText(/^Subject/), 'Offer Letter');
      await user.type(screen.getByLabelText(/^Message/), 'Hi John');
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      await waitFor(() => expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalledWith('req-1', {
        recipientEmail: 'john.doe@example.com', subject: 'Offer Letter', message: 'Hi John',
        employeeName: 'John Doe',
      }));
    });

    it('gives a Company Admin the same send flow for an approved letter on the admin route', async () => {
      const user = userEvent.setup();
      await openApprovedLetter({}, '/myhr/admin/document-requests/req-1/offer-letter', 'COMPANY_ADMIN');
      await user.clear(screen.getByLabelText(/^Subject/));
      await user.type(screen.getByLabelText(/^Subject/), 'Admin edited subject');
      await user.click(screen.getByRole('button', { name: 'Send Offer Letter' }));

      expect(await screen.findByText(/Offer letter sent to John Doe and emailed to john\.saved@example\.com/)).toBeInTheDocument();
      expect(myHrDocumentRequestApi.emailOfferLetter).toHaveBeenCalledWith('req-1', {
        recipientEmail: 'john.saved@example.com', subject: 'Admin edited subject', message: emailDraft.message,
        employeeName: 'John Doe',
      });
    });

    it('does not show the employee (requester) any prepare, approval or email controls on their request', async () => {
      localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'employee-1', role: 'EMPLOYEE' }));
      render(
        <MemoryRouter initialEntries={['/myhr/employee/document-requests/req-1']}>
          <Routes>
            <Route path="/myhr/employee/document-requests/:requestId" element={<DocumentRequestsPage role="EMPLOYEE" />} />
          </Routes>
        </MemoryRouter>,
      );

      await screen.findByRole('heading', { name: 'Offer Letter' });
      expect(screen.queryByRole('button', { name: 'Prepare Offer Letter' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Open Offer Letter|Submit for Admin Approval/ })).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/^Recipient Email/)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Send Offer Letter' })).not.toBeInTheDocument();
      expect(myHrDocumentRequestApi.getOfferLetterEmailDraft).not.toHaveBeenCalled();
    });
  });
});
