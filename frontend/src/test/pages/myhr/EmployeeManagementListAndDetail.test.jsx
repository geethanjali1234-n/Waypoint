import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import EmployeeManagementPage from '../../../pages/myhr/features/employeeManagement/EmployeeManagementPage';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import myHrAccountApi from '../../../api/myHrAccountApi';
import * as reportExport from '../../../pages/myhr/features/reports/reportExport';

vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../api/myHrAccountApi');
vi.mock('../../../pages/myhr/features/reports/reportExport', () => ({
  downloadReportExcel: vi.fn(),
  downloadReportPdf: vi.fn(),
}));

const summaryRows = [
  {
    userId: 'emp-1', employeeId: 'TD003', name: 'John Doe', personType: 'EMPLOYEE',
    workEmail: 'john@work.test', personalEmail: 'john@home.test', designation: 'Software Engineer',
    employmentType: 'FULL_TIME', startDate: '2026-01-01', reportingManagerName: 'Sarah Jones',
    accountStatus: 'ACTIVE',
  },
  {
    userId: 'hr-1', employeeId: 'TD002', name: 'Sarah Jones', personType: 'HR',
    workEmail: 'sarah@work.test', personalEmail: 'sarah@home.test', designation: 'HR Manager',
    employmentType: 'FULL_TIME', startDate: '2025-06-01', reportingManagerName: null,
    accountStatus: 'INACTIVE',
  },
];

function detailFor(userId, overrides = {}) {
  return {
    userId, employeeId: 'TD003', name: 'John Doe', firstName: 'John', lastName: 'Doe',
    personType: 'EMPLOYEE', workEmail: 'john@work.test', personalEmail: 'john@home.test',
    phoneNumber: '555-0100', designation: 'Software Engineer', employmentType: 'FULL_TIME',
    startDate: '2026-01-01', streetAddress: '1 Main St', city: 'Austin', state: 'Texas', zipCode: '78701',
    accountStatus: 'ACTIVE', createdAt: '2026-01-01',
    reportingManager: { userId: 'hr-1', name: 'Sarah Jones', effectiveFrom: '2026-01-01', assignedByName: 'Anil Patel' },
    reportingHistory: [{ managerName: 'Sarah Jones', effectiveFrom: '2026-01-01', effectiveTo: null, active: true }],
    directReports: [],
    ...overrides,
  };
}

function renderPage() {
  return render(<MemoryRouter><EmployeeManagementPage role="COMPANY_ADMIN" /></MemoryRouter>);
}

