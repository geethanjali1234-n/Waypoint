import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MobileBottomNav from '../../components/navigation/MobileBottomNav';

function renderBottomNav(role = 'HR', path = '/myhr/hr/dashboard') {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'fixture-user', role }));
  return render(<MemoryRouter initialEntries={[path]}><MobileBottomNav /></MemoryRouter>);
}

describe('MobileBottomNav', () => {
  beforeEach(() => localStorage.clear());
  it.each([
    ['HR', 'hr'], ['COMPANY_ADMIN', 'admin'], ['EMPLOYEE', 'employee'],
  ])('provides Home, Time, Docs and Me for %s using its existing routes', (role, segment) => {
    renderBottomNav(role, `/myhr/${segment}/dashboard`);
    expect(screen.getByRole('navigation', { name: /mobile bottom navigation/i })).toBeInTheDocument();
    expect(screen.getAllByRole('link')).toHaveLength(4);
    for (const [label, suffix] of [['Home', 'dashboard'], ['Time', 'timesheets'], ['Docs', 'document-requests'], ['Me', 'profile']]) {
      expect(screen.getByRole('link', { name: `Mobile ${label}` })).toHaveAttribute('href', `/myhr/${segment}/${suffix}`);
    }
    expect(screen.getByRole('link', { name: 'Mobile Home' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Mobile Time' })).not.toHaveAttribute('aria-current');
  });
  it('changes the active destination when navigating', async () => {
    renderBottomNav('EMPLOYEE', '/myhr/employee/dashboard');
    await userEvent.setup().click(screen.getByRole('link', { name: 'Mobile Time' }));
    expect(screen.getByRole('link', { name: 'Mobile Time' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Mobile Home' })).not.toHaveAttribute('aria-current');
  });
  it('does not escalate an employee on an admin path to admin destinations', () => {
    renderBottomNav('EMPLOYEE', '/myhr/admin/dashboard');
    expect(screen.getByRole('link', { name: 'Mobile Time' })).toHaveAttribute('href', '/myhr/employee/timesheets');
  });
  it('supports workspace-specific destinations without adding My HR routes', () => {
    render(<MemoryRouter initialEntries={['/careers/fixture-job']}><MobileBottomNav items={[{ label: 'Careers', path: '/careers', icon: 'briefcase' }]} /></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'Mobile Careers' })).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByRole('link', { name: 'Mobile Time' })).not.toBeInTheDocument();
  });
});
