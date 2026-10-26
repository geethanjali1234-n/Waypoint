import './Toast.css';

/**
 * Toast - transient notification message, typically rendered by a host
 * container after an action succeeds or fails.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.message - Notification text.
 * @param {'success'|'error'|'info'|'warning'} [props.variant='info'] - Visual tone.
 * @param {() => void} [props.onClose] - Called when the dismiss control is clicked.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Toast message="Application submitted" variant="success" onClose={dismiss} />
 */
export default function Toast({ message, variant = 'info', onClose, className = '' }) {
  const classes = ['wp-toast', `wp-toast--${variant}`, className].filter(Boolean).join(' ');

  return (
    <div className={classes} role="status">
      <span className="wp-toast__message">{message}</span>
      {onClose && (
        <button type="button" className="wp-toast__close" aria-label="Dismiss" onClick={onClose}>
          ×
        </button>
      )}
    </div>
  );
}
