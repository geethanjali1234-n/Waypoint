import './Header.css';

/**
 * Header - top navigation bar shared across pages. Accepts pre-built slot
 * content so it can host role-specific navigation (e.g. `NavigationMenu`,
 * `NotificationMenu`, `ProfileMenu`) without depending on those components.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} [props.logo] - Brand/logo slot, rendered on the left.
 * @param {import('react').ReactNode} [props.navigation] - Primary navigation slot, rendered centered.
 * @param {import('react').ReactNode} [props.actions] - Actions slot (notifications, profile menu), rendered on the right.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Header
 *   logo={<Logo />}
 *   navigation={<NavigationMenu items={items} />}
 *   actions={<><NotificationMenu notifications={n} /><ProfileMenu user={user} items={menu} /></>}
 * />
 */
export default function Header({ logo, navigation, actions, className = '' }) {
  const classes = ['wp-header', className].filter(Boolean).join(' ');

  return (
    <header className={classes}>
      {logo && <div className="wp-header__logo">{logo}</div>}
      {navigation && <div className="wp-header__nav">{navigation}</div>}
      {actions && <div className="wp-header__actions">{actions}</div>}
    </header>
  );
}
