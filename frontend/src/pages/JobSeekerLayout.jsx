import { useEffect, useState } from 'react';
import { useNavigate, Outlet } from 'react-router-dom';
import {
  MobileBottomNav,
  NotificationMenu,
  ProfileMenu,
} from '../components';
import useJobSeekerIdentity from '../hooks/useJobSeekerIdentity';
import notificationApi from '../api/notificationApi';
import WorkspaceFrame from '../components/layout/WorkspaceFrame';
import WorkspaceSidebar from '../components/navigation/WorkspaceSidebar';
import StrokeIcon from '../components/global/StrokeIcon';
import { jobSeekerNavigation, navigationActions } from '../components/navigation/workspaceNavigation';
import './JobSeekerLayout.css';

function formatNotificationTimestamp(value) {
  if (!value) return undefined;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return undefined;
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function initialFor(firstName) {
  return firstName ? firstName.charAt(0).toUpperCase() : 'E';
}

/**
 * JobSeekerLayout - persistent job seeker shell (header, navigation, avatar),
 * mirroring EmployerLayout's structure and styling. Shared by every
 * /job-seeker/* page plus /careers and /events.
 *
 * @example
 * <Route element={<JobSeekerLayout />}>
 *   <Route path="dashboard" element={<JobSeekerDashboardPage />} />
 * </Route>
 */
export default function JobSeekerLayout() {
  const navigate = useNavigate();
  const identity = useJobSeekerIdentity();
  const [unreadCount, setUnreadCount] = useState(null);
  const [recentNotifications, setRecentNotifications] = useState([]);


  const refreshUnreadCount = () => {
    notificationApi
      .getUnreadCount()
      .then((response) => {
        setUnreadCount(response.data.data.count);
      })
      .catch(() => {
        /* Bell badge simply stays at its last known value if this fails. */
      });

    notificationApi
      .listNotifications(5)
      .then((response) => {
        const items = response.data.data || [];
        setRecentNotifications(
          items.map((notification) => ({
            id: notification.id,
            title: notification.title,
            subtitle: notification.message,
            timestamp: formatNotificationTimestamp(notification.createdAt),
            read: notification.read,
            actionUrl: notification.actionUrl,
          }))
        );
      })
      .catch(() => {
        /* Dropdown simply keeps its last known list if this fails. */
      });
  };

  useEffect(() => {
    refreshUnreadCount();
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    navigate('/login');
  };

  const handleMarkAllRead = () => {
    notificationApi
      .markAllAsRead()
      .then(() => {
        refreshUnreadCount();
      })
      .catch(() => {
        /* No-op: badge simply won't update if this fails. */
      });
  };

  const handleNotificationClick = (notification) => {
    if (!notification.read) {
      notificationApi
        .markAsRead(notification.id)
        .then(() => {
          refreshUnreadCount();
        })
        .catch(() => {
          /* Navigation still proceeds even if marking as read fails. */
        });
    }

    if (notification.actionUrl) {
      navigate(notification.actionUrl);
    }
  };

  const displayName = identity.name || identity.firstName || 'Job Seeker';
  return <WorkspaceFrame home="/job-seeker/dashboard" workspaceLabel="Job seeker workspace" className="job-seeker-shell"
    sidebar={<WorkspaceSidebar groups={jobSeekerNavigation} storageKey="waypoint_job_seeker_sidebar_collapsed" />}
    quickJumpActions={navigationActions(jobSeekerNavigation)}
    mobileNav={<MobileBottomNav items={[
      { label: 'Home', path: '/job-seeker/dashboard', icon: 'home' },
      { label: 'Careers', path: '/careers', icon: 'briefcase' },
      { label: 'Events', path: '/events', icon: 'calendar' },
      { label: 'Me', path: '/job-seeker/profile', icon: 'profile' },
    ]} />}
    account={<>
      <NotificationMenu icon={<StrokeIcon name="bell" />} unreadCount={unreadCount} notifications={recentNotifications}
        onMarkAllRead={handleMarkAllRead} onNotificationClick={handleNotificationClick} />
      <ProfileMenu user={{ name: displayName, subtitle: identity.loginId, showSubtitle: true, initials: initialFor(identity.firstName), imageUrl: identity.profilePicturePath }}
        items={[
          { label: 'Profile', onClick: () => navigate('/job-seeker/profile') },
          { label: 'Contact Support', onClick: () => navigate('/job-seeker/contact-us') },
          { label: 'Change Password', onClick: () => navigate('/job-seeker/change-password') },
          { label: 'Logout', onClick: handleLogout },
        ]} className="job-seeker-shell__profile-menu" />
    </>}>
    <div className="job-seeker-shell__workspace"><Outlet context={{ refreshUnreadCount, refreshIdentity: identity.refresh }} /></div>
  </WorkspaceFrame>;
}