describe('EmployeeManagementPage list and detail (non account-creation flows)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    myHrEmployeeApi.list.mockResolvedValue({ data: { data: summaryRows } });
  });

  it('keeps roster counts in filter pills and uses them to filter the shared table', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    const filters = screen.getByRole('group', { name: 'Quick triage filters' });
    expect(within(filters).getByRole('button', { name: 'All 2' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('region', { name: 'Employee roster overview' })).not.toBeInTheDocument();
    await user.click(within(filters).getByRole('button', { name: 'Inactive 1' }));
    expect(screen.queryByText('John Doe')).not.toBeInTheDocument();
    expect(screen.getByText('Sarah Jones')).toBeInTheDocument();
    expect(within(filters).getByRole('button', { name: 'All 2' })).toBeInTheDocument();
    expect(screen.getByText('Showing 1–1 of 1')).toBeInTheDocument();
  });

  it('retains selection across pages without mutating employee accounts', async () => {
    myHrEmployeeApi.list.mockResolvedValue({ data: { data: Array.from({ length: 12 }, (_, index) => ({ ...summaryRows[0], userId: `select-${index}`, employeeId: `ID-${index}`, name: `Person ${index}` })) } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Person 0');
    await user.click(screen.getByRole('checkbox', { name: 'Select this page' }));
    expect(screen.getByText('10 selected')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await user.click(screen.getByRole('checkbox', { name: 'Select Person 11' }));
    expect(screen.getByText('11 selected')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Select this page' })).toHaveAttribute('aria-checked', 'mixed');
    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(screen.getByRole('checkbox', { name: 'Select Person 0' })).toBeChecked();
    expect(myHrEmployeeApi.updateStatus).not.toHaveBeenCalled();
  });

  it('closes the filter menu when clicking outside it', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.click(screen.getByText('Filters'));
    expect(screen.getByLabelText('Person Type')).toBeVisible();

    await user.click(document.body);
    expect(screen.getByLabelText('Person Type')).not.toBeVisible();
  });

  it('exports only selected employees to both PDF and Excel', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.click(screen.getByRole('checkbox', { name: 'Select John Doe' }));
    await user.click(screen.getByRole('button', { name: 'Export PDF' }));
    await user.click(screen.getByRole('button', { name: 'Export Excel' }));

    expect(reportExport.downloadReportPdf).toHaveBeenCalledWith(expect.objectContaining({
      rows: [expect.objectContaining({ userId: 'emp-1', name: 'John Doe' })],
    }));
    expect(reportExport.downloadReportExcel).toHaveBeenCalledWith(expect.objectContaining({
      rows: [expect.objectContaining({ userId: 'emp-1', name: 'John Doe' })],
    }));
  });

  it('exports all filtered employees when no selection exists', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.click(screen.getByRole('button', { name: 'Export PDF' }));

    expect(reportExport.downloadReportPdf).toHaveBeenCalledWith(expect.objectContaining({ rows: summaryRows }));
  });

  it('keeps the roster in place and cancels a nested manager dialog with Escape', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1') } });
    myHrEmployeeApi.reportingManagers.mockResolvedValue({ data: { data: [{ userId: 'admin-1', name: 'Anil Patel', roleLabel: 'Company Admin' }] } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'John Doe' }));
    const drawer = await screen.findByRole('dialog', { name: 'Employee details' });
    expect(await within(drawer).findByRole('list', { name: 'Reporting relationship' })).toBeInTheDocument();
    expect(document.querySelector('.wp-data-table')).toBeInTheDocument();
    const trigger = within(drawer).getByRole('button', { name: 'Change Reporting Manager' });
    await user.click(trigger);
    const confirmation = await screen.findByRole('dialog', { name: 'Change Reporting Manager' });
    expect(within(confirmation).getByRole('button', { name: 'Close' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Change Reporting Manager' })).not.toBeInTheDocument();
    expect(drawer).toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(myHrEmployeeApi.changeReportingManager).not.toHaveBeenCalled();
  });

  it('shows a loader and then the employee list', async () => {
    renderPage();
    expect(screen.getByRole('status')).toBeInTheDocument();

    expect(await screen.findByText('John Doe')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sarah Jones' })).toBeInTheDocument();
    expect(myHrEmployeeApi.list).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('columnheader', { name: 'Work Email' })).not.toBeInTheDocument();
  });

  it('paginates the filtered employee collection ten records at a time', async () => {
    myHrEmployeeApi.list.mockResolvedValue({ data: { data: Array.from({ length: 12 }, (_, index) => ({
      ...summaryRows[0], userId: `emp-${index}`, employeeId: `TD${index}`, name: `Employee ${index + 1}`,
    })) } });
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText('Employee 1')).toBeInTheDocument();
    expect(screen.queryByText('Employee 11')).not.toBeInTheDocument();
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Employee 11')).toBeInTheDocument();
    expect(screen.queryByText('Employee 1')).not.toBeInTheDocument();
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
  });

  it('shows an error state and reloads on retry', async () => {
    myHrEmployeeApi.list.mockRejectedValueOnce({ response: { data: { message: 'Server unavailable' } } });
    renderPage();

    expect(await screen.findByText('Could not load employees')).toBeInTheDocument();
    expect(screen.getByText('Server unavailable')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('John Doe')).toBeInTheDocument();
    expect(myHrEmployeeApi.list).toHaveBeenCalledTimes(2);
  });

  it('shows an empty state when there are no employees', async () => {
    myHrEmployeeApi.list.mockResolvedValue({ data: { data: [] } });
    renderPage();

    expect(await screen.findByText('No employees yet')).toBeInTheDocument();
  });

  it('filters the list by search text, person type, and status', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.type(screen.getByPlaceholderText('Search by employee, ID, email, or designation'), 'Sarah');
    expect(screen.queryByText('John Doe')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sarah Jones' })).toBeInTheDocument();

    await user.clear(screen.getByPlaceholderText('Search by employee, ID, email, or designation'));
    await user.selectOptions(screen.getByLabelText('Person Type'), 'EMPLOYEE');
    expect(screen.getByText('John Doe')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sarah Jones' })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Person Type'), 'ALL');
    await user.selectOptions(screen.getByLabelText('Account Status'), 'INACTIVE');
    expect(screen.queryByText('John Doe')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sarah Jones' })).toBeInTheDocument();
  });

  it('opens an employee detail view and returns to the list', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1') } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');

    await user.click(screen.getByRole('button', { name: 'John Doe' }));

    expect(myHrEmployeeApi.get).toHaveBeenCalledWith('emp-1');
    expect(await screen.findByText(/TD003 · Software Engineer/)).toBeInTheDocument();
    expect(screen.getAllByText('Sarah Jones').length).toBeGreaterThan(0);
    expect(screen.getByText('Anil Patel')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Back to employees' }));
    expect(screen.queryByText('Anil Patel')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'John Doe' })).toBeInTheDocument();
  });

  it('shows an error and lets the user retry loading the detail view', async () => {
    myHrEmployeeApi.get.mockRejectedValueOnce({ response: { data: { message: 'Not found' } } });
    myHrEmployeeApi.get.mockResolvedValueOnce({ data: { data: detailFor('emp-1') } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));

    expect(await screen.findByText('Could not load this employee')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText(/TD003 · Software Engineer/)).toBeInTheDocument();
    expect(myHrEmployeeApi.get).toHaveBeenCalledTimes(2);
  });

  it('changes the reporting manager and refreshes the list', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1') } });
    myHrEmployeeApi.reportingManagers.mockResolvedValue({ data: { data: [
      { userId: 'hr-1', name: 'Sarah Jones', roleLabel: 'HR' },
      { userId: 'admin-1', name: 'Anil Patel', roleLabel: 'Company Admin' },
    ] } });
    myHrEmployeeApi.changeReportingManager.mockResolvedValue({
      data: { data: detailFor('emp-1', { reportingManager: { userId: 'admin-1', name: 'Anil Patel', effectiveFrom: '2026-02-01', assignedByName: 'Anil Patel' } }) },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);

    await user.click(screen.getByRole('button', { name: 'Change Reporting Manager' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change Reporting Manager' });

    expect(within(dialog).queryByRole('option', { name: /Sarah Jones/ })).not.toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('New Reporting Manager'), 'admin-1');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm Change' }));

    await waitFor(() => expect(myHrEmployeeApi.changeReportingManager).toHaveBeenCalledWith('emp-1', {
      newReportingManagerUserId: 'admin-1', effectiveDate: null, reason: null,
    }));
    expect(await screen.findByText('Reporting manager updated.')).toBeInTheDocument();
    expect(myHrEmployeeApi.list).toHaveBeenCalledTimes(2);
  });

  it('constrains the reporting-manager effective date to the day after the employee starts', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1', { startDate: '2099-01-01' }) } });
    myHrEmployeeApi.reportingManagers.mockResolvedValue({ data: { data: [
      { userId: 'admin-1', name: 'Anil Patel', roleLabel: 'Company Admin' },
    ] } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);

    await user.click(screen.getByRole('button', { name: 'Change Reporting Manager' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change Reporting Manager' });

    expect(within(dialog).getByLabelText('Effective Date')).toHaveAttribute('min', '2099-01-02');
  });

  it('pushes the effective-date minimum out further when the selected manager starts even later', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1', { startDate: '2026-01-01' }) } });
    myHrEmployeeApi.reportingManagers.mockResolvedValue({ data: { data: [
      { userId: 'admin-1', name: 'Anil Patel', roleLabel: 'Company Admin', startDate: '2099-03-01' },
    ] } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);

    await user.click(screen.getByRole('button', { name: 'Change Reporting Manager' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change Reporting Manager' });
    const effectiveDateInput = within(dialog).getByLabelText('Effective Date');

    await user.selectOptions(within(dialog).getByLabelText('New Reporting Manager'), 'admin-1');

    expect(effectiveDateInput).toHaveAttribute('min', '2099-03-02');
  });

  it('shows an inline error and keeps the manager dialog open on failure', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1') } });
    myHrEmployeeApi.reportingManagers.mockResolvedValue({ data: { data: [
      { userId: 'admin-1', name: 'Anil Patel', roleLabel: 'Company Admin' },
    ] } });
    myHrEmployeeApi.changeReportingManager.mockRejectedValue({ response: { data: { message: 'Cannot reassign' } } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);
    await user.click(screen.getByRole('button', { name: 'Change Reporting Manager' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change Reporting Manager' });
    await user.selectOptions(within(dialog).getByLabelText('New Reporting Manager'), 'admin-1');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm Change' }));

    expect(await within(dialog).findByText('Cannot reassign')).toBeInTheDocument();
  });

  it('deactivates an active account and shows a confirmation notice', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1', { accountStatus: 'ACTIVE' }) } });
    myHrEmployeeApi.updateStatus.mockResolvedValue({
      data: { data: detailFor('emp-1', { accountStatus: 'INACTIVE' }) },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);

    await user.click(screen.getByRole('button', { name: 'Deactivate Account' }));
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate Account?' });
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(myHrEmployeeApi.updateStatus).toHaveBeenCalledWith('emp-1', { status: 'INACTIVE' });
    expect(await screen.findByText('Account deactivated.')).toBeInTheDocument();
  });

  it('activates an inactive account', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1', { accountStatus: 'INACTIVE' }) } });
    myHrEmployeeApi.updateStatus.mockResolvedValue({
      data: { data: detailFor('emp-1', { accountStatus: 'ACTIVE' }) },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);

    await user.click(screen.getByRole('button', { name: 'Activate Account' }));
    const dialog = await screen.findByRole('dialog', { name: 'Activate Account?' });
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));

    expect(myHrEmployeeApi.updateStatus).toHaveBeenCalledWith('emp-1', { status: 'ACTIVE' });
    expect(await screen.findByText('Account activated.')).toBeInTheDocument();
  });

  it('requires reassigning direct reports before deactivating an HR with active reports', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('hr-1', {
      personType: 'HR', name: 'Sarah Jones', firstName: 'Sarah', designation: 'HR Manager', accountStatus: 'ACTIVE',
      directReports: [{ userId: 'emp-1', employeeId: 'TD003', name: 'John Doe', designation: 'Software Engineer', accountStatus: 'ACTIVE' }],
    }) } });
    myHrEmployeeApi.reportingManagers.mockResolvedValue({ data: { data: [
      { userId: 'hr-1', name: 'Sarah Jones', roleLabel: 'HR' },
      { userId: 'admin-1', name: 'Anil Patel', roleLabel: 'Company Admin' },
    ] } });
    myHrEmployeeApi.updateStatus.mockResolvedValue({
      data: { data: detailFor('hr-1', { personType: 'HR', accountStatus: 'INACTIVE', directReports: [] }) },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'Sarah Jones' });
    await user.click(screen.getByRole('button', { name: 'Sarah Jones' }));
    await screen.findByText(/TD003 · HR Manager/);

    await user.click(screen.getByRole('button', { name: 'Deactivate Account' }));
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate Account?' });
    expect(myHrEmployeeApi.reportingManagers).toHaveBeenCalledWith();
    expect(await within(dialog).findByText(/1 employee currently reports to Sarah/)).toBeInTheDocument();

    const confirmButton = within(dialog).getByRole('button', { name: 'Deactivate' });
    expect(confirmButton).toBeDisabled();

    const reassignSelect = within(dialog).getByLabelText(/Reassign Employees To/);
    expect(within(reassignSelect).queryByRole('option', { name: /Sarah Jones/ })).not.toBeInTheDocument();
    await user.selectOptions(reassignSelect, 'admin-1');
    expect(confirmButton).toBeEnabled();
    await user.click(confirmButton);

    expect(myHrEmployeeApi.updateStatus).toHaveBeenCalledWith('hr-1', { status: 'INACTIVE', reassignToUserId: 'admin-1' });
    expect(await screen.findByText('Account deactivated.')).toBeInTheDocument();
  });

  it('does not ask for reassignment when deactivating an HR with no direct reports', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('hr-1', {
      personType: 'HR', name: 'Sarah Jones', designation: 'HR Manager', accountStatus: 'ACTIVE', directReports: [],
    }) } });
    myHrEmployeeApi.updateStatus.mockResolvedValue({
      data: { data: detailFor('hr-1', { personType: 'HR', accountStatus: 'INACTIVE' }) },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'Sarah Jones' });
    await user.click(screen.getByRole('button', { name: 'Sarah Jones' }));
    await screen.findByText(/TD003 · HR Manager/);

    await user.click(screen.getByRole('button', { name: 'Deactivate Account' }));
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate Account?' });
    expect(within(dialog).queryByLabelText(/Reassign Employees To/)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(myHrEmployeeApi.reportingManagers).not.toHaveBeenCalled();
    expect(myHrEmployeeApi.updateStatus).toHaveBeenCalledWith('hr-1', { status: 'INACTIVE' });
  });

  it('never calls the account-creation endpoints from any of these flows', async () => {
    myHrEmployeeApi.get.mockResolvedValue({ data: { data: detailFor('emp-1') } });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('John Doe');
    await user.click(screen.getByRole('button', { name: 'John Doe' }));
    await screen.findByText(/TD003 · Software Engineer/);

    expect(myHrAccountApi.createHrAccount).not.toHaveBeenCalled();
    expect(myHrAccountApi.createEmployeeAccount).not.toHaveBeenCalled();
  });
});
