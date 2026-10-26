import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import OrganizationSettingsPage from '../../../pages/myhr/features/admin/OrganizationSettingsPage';
import myHrOrganizationSettingsApi from '../../../api/myHrOrganizationSettingsApi';

vi.mock('../../../api/myHrOrganizationSettingsApi');

const settingsResponse = (overrides = {}) => ({ data: { data: {
  organizationId: 'org-1', organizationCode: 'TD', organizationEmail: 'hr@tardus.test',
  organizationName: 'Tardus Inc', phoneNumber: '+13125550100', streetAddress: '1 Old Street',
  city: 'Chicago', state: 'IL', zipCode: '60601', organizationSize: 'MEDIUM', industry: 'Software',
  website: 'https://tardus.test', einNumber: '84-3596475', eVerifyNumber: '1533740',
  description: 'We build things.', payrollPeriod: 'BIWEEKLY', ...overrides,
} } });

const authorizedSignature = (overrides = {}) => ({ data: { data: {
  signatoryName: null, signatoryTitle: null, effectiveDate: null, active: false, signatureOwnerUserId: 'admin-1',
  signatureOwnerName: 'admin', signatureOwnerIsViewer: true, signatureConfigured: false, inEffect: false, signatureUrl: null,
  ...overrides,
} } });

function renderPage() { return render(<MemoryRouter><OrganizationSettingsPage /></MemoryRouter>); }

