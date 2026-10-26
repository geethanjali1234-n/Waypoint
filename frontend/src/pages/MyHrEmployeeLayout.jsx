import { useMemo, useState } from 'react';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { NotificationMenu, ProfileMenu } from '../components';
import WaypointMark from '../components/branding/WaypointMark';
import { clearMyHrSession } from './myhr/mockDirectory';
import './MyHrEmployeeLayout.css';

const INITIAL_NOTIFICATIONS = [
  {
    id: 'timesheet-submission',
    title: 'Timesheet submission',
    subtitle: 'Your submitted hours are available to your Reporting Manager.',
    timestamp: 'Today',
    read: false,
  },
  {
    id: 'paystub-generation',
    title: 'Paystub generation',
    subtitle: 'Your paystub is available to view and download as a PDF.',
    timestamp: 'Yesterday',
    read: false,
  },
  {
    id: 'profile-change-request',
    title: 'Profile change request',
    subtitle: 'Your request is assigned to your Reporting Manager.',
    timestamp: 'Aug 12',
    read: true,
  },
];

const NOTIFICATION_DESTINATIONS = {
  'timesheet-submission': '/employee/timesheets',
  'timesheet-submitted': '/employee/timesheets',
  'paystub-generation': '/employee/paystubs',
  'profile-change-request': '/employee/profile',
  'profile-request-submitted': '/employee/profile',
};

function BellIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="M5.2 14.5h9.6l-1.1-1.6V9a3.7 3.7 0 0 0-7.4 0v3.9z" fill="none" strokeLinejoin="round" />
      <path d="M8.3 16.1a1.8 1.8 0 0 0 3.4 0" fill="none" strokeLinecap="round" />
    </svg>
  );
}

export default function MyHrEmployeeLayout() {
  const navigate = useNavigate();
  const [notifications, setNotifications] = useState(INITIAL_NOTIFICATIONS);

  const unreadCount = useMemo(
    () => notifications.filter((notification) => !notification.read).length,
    [notifications],
  );

  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    clearMyHrSession();
    navigate('/login?persona=my-hr');
  };

  const handleMarkAllRead = () => {
    setNotifications((current) => current.map((notification) => ({ ...notification, read: true })));
  };

  const handleMarkNotificationRead = (notificationId) => {
    setNotifications((current) => current.map((notification) => (
      notification.id === notificationId ? { ...notification, read: true } : notification
    )));
  };

  const handleAddNotification = (notification) => {
    const normalizedNotification = {
      ...notification,
      subtitle: notification.subtitle || notification.message,
      timestamp: notification.timestamp || 'Just now',
      read: notification.read ?? false,
    };

    setNotifications((current) => (
      current.some((item) => item.id === normalizedNotification.id)
        ? current
        : [normalizedNotification, ...current]
    ));
  };

  const handleNotificationClick = (notification) => {
    handleMarkNotificationRead(notification.id);
    navigate(notification.actionUrl || NOTIFICATION_DESTINATIONS[notification.id] || '/employee/dashboard');
  };

  return (
    <div className="myhr-employee-shell">
      <header className="myhr-employee-shell__header">
        <Link
          to="/employee/dashboard"
          className="myhr-employee-shell__brand"
          aria-label="Go to MY HR employee dashboard"
        >
          <WaypointMark />
          <span>
            <strong>MY HR</strong>
            <small>Employee workspace</small>
          </span>
        </Link>

        <div className="myhr-employee-shell__header-actions">
          <span className="myhr-employee-shell__mode-pill"><i aria-hidden="true" /> Employee portal</span>
          <div className="myhr-employee-shell__bell-wrapper">
            <NotificationMenu
              icon={<BellIcon />}
              notifications={notifications}
              onMarkAllRead={handleMarkAllRead}
              onNotificationClick={handleNotificationClick}
              className="myhr-employee-shell__bell"
            />
            {unreadCount > 0 && (
              <span className="myhr-employee-shell__bell-badge" aria-hidden="true">
                {unreadCount > 9 ? '9+' : unreadCount}
              </span>
            )}
          </div>
          <ProfileMenu
            user={{
              name: 'John',
              subtitle: 'Employee · MY HR',
              initials: 'J',
            }}
            items={[
              { label: '📱 Download Mobile App', onClick: () => navigate('/download') },
              { label: 'Change Password', onClick: () => navigate('/employee/change-password') },
              { label: 'Logout', onClick: handleLogout },
            ]}
            className="myhr-employee-shell__profile-menu"
          />
        </div>
      </header>

      <main className="myhr-employee-shell__content">
        <Outlet
          context={{
            notifications,
            addNotification: handleAddNotification,
          }}
        />
      </main>
    </div>
  );
}
