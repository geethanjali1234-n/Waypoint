import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import TimesheetsPage from '../../../pages/myhr/features/timesheets/TimesheetsPage';

vi.mock('../../../pages/myhr/MyHrShell', () => ({ default: ({ children }) => <main>{children}</main> }));
vi.mock('../../../api/timesheetApi', () => ({ default: {
  list: vi.fn().mockResolvedValue({ data: { data: [] } }),
  getSettings: vi.fn().mockResolvedValue({ data: { data: { payrollPeriod: 'MONTHLY' } } }),
  getPeriod: vi.fn().mockResolvedValue({ data: { data: null } }),
} }));
vi.mock('../../../api/myHrEmployeeApi', () => ({ default: {
  getMine: vi.fn().mockResolvedValue({ data: { data: {} } }),
  list: vi.fn().mockResolvedValue({ data: { data: [] } }),
  reportingManagers: vi.fn().mockResolvedValue({ data: { data: [] } }),
} }));
vi.mock('../../../api/myHrReportApi', () => ({ default: { searchEmployees: vi.fn().mockResolvedValue({ data: { data: [] } }) } }));
afterEach(() => localStorage.clear());
it.each(['HR', 'COMPANY_ADMIN'])('opens Required Actions from the %s dashboard URL', async (role) => {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'reviewer-1', role }));
  render(<MemoryRouter initialEntries={['/myhr/hr/timesheets?view=team&subtab=actions&status=SUBMITTED']}><TimesheetsPage role={role} /></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole('button', { name: /Required Actions/ })).toHaveAttribute('aria-pressed', 'true'));
  expect(screen.getByRole('button', { name: /All Submissions/ })).toHaveAttribute('aria-pressed', 'false');
});
