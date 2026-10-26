import { useEffect, useId, useRef, useState } from 'react';
import { Avatar } from '../global';
import './ProfileMenu.css';

/**
 * ProfileMenu - header dropdown exposing the current user's account actions
 * (e.g. change password, support, log out).
 *
 * @param {Object} props
 * @param {{name: string, email?: string, subtitle?: string, initials?: string, imageUrl?: string}} props.user - Current user summary.
 * @param {{label: string, onClick?: () => void}[]} props.items - Menu action items.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <ProfileMenu
 *   user={{ name: 'Meridian Labs', subtitle: 'Employer', initials: 'ML' }}
 *   items={[{ label: 'Change password', onClick: openChangePassword }, { label: 'Log out', onClick: logOut }]}
 * />
 */
export default function ProfileMenu({ user, items, className = '' }) {
  const panelId = useId();
  const [isOpen, setIsOpen] = useState(false);
  const menuRef = useRef(null);
  const classes = ['wp-profile-menu', className].filter(Boolean).join(' ');

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

  return (
    <div className={classes} ref={menuRef}>
      <button
        type="button"
        className="wp-profile-menu__trigger"
        onClick={() => setIsOpen((open) => !open)}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-controls={isOpen ? panelId : undefined}
      >
        <Avatar imageUrl={user.imageUrl} initials={user.initials} size="sm" alt={user.name} />
        <span className="wp-profile-menu__identity"><span className="wp-profile-menu__name">{user.name}</span>{user.showSubtitle !== false && user.subtitle && <small className="wp-profile-menu__secondary">{user.subtitle}</small>}</span>
      </button>
      {isOpen && (
        <div id={panelId} className="wp-profile-menu__panel" role="menu">
          <div className="wp-profile-menu__header">
            <p className="wp-profile-menu__title">{user.name}</p>
            {(user.email || user.subtitle) && (
              <p className="wp-profile-menu__subtitle">{user.email || user.subtitle}</p>
            )}
          </div>
          <ul className="wp-profile-menu__list">
            {items.map((item) => (
              <li key={item.label}>
                <button
                  type="button"
                  className="wp-profile-menu__item"
                  role="menuitem"
                  onClick={() => {
                    setIsOpen(false);
                    item.onClick && item.onClick();
                  }}
                >
                  {item.label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
