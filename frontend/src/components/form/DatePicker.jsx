import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';

/**
 * DatePicker - native date selector with label and validation message.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.value=''] - Controlled value in yyyy-mm-dd format.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onChange] - Change handler.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {string} [props.min] - Minimum selectable date.
 * @param {string} [props.max] - Maximum selectable date.
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <DatePicker label="Application deadline" name="applicationDeadline" value={date} onChange={handleChange} />
 */
export default function DatePicker({
  name,
  label,
  value = '',
  onChange,
  required = false,
  min,
  max,
  error,
  hint,
  className = '',
}) {
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
      <input
        id={name}
        name={name}
        type="date"
        value={value}
        onChange={onChange}
        onClick={(event) => event.currentTarget.showPicker?.()}
        required={required}
        min={min}
        max={max}
        className={controlClasses}
      />
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
