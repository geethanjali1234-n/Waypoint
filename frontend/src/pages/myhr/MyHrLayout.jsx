import { Outlet, useNavigate } from 'react-router-dom';
import { AppLayout, Header, ProfileMenu } from '../../components';
import WaypointMark from '../../components/branding/WaypointMark';
import { MYHR_ORGANIZATION, clearMyHrSession, getMyHrSession } from './mockDirectory';
import './MyHrLayout.css';

function getInitials(name) {
  if (!name) return '—';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word[0])
    .join('')
    .toUpperCase();
}

/** Shared shell for the HR role's My HR pages. */
export default function MyHrLayout() {
  const navigate = useNavigate();
  const session = getMyHrSession();

  const handleLogout = () => {
    clearMyHrSession();
    localStorage.removeItem('accessToken');
    navigate('/login?persona=my-hr', { replace: true });
  };

  const header = (
    <Header
      className="myhr-layout__header"
      logo={
        <button
          type="button"
          className="myhr-layout__brand"
          aria-label="Go to My HR dashboard"
          onClick={() => navigate('/myhr/hr/dashboard')}
        >
          <WaypointMark className="myhr-layout__brand-mark" />
          <span>Waypoint My HR</span>
        </button>
      }
      actions={
        <ProfileMenu
          user={{
            name: session?.name || '—',
            subtitle: `${session?.role || ''} · ${MYHR_ORGANIZATION.name}`,
            initials: getInitials(session?.name),
          }}
          items={[{ label: 'Logout', onClick: handleLogout }]}
          className="myhr-layout__profile-menu"
        />
      }
    />
  );

  return (
    <AppLayout header={header} className="myhr-layout">
      <Outlet />
    </AppLayout>
  );
}
