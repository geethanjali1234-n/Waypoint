import './HeroSection.css';

/**
 * HeroSection - dark hero banner shown at the top of the job seeker dashboard
 * landing page. Only the greeting name is dynamic; the headline and
 * description are static marketing copy.
 *
 * @param {Object} props
 * @param {string} [props.firstName] - Job Seeker's first name shown in the greeting.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <HeroSection firstName="Jordan" />
 */
export default function HeroSection({ firstName, className = '' }) {
  const classes = ['wp-hero-section', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <div className="wp-hero-section__inner">
        <p className="wp-hero-section__eyebrow">Welcome back, {firstName}</p>
        <h1 className="wp-hero-section__headline">
          The jobs and events actually worth your commute.
        </h1>
        <p className="wp-hero-section__description">
          Waypoint matches you to roles and career events based on your skills, not just your
          keywords. New matches land here every week.
        </p>
      </div>
    </div>
  );
}
