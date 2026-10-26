import { useEffect, useState } from 'react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { PageHeader, Card, Loader, EmptyState, ValidationMessage, Button } from '../components';
import employerNotificationApi from '../api/employerNotificationApi';
import './EmployerNotificationsPage.css';

function formatTimestamp(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export default function EmployerNotificationsPage() {
  const navigate = useNavigate();
  const outletContext = useOutletContext();
  const refreshUnreadCount = outletContext?.refreshUnreadCount;

  const [notifications, setNotifications] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let isMounted = true;

    employerNotificationApi
      .listNotifications()
      .then((response) => {
        if (isMounted) setNotifications(response.data.data || []);
      })
      .catch((fetchError) => {
        if (isMounted) {
          setError(fetchError?.response?.data?.message || 'Unable to load your notifications. Please try again.');
        }
      })
      .finally(() => {
        if (isMounted) setIsLoading(false);
      });

    return () => {
      isMounted = false;
    };
  }, []);

  const handleNotificationClick = (notification) => {
    if (!notification.read) {
      setNotifications((previous) =>
        previous.map((item) => (item.id === notification.id ? { ...item, read: true } : item))
      );

      employerNotificationApi
        .markAsRead(notification.id)
        .then(() => {
          if (refreshUnreadCount) refreshUnreadCount();
        })
        .catch(() => {
          setNotifications((previous) =>
            previous.map((item) => (item.id === notification.id ? { ...item, read: false } : item))
          );
        });
    }

    if (notification.actionUrl) {
      navigate(notification.actionUrl);
    }
  };

  const handleMarkAllRead = () => {
    const previousNotifications = notifications;
    setNotifications((previous) => previous.map((item) => ({ ...item, read: true })));

    employerNotificationApi
      .markAllAsRead()
      .then(() => {
        if (refreshUnreadCount) refreshUnreadCount();
      })
      .catch(() => {
        setNotifications(previousNotifications);
      });
  };

  const hasUnread = notifications.some((notification) => !notification.read);

  return (
    <div>
      <PageHeader
        title="Notifications"
        subtitle="New applications and updates on your job postings."
        action={
          hasUnread ? (
            <Button variant="secondary" onClick={handleMarkAllRead}>
              Mark all read
            </Button>
          ) : undefined
        }
      />

      {isLoading && <Loader label="Loading your notifications…" />}

      {!isLoading && error && <ValidationMessage message={error} variant="error" />}

      {!isLoading && !error && notifications.length === 0 && (
        <EmptyState
          title="No notifications yet"
          description="New applications and updates on your job postings will show up here."
        />
      )}

      {!isLoading && !error && notifications.length > 0 && (
        <div className="wp-employer-notifications__list">
          {notifications.map((notification) => (
            <button
              type="button"
              key={notification.id}
              className="wp-employer-notifications__item-button"
              onClick={() => handleNotificationClick(notification)}
            >
              <Card className={notification.read ? '' : 'wp-employer-notifications__card--unread'}>
                <p className="wp-employer-notifications__title">{notification.title}</p>
                <p className="wp-employer-notifications__message">{notification.message}</p>
                {formatTimestamp(notification.createdAt) && (
                  <p className="wp-employer-notifications__timestamp">
                    {formatTimestamp(notification.createdAt)}
                  </p>
                )}
              </Card>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
