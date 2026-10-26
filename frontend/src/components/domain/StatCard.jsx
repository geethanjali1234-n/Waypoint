import './StatCard.css';

/**
 * StatCard - small metric tile showing a value and its label
 * (e.g. "4 Jobs applied", "92% Top match accuracy").
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.value - The metric value.
 * @param {string} props.label - Label describing the metric.
 * @param {import('react').ReactNode} [props.icon] - Optional icon shown above/beside the value.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <StatCard value={4} label="Jobs applied" />
 */
export default function StatCard({ value, label, icon, className = '' }) {
  const classes = ['wp-stat-card', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {icon && <div className="wp-stat-card__icon">{icon}</div>}
      <span className="wp-stat-card__value">{value}</span>
      <span className="wp-stat-card__label">{label}</span>
    </div>
  );
}
