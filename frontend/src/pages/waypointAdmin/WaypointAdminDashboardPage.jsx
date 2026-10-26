import WaypointAdminShell from './WaypointAdminShell';
import PageHeader from '../../components/layout/PageHeader';
import './WaypointAdminDashboardPage.css';

export default function WaypointAdminDashboardPage() {
  return (
    <WaypointAdminShell>
      <section className="waypoint-admin-dashboard">
        <PageHeader eyebrow="Platform administration" title="Waypoint Admin Dashboard" summary="Manage approved platform operations from a workspace separated from customer My HR data." />
        <section className="waypoint-admin-dashboard__empty" aria-labelledby="platform-tools-heading">
          <h2 id="platform-tools-heading">Platform tools</h2>
          <p>Use Organizations in the navigation to review approved business information.</p>
        </section>
      </section>
    </WaypointAdminShell>
  );
}
