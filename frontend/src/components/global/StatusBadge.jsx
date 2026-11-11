import { formatDisplayLabel } from '../../utils/myHrDisplayLabel';
import './StatusBadge.css';

const aliases = { completed: 'approved', fulfilled: 'approved', submitted: 'pending', awaiting_signature: 'pending', 'awaiting employee signature': 'pending', returned_for_correction: 'rejected', 'approval rejected': 'rejected' };
const supported = new Set(['active', 'pending', 'approved', 'rejected', 'inactive', 'draft', 'overdue']);
export default function StatusBadge({ status, label, className = '' }) {
  const rawKey = String(status || '').toLowerCase().replace(/-/g, '_');
  const key = aliases[rawKey] || aliases[String(status || '').toLowerCase()] || rawKey;
  return <span className={`wp-status-badge wp-status-badge--${supported.has(key) ? key : 'neutral'} ${className}`}>
    <span className="wp-status-badge__dot" aria-hidden="true" />{label || formatDisplayLabel(status) || 'Status unavailable'}
  </span>;
}
