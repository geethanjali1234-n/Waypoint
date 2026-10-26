import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import WorkspaceFrame from '../../components/layout/WorkspaceFrame';
import WorkspaceSidebar from '../../components/navigation/WorkspaceSidebar';
import ProfileMenu from '../../components/navigation/ProfileMenu';
import { ThemeProvider } from '../../context/ThemeContext';
import myHrReportApi from '../../api/myHrReportApi';

vi.mock('../../api/myHrReportApi', () => ({ default: { searchEmployees: vi.fn() } }));
const groups = [{ label: 'Governance', items: [{ label: 'Organizations', path: '/waypoint-admin/organizations', icon: 'organization' }] }];
function show() {
  return render(<MemoryRouter initialEntries={['/waypoint-admin/organizations']}><ThemeProvider>
    <WorkspaceFrame home="/waypoint-admin/dashboard" workspaceLabel="Platform workspace"
      sidebar={<WorkspaceSidebar groups={groups} storageKey="test-sidebar" />}
      account={<ProfileMenu user={{ name: 'Fixture person', subtitle: 'FIXTURE-ID', initials: 'F' }} items={[]} />}
      quickJumpActions={[{ id: 'organizations', title: 'Organizations', category: 'Governance', path: '/waypoint-admin/organizations' }]}>
      <h1>Page content</h1>
    </WorkspaceFrame>
  </ThemeProvider></MemoryRouter>);
}
beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); });
describe('WorkspaceFrame', () => {
  it('provides a skip link, person-first identity, theme toggle and active sidebar', async () => {
    show();
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#workspace-content');
    expect(screen.getByRole('main')).toHaveAttribute('id', 'workspace-content');
    expect(screen.getByRole('button', { name: /Fixture person.*FIXTURE-ID/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Organizations' })).toHaveAttribute('aria-current', 'page');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Switch to dark theme' }));
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  });
  it('persists sidebar collapse and retains accessible destinations', () => {
    show();
    fireEvent.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
    expect(localStorage.getItem('test-sidebar')).toBe('true');
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('link', { name: 'Organizations' })).toHaveAttribute('href', '/waypoint-admin/organizations');
  });
  it('opens on Ctrl K, traps focus and restores the trigger when closed', async () => {
    show();
    const trigger = screen.getByRole('button', { name: /Universal Quick Jump/ });
    trigger.focus();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    const dialog = screen.getByRole('dialog', { name: 'Universal Quick Jump' });
    const input = within(dialog).getByRole('textbox', { name: 'Search Quick Jump' });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.keyDown(input, { key: 'Tab', shiftKey: true });
    expect(within(dialog).getByRole('button', { name: /Organizations/ })).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
    expect(myHrReportApi.searchEmployees).not.toHaveBeenCalled();
  });
  it('opens on Command K and can dismiss with an actual backdrop button', () => {
    show();
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss Quick Jump' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
