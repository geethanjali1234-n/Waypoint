import './ActivityCard.css';

/**
 * ActivityCard - single row within a "recent activity" feed
 * (e.g. "Applied to Senior frontend engineer · Jul 8").
 *
 * @param {Object} props
 * @param {import('react').ReactNode} [props.icon] - Optional leading icon.
 * @param {string} props.title - Activity title.
 * @param {string} [props.subtitle] - Supporting subtitle text.
 * @param {string} [props.date] - Date/timestamp shown on the trailing edge.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <ActivityCard title="Applied to Senior frontend engineer" subtitle="Meridian Labs" date="Jul 8" />
 */
export default function ActivityCard({ icon, title, subtitle, date, className = '' }) {
  const classes = ['wp-activity-card', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {icon && <div className="wp-activity-card__icon">{icon}</div>}
      <div className="wp-activity-card__text">
        <p className="wp-activity-card__title">{title}</p>
        {subtitle && <p className="wp-activity-card__subtitle">{subtitle}</p>}
      </div>
      {date && <span className="wp-activity-card__date">{date}</span>}
    </div>
  );
}
