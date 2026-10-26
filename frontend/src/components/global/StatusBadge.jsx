import { formatDisplayLabel } from '../../utils/myHrDisplayLabel';
import './StatusBadge.css';

const supported = new Set(['active', 'pending', 'approved', 'rejected', 'inactive', 'draft']);
export default function StatusBadge({ status, label, className = '' }) {
  const key = String(status || '').toLowerCase();
  return <span className={`wp-status-badge wp-status-badge--${supported.has(key) ? key : 'neutral'} ${className}`}>
    <span className="wp-status-badge__dot" aria-hidden="true" />{label || formatDisplayLabel(status) || 'Status unavailable'}
  </span>;
}
