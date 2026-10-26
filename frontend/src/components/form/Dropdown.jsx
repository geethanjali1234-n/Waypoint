import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';

/**
 * Dropdown - reusable select field.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the select id.
 * @param {string} [props.label] - Field label text.
 * @param {{label: string, value: string}[]} props.options - Selectable options.
 * @param {string} [props.value=''] - Controlled selected value.
 * @param {(event: import('react').ChangeEvent<HTMLSelectElement>) => void} [props.onChange] - Change handler.
 * @param {string} [props.placeholder='Select one'] - Placeholder shown as the disabled first option.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {boolean} [props.disabled=false] - Disables the field.
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <Dropdown
 *   label="Industry"
 *   name="industry"
 *   options={[{ label: 'Software', value: 'software' }]}
 *   value={industry}
 *   onChange={handleChange}
 * />
 */
export default function Dropdown({
  name,
  label,
  options = [],
  value = '',
  onChange,
  placeholder,
  required = false,
  disabled = false,
  error,
  hint,
  className = '',
}) {
  const classes = ['wp-field', className].filter(Boolean).join(' ');
  const controlClasses = ['wp-field__control', error ? 'wp-field__control--error' : '']
    .filter(Boolean)
    .join(' ');

  const hasAllOrEmptyOption = options.some((opt) => opt.value === 'ALL' || opt.value === '');
  const effectivePlaceholder = placeholder !== undefined
    ? placeholder
    : (hasAllOrEmptyOption ? '' : 'Select one');

  return (
    <div className={classes}>
      {label && (
        <FormLabel htmlFor={name} required={required}>
          {label}
        </FormLabel>
      )}
      <select
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        required={required}
        disabled={disabled}
        className={controlClasses}
      >
        {effectivePlaceholder ? (
          <option value="" disabled>
            {effectivePlaceholder}
          </option>
        ) : null}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
