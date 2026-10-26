import { useNavigate } from 'react-router-dom';
import { MobileBottomNav, ProfileMenu } from '../../components';
import WorkspaceFrame from '../../components/layout/WorkspaceFrame';
import WorkspaceSidebar from '../../components/navigation/WorkspaceSidebar';
import { navigationActions, platformNavigation } from '../../components/navigation/workspaceNavigation';
import { clearMyHrAuthentication, getMyHrSession } from '../../auth/myHrSession';
import './WaypointAdminShell.css';

export default function WaypointAdminShell({ children }) {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const signOut = () => {
    clearMyHrAuthentication();
    navigate('/login', { replace: true });
  };

  const name = session?.name || session?.loginId || session?.email || 'Waypoint Admin';
  return <WorkspaceFrame home="/waypoint-admin/dashboard" workspaceLabel="Platform workspace" className="waypoint-admin-shell"
    mainClassName="waypoint-admin-shell__main"
    quickJumpActions={navigationActions(platformNavigation)}
    sidebar={<WorkspaceSidebar groups={platformNavigation} label="Waypoint Admin navigation" storageKey="waypoint_platform_sidebar_collapsed" />}
    mobileNav={<MobileBottomNav items={platformNavigation.flatMap((group) => group.items)} />}
    account={<ProfileMenu user={{ name, subtitle: session?.loginId || session?.email, showSubtitle: true, initials: name.slice(0, 1).toUpperCase() }} items={[{ label: 'Sign out', onClick: signOut }]} />}>
    {children}
  </WorkspaceFrame>;
}
