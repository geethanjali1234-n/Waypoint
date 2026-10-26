import './FormLabel.css';

/**
 * FormLabel - accessible label for a form field, with an optional required marker.
 *
 * @param {Object} props
 * @param {string} [props.htmlFor] - id of the associated form control.
 * @param {import('react').ReactNode} props.children - Label text.
 * @param {boolean} [props.required=false] - Shows a required-field asterisk.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <FormLabel htmlFor="email" required>Email</FormLabel>
 */
export default function FormLabel({ htmlFor, children, required = false, className = '' }) {
  const classes = ['wp-form-label', className].filter(Boolean).join(' ');

  return (
    <label htmlFor={htmlFor} className={classes}>
      {children}
      {required && (
        <span className="wp-form-label__required" aria-hidden="true">
          *
        </span>
      )}
    </label>
  );
}
