import './StatusTag.css';
import { formatDisplayLabel } from '../../utils/myHrDisplayLabel';

/**
 * StatusTag - color-coded label representing workflow/record state
 * (e.g. Applied, In Review, Shortlisted, Active, Closed).
 *
 * @param {Object} props
 * @param {string} props.status - Status text to display.
 * @param {'neutral'|'info'|'success'|'warning'|'error'} [props.tone='neutral'] - Visual tone for the given status.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <StatusTag status="Shortlisted" tone="success" />
 */
export default function StatusTag({ status, tone = 'neutral', className = '' }) {
  const classes = ['wp-status-tag', `wp-status-tag--${tone}`, className]
    .filter(Boolean)
    .join(' ');

  return <span className={classes}>{formatDisplayLabel(status)}</span>;
}
