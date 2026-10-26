import './ValidationMessage.css';

/**
 * ValidationMessage - inline error or hint text shown beneath a form field.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.message - Message text.
 * @param {'error'|'hint'} [props.variant='hint'] - Visual style.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <ValidationMessage message="Email is required" variant="error" />
 */
export default function ValidationMessage({ message, variant = 'hint', className = '' }) {
  if (!message) return null;

  const classes = ['wp-validation-message', `wp-validation-message--${variant}`, className]
    .filter(Boolean)
    .join(' ');

  return (
    <p className={classes} role={variant === 'error' ? 'alert' : undefined}>
      {message}
    </p>
  );
}
