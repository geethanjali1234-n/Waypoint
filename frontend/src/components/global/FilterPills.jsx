import './FilterPills.css';

/** Controlled filter values and counts must come from the caller's data. */
export default function FilterPills({ options = [], value, onChange, label = 'Filters', className = '' }) {
  return <div role="group" aria-label={label} className={`wp-filter-pills ${className}`}>
    {options.map((option) => <button type="button" key={option.value} aria-pressed={value === option.value} disabled={option.disabled} onClick={() => onChange?.(option.value)}>
      <span>{option.label}</span>{typeof option.count === 'number' && <>{' '}<span className="wp-filter-pills__count">{option.count.toLocaleString()}</span></>}
    </button>)}
  </div>;
}
