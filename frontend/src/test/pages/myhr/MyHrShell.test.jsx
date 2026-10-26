import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import MyHrShell from '../../../pages/myhr/MyHrShell';
import MyHrProtectedRoute from '../../../routes/MyHrProtectedRoute';
import { saveMyHrAuthentication } from '../../../auth/myHrSession';
import myHrAuthApi from '../../../api/myHrAuthApi';
import myHrNotificationApi from '../../../api/myHrNotificationApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';

vi.mock('../../../api/myHrAuthApi');
vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../api/myHrNotificationApi', () => ({
  default: {
    listNotifications: vi.fn(),
    getUnreadCount: vi.fn(),
    markAsRead: vi.fn(),
    markAllAsRead: vi.fn(),
  },
}));

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="loc">{location.pathname + location.search}</div>;
}

function renderShell(role) {
  localStorage.setItem('accessToken', 'test-token');
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'u1', loginId: 'TD001', role, organizationId: 'org-1' }));
  return render(
    <MemoryRouter initialEntries={['/myhr/hr/dashboard']}>
      <Routes>
        <Route path="*" element={<><MyHrShell><div>content</div></MyHrShell><LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('MyHrShell logout', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      loginId: 'TD001', role: 'HR', mustChangePassword: false,
    } } });
    myHrNotificationApi.getUnreadCount.mockResolvedValue({ data: { data: { count: 0 } } });
    myHrNotificationApi.listNotifications.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { organizationName: 'SNR Tech' } } });
  });

  it('clears all My HR authentication and replaces the protected route with login', async () => {
    saveMyHrAuthentication('signed-jwt', { loginId: 'TD001', role: 'HR' });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/myhr/hr/dashboard']}>
        <Routes>
          <Route path="/myhr/login" element={<p>My HR Login</p>} />
          <Route path="/myhr/hr/dashboard" element={
            <MyHrProtectedRoute allowedRoles={['HR']}>
              <MyHrShell><p>Protected dashboard</p></MyHrShell>
            </MyHrProtectedRoute>
          } />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(await screen.findByRole('button', { name: /TD001/i }));
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }));

    expect(await screen.findByText('My HR Login')).toBeInTheDocument();
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('myHrAuthenticatedUser')).toBeNull();
  });
});

describe('MyHrShell account menu', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    myHrNotificationApi.getUnreadCount.mockResolvedValue({ data: { data: { count: 0 } } });
    myHrNotificationApi.listNotifications.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { organizationName: 'SNR Tech' } } });
  });

  it('shows the Company Admin person name and login ID and retains account actions', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { firstName: 'Alex', lastName: 'Morgan', organizationName: 'SNR Tech' } } });
    const user = userEvent.setup();
    renderShell('COMPANY_ADMIN');

    await user.click(await screen.findByRole('button', { name: /Alex Morgan.*TD001/i }));
    expect(myHrEmployeeApi.getMine).toHaveBeenCalledOnce();
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent))
      .toEqual(['Profile', 'Change password', 'Sign out']);
  });

  it('uses the session login ID when the Company Admin profile is unavailable', async () => {
    myHrEmployeeApi.getMine.mockRejectedValue(new Error('not available'));
    renderShell('COMPANY_ADMIN');

    expect(await screen.findByRole('button', { name: /TD001/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /SNR Tech/i })).not.toBeInTheDocument();
  });

  it.each(['HR', 'EMPLOYEE'])('shows the %s profile name with the login ID as secondary text', async (role) => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { firstName: 'lilly' } } });
    renderShell(role);

    expect(await screen.findByRole('button', { name: /Lilly.*TD001/i })).toBeInTheDocument();
    expect(myHrEmployeeApi.getMine).toHaveBeenCalledOnce();
  });

  it.each([
    ['COMPANY_ADMIN', '/myhr/admin/profile'],
    ['HR', '/myhr/hr/profile'],
    ['EMPLOYEE', '/myhr/employee/profile'],
  ])('shows Profile before Change password and routes %s correctly', async (role, expectedPath) => {
    const user = userEvent.setup();
    renderShell(role);

    await user.click(await screen.findByRole('button', {
      name: /TD001/i,
    }));
    const items = screen.getAllByRole('menuitem');
    expect(items.map((item) => item.textContent)).toEqual(['Profile', 'Change password', 'Sign out']);
    await user.click(screen.getByRole('menuitem', { name: 'Profile' }));

    expect(await screen.findByText(expectedPath)).toBeInTheDocument();
  });
});

describe('MyHrShell notification navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { organizationName: 'SNR Tech' } } });
    myHrNotificationApi.getUnreadCount.mockResolvedValue({ data: { data: { count: 1 } } });
    myHrNotificationApi.markAsRead.mockResolvedValue({});
  });

  it('rewrites an employee-path notification link to the HR page for an HR user', async () => {
    const user = userEvent.setup();
    myHrNotificationApi.listNotifications.mockResolvedValue({
      data: { data: [{
        id: 'n1', title: 'Timesheet approved', message: 'Your timesheet was approved.',
        read: false, createdAt: '2026-09-10T10:00:00Z',
        actionUrl: '/myhr/employee/timesheets?mode=history&timesheetId=ts-9',
      }] },
    });

    renderShell('HR');

    await user.click(await screen.findByRole('button', { name: 'Notifications' }));
    await user.click(await screen.findByRole('button', { name: /Timesheet approved/ }));

    expect(await screen.findByText('/myhr/hr/timesheets?mode=history&timesheetId=ts-9&view=mine'))
      .toBeInTheDocument();
  });

  it('leaves an employee notification link untouched for an employee', async () => {
    const user = userEvent.setup();
    myHrNotificationApi.listNotifications.mockResolvedValue({
      data: { data: [{
        id: 'n2', title: 'Timesheet rejected', message: 'Please revise.', read: false,
        createdAt: '2026-09-10T10:00:00Z',
        actionUrl: '/myhr/employee/timesheets?mode=history&rejected=true&timesheetId=ts-3',
      }] },
    });

    renderShell('EMPLOYEE');

    await user.click(await screen.findByRole('button', { name: 'Notifications' }));
    await user.click(await screen.findByRole('button', { name: /Timesheet rejected/ }));

    expect(await screen.findByText('/myhr/employee/timesheets?mode=history&rejected=true&timesheetId=ts-3'))
      .toBeInTheDocument();
  });

  it('rewrites a notification link to the admin team view for a COMPANY_ADMIN user', async () => {
    const user = userEvent.setup();
    myHrNotificationApi.listNotifications.mockResolvedValue({
      data: { data: [{
        id: 'n3', title: 'New timesheet submitted', message: 'Employee submitted a timesheet.', read: false,
        createdAt: '2026-09-10T10:00:00Z',
        actionUrl: '/myhr/admin/timesheets?timesheetId=ts-admin-1',
      }] },
    });

    renderShell('COMPANY_ADMIN');

    await user.click(await screen.findByRole('button', { name: 'Notifications' }));
    await user.click(await screen.findByRole('button', { name: /New timesheet submitted/ }));

    expect(await screen.findByText('/myhr/admin/timesheets?timesheetId=ts-admin-1&view=team'))
      .toBeInTheDocument();
  });
});
