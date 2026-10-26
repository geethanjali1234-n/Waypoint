import './FilterBar.css';

/**
 * FilterBar - layout/container for search and filter controls. Renders
 * whatever filter elements (search inputs, dropdowns, chip filters) are
 * passed in as children, without prescribing their type.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.children - Filter controls to lay out (e.g. SearchInput, Dropdown, Chip).
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <FilterBar>
 *   <SearchInput placeholder="Job title or role" value={query} onChange={handleChange} />
 *   <Dropdown name="employmentType" options={typeOptions} value={type} onChange={handleTypeChange} />
 * </FilterBar>
 */
export default function FilterBar({ children, className = '' }) {
  const classes = ['wp-filter-bar', className].filter(Boolean).join(' ');

  return <div className={classes}>{children}</div>;
}
