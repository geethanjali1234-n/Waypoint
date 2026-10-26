import { personDisplayName } from './myHrDisplayName';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MobileBottomNav, NotificationMenu, ProfileMenu } from '../../components';
import WorkspaceFrame from '../../components/layout/WorkspaceFrame';
import StrokeIcon from '../../components/global/StrokeIcon';
import { clearMyHrAuthentication, getMyHrSession } from '../../auth/myHrSession';
import myHrNotificationApi from '../../api/myHrNotificationApi';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';
import { formatDateOnly } from '../../utils/dateFormat';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import './MyHrShell.css';
import MyHrNavigation from './components/MyHrNavigation';

function formatNotificationTimestamp(value) {
  if (!value) return 'Just now';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Recently';
  const now = new Date();
  const diffMs = now - date;
  const diffMinutes = Math.floor(diffMs / 60000);
  if (diffMinutes < 1) return 'Just now';
  if (diffMinutes < 60) return `${diffMinutes}m ago`;
  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  return formatDateOnly(date);
}

function initials(value) {
  return (value || 'My HR').split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
}

// Notification actionUrls are stored with a persona segment that may not match
// the recipient (e.g. an HR user who filed their own timesheet gets an
// "/myhr/employee/..." link). Rewrite it so the role guard doesn't bounce them
// back to the dashboard.
function resolveNotificationUrl(actionUrl, role) {
  if (!actionUrl || !actionUrl.startsWith('/myhr/')) return actionUrl || '/';
  const persona = role === 'COMPANY_ADMIN' ? 'admin' : role === 'HR' ? 'hr' : 'employee';
  const fixed = actionUrl.replace(/^\/myhr\/(employee|hr|admin)\//, `/myhr/${persona}/`);
  if (persona === 'admin' && fixed.includes('/timesheets')) {
    const [path, qs = ''] = fixed.split('?');
    const params = new URLSearchParams(qs);
    params.set('view', 'team');
    return `${path}?${params.toString()}`;
  }
  if (persona === 'hr' && fixed.includes('/timesheets')) {
    const [path, qs = ''] = fixed.split('?');
    const params = new URLSearchParams(qs);
    if (!params.get('view')) {
      params.set('view', params.get('mode') === 'history' ? 'mine' : 'team');
    }
    return `${path}?${params.toString()}`;
  }
  return fixed;
}

function notificationErrorMessage(error) {
  return error?.response?.data?.message || 'Notifications could not be loaded. Try again.';
}

function notificationItems(response) {
  const payload = response?.data?.data;
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.items)) return payload.items;
  return [];
}

// COMPANY_ADMIN -> /myhr/admin/profile, HR -> /myhr/hr/profile, EMPLOYEE -> /myhr/employee/profile.
function myProfilePath(role) {
  if (role === 'COMPANY_ADMIN') return '/myhr/admin/profile';
  if (role === 'HR') return '/myhr/hr/profile';
  return '/myhr/employee/profile';
}

export default function MyHrShell({ children, fullWidth = false }) {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(null);
  const [notificationLoading, setNotificationLoading] = useState(false);
  const [notificationError, setNotificationError] = useState('');
  const [personName, setPersonName] = useState(() => personDisplayName(null, session));
  const notificationRequestRef = useRef(0);
  const refreshNotifications = useCallback(async () => {
    if (!session?.userId || !localStorage.getItem('accessToken')) return;

    const requestId = notificationRequestRef.current + 1;
    notificationRequestRef.current = requestId;
    setNotificationLoading(true);
    setNotificationError('');

    const [listResult, countResult] = await Promise.allSettled([
      myHrNotificationApi.listNotifications(30),
      myHrNotificationApi.getUnreadCount(),
    ]);

    if (requestId !== notificationRequestRef.current) return;

    if (listResult.status === 'fulfilled') {
      setNotifications(notificationItems(listResult.value).map((item) => ({
        ...item,
        title: item.title,
        subtitle: item.message || item.subtitle,
        timestamp: formatNotificationTimestamp(item.createdAt),
        read: item.read ?? false,
      })));
    } else {
      setNotificationError(notificationErrorMessage(listResult.reason));
    }

    if (countResult.status === 'fulfilled') {
      const count = Number(countResult.value?.data?.data?.count);
      setUnreadCount(Number.isFinite(count) ? count : null);
    }

    setNotificationLoading(false);
  }, [session?.userId]);

  useEffect(() => {
    refreshNotifications();
  }, [refreshNotifications]);

  useEffect(() => {
    if (!['COMPANY_ADMIN', 'HR', 'EMPLOYEE'].includes(session?.role)) return undefined;
    let active = true;
    Promise.resolve().then(() => myHrEmployeeApi.getMine())
      .then((response) => {
        if (!active) return;
        setPersonName(personDisplayName(response.data?.data, { name: session?.name, loginId: session?.loginId }));
      })
      .catch(() => { /* Retain the session identity when profile data is unavailable. */ });
    return () => { active = false; };
  }, [session?.role, session?.userId, session?.loginId, session?.name]);

  const handleMarkAllRead = async () => {
    setNotificationLoading(true);
    setNotificationError('');
    try {
      await myHrNotificationApi.markAllAsRead();
      await refreshNotifications();
    } catch (error) {
      setNotificationLoading(false);
      setNotificationError(notificationErrorMessage(error));
    }
  };

  const handleNotificationClick = async (notification) => {
    if (!notification.read) {
      try {
        await myHrNotificationApi.markAsRead(notification.id);
        await refreshNotifications();
      } catch (error) {
        setNotificationError(notificationErrorMessage(error));
      }
    }
    if (notification.actionUrl) {
      navigate(resolveNotificationUrl(notification.actionUrl, session?.role));
    }
  };

  const signOut = () => {
    clearMyHrAuthentication();
    navigate('/myhr/login', { replace: true });
  };

  const workspaceLabel =
    session?.role === 'COMPANY_ADMIN'
      ? 'Admin workspace'
      : session?.role === 'HR'
        ? 'HR workspace'
        : session?.role === 'EMPLOYEE'
          ? 'Employee workspace'
          : 'My HR workspace';

  const home = '/myhr/' + (session?.role === 'COMPANY_ADMIN' ? 'admin' : session?.role?.toLowerCase() || 'login') + '/dashboard';
  return <WorkspaceFrame home={home} workspaceLabel={workspaceLabel} className="myhr-shell"
    mainClassName={fullWidth ? 'myhr-shell__main myhr-shell__main--full' : 'myhr-shell__main'}
    sidebar={<MyHrNavigation role={session?.role} />}
    mobileNav={<MobileBottomNav />}
    assistant={<WaypointChatbot displayName={personName} />}
    account={<>
      <NotificationMenu icon={<StrokeIcon name="bell" />} unreadCount={unreadCount}
        notifications={notifications} isLoading={notificationLoading} error={notificationError}
        onOpen={refreshNotifications} onRetry={refreshNotifications} onMarkAllRead={handleMarkAllRead}
        onNotificationClick={handleNotificationClick} />
      <ProfileMenu user={{ name: personName, subtitle: session?.loginId, showSubtitle: true, initials: initials(personName) }}
        items={[
          { label: 'Profile', onClick: () => navigate(myProfilePath(session?.role)) },
          { label: 'Change password', onClick: () => navigate('/myhr/change-password') },
          { label: 'Sign out', onClick: signOut },
        ]} />
    </>}>{children}</WorkspaceFrame>;
}
