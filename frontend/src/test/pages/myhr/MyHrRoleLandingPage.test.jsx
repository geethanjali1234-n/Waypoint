import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { UiVariantContext } from '../../../context/uiVariant';
import { MemoryRouter } from 'react-router-dom';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import { saveMyHrAuthentication } from '../../../auth/myHrSession';
import MyHrRoleLandingPage from '../../../pages/myhr/MyHrRoleLandingPage';

vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../pages/myhr/MyHrShell', () => ({
  default: ({ children }) => <div data-testid="shell">{children}</div>,
}));

function renderDashboard(role, loginId = 'TD001', activeVariant = 'v1') {
  saveMyHrAuthentication('signed-jwt', { loginId, role });
  return render(<UiVariantContext.Provider value={{ activeVariant }}><MemoryRouter><MyHrRoleLandingPage role={role} /></MemoryRouter></UiVariantContext.Provider>);
}

function hero() {
  return document.querySelector('.myhr-role-landing__hero');
}

describe('MyHrRoleLandingPage hero', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('greets the Company Admin with its authenticated organization name', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      organizationId: 'org-1', organizationName: 'SNR Tech', loginId: 'SNRTECH-ADMIN',
    } } });
    renderDashboard('COMPANY_ADMIN', 'TDADMIN');

    expect(within(hero()).getByRole('heading', { name: 'Welcome' })).toBeInTheDocument();
    expect(await within(hero()).findByRole('heading', { name: 'Welcome, SNR Tech' })).toBeInTheDocument();
    expect(within(hero()).getByText('YOUR WORKSPACE')).toBeInTheDocument();
  });

  it('keeps the same professional actions when a legacy visual variant is selected', () => {
    renderDashboard('COMPANY_ADMIN', 'TDADMIN', 'v2');
    expect(screen.getByRole('heading', { name: 'Admin Actions' })).toBeInTheDocument();
    expect(document.querySelectorAll('.myhr-role-landing__actions .wp-action-card')).toHaveLength(7);
    expect(screen.queryByText('WAYPOINT / MISSION CONTROL')).not.toBeInTheDocument();
  });

  it('renders all actions cleanly without a shuffle control', () => {
    renderDashboard('COMPANY_ADMIN', 'TDADMIN', 'v3');
    const actions = () => Array.from(document.querySelectorAll('.myhr-role-landing__actions .wp-action-card')).map((node) => node.textContent);
    expect(actions()).toHaveLength(7);
    expect(screen.queryByRole('button', { name: /Mix it up/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/PORTALS/)).not.toBeInTheDocument();
  });

  it('keeps the Admin login greeting without loading an employee profile', () => {
    renderDashboard('COMPANY_ADMIN', 'TDADMIN');

    expect(within(hero()).getByRole('heading', { name: 'Welcome, TDADMIN' })).toBeInTheDocument();
    expect(within(hero()).getByText('YOUR WORKSPACE')).toBeInTheDocument();
    expect(hero().querySelector('.myhr-role-landing__identity')).toBeNull();
    expect(screen.getByRole('heading', { name: 'Admin Actions' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Company Admin Dashboard' })).not.toBeInTheDocument();
    expect(myHrEmployeeApi.getMine).toHaveBeenCalledOnce();
  });

  it('uses a safe Company Admin fallback when organization data is missing', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {
      organizationId: 'org-1', organizationName: '   ', loginId: 'TDADMIN',
    } } });
    renderDashboard('COMPANY_ADMIN', 'TDADMIN');

    expect(await within(hero()).findByRole('heading', { name: 'Welcome, Company Admin' })).toBeInTheDocument();
    expect(within(hero()).queryByText('TDADMIN')).not.toBeInTheDocument();
  });

  it('does not render organization data outside the authenticated profile response', async () => {
    myHrEmployeeApi.getMine.mockRejectedValue({ response: { status: 404 } });
    renderDashboard('COMPANY_ADMIN', 'TDADMIN');

    expect(await within(hero()).findByRole('heading', { name: 'Welcome, Company Admin' })).toBeInTheDocument();
    expect(within(hero()).queryByText(/SNR Tech|Tardus/i)).not.toBeInTheDocument();
  });

  it.each([
    ['HR', 'John', 'HR Actions'],
    ['EMPLOYEE', 'Lilly', 'Employee Actions'],
  ])('never flashes the login ID before the canonical %s first name resolves', async (role, firstName, actionsHeading) => {
    let resolveGetMine;
    myHrEmployeeApi.getMine.mockReturnValue(new Promise((resolve) => { resolveGetMine = resolve; }));
    renderDashboard(role);

    // While the own-profile request is still pending, the hero must show
    // neither the login ID nor a name - just the static "Welcome" greeting.
    expect(within(hero()).getByRole('heading', { name: 'Welcome' })).toBeInTheDocument();
    expect(within(hero()).queryByText('TD001')).not.toBeInTheDocument();

    resolveGetMine({ data: { data: { firstName: `  ${firstName}  ` } } });

    expect(await within(hero()).findByRole('heading', { name: `Welcome, ${firstName}` })).toBeInTheDocument();
    expect(within(hero()).queryByRole('heading', { name: 'Welcome, TD001' })).not.toBeInTheDocument();
    expect(within(hero()).getByText('YOUR WORKSPACE')).toBeInTheDocument();
    expect(hero().querySelector('.myhr-role-landing__identity')).toBeNull();
    expect(screen.getByRole('heading', { name: actionsHeading })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: `${role === 'HR' ? 'HR' : 'Employee'} Dashboard` })).not.toBeInTheDocument();
  });

  it('falls back to the login ID once own-profile loading fails, and keeps actions usable', async () => {
    myHrEmployeeApi.getMine.mockRejectedValue(new Error('profile unavailable'));
    renderDashboard('HR');

    expect(within(hero()).getByRole('heading', { name: 'Welcome' })).toBeInTheDocument();
    await waitFor(() => expect(myHrEmployeeApi.getMine).toHaveBeenCalledOnce());
    expect(await within(hero()).findByRole('heading', { name: 'Welcome, TD001' })).toBeInTheDocument();
    expect(within(hero()).queryByText(/undefined|null/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Employee Management/ })).toBeEnabled();
  });

  it('falls back to the login ID when the profile has no usable first name', async () => {
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: { firstName: '   ' } } });
    renderDashboard('EMPLOYEE');

    expect(await within(hero()).findByRole('heading', { name: 'Welcome, TD001' })).toBeInTheDocument();
  });
});
