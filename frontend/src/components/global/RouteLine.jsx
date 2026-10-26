import StrokeIcon from './StrokeIcon';
import './RouteLine.css';

const states = { done: 'Completed', current: 'You are here', upcoming: 'Upcoming' };

export default function RouteLine({ steps = [], orientation = 'horizontal', label = 'Progress', className = '' }) {
  return <ol className={`wp-route-line wp-route-line--${orientation} ${className}`} aria-label={label}>
    {steps.map((step, index) => <li key={step.id || `${step.label}-${index}`} className={`wp-route-line__stop wp-route-line__stop--${step.state}`} aria-current={step.state === 'current' ? 'step' : undefined}>
      <span className="wp-route-line__marker" aria-hidden="true">{step.state === 'done' && <StrokeIcon name="check" size={20} />}</span>
      <div className="wp-route-line__copy"><strong>{step.label}</strong><span className="wp-route-line__state">{states[step.state]}</span>{step.sublabel && <small>{step.sublabel}</small>}</div>
    </li>)}
  </ol>;
}
