import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';

/**
 * TimePicker - native time selector with label and validation message.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.value=''] - Controlled value in HH:mm format.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onChange] - Change handler.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <TimePicker label="Start time" name="startTime" value={startTime} onChange={handleChange} />
 */
export default function TimePicker({
  name,
  label,
  value = '',
  onChange,
  required = false,
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
        type="time"
        value={value}
        onChange={onChange}
        required={required}
        className={controlClasses}
      />
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
