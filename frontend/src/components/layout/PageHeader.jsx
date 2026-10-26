import './PageHeader.css';

/**
 * PageHeader - displays a page title, optional subtitle, and an optional
 * trailing action (e.g. a "New posting" button).
 *
 * @param {Object} props
 * @param {string} props.title - Page title.
 * @param {string} [props.subtitle] - Supporting subtitle text.
 * @param {import('react').ReactNode} [props.action] - Optional action rendered on the right.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <PageHeader title="Job postings" subtitle="Manage only jobs owned by this employer account." action={<Button>New posting</Button>} />
 */
export default function PageHeader({ eyebrow, title, summary, subtitle, primaryAction, action, className = '' }) {
  const classes = ['wp-page-header', className].filter(Boolean).join(' ');

  return (
    <header className={classes}>
      <div className="wp-page-header__text">
        {eyebrow && <p className="wp-page-header__eyebrow">{eyebrow}</p>}
        <h1 className="wp-page-header__title">{title}</h1>
        {(summary || subtitle) && <p className="wp-page-header__subtitle">{summary || subtitle}</p>}
      </div>
      {(primaryAction || action) && <div className="wp-page-header__action">{primaryAction || action}</div>}
    </header>
  );
}
