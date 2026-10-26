import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';
import './PhoneInput.css';

/**
 * PhoneInput - US phone number field with a fixed, non-editable '+1' country
 * code prefix. The user only ever types the remaining 10 digits; non-digit
 * characters are stripped as they type and input is capped at 10 digits.
 * The value passed to `onChange` (and thus the form's field value) is always
 * either '' or the full '+1XXXXXXXXXX' string, ready to submit as-is.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.value=''] - Controlled value, either '' or '+1' followed by up to 10 digits.
 * @param {(event: {target: {name: string, value: string}}) => void} [props.onChange] - Change handler; called with a synthetic event shape compatible with react-hook-form's Controller.
 * @param {string} [props.placeholder='(555) 123-4567'] - Placeholder for the national phone number.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {boolean} [props.disabled=false] - Disables the field.
 * @param {boolean} [props.showCountryCode=true] - Shows the fixed +1 country code beside the field.
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <PhoneInput label="Phone number" name="phoneNumber" value={phoneNumber} onChange={handleChange} required />
 */
export default function PhoneInput({
  name,
  label,
  value = '',
  onChange,
  placeholder = '(555) 123-4567',
  required = false,
  disabled = false,
  showCountryCode = true,
  error,
  hint,
  className = '',
}) {
  const rawValue = String(value || '');
  const rawDigits = rawValue.startsWith('+1')
    ? rawValue.slice(2).replace(/\D/g, '')
    : rawValue.replace(/\D/g, '');
  const localDigits = !rawValue.startsWith('+1') && rawDigits.length === 11 && rawDigits.startsWith('1')
    ? rawDigits.slice(1)
    : rawDigits;
  const digits = localDigits.slice(0, 10);
  const displayValue = digits.length > 6
    ? `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`
    : digits.length > 3
      ? `(${digits.slice(0, 3)}) ${digits.slice(3)}`
      : digits;

  const handleChange = (event) => {
    const formattedDigits = (event.target.value || '').replace(/\D/g, '');
    const digitsWithoutCountry = formattedDigits.length === 11 && formattedDigits.startsWith('1')
      ? formattedDigits.slice(1)
      : formattedDigits;
    const nextDigits = digitsWithoutCountry.slice(0, 10);
    const nextValue = nextDigits ? `+1${nextDigits}` : '';
    onChange && onChange({ target: { name, value: nextValue } });
  };

  const classes = ['wp-field', className].filter(Boolean).join(' ');
  const controlClasses = [
    'wp-field__control',
    'wp-phone-input__control',
    error ? 'wp-field__control--error' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={classes}>
      {label && (
        <FormLabel htmlFor={name} required={required}>
          {label}
        </FormLabel>
      )}
      <div className={`wp-phone-input__wrapper${showCountryCode ? '' : ' wp-phone-input__wrapper--without-prefix'}`}>
        {showCountryCode && <span className="wp-phone-input__prefix" aria-hidden="true">
          +1
        </span>}
        <input
          id={name}
          name={name}
          type="tel"
          inputMode="numeric"
          autoComplete="tel-national"
          value={displayValue}
          onChange={handleChange}
          placeholder={placeholder}
          required={required}
          disabled={disabled}
          maxLength={14}
          className={controlClasses}
        />
      </div>
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