describe('OrganizationSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrOrganizationSettingsApi.get.mockResolvedValue(settingsResponse());
    myHrOrganizationSettingsApi.update.mockResolvedValue(settingsResponse({ description: 'Updated description', payrollPeriod: 'MONTHLY' }));
    myHrOrganizationSettingsApi.getAuthorizedSignature.mockResolvedValue(authorizedSignature());
  });

  it('shows the Authorized Signature taken from the admin Profile, with no upload on this page', async () => {
    myHrOrganizationSettingsApi.getAuthorizedSignature.mockResolvedValue(authorizedSignature({
      signatoryName: 'Jane Admin', signatoryTitle: 'President', effectiveDate: '2026-01-01', active: true, inEffect: true,
      signatureConfigured: true, signatureUrl: '/uploads/gridfs/abc/signature.png',
    }));
    renderPage();
    expect(await screen.findByText('Jane Admin')).toBeInTheDocument();
    expect(screen.getByText('President')).toBeInTheDocument();
    expect(screen.getByAltText('Authorized signature')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'My ADMIN Profile' })).toHaveAttribute('href', '/myhr/admin/profile');
    expect(screen.queryByText('Upload Signature')).not.toBeInTheDocument();
  });

  it('keeps the profile signature visible while the organization signature is Inactive', async () => {
    myHrOrganizationSettingsApi.getAuthorizedSignature.mockResolvedValue(authorizedSignature({
      signatureConfigured: true, signatureUrl: '/uploads/gridfs/abc/signature.png', active: false, inEffect: false,
    }));
    renderPage();
    expect(await screen.findByAltText('Authorized signature')).toBeInTheDocument();
    expect(screen.getAllByText('Inactive').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Not provided').length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText('Active')).not.toBeInTheDocument();
  });

  it('shows a future-dated active signature as pending, not Active', async () => {
    myHrOrganizationSettingsApi.getAuthorizedSignature.mockResolvedValue(authorizedSignature({
      signatoryName: 'Jane Admin', signatoryTitle: 'President', effectiveDate: '2099-01-01', active: true, inEffect: false,
      signatureConfigured: true, signatureUrl: '/uploads/gridfs/abc/signature.png',
    }));
    renderPage();
    expect((await screen.findAllByText(/^Active from/)).length).toBe(2);
    expect(screen.queryByText('Active')).not.toBeInTheDocument();
  });

  it('shows Active after saving a signature that is genuinely in effect, and gives Edit Authorization its own hover class', async () => {
    myHrOrganizationSettingsApi.updateAuthorizedSignature.mockResolvedValue(authorizedSignature({
      signatoryName: 'Jane Admin', signatoryTitle: 'President', effectiveDate: '2026-01-01', active: true, inEffect: true,
      signatureConfigured: true, signatureUrl: '/uploads/gridfs/abc/signature.png',
    }));
    const user = userEvent.setup(); renderPage();
    const edit = await screen.findByRole('button', { name: 'Edit Authorization' });
    expect(edit).toHaveClass('authorized-signature__edit');
    await user.click(edit);
    await user.type(screen.getByLabelText(/^Authorized Signatory Name/), 'Jane Admin');
    await user.type(screen.getByLabelText(/^Authorized Signatory Title/), 'President');
    await user.type(screen.getByLabelText(/^Effective Date/), '2026-01-01');
    await user.click(screen.getByLabelText(/^Active/));
    await user.click(screen.getByRole('button', { name: 'Save Signatory' }));
    expect((await screen.findAllByText('Active')).length).toBe(2);
    expect(screen.getByText('Jane Admin')).toBeInTheDocument();
  });

  it('does not save and shows the backend message when activation is rejected for a missing profile signature', async () => {
    myHrOrganizationSettingsApi.updateAuthorizedSignature.mockRejectedValue({ response: { status: 400, data: { message: 'Upload your signature in My HR Profile before marking the authorized signature active' } } });
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Authorization' }));
    await user.type(screen.getByLabelText(/^Authorized Signatory Name/), 'Jane Admin');
    await user.type(screen.getByLabelText(/^Authorized Signatory Title/), 'President');
    await user.type(screen.getByLabelText(/^Effective Date/), '2026-01-01');
    await user.click(screen.getByLabelText(/^Active/));
    await user.click(screen.getByRole('button', { name: 'Save Signatory' }));
    expect(await screen.findByText(/Upload your signature in My HR Profile/)).toBeInTheDocument();
    expect(screen.queryByText('Authorized signature saved.')).not.toBeInTheDocument();
  });

  it('offers no way to draw, upload, replace or remove the signature here and links to My ADMIN Profile', async () => {
    myHrOrganizationSettingsApi.getAuthorizedSignature.mockResolvedValue(authorizedSignature({
      signatureConfigured: true, signatureUrl: '/uploads/gridfs/abc/signature.png',
    }));
    const user = userEvent.setup(); const { container } = renderPage();
    await screen.findByAltText('Authorized signature');
    await user.click(screen.getByRole('button', { name: 'Edit Authorization' }));
    expect(container.querySelector('input[type="file"]')).toBeNull();
    expect(screen.queryByRole('button', { name: /upload|draw|replace|remove|clear/i })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'My ADMIN Profile' })).toHaveAttribute('href', '/myhr/admin/profile');
  });

  it('saves the authorized signatory details and active status', async () => {
    myHrOrganizationSettingsApi.updateAuthorizedSignature.mockResolvedValue(authorizedSignature({
      signatoryName: 'Jane Admin', signatoryTitle: 'President', effectiveDate: '2026-01-01', active: true, inEffect: true,
    }));
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Authorization' }));
    expect(screen.getByRole('region', { name: 'Save authorized signature' })).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Authorized Signatory Name/), 'Jane Admin');
    await user.type(screen.getByLabelText(/^Authorized Signatory Title/), 'President');
    await user.type(screen.getByLabelText(/^Effective Date/), '2026-01-01');
    await user.click(screen.getByLabelText(/^Active/));
    await user.click(screen.getByRole('button', { name: 'Save Signatory' }));
    expect(myHrOrganizationSettingsApi.updateAuthorizedSignature).toHaveBeenCalledWith({
      signatoryName: 'Jane Admin', signatoryTitle: 'President', effectiveDate: '2026-01-01', active: true,
    });
    expect(await screen.findByText('Authorized signature saved.')).toBeInTheDocument();
  });

  it('loads in read-only mode with organization identity and settings', async () => {
    renderPage();
    expect(await screen.findByText('Tardus Inc')).toBeInTheDocument();
    expect(screen.getByText('TD')).toBeInTheDocument();
    expect(screen.getByText('1 Old Street')).toBeInTheDocument();
    expect(screen.getByText('84-3596475')).toBeInTheDocument();
    expect(screen.getByText('1533740')).toBeInTheDocument();
    expect(screen.getByText('Biweekly')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Settings' })).toBeInTheDocument();
  });

  it('edit mode exposes every organization field, with Organization Code locked', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    expect(screen.getByLabelText('Description')).toBeInTheDocument();
    expect(screen.getByLabelText(/Payroll Period/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Organization Name/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Organization Email/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Street Address/)).toBeInTheDocument();
    expect(screen.getByLabelText(/EIN Number/)).toBeInTheDocument();
    expect(screen.getByLabelText(/E-Verify Number/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Organization Code/)).toBeDisabled();
  });

  it('saves every edited organization field and returns to view mode', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    const description = screen.getByLabelText('Description');
    await user.clear(description); await user.type(description, 'Updated description');
    await user.selectOptions(screen.getByLabelText(/Payroll Period/), 'MONTHLY');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(myHrOrganizationSettingsApi.update).toHaveBeenCalledWith({
      organizationName: 'Tardus Inc',
      organizationEmail: 'hr@tardus.test',
      phoneNumber: '+13125550100',
      streetAddress: '1 Old Street',
      city: 'Chicago',
      state: 'IL',
      zipCode: '60601',
      organizationSize: 'MEDIUM',
      industry: 'Software',
      website: 'https://tardus.test',
      einNumber: '84-3596475',
      eVerifyNumber: '1533740',
      adminName: null,
      adminEmail: null,
      adminPhoneNumber: null,
      description: 'Updated description',
      payrollPeriod: 'MONTHLY',
      removeLogo: false,
      removeSignature: false,
    }));
    expect(await screen.findByText('Organization settings saved.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Description')).not.toBeInTheDocument();
  });

  it('accepts only nine EIN digits and formats them with the standard hyphen', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    const ein = screen.getByLabelText(/EIN Number/);
    await user.clear(ein);
    await user.type(ein, '145236526ww789');

    expect(ein).toHaveValue('14-5236526');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(myHrOrganizationSettingsApi.update).toHaveBeenCalledWith(expect.objectContaining({
      einNumber: '14-5236526',
    })));
  });

  it('rejects saving without a required Organization Name', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    const organizationName = screen.getByLabelText(/Organization Name/);
    await user.clear(organizationName);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Organization Name is required.')).toBeInTheDocument();
    expect(myHrOrganizationSettingsApi.update).not.toHaveBeenCalled();
  });

  it('Cancel discards edits and restores persisted values', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    const description = screen.getByLabelText('Description');
    await user.clear(description); await user.type(description, 'Unsaved');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('We build things.')).toBeInTheDocument();
    expect(myHrOrganizationSettingsApi.update).not.toHaveBeenCalled();
  });

  it('shows organization initials when no logo has been uploaded yet', async () => {
    renderPage();
    expect(await screen.findByText('Tardus Inc')).toBeInTheDocument();
    expect(screen.getByText('TI')).toBeInTheDocument();
  });

  it('shows the uploaded logo image when one exists', async () => {
    myHrOrganizationSettingsApi.get.mockResolvedValue(settingsResponse({ organizationLogoUrl: '/uploads/company-logos/org-1/logo.png' }));
    renderPage();
    const logo = await screen.findByAltText('Tardus Inc logo');
    expect(logo).toBeInTheDocument();
  });

  it('edit mode offers a logo upload control', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    expect(screen.getByLabelText('Organization Logo')).toBeInTheDocument();
  });

  it('uploads a newly selected logo after saving the other fields', async () => {
    myHrOrganizationSettingsApi.uploadLogo.mockResolvedValue(
      settingsResponse({ description: 'Updated description', payrollPeriod: 'MONTHLY', organizationLogoUrl: '/uploads/company-logos/org-1/logo.png' }));
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Edit Settings' }));
    const file = new File(['logo-bytes'], 'logo.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Organization Logo'), file);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(myHrOrganizationSettingsApi.uploadLogo).toHaveBeenCalledWith(file));
    expect(await screen.findByAltText('Tardus Inc logo')).toBeInTheDocument();
  });

  it("no longer offers an Organization Signature - signatures are managed on each user's own Profile", async () => {
    myHrOrganizationSettingsApi.get.mockResolvedValue(settingsResponse({ organizationSignatureUrl: '/uploads/company-signatures/org-1/signature.png' }));
    const user = userEvent.setup(); renderPage();
    await screen.findByRole('button', { name: 'Edit Settings' });
    expect(screen.queryByText('Organization Signature')).not.toBeInTheDocument();
    expect(screen.queryByAltText('Organization signature')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Edit Settings' }));
    expect(screen.queryByText('Organization Signature')).not.toBeInTheDocument();
    expect(screen.queryByText('Upload Signature')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(myHrOrganizationSettingsApi.update).toHaveBeenCalled());
    // The settings API still requires the flag; it must never ask to remove anything.
    expect(myHrOrganizationSettingsApi.update.mock.calls[0][0]).toHaveProperty('removeSignature', false);
  });

  it('shows loading and load errors', async () => {
    myHrOrganizationSettingsApi.get.mockRejectedValue({ response: { data: { message: 'Could not load.' } } });
    renderPage();
    expect(screen.getByText('Loading organization settings…')).toBeInTheDocument();
    expect(await screen.findByText('Could not load.')).toBeInTheDocument();
  });
});
