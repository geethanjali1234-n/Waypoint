import './EmptyState.css';

/**
 * EmptyState - placeholder shown when a list or section has no records.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} [props.icon] - Optional icon or illustration.
 * @param {string} props.title - Primary message.
 * @param {string} [props.description] - Secondary, supporting text.
 * @param {import('react').ReactNode} [props.action] - Optional action (e.g. a Button) shown below the text.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <EmptyState title="No applications yet" description="Jobs you apply to will show up here." />
 */
export default function EmptyState({ icon, title, description, action, className = '' }) {
  const classes = ['wp-empty-state', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {icon && <div className="wp-empty-state__icon">{icon}</div>}
      <p className="wp-empty-state__title">{title}</p>
      {description && <p className="wp-empty-state__description">{description}</p>}
      {action && <div className="wp-empty-state__action">{action}</div>}
    </div>
  );
}
