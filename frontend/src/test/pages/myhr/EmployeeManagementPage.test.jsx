import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import EmployeeManagementPage from '../../../pages/myhr/features/employeeManagement/EmployeeManagementPage';
import myHrAccountApi from '../../../api/myHrAccountApi';
import { saveMyHrAuthentication } from '../../../auth/myHrSession';

vi.mock('../../../api/myHrAccountApi');

const managers = [
  { userId: 'hr-self', loginId: 'TD001', firstName: 'Anil', lastName: 'Patel' },
  { userId: 'hr-sarah', loginId: 'TD002', firstName: 'Sarah', lastName: 'Jones' },
];

function renderPage(role) {
  saveMyHrAuthentication('signed-jwt', { loginId: role === 'COMPANY_ADMIN' ? 'TDADMIN' : 'TD001', role });
  return render(<MemoryRouter><EmployeeManagementPage role={role} /></MemoryRouter>);
}

async function openAccountForm(user) {
  await user.click(screen.getByRole('button', { name: 'Create Account' }));
  return screen.getByRole('dialog', { name: 'Create Account' });
}

async function fillRequiredProfile(user, dialog) {
  await user.type(within(dialog).getByLabelText(/First Name/), ' John ');
  await user.type(within(dialog).getByLabelText(/Last Name/), ' Smith ');
  await user.type(within(dialog).getByLabelText(/Personal Email/), ' JOHN@EXAMPLE.COM ');
  await user.type(within(dialog).getByLabelText(/Phone Number/), '312-555-0100');
  await user.type(within(dialog).getByLabelText(/Designation/), ' Software Engineer ');
  await user.selectOptions(within(dialog).getByLabelText(/Employment Type/), 'FULL_TIME');
  await user.type(within(dialog).getByLabelText(/Start Date/), '2026-09-08');
  await user.type(within(dialog).getByLabelText(/Street Address/), ' 1 Main Street ');
  await user.type(within(dialog).getByLabelText(/^City/), ' Austin ');
  await user.type(within(dialog).getByLabelText(/^State/), ' Texas ');
  await user.type(within(dialog).getByLabelText(/ZIP Code/), ' 78701 ');
}

async function continueAndCreate(user) {
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  const review = await screen.findByRole('dialog', { name: 'Account Setup / Review' });
  await user.click(within(review).getByRole('button', { name: 'Create Account' }));
}

