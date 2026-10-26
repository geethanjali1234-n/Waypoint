import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';

/**
 * TextArea - multi-line text field for longer content such as messages or descriptions.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the textarea id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.value=''] - Controlled textarea value.
 * @param {(event: import('react').ChangeEvent<HTMLTextAreaElement>) => void} [props.onChange] - Change handler.
 * @param {string} [props.placeholder] - Placeholder text.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {number} [props.rows=4] - Number of visible text rows.
 * @param {number} [props.maxLength] - Maximum character length.
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <TextArea label="Message" name="message" value={message} onChange={handleChange} rows={5} />
 */
export default function TextArea({
  name,
  label,
  value = '',
  onChange,
  placeholder,
  required = false,
  rows = 4,
  maxLength,
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
      <textarea
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        required={required}
        rows={rows}
        maxLength={maxLength}
        className={controlClasses}
      />
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
