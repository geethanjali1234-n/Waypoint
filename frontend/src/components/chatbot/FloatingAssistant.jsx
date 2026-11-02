import './FloatingAssistant.css';
import WaviFace from './WaviFace';

export default function FloatingAssistant({ onClick, label = 'Open Waypoint Assistant', title = 'Ask Waypoint AI', controls, expanded = false, hasUnread = false, hidden = false, className = '' }) {
  return <button hidden={hidden} type="button" className={`wp-floating-assistant ${className}`} onClick={onClick} aria-label={expanded ? 'Close Waypoint Assistant' : label} title={title} aria-controls={controls} aria-expanded={expanded}>
    <span className="wp-floating-assistant__orbit" aria-hidden="true" />
    <span className="wp-floating-assistant__spark" aria-hidden="true">✦</span>
    <WaviFace active={!hidden} />
    <span className="wp-floating-assistant__greeting" aria-hidden="true">
      <span className="wp-floating-assistant__eyebrow">MEET WAVY</span>
      A little help for your workday?
      <span className="wp-floating-assistant__hint">Ask me anything <span>↗</span></span>
    </span>
    <span className="wp-floating-assistant__tooltip" aria-hidden="true">Ask Wavy</span>
    {hasUnread && <span className="wp-floating-assistant__notification"><span className="wp-assistant-sr-only">New chatbot messages</span></span>}
  </button>;
}
