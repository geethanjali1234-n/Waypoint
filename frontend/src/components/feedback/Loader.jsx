import './Loader.css';

/**
 * Loader - spinning indicator for in-progress operations.
 *
 * @param {Object} props
 * @param {'sm'|'md'|'lg'} [props.size='md'] - Loader size.
 * @param {string} [props.label] - Optional text shown next to the spinner.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Loader size="md" label="Loading jobs…" />
 */
export default function Loader({ size = 'md', label, className = '' }) {
  const classes = ['wp-loader', className].filter(Boolean).join(' ');

  return (
    <div className={classes} role="status" aria-live="polite">
      <span className={`wp-loader__spinner wp-loader__spinner--${size}`} aria-hidden="true" />
      {label && <span className="wp-loader__label">{label}</span>}
    </div>
  );
}
