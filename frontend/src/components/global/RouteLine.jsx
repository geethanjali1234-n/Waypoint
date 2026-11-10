import StrokeIcon from './StrokeIcon';
import './RouteLine.css';

/**
 * Shared progress tracker. Every workflow passes its own steps; the look of each state is the same everywhere:
 * done = green check, current = yellow outline, rejected = red X, cancelled = gray X,
 * overdue = orange warning, upcoming = gray outline.
 * A step may set `stateLabel` to replace the default state text (e.g. "Not logged").
 */
const states = {
  done: 'Completed',
  current: 'You are here',
  upcoming: 'Upcoming',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
  overdue: 'Overdue',
};
const icons = { done: 'check', rejected: 'close', cancelled: 'close', overdue: 'alert' };

export default function RouteLine({ steps = [], orientation = 'horizontal', label = 'Progress', className = '' }) {
  return <ol className={`wp-route-line wp-route-line--${orientation} ${className}`} aria-label={label}>
    {steps.map((step, index) => <li key={step.id || `${step.label}-${index}`} className={`wp-route-line__stop wp-route-line__stop--${step.state}`} aria-current={step.state === 'current' || step.state === 'overdue' ? 'step' : undefined}>
      <span className="wp-route-line__marker" aria-hidden="true">{icons[step.state] && <StrokeIcon name={icons[step.state]} size={step.state === 'done' ? 20 : 18} />}</span>
      <div className="wp-route-line__copy"><strong>{step.label}</strong><span className="wp-route-line__state">{step.stateLabel || states[step.state]}</span>{step.sublabel && <small>{step.sublabel}</small>}</div>
    </li>)}
  </ol>;
}
