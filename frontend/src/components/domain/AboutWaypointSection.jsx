import FeatureCard from './FeatureCard';
import './AboutWaypointSection.css';

const FEATURES = [
  {
    title: 'Local-first matching',
    description: 'Ranked by real commute distance.',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M12 21s7-6.6 7-12a7 7 0 1 0-14 0c0 5.4 7 12 7 12z" />
        <circle cx="12" cy="9" r="2.5" />
      </svg>
    ),
  },
  {
    title: 'Verified employers',
    description: 'Every company is reviewed.',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M12 22s8-4.5 8-11V5l-8-3-8 3v6c0 6.5 8 11 8 11z" />
        <path d="M9 12l2 2 4-4" />
      </svg>
    ),
  },
  {
    title: 'In-person events',
    description: 'Career fairs, not online listings.',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <rect x="3" y="5" width="18" height="16" rx="2" />
        <path d="M3 10h18M8 3v4M16 3v4" />
      </svg>
    ),
  },
  {
    title: 'Skill-based ranking',
    description: 'Matches improve as you apply.',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M4 20V10M12 20V4M20 20v-7" />
      </svg>
    ),
  },
];

/**
 * AboutWaypointSection - static "About Waypoint" section: an introductory
 * headline and copy alongside a 2x2 grid of platform feature highlights.
 * Fully static, no props, no backend data.
 *
 * @param {Object} props
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <AboutWaypointSection />
 */
export default function AboutWaypointSection({ className = '' }) {
  const classes = ['wp-about-waypoint-section', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <p className="wp-about-waypoint-section__eyebrow">About Waypoint</p>
      <div className="wp-about-waypoint-section__grid">
        <div className="wp-about-waypoint-section__copy">
          <h2 className="wp-about-waypoint-section__headline">
            Built to shorten the distance between good people and good jobs.
          </h2>
          <p className="wp-about-waypoint-section__paragraph">
            Waypoint started with a simple observation: most job boards optimize for volume, not
            fit. We built a platform that ranks opportunities by real skill match and real
            distance, and pairs it with local hiring events.
          </p>
          <p className="wp-about-waypoint-section__paragraph">
            Employers post once and reach candidates who are actually qualified and nearby. Job
            seekers see fewer, better matches instead of an endless scroll.
          </p>
        </div>
        <div className="wp-about-waypoint-section__features">
          {FEATURES.map((feature) => (
            <FeatureCard
              key={feature.title}
              icon={feature.icon}
              title={feature.title}
              description={feature.description}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
