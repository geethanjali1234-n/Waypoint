import './NavigationMenu.css';

/**
 * NavigationMenu - primary horizontal menu of page links, shown in the Header.
 *
 * @param {Object} props
 * @param {{label: string, href: string, active?: boolean, disabled?: boolean}[]} props.items - Navigation links.
 * @param {(item: Object) => void} [props.onItemClick] - Called with the clicked item (not called when `item.disabled` is true).
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <NavigationMenu items={[{ label: 'Home', href: '/', active: true }, { label: 'Careers', href: '/careers', disabled: true }]} />
 */
export default function NavigationMenu({ items, onItemClick, className = '' }) {
  const classes = ['wp-nav-menu', className].filter(Boolean).join(' ');

  return (
    <nav className={classes}>
      <ul className="wp-nav-menu__list">
        {items.map((item) => (
          <li key={item.label}>
            <a
              href={item.href}
              className={[
                'wp-nav-menu__link',
                item.active ? 'wp-nav-menu__link--active' : '',
                item.disabled ? 'wp-nav-menu__link--disabled' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              aria-disabled={item.disabled || undefined}
              onClick={(event) => {
                event.preventDefault();
                if (item.disabled) {
                  return;
                }
                if (onItemClick) {
                  onItemClick(item);
                }
              }}
            >
              {item.label}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
