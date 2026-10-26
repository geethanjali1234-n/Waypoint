import './fieldShared.css';
import './SearchInput.css';

/**
 * SearchInput - text box paired with a search action, used on listing pages.
 *
 * @param {Object} props
 * @param {string} [props.value=''] - Controlled input value.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onChange] - Change handler.
 * @param {(value: string) => void} [props.onSearch] - Called with the current value on submit.
 * @param {string} [props.placeholder='Search'] - Placeholder text.
 * @param {string} [props.className=''] - Additional class names.
 * @param {string} [props.id] - Optional input id.
 * @param {string} [props.name] - Optional input name.
 *
 * @example
 * <SearchInput value={query} onChange={handleChange} onSearch={handleSearch} placeholder="Job title or role" />
 */
export default function SearchInput({
  value = '',
  onChange,
  onSearch,
  placeholder = 'Search',
  className = '',
  id,
  name,
}) {
  const classes = ['wp-search-input', className].filter(Boolean).join(' ');

  return (
    <form
      className={classes}
      onSubmit={(event) => {
        event.preventDefault();
        onSearch && onSearch(value);
      }}
    >
      <input
        type="search"
        id={id}
        name={name}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        className="wp-field__control wp-search-input__control"
      />
    </form>
  );
}
