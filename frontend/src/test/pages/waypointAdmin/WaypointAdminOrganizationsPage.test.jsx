import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import WaypointAdminOrganizationsPage from '../../../pages/waypointAdmin/WaypointAdminOrganizationsPage';
import waypointAdminApi from '../../../api/waypointAdminApi';

vi.mock('../../../api/waypointAdminApi', () => ({
  default: {
    getOrganizations: vi.fn(),
    getOrganization: vi.fn(),
  },
}));

const listResponse = (items, overrides = {}) => ({
  data: { data: { items, page: 0, size: 10, totalElements: items.length, totalPages: 1, ...overrides } },
});

describe('WaypointAdminOrganizationsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ loginId: 'WPADMIN', role: 'WAYPOINT_ADMIN' }));
  });

  it('shows loading, renders the directory, and opens approved organization details', async () => {
    let resolveList;
    waypointAdminApi.getOrganizations.mockReturnValue(new Promise((resolve) => { resolveList = resolve; }));
    waypointAdminApi.getOrganization.mockResolvedValue({ data: { data: {
      id: 'org-1', organizationName: 'Acme Health', organizationCode: 'ACME',
      organizationEmail: 'people@acme.example', phoneNumber: '555-0100', industry: 'Healthcare',
      status: 'INACTIVE',
      organizationSize: 'MEDIUM', website: 'https://acme.example', streetAddress: '100 Main St',
      city: 'Chicago', state: 'IL', zipCode: '60601', description: 'Regional provider',
    } } });
    const user = userEvent.setup();

    render(<MemoryRouter><WaypointAdminOrganizationsPage /></MemoryRouter>);
    expect(screen.getByRole('status')).toHaveTextContent('Loading organizations');
    resolveList(listResponse([{
      id: 'org-1', organizationName: 'Acme Health', organizationCode: 'ACME',
      organizationEmail: 'people@acme.example', city: 'Chicago', state: 'IL', industry: 'Healthcare',
    }]));

    expect(await screen.findByText('Acme Health')).toBeInTheDocument();
    expect(screen.getByText('Chicago, IL')).toBeInTheDocument();
    expect(screen.getByText('Status unavailable')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'View Acme Health' }));
    expect(await screen.findByRole('heading', { name: 'Acme Health', level: 2 })).toBeInTheDocument();
    expect(screen.getByText('people@acme.example')).toBeInTheDocument();
    expect(screen.getByText('INACTIVE')).toBeInTheDocument();
    expect(screen.queryByText(/storage key/i)).not.toBeInTheDocument();
    expect(waypointAdminApi.getOrganization).toHaveBeenCalledWith('org-1');
    expect(screen.getByRole('dialog', { name: 'Acme Health' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View Acme Health' })).toHaveFocus();
  });

  it('renders authoritative active and inactive statuses in the directory', async () => {
    waypointAdminApi.getOrganizations.mockResolvedValue(listResponse([
      { id: 'org-1', organizationName: 'Active Company', organizationCode: 'ACO', status: 'ACTIVE' },
      { id: 'org-2', organizationName: 'Inactive Company', organizationCode: 'ICO', status: 'INACTIVE' },
    ]));

    render(<MemoryRouter><WaypointAdminOrganizationsPage /></MemoryRouter>);

    expect(await screen.findByText('Active Company')).toBeInTheDocument();
    expect(screen.getByText('Inactive Company')).toBeInTheDocument();
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.getByText('INACTIVE')).toBeInTheDocument();
  });

  it('supports server-side search and pagination', async () => {
    waypointAdminApi.getOrganizations
      .mockResolvedValueOnce(listResponse([{ id: 'org-1', organizationName: 'Acme', organizationCode: 'ACME' }], { totalElements: 11, totalPages: 2 }))
      .mockResolvedValueOnce(listResponse([{ id: 'org-2', organizationName: 'Acme Two', organizationCode: 'ACME2' }], { page: 1, totalPages: 2 }))
      .mockResolvedValueOnce(listResponse([{ id: 'org-3', organizationName: 'Beacon', organizationCode: 'BEACON' }], { page: 0 }));
    const user = userEvent.setup();
    render(<MemoryRouter><WaypointAdminOrganizationsPage /></MemoryRouter>);

    await screen.findByText('Acme');
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(waypointAdminApi.getOrganizations).toHaveBeenCalledWith({ page: 1, size: 10, search: '' }));
    await screen.findByText('Acme Two');
    await user.type(screen.getByLabelText('Search organizations'), '  Beacon  ');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(waypointAdminApi.getOrganizations).toHaveBeenCalledWith({ page: 0, size: 10, search: 'Beacon' }));
    await screen.findByText('Beacon');
  });

  it('renders the empty state', async () => {
    waypointAdminApi.getOrganizations.mockResolvedValue(listResponse([]));
    render(<MemoryRouter><WaypointAdminOrganizationsPage /></MemoryRouter>);
    expect(await screen.findByText('No organizations are available.')).toBeInTheDocument();
  });

  it('renders list and detail errors without exposing stale data', async () => {
    waypointAdminApi.getOrganizations.mockRejectedValueOnce({ response: { data: { message: 'Directory unavailable' } } });
    const { unmount } = render(<MemoryRouter><WaypointAdminOrganizationsPage /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Directory unavailable');
    unmount();

    waypointAdminApi.getOrganizations.mockResolvedValue(listResponse([{ id: 'org-1', organizationName: 'Acme', organizationCode: 'ACME' }]));
    waypointAdminApi.getOrganization.mockRejectedValue({ response: { data: { message: 'Details unavailable' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><WaypointAdminOrganizationsPage /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: 'View Acme' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Details unavailable');
  });
});
