import './SegmentedControl.css';

/**
 * SegmentedControl - pill-style toggle for a small set of mutually exclusive
 * options (e.g. the Job Seeker/Employer selector on Sign In and Sign Up).
 *
 * @param {Object} props
 * @param {{label: string, value: string}[]} props.options - Selectable options.
 * @param {string} props.value - Currently selected value.
 * @param {(value: string) => void} [props.onChange] - Called with the newly selected value.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <SegmentedControl
 *   options={[{ label: 'Job Seeker', value: 'job-seeker' }, { label: 'Employer', value: 'employer' }]}
 *   value={role}
 *   onChange={setRole}
 * />
 */
export default function SegmentedControl({ options, value, onChange, className = '' }) {
  const classes = ['wp-segmented-control', className].filter(Boolean).join(' ');

  return (
    <div className={classes} role="tablist">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={option.value === value}
          className={`wp-segmented-control__option ${
            option.value === value ? 'wp-segmented-control__option--active' : ''
          }`}
          onClick={() => onChange && onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
