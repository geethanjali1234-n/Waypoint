import './Checkbox.css';

/**
 * Checkbox - single confirmation/option control.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {import('react').ReactNode} props.label - Label displayed next to the checkbox.
 * @param {boolean} [props.checked=false] - Controlled checked state.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onChange] - Change handler.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {boolean} [props.disabled=false] - Disables the field.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Checkbox name="authorized" label="I'm authorized to work in the United States" checked={authorized} onChange={handleChange} required />
 */
export default function Checkbox({
  name,
  label,
  checked = false,
  onChange,
  required = false,
  disabled = false,
  className = '',
}) {
  const classes = ['wp-checkbox', disabled ? 'wp-checkbox--disabled' : '', className].filter(Boolean).join(' ');

  return (
    <label htmlFor={name} className={classes}>
      <input
        id={name}
        name={name}
        type="checkbox"
        checked={checked}
        onChange={onChange}
        required={required}
        disabled={disabled}
        className="wp-checkbox__input"
      />
      <span className="wp-checkbox__label">
        {label}
        {required && <span className="wp-checkbox__required"> *</span>}
      </span>
    </label>
  );
}
