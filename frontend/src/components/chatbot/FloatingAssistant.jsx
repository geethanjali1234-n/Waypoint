import StrokeIcon from '../global/StrokeIcon';
import './FloatingAssistant.css';

export default function FloatingAssistant({ onClick, label = 'Open Waypoint Assistant', title = 'Waypoint Assistant', controls, expanded = false, className = '' }) {
  return <button type="button" className={`wp-floating-assistant ${className}`} onClick={onClick} aria-label={label} title={title} aria-controls={controls} aria-expanded={expanded}><StrokeIcon name="help" /></button>;
}
