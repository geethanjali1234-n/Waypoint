import ProcessStep from './ProcessStep';
import './HowWaypointWorksSection.css';

const STEPS = [
  {
    title: 'Tell us your skills',
    description: 'Add skills and resume once, no separate application for every role.',
  },
  {
    title: 'Get ranked matches',
    description: 'We surface roles and events by fit and by distance from your zip code.',
  },
  {
    title: 'Apply or show up',
    description: 'Apply in a click, or register for a nearby event to meet employers directly.',
  },
];

/**
 * HowWaypointWorksSection - static "How Waypoint works" section: a
 * three-step explainer. Fully static, no props, no backend data.
 *
 * @param {Object} props
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <HowWaypointWorksSection />
 */
export default function HowWaypointWorksSection({ className = '' }) {
  const classes = ['wp-how-waypoint-works-section', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <p className="wp-how-waypoint-works-section__eyebrow">How Waypoint works</p>
      <div className="wp-how-waypoint-works-section__grid">
        {STEPS.map((step, index) => (
          <ProcessStep
            key={step.title}
            stepNumber={index + 1}
            title={step.title}
            description={step.description}
          />
        ))}
      </div>
    </div>
  );
}
