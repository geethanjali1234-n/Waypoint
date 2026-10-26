import './FeatureCard.css';

/**
 * FeatureCard - icon-grid card used to highlight a platform feature
 * (e.g. the "About Waypoint" grid: Local-first matching, Verified employers).
 *
 * @param {Object} props
 * @param {import('react').ReactNode} [props.icon] - Feature icon.
 * @param {string} props.title - Feature title.
 * @param {string} [props.description] - Feature description.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <FeatureCard icon={<PinIcon />} title="Local-first matching" description="Ranked by real commute distance, not just city name." />
 */
export default function FeatureCard({ icon, title, description, className = '' }) {
  const classes = ['wp-feature-card', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {icon && <div className="wp-feature-card__icon">{icon}</div>}
      <p className="wp-feature-card__title">{title}</p>
      {description && <p className="wp-feature-card__description">{description}</p>}
    </div>
  );
}
