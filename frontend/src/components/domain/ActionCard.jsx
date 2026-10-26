import './ActionCard.css';

/** A consistent, keyboard-accessible entry point for a primary workflow. */
export default function ActionCard({
  icon,
  eyebrow,
  title,
  description,
  actionLabel = 'Open',
  onClick,
  disabled = false,
  primary = false,
  className = '',
  ariaLabel,
}) {
  const classes = ['wp-action-card', primary && 'wp-action-card--primary', className]
    .filter(Boolean).join(' ');

  return (
    <button type="button" className={classes} onClick={onClick} disabled={disabled} aria-label={ariaLabel}>
      {icon && <span className="wp-action-card__icon" aria-hidden="true">{icon}</span>}
      <span className="wp-action-card__content">
        {eyebrow && <span className="wp-action-card__eyebrow">{eyebrow}</span>}
        <strong className="wp-action-card__title">{title}</strong>
        {description && <span className="wp-action-card__description">{description}</span>}
      </span>
      <span className="wp-action-card__footer"><span>{actionLabel}</span><span aria-hidden="true">→</span></span>
    </button>
  );
}
