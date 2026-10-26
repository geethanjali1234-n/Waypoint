import './Card.css';

/**
 * Card - bordered container wrapping related content, with an optional
 * uppercase header title and trailing actions.
 *
 * @param {Object} props
 * @param {string} [props.title] - Optional card header title (rendered uppercase, e.g. "ABOUT THE ROLE").
 * @param {import('react').ReactNode} [props.actions] - Optional actions rendered next to the title (e.g. an "Edit" link).
 * @param {import('react').ReactNode} props.children - Card content.
 * @param {string} [props.className=''] - Additional class names.
 * @param {string} [props.id] - Optional id used for in-page navigation.
 *
 * @example
 * <Card title="About the role">
 *   <p>Meridian Labs is looking for a senior frontend engineer…</p>
 * </Card>
 */
export default function Card({ title, actions, children, className = '', id }) {
  const classes = ['wp-card', className].filter(Boolean).join(' ');

  return (
    <div className={classes} id={id}>
      {(title || actions) && (
        <div className="wp-card__header">
          {title && <h3 className="wp-card__title">{title}</h3>}
          {actions && <div className="wp-card__actions">{actions}</div>}
        </div>
      )}
      <div className="wp-card__body">{children}</div>
    </div>
  );
}
