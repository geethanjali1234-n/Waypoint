import { useEffect, useId, useRef, useState } from 'react';
import './NotificationMenu.css';

/**
 * NotificationMenu - header dropdown listing recent notifications.
 *
 * @param {Object} props
 * @param {{id: string, title: string, subtitle?: string, timestamp?: string, read?: boolean}[]} props.notifications - Notification items.
 * @param {import('react').ReactNode} [props.icon] - Trigger icon (e.g. a bell icon).
 * @param {() => void} [props.onMarkAllRead] - Called when "Mark all read" is clicked.
 * @param {(notification: Object) => void} [props.onNotificationClick] - Called with the clicked notification (rendered as a button when supplied).
 * @param {() => void} [props.onOpen] - Called whenever the dropdown is opened.
 * @param {boolean} [props.isLoading=false] - Whether notifications are currently loading.
 * @param {string} [props.error] - Error message to show when notifications could not be loaded.
 * @param {() => void} [props.onRetry] - Called when the user asks to retry a failed load.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <NotificationMenu
 *   icon={<BellIcon />}
 *   notifications={[{ id: '1', title: 'New application received', subtitle: 'Priya Shah applied for Frontend Engineer', timestamp: 'Today' }]}
 *   onMarkAllRead={markAllRead}
 * />
 */
export default function NotificationMenu({
  notifications = [],
  icon,
  onMarkAllRead,
  onNotificationClick,
  onOpen,
  isLoading = false,
  error = '',
  onRetry,
  className = '',
  unreadCount,
}) {
  const countId = useId();
  const panelId = useId();
  const [isOpen, setIsOpen] = useState(false);
  const menuRef = useRef(null);
  const classes = ['wp-notification-menu', className].filter(Boolean).join(' ');
  const safeNotifications = Array.isArray(notifications) ? notifications : [];

  useEffect(() => {
    if (!isOpen) return undefined;

    const closeOnOutsideClick = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setIsOpen(false);
      }
    };

    const closeOnEscape = (event) => {
      if (event.key === 'Escape') {
        setIsOpen(false);
        menuRef.current?.querySelector('button')?.focus();
      }
    };

    document.addEventListener('pointerdown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [isOpen]);

  const handleTriggerClick = () => {
    const nextIsOpen = !isOpen;
    setIsOpen(nextIsOpen);
    if (nextIsOpen && onOpen) onOpen();
  };

  return (
    <div className={classes} ref={menuRef}>
      <button
        type="button"
        className="wp-notification-menu__trigger"
        onClick={handleTriggerClick}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-controls={isOpen ? panelId : undefined}
        aria-label="Notifications"
        aria-describedby={Number.isFinite(unreadCount) ? countId : undefined}
      >
        {icon}
        {Number.isFinite(unreadCount) && <span id={countId} className="wp-notification-menu__sr" role="status">{unreadCount} unread notifications</span>}
        {unreadCount > 0 && <span className="wp-notification-menu__badge" aria-hidden="true">{unreadCount > 99 ? '99+' : unreadCount}</span>}
      </button>
      {isOpen && (
        <div id={panelId} className="wp-notification-menu__panel" role="menu">
          <div className="wp-notification-menu__header">
            <span className="wp-notification-menu__title">Notifications</span>
            {onMarkAllRead && (
              <button type="button" className="wp-notification-menu__mark-read" onClick={onMarkAllRead}>
                Mark all read
              </button>
            )}
          </div>
          <ul className="wp-notification-menu__list" aria-live="polite">
            {isLoading && (
              <li className="wp-notification-menu__status">Loading notifications&hellip;</li>
            )}
            {!isLoading && error && (
              <li className="wp-notification-menu__status wp-notification-menu__status--error" role="alert">
                <span>{error}</span>
                {onRetry && (
                  <button type="button" className="wp-notification-menu__retry" onClick={onRetry}>
                    Try again
                  </button>
                )}
              </li>
            )}
            {!isLoading && !error && safeNotifications.length === 0 && (
              <li className="wp-notification-menu__empty">You&apos;re all caught up. No notifications yet.</li>
            )}
            {!isLoading && !error && safeNotifications.map((notification) => {
              const content = (
                <>
                  {!notification.read && <span className="wp-notification-menu__sr">Unread notification</span>}
                  <p className="wp-notification-menu__item-title">{notification.title}</p>
                  {notification.subtitle && (
                    <p className="wp-notification-menu__item-subtitle">{notification.subtitle}</p>
                  )}
                  {notification.timestamp && (
                    <p className="wp-notification-menu__item-timestamp">{notification.timestamp}</p>
                  )}
                </>
              );

              return (
                <li
                  key={notification.id}
                  className={`wp-notification-menu__item ${
                    notification.read ? '' : 'wp-notification-menu__item--unread'
                  }`}
                >
                  {onNotificationClick ? (
                    <button
                      type="button"
                      className="wp-notification-menu__item-button"
                      onClick={() => {
                        onNotificationClick(notification);
                        setIsOpen(false);
                      }}
                    >
                      {content}
                    </button>
                  ) : (
                    content
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
