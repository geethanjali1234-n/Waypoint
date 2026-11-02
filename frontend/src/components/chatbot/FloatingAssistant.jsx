import './FloatingAssistant.css';
import WaviMascot from './WaviMascot';

export default function FloatingAssistant({ onClick, label = 'Open Waypoint Assistant', title = 'Ask Waypoint AI', controls, expanded = false, hasUnread = false, hidden = false, animation, onWake, className = '' }) {
  return <button hidden={hidden} type="button" className={`wp-floating-assistant ${className}`} onClick={onClick} aria-label={expanded ? 'Close Waypoint Assistant' : label} title={title} aria-controls={controls} aria-expanded={expanded} onFocus={onWake}>
    <WaviMascot variant="compact" active={!hidden} animation={animation} onWake={onWake} />
    <span className="wp-floating-assistant__tooltip" aria-hidden="true">Ask Wavy</span>
    {hasUnread && <span className="wp-floating-assistant__notification"><span className="wp-assistant-sr-only">New chatbot messages</span></span>}
  </button>;
}
