import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import OrganizationRegistrationPage from '../../../pages/auth/OrganizationRegistrationPage';
import authApi from '../../../api/authApi';

vi.mock('../../../api/authApi');
const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal()), useNavigate: () => navigate,
}));

describe('OrganizationRegistrationPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('keeps company and Admin emails separate, verifies OTP, and redirects to existing My HR login', async () => {
    authApi.startOrganizationRegistration.mockResolvedValue({ data: { data: {
      registrationId: 'registration-1', adminEmail: 'admin@acme.example', expiresAt: '2026-09-23T12:30:00',
    } } });
    authApi.completeOrganizationRegistration.mockResolvedValue({ data: { data: {
      organizationId: 'org-1', organizationCode: 'ACME-123456', adminLoginId: 'ACMEADMIN',
    } } });
    const user = userEvent.setup();
    render(<MemoryRouter><OrganizationRegistrationPage /></MemoryRouter>);

    await fillRequiredForm(user);
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));

    expect(await screen.findByRole('heading', { name: 'Verify Admin email' })).toBeInTheDocument();
    expect(authApi.startOrganizationRegistration).toHaveBeenCalledWith(expect.objectContaining({
      organizationEmail: 'contact@acme.example', adminEmail: 'admin@acme.example',
      industry: 'Healthcare', einNumber: '12-3456789', eVerifyNumber: '1533740', payrollPeriod: 'MONTHLY',
      adminName: 'Jane Admin', adminPhoneNumber: '5550101', description: '',
    }));
    const submitted = authApi.startOrganizationRegistration.mock.calls[0][0];
    expect(submitted).not.toHaveProperty('adminLoginId');
    expect(submitted).not.toHaveProperty('password');
    expect(submitted).not.toHaveProperty('confirmPassword');
    expect(screen.queryByLabelText(/Admin login ID/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Password/)).not.toBeInTheDocument();
    expect(screen.queryByText('123456')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText(/Verification code/), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify and create account' }));

    expect(authApi.completeOrganizationRegistration).toHaveBeenCalledWith({
      registrationId: 'registration-1', otp: '123456',
    });
    expect(navigate).toHaveBeenCalledWith('/myhr/login', { replace: true });
  });

  it('validates required fields before calling the backend', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><OrganizationRegistrationPage /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));
    expect(await screen.findByText('Organization name is required')).toBeInTheDocument();
    expect(screen.getByText('Industry is required')).toBeInTheDocument();
    expect(screen.getByText('EIN must be 9 digits, e.g. 12-3456789')).toBeInTheDocument();
    expect(screen.getByText('Payroll period is required')).toBeInTheDocument();
    expect(screen.getByText('Admin name is required')).toBeInTheDocument();
    expect(screen.getByText('Admin contact number is required')).toBeInTheDocument();
    expect(screen.getByText('E-Verify number is required')).toBeInTheDocument();
    expect(authApi.startOrganizationRegistration).not.toHaveBeenCalled();
  });

  it('sends an optional PNG logo with the registration', async () => {
    authApi.startOrganizationRegistration.mockResolvedValue({ data: { data: {
      registrationId: 'registration-1', adminEmail: 'admin@acme.example',
    } } });
    const user = userEvent.setup();
    render(<MemoryRouter><OrganizationRegistrationPage /></MemoryRouter>);
    const logo = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'logo.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText(/Organization logo/), logo);
    await fillRequiredForm(user);
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));

    await screen.findByRole('heading', { name: 'Verify Admin email' });
    const [, sentLogo] = authApi.startOrganizationRegistration.mock.calls[0];
    expect(sentLogo).toBe(logo);
  });

  it('rejects a logo that is not PNG or JPEG without sending it', async () => {
    const user = userEvent.setup({ applyAccept: false });
    render(<MemoryRouter><OrganizationRegistrationPage /></MemoryRouter>);
    const gif = new File(['GIF89a'], 'logo.gif', { type: 'image/gif' });
    await user.upload(screen.getByLabelText(/Organization logo/), gif);

    expect(await screen.findByText('Use a PNG or JPEG logo no larger than 2 MB.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove photo' })).not.toBeInTheDocument();
  });

  it('resends through the bounded registration endpoint', async () => {
    authApi.startOrganizationRegistration.mockResolvedValue({ data: { data: {
      registrationId: 'registration-1', adminEmail: 'admin@acme.example',
    } } });
    authApi.resendOrganizationRegistrationOtp.mockResolvedValue({ data: { data: {} } });
    const user = userEvent.setup();
    render(<MemoryRouter><OrganizationRegistrationPage /></MemoryRouter>);
    await fillRequiredForm(user);
    await user.click(screen.getByRole('button', { name: 'Send verification code' }));
    await user.click(await screen.findByRole('button', { name: 'Resend code' }));
    expect(authApi.resendOrganizationRegistrationOtp).toHaveBeenCalledWith('registration-1');
  });
});

async function fillRequiredForm(user) {
  await user.type(screen.getByLabelText(/Organization name/), 'Acme Health');
  await user.type(screen.getByLabelText(/Company contact email/), 'contact@acme.example');
  await user.type(screen.getByLabelText(/Company phone/), '5550100');
  await user.type(screen.getByLabelText(/Street address/), '100 Main St');
  await user.type(screen.getByLabelText(/^City/), 'Chicago');
  await user.type(screen.getByLabelText(/^State/), 'Illinois');
  await user.type(screen.getByLabelText(/ZIP code/), '60601');
  await user.selectOptions(screen.getByLabelText(/Company size/), 'MEDIUM');
  await user.type(screen.getByLabelText(/^Industry/), 'Healthcare');
  await user.type(screen.getByLabelText(/EIN number/), '12-3456789');
  await user.type(screen.getByLabelText(/E-Verify number/), '1533740');
  await user.selectOptions(screen.getByLabelText(/Payroll period/), 'MONTHLY');
  await user.type(screen.getByLabelText(/Admin name/), 'Jane Admin');
  await user.type(screen.getByLabelText(/Admin login email/), 'admin@acme.example');
  await user.type(screen.getByLabelText(/Admin contact number/), '5550101');
}