describe('EmployeeManagementPage account creation', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    myHrAccountApi.getEligibleReportingManagers.mockResolvedValue({ data: { data: managers } });
  });

  it('gives Company Admin only HR and Employee role options', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    const roleSelect = within(dialog).getByLabelText(/Role/);

    expect(within(roleSelect).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['Select Role', 'HR', 'Employee']);
    expect(within(roleSelect).queryByRole('option', { name: 'Company Admin' })).not.toBeInTheDocument();
  });

  it('shows a clear US phone number format in the account form', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    const dialog = await openAccountForm(user);
    const phone = within(dialog).getByLabelText(/Phone Number/);

    expect(phone).toHaveAttribute('placeholder', '(555) 123-4567');
    await user.type(phone, '312-555-0100');
    expect(phone).toHaveValue('(312) 555-0100');
  });

  it('accepts only letters and spaces in account names', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    const dialog = await openAccountForm(user);

    await user.type(within(dialog).getByLabelText(/First Name/), 'J@ne123 Doe!');
    expect(within(dialog).getByLabelText(/First Name/)).toHaveValue('Jne Doe');
    expect(within(dialog).queryByText('Letters and spaces only')).not.toBeInTheDocument();
  });

  it('accepts only a five-digit ZIP code in the account form', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    const dialog = await openAccountForm(user);
    const zipCode = within(dialog).getByLabelText(/ZIP Code/);

    await user.type(zipCode, '78A701234');
    expect(zipCode).toHaveValue('78701');
    expect(within(dialog).queryByText('5 digits')).not.toBeInTheDocument();
  });

  it('accepts only letters and spaces in city and state', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    const dialog = await openAccountForm(user);

    await user.type(within(dialog).getByLabelText(/^City/), 'Aust!n123');
    await user.type(within(dialog).getByLabelText(/^State/), 'Tex@s12');
    expect(within(dialog).getByLabelText(/^City/)).toHaveValue('Austn');
    expect(within(dialog).getByLabelText(/^State/)).toHaveValue('Texs');
  });

  it('creates HR without showing or requiring an Employee reporting manager', async () => {
    const user = userEvent.setup();
    myHrAccountApi.createHrAccount.mockResolvedValue({ data: { data: {
      loginId: 'LOGIN-010', employeeId: 'EMP-010', role: 'HR', temporaryPassword: 'Temporary9!',
    } } });
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    await user.selectOptions(within(dialog).getByLabelText(/Role/), 'HR');
    expect(within(dialog).queryByLabelText(/Reporting Manager/)).not.toBeInTheDocument();
    await fillRequiredProfile(user, dialog);
    await continueAndCreate(user);

    expect(myHrAccountApi.createHrAccount).toHaveBeenCalledWith(expect.objectContaining({
      firstName: 'John', lastName: 'Smith', personalEmail: 'john@example.com',
    }));
    expect(myHrAccountApi.createEmployeeAccount).not.toHaveBeenCalled();
  });

  it('requires an active HR selection before Company Admin creates Employee', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    await user.selectOptions(within(dialog).getByLabelText(/Role/), 'EMPLOYEE');
    await fillRequiredProfile(user, dialog);
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }));

    expect(await within(dialog).findByText('Reporting Manager is required')).toBeInTheDocument();
  });

  it('creates Employee with the selected reporting HR userId', async () => {
    const user = userEvent.setup();
    myHrAccountApi.createEmployeeAccount.mockResolvedValue({ data: { data: {
      loginId: 'TD011', role: 'EMPLOYEE', temporaryPassword: 'Temporary9!',
    } } });
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    await user.selectOptions(within(dialog).getByLabelText(/Role/), 'EMPLOYEE');
    await fillRequiredProfile(user, dialog);
    await user.selectOptions(await within(dialog).findByLabelText(/Reporting Manager/), 'hr-sarah');
    await continueAndCreate(user);

    expect(myHrAccountApi.createEmployeeAccount).toHaveBeenCalledWith(
      expect.not.objectContaining({ workEmail: expect.anything() }), 'hr-sarah');
  });

  it('fixes HR account creation to Employee and permits self or another returned HR manager', async () => {
    const user = userEvent.setup();
    myHrAccountApi.createEmployeeAccount.mockResolvedValue({ data: { data: {
      loginId: 'TD011', role: 'EMPLOYEE', temporaryPassword: 'Temporary9!',
    } } });
    renderPage('HR');
    const dialog = await openAccountForm(user);
    const role = within(dialog).getByLabelText(/Role/);
    expect(role).toBeDisabled();
    expect(role).toHaveValue('Employee');
    const managerSelect = await within(dialog).findByLabelText(/Reporting Manager/);
    expect(within(managerSelect).getByRole('option', { name: 'Anil Patel' })).toHaveValue('hr-self');
    expect(within(managerSelect).getByRole('option', { name: 'Sarah Jones' })).toHaveValue('hr-sarah');
    expect(within(managerSelect).queryByRole('option', { name: /TD001/ })).not.toBeInTheDocument();
    expect(within(managerSelect).queryByRole('option', { name: /TD002/ })).not.toBeInTheDocument();
    await fillRequiredProfile(user, dialog);
    await user.selectOptions(managerSelect, 'hr-sarah');
    await continueAndCreate(user);

    expect(myHrAccountApi.createEmployeeAccount).toHaveBeenCalledWith(expect.any(Object), 'hr-sarah');
    expect(myHrAccountApi.createHrAccount).not.toHaveBeenCalled();
  });

  it('does not expose Create Account to Employee', () => {
    renderPage('EMPLOYEE');
    expect(screen.queryByRole('button', { name: 'Create Account' })).not.toBeInTheDocument();
  });

  it('shows field errors for missing, whitespace-only, and invalid values', async () => {
    const user = userEvent.setup();
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    await user.selectOptions(within(dialog).getByLabelText(/Role/), 'HR');
    await user.type(within(dialog).getByLabelText(/First Name/), '   ');
    await user.type(within(dialog).getByLabelText(/Personal Email/), 'invalid-email');
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }));

    expect(within(dialog).getByText('First Name is required')).toBeInTheDocument();
    expect(within(dialog).getByText('Last Name is required')).toBeInTheDocument();
    expect(within(dialog).getByText('Enter a valid personal email address')).toBeInTheDocument();
    expect(myHrAccountApi.createHrAccount).not.toHaveBeenCalled();
  });

  it('shows all backend employment types', async () => {
    const user = userEvent.setup();
    renderPage('HR');
    const dialog = await openAccountForm(user);
    const select = within(dialog).getByLabelText(/Employment Type/);
    expect(within(select).getAllByRole('option').map((option) => option.value))
      .toEqual(['', 'FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP']);
  });

  it('says the sign-in details were emailed when delivery succeeded, without any password', async () => {
    const user = userEvent.setup();
    myHrAccountApi.createHrAccount.mockResolvedValue({ data: { data: {
      loginId: 'LOGIN-011', employeeId: 'EMP-011', role: 'HR', email: 'john@example.com', emailDelivered: true,
    } } });
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    await user.selectOptions(within(dialog).getByLabelText(/Role/), 'HR');
    await fillRequiredProfile(user, dialog);
    await continueAndCreate(user);

    const success = await screen.findByRole('dialog', { name: 'Account Created Successfully' });
    expect(within(success).getByRole('status')).toHaveTextContent('emailed to john@example.com');
    expect(within(success).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows returned IDs without displaying or persisting the temporary password', async () => {
    const user = userEvent.setup();
    myHrAccountApi.createHrAccount.mockResolvedValue({ data: { data: {
      loginId: 'LOGIN-010', employeeId: 'EMP-010', role: 'HR', temporaryPassword: 'Temporary9!',
    } } });
    renderPage('COMPANY_ADMIN');
    const dialog = await openAccountForm(user);
    await user.selectOptions(within(dialog).getByLabelText(/Role/), 'HR');
    await fillRequiredProfile(user, dialog);
    await continueAndCreate(user);

    const success = await screen.findByRole('dialog', { name: 'Account Created Successfully' });
    expect(within(success).getByText('User ID').nextElementSibling).toHaveTextContent('LOGIN-010');
    expect(within(success).getByText('Employee ID').nextElementSibling).toHaveTextContent('EMP-010');
    expect(within(success).getByText('Employee ID').nextElementSibling).not.toHaveTextContent('LOGIN-010');
    expect(within(success).getByRole('alert')).toHaveTextContent('welcome email could not be delivered');
    expect(within(success).getByRole('alert')).not.toHaveTextContent('Temporary9!');
    expect(screen.queryByText('Temporary Password')).not.toBeInTheDocument();
    expect(screen.queryByText('Temporary9!')).not.toBeInTheDocument();
    expect(JSON.stringify(localStorage)).not.toContain('Temporary9!');
    expect(sessionStorage.length).toBe(0);
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('Temporary9!')).not.toBeInTheDocument();
  });
});
