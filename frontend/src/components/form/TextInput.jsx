import { useEffect, useRef } from 'react';
import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './fieldShared.css';

/**
 * TextInput - standard single-line text field with label and validation message.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.value=''] - Controlled input value.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onChange] - Change handler.
 * @param {string} [props.type='text'] - Native input type.
 * @param {string} [props.placeholder] - Placeholder text.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {boolean} [props.disabled=false] - Disables the field.
 * @param {string|number} [props.min] - Native minimum value for number/date inputs.
 * @param {string|number} [props.max] - Native maximum value for number/date inputs.
 * @param {string|number} [props.step] - Native step value for number/time inputs.
 * @param {string} [props.error] - Error message; renders in place of hint text when present.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <TextInput label="First name" name="firstName" value={firstName} onChange={handleChange} required />
 */
export default function TextInput({
  name,
  label,
  value = '',
  onChange,
  onWheel,
  onKeyDown,
  type = 'text',
  placeholder,
  required = false,
  disabled = false,
  min,
  max,
  step,
  maxLength,
  error,
  hint,
  className = '',
}) {
  const classes = ['wp-field', className].filter(Boolean).join(' ');
  const controlClasses = ['wp-field__control', error ? 'wp-field__control--error' : '']
    .filter(Boolean)
    .join(' ');
  const inputRef = useRef(null);
  const isZipCode = name === 'zipCode';
  const isPlaceName = name === 'city' || name === 'state';
  const resolvedMaxLength = isZipCode ? Math.min(Number(maxLength) || 5, 5) : maxLength;

  useEffect(() => {
    const el = inputRef.current;
    if (!el || type !== 'number') return;

    const handleNativeWheel = (e) => {
      // Prevent mouse wheel / scroll gesture from changing the number value
      e.preventDefault();
    };

    el.addEventListener('wheel', handleNativeWheel, { passive: false });
    return () => {
      el.removeEventListener('wheel', handleNativeWheel);
    };
  }, [type]);

  const handleKeyDown = (event) => {
    if (type === 'number' && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
      event.preventDefault();
    }
    if (onKeyDown) {
      onKeyDown(event);
    }
  };

  const handleWheel = (event) => {
    if (type === 'number') {
      event.currentTarget.blur();
    }
    if (onWheel) {
      onWheel(event);
    }
  };

  const handleChange = (event) => {
    if (!isZipCode && !isPlaceName) {
      onChange?.(event);
      return;
    }

    const value = isZipCode
      ? event.target.value.replace(/\D/g, '').slice(0, 5)
      : event.target.value.replace(/[^\p{L}\p{M} ]/gu, '');
    onChange?.({ target: { name, value } });
  };

  return (
    <div className={classes}>
      {label && (
        <FormLabel htmlFor={name} required={required}>
          {label}
        </FormLabel>
      )}
      <input
        ref={inputRef}
        id={name}
        name={name}
        type={type}
        value={value}
        onChange={handleChange}
        onWheel={handleWheel}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        required={required}
        disabled={disabled}
        min={min}
        max={max}
        step={step}
        maxLength={resolvedMaxLength}
        inputMode={isZipCode ? 'numeric' : undefined}
        pattern={isZipCode ? '[0-9]{5}' : undefined}
        className={controlClasses}
      />
      <ValidationMessage message={error || hint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
