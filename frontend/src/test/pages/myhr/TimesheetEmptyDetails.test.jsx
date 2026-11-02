import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import TimesheetsPage from '../../../pages/myhr/features/timesheets/TimesheetsPage';

vi.mock('../../../api/timesheetApi', () => ({ default: {
  list: vi.fn().mockResolvedValue({ data: { data: [] } }),
  getSettings: vi.fn().mockResolvedValue({ data: { data: { payrollPeriod: 'MONTHLY' } } }),
  getPeriod: vi.fn().mockResolvedValue({ data: { data: null } }),
} }));
vi.mock('../../../api/myHrEmployeeApi', () => ({ default: {
  getMine: vi.fn().mockResolvedValue({ data: { data: { firstName: 'Jordan' } } }),
} }));
vi.mock('../../../api/myHrNotificationApi', () => ({ default: {
  listNotifications: vi.fn().mockResolvedValue({ data: { data: [] } }),
  getUnreadCount: vi.fn().mockResolvedValue({ data: { data: { count: 0 } } }),
} }));

afterEach(() => localStorage.clear());

it('does not present an empty period as a submitted timesheet or show an empty scrollable table', async () => {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'test-employee', role: 'EMPLOYEE' }));
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/myhr/employee/timesheets?mode=history']}><TimesheetsPage role="EMPLOYEE" /></MemoryRouter>);
  await user.click(await screen.findByRole('button', { name: 'View Period Details' }));
  const dialog = screen.getByRole('dialog', { name: /Timesheet details/ });
  expect(within(dialog).getByText('No submitted hours')).toBeInTheDocument();
  expect(within(dialog).getByText('No time entries recorded for this period.')).toBeInTheDocument();
  expect(within(dialog).queryByRole('table')).not.toBeInTheDocument();
});
