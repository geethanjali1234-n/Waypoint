import './Badge.css';

/**
 * Badge - small pill labelling a category or type (e.g. "ON-SITE", "FULL-TIME").
 * Distinct from StatusTag, which represents workflow state rather than a category.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.label - Badge text/content.
 * @param {'neutral'|'info'|'success'|'warning'} [props.variant='neutral'] - Color variant.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Badge label="ON-SITE · FULL-TIME" variant="neutral" />
 */
export default function Badge({ label, variant = 'neutral', className = '' }) {
  const classes = ['wp-badge', `wp-badge--${variant}`, className]
    .filter(Boolean)
    .join(' ');

  return <span className={classes}>{label}</span>;
}
