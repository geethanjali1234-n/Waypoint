import { useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  EmptyState,
  Loader,
  MobileBottomNav,
  NotificationMenu,
  ProfileMenu,
} from '../components';
import employerDashboardApi from '../api/employerDashboardApi';
import employerNotificationApi from '../api/employerNotificationApi';
import WorkspaceFrame from '../components/layout/WorkspaceFrame';
import WorkspaceSidebar from '../components/navigation/WorkspaceSidebar';
import StrokeIcon from '../components/global/StrokeIcon';
import { employerNavigation, navigationActions } from '../components/navigation/workspaceNavigation';
import './EmployerLayout.css';

function formatNotificationTimestamp(value) {
  if (!value) return undefined;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return undefined;
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function getInitials(companyName) {
  if (!companyName) {
    return '—';
  }

  const words = companyName.trim().split(/\s+/);
  const initials = words
    .slice(0, 2)
    .map((word) => word[0])
    .join('');

  return initials.toUpperCase();
}

/**
 * EmployerLayout - persistent employer shell (header, navigation, avatar)
 * shared by every /employer/* page. Fetches the authenticated employer's
 * dashboard data once and shares it with child routes via Outlet context,
 * so nested pages (Dashboard, Job Postings) don't need their own copy of
 * the header or a second GET /api/employer/dashboard request. The backend
 * JwtAuthInterceptor is the real authorization boundary; this layout only
 * reacts to its outcomes (redirect on 401, friendly messaging on 403/404).
 *
 * @example
 * <Route element={<EmployerLayout />}>
 *   <Route path="dashboard" element={<EmployerDashboardPage />} />
 * </Route>
 */
export default function EmployerLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [status, setStatus] = useState('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [dashboard, setDashboard] = useState(null);
  const [unreadCount, setUnreadCount] = useState(null);
  const [recentNotifications, setRecentNotifications] = useState([]);

  const refreshDashboard = useCallback(async () => {
    try {
      const response = await employerDashboardApi.getEmployerDashboard();
      setDashboard(response.data.data);
      setStatus('success');
    } catch (fetchError) {
      const httpStatus = fetchError?.response?.status;
      const message = fetchError?.response?.data?.message;

      if (httpStatus === 401) {
        localStorage.removeItem('accessToken');
        navigate('/live-login');
        return;
      }

      if (httpStatus === 403) {
        setErrorMessage(message || 'You do not have access to this dashboard.');
        setStatus('forbidden');
      } else if (httpStatus === 404) {
        setErrorMessage(message || 'We could not find an employer profile for this account.');
        setStatus('not-found');
      } else {
        setErrorMessage(message || 'Something went wrong while loading your dashboard.');
        setStatus('error');
      }
    }
  }, [navigate]);

  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    navigate('/live-login', { replace: true });
  };

  const refreshUnreadCount = () => {
    employerNotificationApi
      .getUnreadCount()
      .then((response) => {
        setUnreadCount(response.data.data.count);
      })
      .catch(() => {
        /* Bell badge simply stays at its last known value if this fails. */
      });

    employerNotificationApi
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

  const handleMarkAllRead = () => {
    employerNotificationApi
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
      employerNotificationApi
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

  useEffect(() => {
    refreshUnreadCount();
  }, []);

  useEffect(() => {
    // The initial authenticated dashboard request populates the persistent employer shell.
    refreshDashboard();
  }, [refreshDashboard]);

  const usesPrimaryPageFrame = [
    '/employer/dashboard',
    '/employer/jobs',
    '/employer/events',
    '/employer/profile',
  ].includes(location.pathname);
  const layoutClassName = `employer-dashboard${usesPrimaryPageFrame ? ' employer-dashboard--primary-page' : ''}`;

  const frameProps = {
    home: '/employer/dashboard', workspaceLabel: 'Employer workspace',
    sidebar: <WorkspaceSidebar groups={employerNavigation} storageKey="waypoint_employer_sidebar_collapsed" />,
    quickJumpActions: navigationActions(employerNavigation),
    mobileNav: <MobileBottomNav items={[
      { label: 'Home', path: '/employer/dashboard', icon: 'home' },
      { label: 'Jobs', path: '/employer/jobs', icon: 'briefcase' },
      { label: 'Events', path: '/employer/events', icon: 'calendar' },
      { label: 'Me', path: '/employer/profile', icon: 'profile' },
    ]} />,
    account: <>
      <NotificationMenu icon={<StrokeIcon name="bell" />} unreadCount={unreadCount} notifications={recentNotifications}
        onMarkAllRead={handleMarkAllRead} onNotificationClick={handleNotificationClick} />
      <ProfileMenu user={{ name: dashboard?.contactName || dashboard?.companyName || 'Employer account', subtitle: dashboard?.email, showSubtitle: true, initials: getInitials(dashboard?.contactName || dashboard?.companyName) }}
        items={[
          { label: 'Profile', onClick: () => navigate('/employer/profile') },
          { label: 'Contact Support', onClick: () => {} },
          { label: 'Change Password', onClick: () => navigate('/employer/change-password') },
          { label: 'Logout', onClick: handleLogout },
        ]} className="employer-dashboard__profile-menu" />
    </>,
  };

  if (status === 'loading') {
    return (
      <WorkspaceFrame {...frameProps} className="employer-dashboard">
        <div className="employer-dashboard__container">
          <Loader size="lg" label="Loading your dashboard…" />
        </div>
      </WorkspaceFrame>
    );
  }

  if (status === 'forbidden' || status === 'not-found' || status === 'error') {
    return (
      <WorkspaceFrame {...frameProps} className="employer-dashboard">
        <div className="employer-dashboard__container">
          <EmptyState title="Unable to load dashboard" description={errorMessage} />
        </div>
      </WorkspaceFrame>
    );
  }

  return (
    <WorkspaceFrame {...frameProps} className={layoutClassName}>
      <Outlet context={{ ...dashboard, refreshDashboard, refreshUnreadCount }} />
    </WorkspaceFrame>
  );
}
