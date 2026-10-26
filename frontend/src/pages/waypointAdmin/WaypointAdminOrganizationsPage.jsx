import { useEffect, useState } from 'react';
import waypointAdminApi from '../../api/waypointAdminApi';
import DataTable from '../../components/global/DataTable';
import StatusBadge from '../../components/global/StatusBadge';
import PageHeader from '../../components/layout/PageHeader';
import DetailPanel from '../../components/layout/DetailPanel';
import WaypointAdminShell from './WaypointAdminShell';
import './WaypointAdminOrganizationsPage.css';

const PAGE_SIZE = 10;
const columns = [
  { key: 'person', header: 'Organization', type: 'person' },
  { key: 'status', header: 'Status', render: (value) => <StatusBadge status={value} label={value} /> },
  { key: 'industry', header: 'Industry', render: display },
  { key: 'location', header: 'Location' },
];

function display(value) { return value || 'Not provided'; }
const location = (organization) => [organization.city, organization.state].filter(Boolean).join(', ') || 'Not provided';

export default function WaypointAdminOrganizationsPage() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [listState, setListState] = useState({ loading: true, error: '', data: null });
  const [detailState, setDetailState] = useState({ loading: false, error: '', data: null });
  const [detailsOpen, setDetailsOpen] = useState(false);
  const closeDetails = () => setDetailsOpen(false);

  useEffect(() => {
    let active = true;
    setListState((current) => ({ ...current, loading: true, error: '' }));
    waypointAdminApi.getOrganizations({ page, size: PAGE_SIZE, search })
      .then((response) => {
        if (active) setListState({ loading: false, error: '', data: response.data.data });
      })
      .catch((error) => {
        if (active) setListState({
          loading: false,
          error: error?.response?.data?.message || 'Organizations could not be loaded.',
          data: null,
        });
      });
    return () => { active = false; };
  }, [page, search]);

  const submitSearch = (event) => {
    event.preventDefault();
    setPage(0);
    setSearch(searchInput.trim());
    setDetailsOpen(false);
    setDetailState({ loading: false, error: '', data: null });
  };

  const openDetails = (organizationId) => {
    setDetailsOpen(true);
    setDetailState({ loading: true, error: '', data: null });
    waypointAdminApi.getOrganization(organizationId)
      .then((response) => setDetailState({ loading: false, error: '', data: response.data.data }))
      .catch((error) => setDetailState({
        loading: false,
        error: error?.response?.data?.message || 'Organization details could not be loaded.',
        data: null,
      }));
  };

  const organizations = listState.data?.items || [];

  return (
    <WaypointAdminShell>
      <section className="waypoint-admin-organizations">
        <PageHeader eyebrow="Platform administration" title="Organizations" summary="View approved business information for companies using Waypoint." />

        <form className="waypoint-admin-organizations__search" role="search" onSubmit={submitSearch}>
          <label htmlFor="organization-search">Search organizations</label>
          <div>
            <input
              id="organization-search"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder="Name, code, or email"
            />
            <button type="submit">Search</button>
          </div>
        </form>

        {listState.loading && <p role="status">Loading organizations…</p>}
        {listState.error && <p role="alert">{listState.error}</p>}
        {!listState.loading && !listState.error && (
          <>
            <DataTable
              label="Organizations"
              columns={columns}
              rows={organizations.map((organization) => ({ ...organization, person: { name: organization.organizationName, id: organization.organizationCode }, location: location(organization) }))}
              getRowLabel={(organization) => organization.organizationName}
              onOpenRow={(organization) => openDetails(organization.id)}
              pagination={{ page: page + 1, pageSize: PAGE_SIZE, total: listState.data?.totalElements ?? organizations.length, onPageChange: (nextPage) => setPage(nextPage - 1) }}
              emptyMessage={search ? 'No organizations match your search.' : 'No organizations are available.'}
            />
          </>
        )}

        <DetailPanel open={detailsOpen} onClose={closeDetails} title={detailState.data?.organizationName || 'Organization details'}>
          {detailState.loading && <p role="status">Loading organization details…</p>}
          {detailState.error && <p role="alert">{detailState.error}</p>}
          {detailState.data && <OrganizationDetails organization={detailState.data} />}
        </DetailPanel>
      </section>
    </WaypointAdminShell>
  );
}

function OrganizationDetails({ organization }) {
  return (
    <section className="waypoint-admin-organizations__detail" aria-label="Organization information">
      <dl>
        <div><dt>Organization code</dt><dd>{display(organization.organizationCode)}</dd></div>
        <div><dt>Status</dt><dd><StatusBadge status={organization.status} label={organization.status} /></dd></div>
        <div><dt>Business email</dt><dd>{display(organization.organizationEmail)}</dd></div>
        <div><dt>Phone</dt><dd>{display(organization.phoneNumber)}</dd></div>
        <div><dt>Industry</dt><dd>{display(organization.industry)}</dd></div>
        <div><dt>Company size</dt><dd>{display(organization.organizationSize)}</dd></div>
        <div><dt>Website</dt><dd>{display(organization.website)}</dd></div>
        <div><dt>Address</dt><dd>{display([organization.streetAddress, organization.city, organization.state, organization.zipCode].filter(Boolean).join(', '))}</dd></div>
        <div><dt>Description</dt><dd>{display(organization.description)}</dd></div>
      </dl>
    </section>
  );
}
