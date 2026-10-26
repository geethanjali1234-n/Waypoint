import './WelcomeBanner.css';

/**
 * WelcomeBanner - dashboard hero banner with a headline, subtitle, and
 * a row of key stats.
 *
 * @param {Object} props
 * @param {string} props.title - Headline text.
 * @param {string} [props.subtitle] - Supporting subtitle text.
 * @param {{value: import('react').ReactNode, label: string}[]} [props.stats=[]] - Stat callouts shown below the headline.
 * @param {import('react').ReactNode} [props.action] - Optional action rendered on the banner (e.g. "+ New posting").
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <WelcomeBanner
 *   title="The jobs and events actually worth your commute."
 *   subtitle="Waypoint matches you to roles and career events based on your skills."
 *   stats={[{ value: '1,200+', label: 'Employers on Waypoint' }]}
 * />
 */
export default function WelcomeBanner({ title, subtitle, stats = [], action, className = '' }) {
  const classes = ['wp-welcome-banner', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <div className="wp-welcome-banner__content">
        <h1 className="wp-welcome-banner__title">{title}</h1>
        {subtitle && <p className="wp-welcome-banner__subtitle">{subtitle}</p>}
        {action && <div className="wp-welcome-banner__action">{action}</div>}
      </div>
      {stats.length > 0 && (
        <div className="wp-welcome-banner__stats">
          {stats.map((stat) => (
            <div key={stat.label} className="wp-welcome-banner__stat">
              <span className="wp-welcome-banner__stat-value">{stat.value}</span>
              <span className="wp-welcome-banner__stat-label">{stat.label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
