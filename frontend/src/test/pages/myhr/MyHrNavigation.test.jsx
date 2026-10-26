import { beforeEach, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MyHrNavigation from '../../../pages/myhr/components/MyHrNavigation';
beforeEach(() => localStorage.clear());
function show(role = 'COMPANY_ADMIN', path = '/myhr/admin/dashboard') {
  render(<MemoryRouter initialEntries={[path]}><MyHrNavigation role={role} name="SNR Tech" /><Routes><Route path="*" element={<p>Page content</p>} /></Routes></MemoryRouter>);
}
it('keeps navigation available and highlights the destination after navigating', () => {
  show();
  expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
  fireEvent.click(screen.getByRole('link', { name: 'Employee Management' }));
  expect(screen.getByRole('link', { name: 'Employee Management' })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current');
  expect(screen.getByRole('navigation')).toBeInTheDocument();
});
it('highlights payroll separately from the timesheet tab', () => {
  show('COMPANY_ADMIN', '/myhr/admin/timesheets?view=payroll');
  expect(screen.getByRole('link', { name: 'Payroll Readiness' })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('link', { name: 'Timesheets' })).not.toHaveAttribute('aria-current');
});
it('retains the collapsed preference across page mounts', () => {
  localStorage.setItem('waypoint_sidebar_collapsed', 'true');
  show();
  expect(screen.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(screen.getByRole('button', { name: 'Expand sidebar' }));
  expect(localStorage.getItem('waypoint_sidebar_collapsed')).toBe('false');
});
it('shows only employee routes for employees', () => {
  show('EMPLOYEE', '/myhr/employee/timesheets');
  expect(screen.getByRole('link', { name: 'My Timesheets' })).toHaveAttribute('href', '/myhr/employee/timesheets');
  expect(screen.queryByRole('link', { name: 'Organization Settings' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Payroll Readiness' })).not.toBeInTheDocument();
});
it('shows HR routes without company-admin controls', () => {
  show('HR', '/myhr/hr/reports');
  expect(screen.getByRole('link', { name: 'Reports' })).toHaveAttribute('aria-current', 'page');
  expect(screen.queryByRole('link', { name: 'Document Approvals' })).not.toBeInTheDocument();
});

it('collapses immediately and reveals labels without using toggle focus', () => {
  show();
  const sidebar = screen.getByRole('complementary', { name: 'Workspace navigation' });
  fireEvent.mouseEnter(sidebar);
  fireEvent.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
  expect(sidebar).toHaveClass('is-collapsed');
  expect(sidebar).not.toHaveClass('is-peeking');
  fireEvent.focus(screen.getByRole('button', { name: 'Expand sidebar' }));
  expect(sidebar).not.toHaveClass('is-peeking');
  fireEvent.mouseLeave(sidebar);
  fireEvent.mouseEnter(sidebar);
  expect(sidebar).toHaveClass('is-peeking');
  fireEvent.mouseLeave(sidebar);
  expect(sidebar).not.toHaveClass('is-peeking');
  fireEvent.focus(screen.getByRole('link', { name: 'Timesheets' }));
  expect(sidebar).toHaveClass('is-peeking');
  fireEvent.blur(sidebar, { relatedTarget: document.body });
  expect(sidebar).not.toHaveClass('is-peeking');
});
