import './Breadcrumb.css';

/**
 * Breadcrumb - shows navigation hierarchy above page content.
 *
 * @param {Object} props
 * @param {{label: string, href?: string}[]} props.items - Ordered breadcrumb trail; the last item is treated as the current page.
 * @param {string} [props.separator='/'] - Separator rendered between items.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Breadcrumb items={[{ label: 'Careers', href: '/careers' }, { label: 'Senior frontend engineer' }]} />
 */
export default function Breadcrumb({ items, separator = '/', className = '' }) {
  const classes = ['wp-breadcrumb', className].filter(Boolean).join(' ');

  return (
    <nav className={classes} aria-label="Breadcrumb">
      <ol className="wp-breadcrumb__list">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          return (
            <li key={item.label} className="wp-breadcrumb__item">
              {item.href && !isLast ? (
                <a href={item.href} className="wp-breadcrumb__link">
                  {item.label}
                </a>
              ) : (
                <span className="wp-breadcrumb__current" aria-current={isLast ? 'page' : undefined}>
                  {item.label}
                </span>
              )}
              {!isLast && <span className="wp-breadcrumb__separator">{separator}</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
