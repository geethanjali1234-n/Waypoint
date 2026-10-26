import { useState } from 'react';
import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';
import './PasswordInput.css';

/**
 * PasswordInput - password field with a show/hide visibility toggle.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.value=''] - Controlled input value.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onChange] - Change handler.
 * @param {string} [props.placeholder] - Placeholder text.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {boolean} [props.disabled=false] - Disables the field.
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <PasswordInput label="Password" name="password" value={password} onChange={handleChange} required />
 */
export default function PasswordInput({
  name,
  label,
  value = '',
  onChange,
  placeholder = 'Enter your password',
  required = false,
  disabled = false,
  error,
  hint,
  className = '',
}) {
  const [visible, setVisible] = useState(false);
  const classes = ['wp-field', className].filter(Boolean).join(' ');
  const controlClasses = ['wp-field__control', error ? 'wp-field__control--error' : '']
    .filter(Boolean)
    .join(' ');

  return (
    <div className={classes}>
      {label && (
        <FormLabel htmlFor={name} required={required}>
          {label}
        </FormLabel>
      )}
      <div className="wp-password-input__wrapper">
        <input
          id={name}
          name={name}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={onChange}
          placeholder={placeholder}
          required={required}
          disabled={disabled}
          className={controlClasses}
        />
        <button
          type="button"
          className="wp-password-input__toggle"
          onClick={() => setVisible((current) => !current)}
          aria-label={visible ? 'Hide password' : 'Show password'}
        >
          {visible ? 'Hide' : 'Show'}
        </button>
      </div>
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
