import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AppRouter from '../../routes/AppRouter';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import jobSeekerApplicationsApi from '../../api/jobSeekerApplicationsApi';
import jobSeekerEventRegistrationsApi from '../../api/jobSeekerEventRegistrationsApi';
import myHrAuthApi from '../../api/myHrAuthApi';
import myHrDocumentRequestApi from '../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';

vi.mock('../../api/jobSeekerProfileApi');
vi.mock('../../api/jobSeekerApplicationsApi');
vi.mock('../../api/jobSeekerEventRegistrationsApi');
vi.mock('../../api/myHrAuthApi');
vi.mock('../../api/myHrDocumentRequestApi');
vi.mock('../../api/myHrEmployeeApi');

function navigateTo(path) {
  window.history.pushState({}, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

describe('AppRouter', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    jobSeekerProfileApi.getProfile.mockReturnValue(new Promise(() => {}));
    jobSeekerApplicationsApi.listMyApplications.mockReturnValue(new Promise(() => {}));
    jobSeekerEventRegistrationsApi.listMyRegistrations.mockReturnValue(new Promise(() => {}));
    myHrAuthApi.currentUser.mockRejectedValue(new Error('unauthenticated'));
    myHrDocumentRequestApi.list.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      loginId: 'TD001', employeeId: 'TD001', name: 'Test User', role: 'EMPLOYEE',
      accountStatus: 'ACTIVE', organizationName: 'Tardus', organizationCode: 'TD',
    } } });
  });

  it('renders NotFoundPage for an unmatched path', async () => {
    navigateTo('/this-path-does-not-exist');

    render(<AppRouter />);

    expect(await screen.findByText('404 - Page Not Found')).toBeInTheDocument();
  });

  it('opens the Experience page first for the root path, with sign-in and organization signup', async () => {
    navigateTo('/');

    render(<AppRouter />);

    expect(await screen.findByText('ENTERPRISE WORKFORCE ORCHESTRATION & COMPLIANCE')).toBeInTheDocument();
    const createLinks = screen.getAllByRole('link', { name: /Create Organization Account/ });
    expect(createLinks.length).toBeGreaterThan(0);
    createLinks.forEach((link) => expect(link).toHaveAttribute('href', '/register/organization'));
    expect(screen.getAllByRole('link', { name: /Sign In/ }).every((link) => link.getAttribute('href') === '/login')).toBe(true);
  });

  it('renders the LoginPage for /login', async () => {
    navigateTo('/login');

    render(<AppRouter />);

    expect(await screen.findByText('Welcome back')).toBeInTheDocument();
  });

  it('renders public organization registration without authentication', async () => {
    navigateTo('/register/organization');
    render(<AppRouter />);
    expect(await screen.findByRole('heading', { name: 'Create Organization Account' })).toBeInTheDocument();
  });

  it('renders the live LoginPage for /live-login', async () => {
    navigateTo('/live-login');

    render(<AppRouter />);

    expect(await screen.findByText('Welcome back')).toBeInTheDocument();
  });

  it('redirects the old MY HR login bookmark to the real login page', async () => {
    navigateTo('/demo/login?persona=my-hr');

    render(<AppRouter />);

    expect(await screen.findByLabelText(/Login ID/)).toBeInTheDocument();
  });

  it('renders ProfilePage for /job-seeker/profile', async () => {
    localStorage.setItem('accessToken', 'test-token');
    navigateTo('/job-seeker/profile');

    render(<AppRouter />);

    expect(await screen.findByText(/loading your profile/i)).toBeInTheDocument();
    expect(jobSeekerProfileApi.getProfile).toHaveBeenCalled();

    localStorage.removeItem('accessToken');
  });

  it('does not allow another account token into the MY HR employee area', async () => {
    localStorage.setItem('accessToken', 'another-account-token');
    navigateTo('/employee/dashboard');

    render(<AppRouter />);

    expect(await screen.findByLabelText(/Login ID/)).toBeInTheDocument();
    // The login page no longer carries persona-specific ("MY HR") copy -
    // there is a single unified Sign In experience (Job Seeker login removed).
    expect(screen.getByText('Sign in to continue.')).toBeInTheDocument();

    localStorage.removeItem('accessToken');
  });

  it('renders the protected My HR Admin dashboard after backend session validation', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: 'admin-1', loginId: 'TDADMIN', role: 'COMPANY_ADMIN',
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
    } } });
    navigateTo('/myhr/admin');

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: 'Welcome, Test User' })).toBeInTheDocument();

    localStorage.removeItem('accessToken');
  });

  it.each([
    ['COMPANY_ADMIN', '/myhr/admin/dashboard', 'Welcome, Test User', ['Employee Management', 'Profile Change Requests', 'Timesheets', 'Request Documents', 'Reports', 'Audit History', 'Organization Settings']],
    ['HR', '/myhr/hr/dashboard', 'Welcome, Test User', ['Employee Management', 'Profile Change Requests', 'Timesheets', 'Request Documents', 'Reports']],
    ['EMPLOYEE', '/myhr/employee/dashboard', 'Welcome, Test User', ['Profile Change Requests', 'My Timesheets', 'Document Requests']],
  ])('renders the protected %s dashboard and its permitted navigation', async (role, path, heading, cards) => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: `${role.toLowerCase()}-1`, loginId: 'TD001', role,
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
    } } });
    navigateTo(path);

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    cards.forEach((card) => expect(screen.getByRole('link', { name: card })).toBeInTheDocument());
    expect(screen.queryByText('Payroll')).not.toBeInTheDocument();
    expect(screen.queryByText('Paystubs')).not.toBeInTheDocument();
    expect(screen.queryByText('Job Postings')).not.toBeInTheDocument();
    expect(screen.queryByText('Events')).not.toBeInTheDocument();
  });

  it.each([
    '/myhr/hr/document-requests/req-1/offer-letter',
    '/myhr/admin/document-requests/req-1/offer-letter',
  ])('never gives an EMPLOYEE the offer letter send/email controls at %s', async (path) => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: 'employee-1', loginId: 'TD001', role: 'EMPLOYEE',
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
    } } });
    navigateTo(path);

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: 'Welcome, Test User' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/myhr/employee/dashboard');
    expect(screen.queryByLabelText(/^Recipient Email/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send Offer Letter' })).not.toBeInTheDocument();
    expect(myHrDocumentRequestApi.getOfferLetterEmailDraft).not.toHaveBeenCalled();
    expect(myHrDocumentRequestApi.emailOfferLetter).not.toHaveBeenCalled();
  });

  it.each(['COMPANY_ADMIN', 'HR', 'EMPLOYEE'])(
    'allows %s to access regular My HR Change Password',
    async (role) => {
      localStorage.setItem('accessToken', 'signed-jwt');
      myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
        userId: `${role.toLowerCase()}-1`, loginId: 'TD001', role,
        organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
      } } });
      navigateTo('/myhr/change-password');

      render(<AppRouter />);

      expect(await screen.findByRole('heading', { name: 'Change Password' })).toBeInTheDocument();
      expect(screen.getByLabelText(/^Current Password/)).toBeInTheDocument();
    });

  it.each([
    ['COMPANY_ADMIN', '/myhr/admin/profile'],
    ['HR', '/myhr/hr/profile'],
    ['EMPLOYEE', '/myhr/employee/profile'],
  ])('renders the protected %s own Profile route', async (role, path) => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: `${role.toLowerCase()}-1`, loginId: 'TD001', role,
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
    } } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      loginId: 'TD001', employeeId: role === 'COMPANY_ADMIN' ? null : 'TD001',
      name: role === 'COMPANY_ADMIN' ? null : 'Test User', role,
      accountStatus: 'ACTIVE', accountEmail: 'user@example.com',
      organizationName: 'Tardus', organizationCode: 'TD',
    } } });
    navigateTo(path);

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: 'My Profile' })).toBeInTheDocument();
    await waitFor(() => expect(myHrEmployeeApi.getMine).toHaveBeenCalled());
  });

  it('forces a temporary-password user into the dedicated first-login route', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: 'hr-1', loginId: 'TD001', role: 'HR',
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: true,
    } } });
    navigateTo('/myhr/hr/dashboard');

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: 'Create your new password' })).toBeInTheDocument();
    expect(screen.getByLabelText(/Temporary Password/)).toBeInTheDocument();
  });

  it('does not force a normal user through the first-login page', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: 'hr-1', loginId: 'TD001', role: 'HR',
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
    } } });
    navigateTo('/myhr/first-login/change-password');

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: 'Welcome, Test User' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Create your new password' })).not.toBeInTheDocument();
  });

  it('renders the protected Document Requests placeholder', async () => {
    localStorage.setItem('accessToken', 'signed-jwt');
    myHrAuthApi.currentUser.mockResolvedValue({ data: { data: {
      userId: 'employee-1', loginId: 'TD003', role: 'EMPLOYEE',
      organizationId: 'org-1', accountStatus: 'ACTIVE', mustChangePassword: false,
    } } });
    navigateTo('/myhr/employee/document-requests');

    render(<AppRouter />);

    expect(await screen.findByRole('heading', { name: 'Document Requests' })).toBeInTheDocument();
    expect(await screen.findByText('No document requests available.')).toBeInTheDocument();
  });

  it('renders DashboardPage for /dashboard', async () => {
    navigateTo('/dashboard');

    render(<AppRouter />);

    expect(await screen.findByLabelText('Development area')).toBeInTheDocument();
  });
});
