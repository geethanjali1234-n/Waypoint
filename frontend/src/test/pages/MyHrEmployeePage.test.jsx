import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MyHrEmployeePage from '../../pages/MyHrEmployeePage';

function renderPage(initialEntries = ['/employee/dashboard']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <MyHrEmployeePage />
    </MemoryRouter>,
  );
}

describe('MyHrEmployeePage', () => {
  it('shows the welcome banner only on the dashboard route', () => {
    const dashboard = renderPage(['/employee/dashboard']);
    expect(screen.getByRole('heading', { name: 'Welcome, John' })).toBeInTheDocument();

    dashboard.unmount();
    renderPage(['/employee/profile']);
    expect(screen.getByRole('heading', { name: 'My Profile', level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Welcome, John' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Employee summary' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Timesheets' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Paystubs' })).not.toBeInTheDocument();
  });

  it('starts a selected feature at the beginning of its page', () => {
    document.documentElement.scrollTop = 320;
    document.body.scrollTop = 320;

    renderPage(['/employee/timesheets']);

    expect(document.documentElement.scrollTop).toBe(0);
    expect(document.body.scrollTop).toBe(0);
    expect(screen.getByRole('heading', { name: 'Timesheets', level: 1 })).toBeInTheDocument();
  });

  it('keeps Home focused on the employee overview', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Welcome, John' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Employee summary' })).toBeInTheDocument();
    expect(screen.queryByText('View your reporting manager and team information.')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Employee ID')).not.toBeInTheDocument();
    expect(screen.queryByText('Upload Optional Approved Timesheet')).not.toBeInTheDocument();
    expect(screen.queryByText('Paystub 001')).not.toBeInTheDocument();
    expect(screen.getByText('Hours this period')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Notifications' })).not.toBeInTheDocument();
    expect(screen.queryByText('Stay informed with important updates and announcements.')).not.toBeInTheDocument();
  });

  it('validates daily hours and routes a valid timesheet to the reporting manager', async () => {
    const user = userEvent.setup();
    renderPage(['/employee/timesheets']);

    expect(screen.queryByText('Draft')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next dates' }));
    let hoursInput = screen.getAllByRole('spinbutton').find((input) => !input.disabled);
    if (!hoursInput) {
      await user.click(screen.getByRole('button', { name: 'Previous dates' }));
      hoursInput = screen.getAllByRole('spinbutton').find((input) => !input.disabled);
    }
    expect(hoursInput).toBeDefined();
    await user.clear(hoursInput);
    await user.click(screen.getByRole('button', { name: 'Submit' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter daily hours from 0 to 24');

    await user.type(hoursInput, '7.5');
    await user.click(screen.getByRole('button', { name: 'Submit' }));

    expect(screen.getByText('Pending manager review')).toBeInTheDocument();
    expect(screen.getByText(/^\d+(\.\d+)? hours$/)).toBeInTheDocument();
  });

  it('navigates dates in order and supports month and date selection', async () => {
    const user = userEvent.setup();
    renderPage(['/employee/timesheets']);

    expect(screen.getByText('Dates 1-7')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next dates' }));
    expect(screen.getByText('Dates 8-14')).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select month' }), '02');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Select year' }), '2026');
    expect(screen.getByText('February 1 - February 28, 2026')).toBeInTheDocument();
    expect(screen.getByText('Dates 1-7')).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select date and day' }), '2026-02-28');
    expect(screen.getByRole('button', { name: 'Select February 28, 2026' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('spinbutton', { name: 'February 28, 2026 hours' })).toBeInTheDocument();
  });

  it('keeps past and future years viewable but prevents submission outside the current period', async () => {
    const user = userEvent.setup();
    const currentYear = new Date().getFullYear();
    renderPage(['/employee/timesheets']);

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select year' }), String(currentYear - 1));
    expect(screen.getAllByText('This month is closed. Past months can be viewed but cannot be submitted.').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select year' }), String(currentYear + 1));
    expect(screen.getAllByText('This month has not opened yet. Future dates cannot be submitted.').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
  });

  it('submits a profile change request for a controlled field', async () => {
    const user = userEvent.setup();
    renderPage(['/employee/profile']);

    await user.click(screen.getByRole('button', { name: 'Submit Profile Change Request' }));
    await user.selectOptions(screen.getByLabelText(/Field Name/), 'Phone Number');
    await user.type(screen.getByLabelText(/Requested Value/), '555-0100');
    await user.click(screen.getByRole('button', { name: 'Submit Profile Change Request' }));

    expect(screen.getByText('Profile Change Request submitted to TD002 - Sarah Williams - HR.')).toBeInTheDocument();
    expect(screen.getByText('Pending')).toBeInTheDocument();
  });

  it('shows timesheet history and paystub details', async () => {
    const user = userEvent.setup();
    const timesheets = renderPage(['/employee/timesheets']);

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select month' }), '07');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Select year' }), '2026');
    await user.click(screen.getByRole('button', { name: 'View History' }));
    expect(screen.getByRole('region', { name: 'Timesheet History' })).toBeInTheDocument();
    expect(screen.getByText('July 16 - July 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 1 - July 15, 2026')).toBeInTheDocument();

    timesheets.unmount();
    renderPage(['/employee/paystubs']);
    await user.click(screen.getByRole('button', { name: 'View Paystub 001' }));
    const dialog = screen.getByRole('dialog', { name: 'Paystub 001' });
    expect(within(dialog).getByText('Gross Pay')).toBeInTheDocument();
    expect(within(dialog).getByText('$1,971.44')).toBeInTheDocument();

    await user.click(within(dialog).getAllByRole('button', { name: 'Close' })[0]);
    expect(screen.queryByRole('dialog', { name: 'Paystub 001' })).not.toBeInTheDocument();
  });

  it('filters timesheet history when the calendar month changes', async () => {
    const user = userEvent.setup();
    renderPage(['/employee/timesheets']);

    await user.click(screen.getByRole('button', { name: 'View History' }));
    expect(screen.getByText('August 16 - August 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 16 - July 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 1 - July 15, 2026')).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select month' }), '07');
    expect(screen.getByText('July 16 - July 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 1 - July 15, 2026')).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Select date and day' }), '2026-07-20');
    expect(screen.getByText('July 16 - July 31, 2026')).toBeInTheDocument();
    expect(screen.queryByText('July 1 - July 15, 2026')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'View history for July 10, 2026' }));
    expect(screen.getByText('July 1 - July 15, 2026')).toBeInTheDocument();
    expect(screen.queryByText('July 16 - July 31, 2026')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Toggle history date calendar' }));
    await user.click(screen.getByRole('button', { name: 'All pay periods' }));
    expect(screen.getByText('August 16 - August 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 16 - July 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 1 - July 15, 2026')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next history month' }));
    const historyCalendar = screen.getByRole('group', { name: 'History date calendar' });
    expect(within(historyCalendar).getByText('August 2026')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next history month' }));
    expect(within(historyCalendar).getByText('September 2026')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Previous history month' }));
    expect(within(historyCalendar).getByText('August 2026')).toBeInTheDocument();
    expect(screen.getByText('August 16 - August 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 16 - July 31, 2026')).toBeInTheDocument();
    expect(screen.getByText('July 1 - July 15, 2026')).toBeInTheDocument();
  });
});
