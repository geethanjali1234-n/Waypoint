import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MyHrProfilePage from '../../../pages/myhr/features/profile/MyHrProfilePage';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import authApi from '../../../api/authApi';
import { extractSignatureFromImage, measureSignatureNaturalSize } from '../../../pages/myhr/features/signatureImageProcessing';

vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../api/authApi');
vi.mock('../../../pages/myhr/features/signatureImageProcessing');

const employeeProfile = {
  userId: 'emp-1', loginId: 'TD001', employeeId: 'TD001', role: 'EMPLOYEE', accountStatus: 'ACTIVE',
  firstName: 'John', lastName: 'Doe', name: 'John Doe', accountEmail: 'login@example.com',
  personalEmail: 'personal@example.com', phoneNumber: '555-0100', designation: 'Engineer',
  employmentType: 'FULL_TIME', startDate: '2026-01-15', streetAddress: '1 Main St', city: 'Austin',
  state: 'TX', zipCode: '78701', organizationName: 'Tardus', organizationCode: 'TD',
};

function renderPage(role, profile = employeeProfile) {
  myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { ...profile, role } } });
  return render(<MemoryRouter><MyHrProfilePage role={role} /></MemoryRouter>);
}

describe('MyHrProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear();
    extractSignatureFromImage.mockResolvedValue('data:image/png;base64,Y2xlYW5lZC1zaWduYXR1cmU=');
    measureSignatureNaturalSize.mockResolvedValue({ width: 150, height: 50, strokeWidth: 3, scale: 0.5 });
  });

  it.each(['EMPLOYEE', 'HR'])('renders the %s identity, account, contact, employment, and organization sections', async (role) => {
    renderPage(role);
    expect(await screen.findByText('John Doe')).toBeInTheDocument();
    expect(screen.getByText('personal@example.com')).toBeInTheDocument();
    expect(screen.getByText('Full Time')).toBeInTheDocument();
    expect(screen.getByText('Tardus')).toBeInTheDocument();
    expect(screen.getByText('Account Information')).toBeInTheDocument();
    expect(screen.getByText('Contact Information')).toBeInTheDocument();
    expect(screen.getByText('Employment Information')).toBeInTheDocument();
    expect(screen.getAllByText('Organization').length).toBeGreaterThan(0);
    expect(screen.getAllByText(role === 'HR' ? 'Hr' : 'Employee').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Active').length).toBeGreaterThan(0);
    expect(screen.queryByText('Request Profile Change')).not.toBeInTheDocument();
    expect(screen.queryByText('Change password')).not.toBeInTheDocument();
  });

  it('renders the Company Admin identity, account, contact, and organization data only', async () => {
    renderPage('COMPANY_ADMIN', {
      loginId: 'SNRTE0A8183ADMIN', accountEmail: 'admin@example.com', accountStatus: 'ACTIVE',
      organizationEmail: 'company@example.com', organizationAdminEmail: 'notify.admin@example.com',
      organizationName: 'SNR Tech', organizationCode: 'SNRTE-0A8183',
    });
    expect(await screen.findByRole('heading', { name: 'SNRTE0A8183ADMIN' })).toBeInTheDocument();
    expect(screen.getByText('Admin / Login ID: SNRTE0A8183ADMIN')).toBeInTheDocument();
    expect(screen.getAllByText('SNRTE0A8183ADMIN')[0]).toBeInTheDocument();
    expect(screen.getByText('Account Information')).toBeInTheDocument();
    expect(screen.getByText('Contact Information')).toBeInTheDocument();
    expect(screen.getAllByText('Company Admin').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Active').length).toBeGreaterThan(0);
    expect(screen.queryByText('Employment Information')).not.toBeInTheDocument();
    expect(screen.queryByText('First Name')).not.toBeInTheDocument();
    expect(screen.getByText('Account Email')).toBeInTheDocument();
    expect(screen.getByText('admin@example.com')).toBeInTheDocument();
    expect(screen.queryByText('Personal Email')).not.toBeInTheDocument();
    expect(screen.getByText('Organization Email')).toBeInTheDocument();
    expect(screen.getByText('company@example.com')).toBeInTheDocument();
    expect(screen.getByText('Admin Email')).toBeInTheDocument();
    expect(screen.getByText('notify.admin@example.com')).toBeInTheDocument();
    expect(screen.queryByText('Request Profile Change')).not.toBeInTheDocument();
    expect(screen.queryByText('Change password')).not.toBeInTheDocument();
  });

  it('uses a safe Company Admin profile heading fallback while retaining the login ID', async () => {
    renderPage('COMPANY_ADMIN', {
      loginId: 'LEGACYADMIN', accountEmail: 'admin@example.com', accountStatus: 'ACTIVE',
      organizationName: '  ', organizationCode: 'LEGACY',
    });

    expect(await screen.findByRole('heading', { name: 'LEGACYADMIN' })).toBeInTheDocument();
    expect(screen.getByText('Admin / Login ID: LEGACYADMIN')).toBeInTheDocument();
    expect(screen.getAllByText('LEGACYADMIN')[0]).toBeInTheDocument();
  });

  it('shows loading and retryable error states', async () => {
    myHrEmployeeApi.getMine.mockReturnValueOnce(new Promise(() => {}));
    const { unmount } = render(<MemoryRouter><MyHrProfilePage role="EMPLOYEE" /></MemoryRouter>);
    expect(screen.getByText('Loading your profile…')).toBeInTheDocument();
    unmount();
    myHrEmployeeApi.getMine.mockRejectedValueOnce(new Error('failed'));
    render(<MemoryRouter><MyHrProfilePage role="EMPLOYEE" /></MemoryRouter>);
    expect(await screen.findByText("Couldn't load your profile")).toBeInTheDocument();
  });

  it('shows the reporting manager employee ID when available', async () => {
    renderPage('EMPLOYEE', { ...employeeProfile, reportingManager: { userId: 'hr-1', employeeId: 'TD001', name: 'Riley Chen' } });
    expect(await screen.findByText('My Profile')).toBeInTheDocument();
    expect(screen.getByText('TD001 · Riley Chen')).toBeInTheDocument();
  });

  it('uses a safe fallback for missing values', async () => {
    renderPage('EMPLOYEE', { ...employeeProfile, phoneNumber: null, designation: null });
    expect((await screen.findAllByText('Not provided')).length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText('undefined')).not.toBeInTheDocument();
  });

  it.each(['COMPANY_ADMIN', 'HR', 'EMPLOYEE'])('does not render profile-change or password actions for %s', async (role) => {
    renderPage(role, role === 'COMPANY_ADMIN' ? {
      loginId: 'TDADMIN', accountEmail: 'admin@example.com', accountStatus: 'ACTIVE',
      organizationName: 'Tardus', organizationCode: 'TD',
    } : employeeProfile);
    await screen.findByText('My Profile');
    expect(screen.queryByText('Request Profile Change')).not.toBeInTheDocument();
    expect(screen.queryByText('Change password')).not.toBeInTheDocument();
  });

  it('lets a Company Admin change the login email and refreshes the stored session', async () => {
    authApi.changeEmail.mockResolvedValue({ data: { data: {
      userId: 'admin-1', email: 'new.admin@example.com', loginId: 'TDADMIN', role: 'COMPANY_ADMIN',
      accessToken: 'fresh-token', tokenType: 'Bearer', accountStatus: 'ACTIVE', isEmailVerified: true,
    } } });
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', {
      loginId: 'TDADMIN', accountEmail: 'admin@example.com', accountStatus: 'ACTIVE',
      organizationName: 'Tardus', organizationCode: 'TD', organizationAdminEmail: 'admin@example.com',
    });

    await user.click(await screen.findByRole('button', { name: 'Change account email' }));
    await user.type(screen.getByLabelText(/New account email/), 'new.admin@example.com');
    await user.type(screen.getByLabelText(/Current password/), 'OldPassword1');
    await user.click(screen.getByRole('button', { name: 'Save email' }));

    await waitFor(() => expect(authApi.changeEmail).toHaveBeenCalledWith({
      newEmail: 'new.admin@example.com', currentPassword: 'OldPassword1',
    }));
    expect(await screen.findByText('Account email updated.')).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBe('fresh-token');
    expect(screen.getAllByText('new.admin@example.com').length).toBeGreaterThanOrEqual(2);
  });

  it.each(['HR', 'EMPLOYEE'])('does not render account-email controls for %s', async (role) => {
    renderPage(role);
    await screen.findByText('My Profile');
    expect(screen.queryByRole('button', { name: 'Change account email' })).not.toBeInTheDocument();
  });

  it.each(['COMPANY_ADMIN', 'HR', 'EMPLOYEE'])('gives the %s their own Signature section with all signing methods', async (role) => {
    renderPage(role);
    expect(await screen.findByText('Signature')).toBeInTheDocument();
    expect(screen.getByText('Choose the signature to use by default in your WayPoint documents.')).toBeInTheDocument();
    expect(screen.getByText('No signature has been saved yet.')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Draw' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Type' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Upload image' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove Signature' })).not.toBeInTheDocument();
  });

  it('processes the uploaded photo and, on Use Signature, saves only the cleaned image for the signed-in user', async () => {
    myHrEmployeeApi.uploadMySignature.mockResolvedValue({ data: { data: { ...employeeProfile, role: 'HR', signatureUrl: '/uploads/gridfs/abc/signature.png' } } });
    const user = userEvent.setup(); renderPage('HR');
    const rawPhoto = new File(['paper-with-signature-photo-bytes'], 'signature.jpg', { type: 'image/jpeg' });
    await user.click(await screen.findByRole('tab', { name: 'Upload image' }));
    await user.upload(await screen.findByLabelText('Signature image'), rawPhoto);

    expect(await screen.findByText('Signature Preview')).toBeInTheDocument();
    expect(extractSignatureFromImage).toHaveBeenCalledWith(rawPhoto);
    await user.click(screen.getByRole('button', { name: 'Use Signature' }));

    await waitFor(() => expect(myHrEmployeeApi.uploadMySignature).toHaveBeenCalledTimes(1));
    const uploaded = myHrEmployeeApi.uploadMySignature.mock.calls[0][0];
    expect(uploaded).toBeInstanceOf(File);
    expect(await uploaded.text()).not.toBe(await rawPhoto.text());
    expect(await screen.findByText('Signature configured')).toBeInTheDocument();
    expect(screen.getByAltText('Your signature')).toHaveStyle({ width: '150px' });
    expect(screen.getByRole('tab', { name: 'Draw' })).toBeInTheDocument();
  });

  it('uses the Profile full name for a typed signature and waits for Continue before previewing it', async () => {
    const context = { fillStyle: '', font: '', textBaseline: '', fillText: vi.fn() };
    const getContext = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context);
    const toDataUrl = vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,dHlwZWQtc2lnbmF0dXJl');
    const user = userEvent.setup(); renderPage('EMPLOYEE');

    await user.click(await screen.findByRole('tab', { name: 'Type' }));
    expect(screen.getByLabelText('Full Name')).toHaveValue('John Doe');
    expect(screen.getByLabelText('Full Name')).toBeDisabled();
    expect(screen.queryByText('Signature Preview')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Edit Name' }));
    expect(screen.getByLabelText('Full Name')).toBeEnabled();
    await user.clear(screen.getByLabelText('Full Name'));
    await user.type(screen.getByLabelText('Full Name'), 'Jordan Lee');

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Signature Preview')).toBeInTheDocument();
    expect(context.fillText).toHaveBeenCalledWith('Jordan Lee', 24, 90);
    getContext.mockRestore(); toDataUrl.mockRestore();
  });

  it('keeps the pad open across several drawn strokes and previews the signature only after Continue', async () => {
    const context = { beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), clearRect: vi.fn() };
    const getContext = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context);
    const toDataUrl = vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,ZHJhd24=');
    HTMLCanvasElement.prototype.setPointerCapture = vi.fn();
    const user = userEvent.setup(); renderPage('EMPLOYEE');

    const pad = await screen.findByLabelText('Signature pad');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    const stroke = (fromX, toX) => {
      fireEvent.pointerDown(pad, { pointerId: 1, clientX: fromX, clientY: 20 });
      fireEvent.pointerMove(pad, { pointerId: 1, clientX: toX, clientY: 30 });
      fireEvent.pointerUp(pad, { pointerId: 1, clientX: toX, clientY: 30 });
    };
    stroke(10, 40);
    fireEvent.pointerLeave(pad, { pointerId: 1 });
    stroke(50, 90);

    // Lifting the pen or leaving the pad ends a stroke, never the signature.
    expect(screen.getByLabelText('Signature pad')).toBeInTheDocument();
    expect(screen.queryByText('Signature Preview')).not.toBeInTheDocument();
    expect(context.stroke).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Signature Preview')).toBeInTheDocument();
    getContext.mockRestore(); toDataUrl.mockRestore(); delete HTMLCanvasElement.prototype.setPointerCapture;
  });

  it('uses the configured administrator name for a Company Admin typed signature without crashing', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN', {
      loginId: 'TDADMIN', accountEmail: 'admin@example.com', accountStatus: 'ACTIVE',
      organizationName: 'Tardus', organizationCode: 'TD', organizationAdminName: 'Avery Admin', name: null,
    });

    await user.click(await screen.findByRole('tab', { name: 'Type' }));
    expect(screen.getByLabelText('Full Name')).toHaveValue('Avery Admin');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });

  it('removes the saved signature only after confirming', async () => {
    const withSignature = { ...employeeProfile, signatureUrl: '/uploads/gridfs/abc/signature.png' };
    myHrEmployeeApi.removeMySignature.mockResolvedValue({ data: { data: { ...employeeProfile, role: 'EMPLOYEE', signatureUrl: null } } });
    const user = userEvent.setup(); renderPage('EMPLOYEE', withSignature);
    expect(await screen.findByText('Signature configured')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove Signature' }));
    expect(screen.getByText('Remove your signature?')).toBeInTheDocument();
    expect(myHrEmployeeApi.removeMySignature).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(myHrEmployeeApi.removeMySignature).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('No signature has been saved yet.')).toBeInTheDocument();
  });

  it('shows the detection error and uploads nothing when no signature is found in the photo', async () => {
    extractSignatureFromImage.mockResolvedValue(null);
    const user = userEvent.setup(); renderPage('COMPANY_ADMIN', { ...employeeProfile, role: 'COMPANY_ADMIN' });
    await user.click(await screen.findByRole('tab', { name: 'Upload image' }));
    await user.upload(await screen.findByLabelText('Signature image'), new File(['blank'], 'blank.png', { type: 'image/png' }));
    expect(await screen.findByText("Couldn't detect a signature from this image. Please upload a clearer image containing the handwritten signature.")).toBeInTheDocument();
    expect(myHrEmployeeApi.uploadMySignature).not.toHaveBeenCalled();
  });
});

