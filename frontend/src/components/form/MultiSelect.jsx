import { useState } from 'react';
import { Chip } from '../global';
import FormLabel from './FormLabel';
import ValidationMessage from './ValidationMessage';
import './MultiSelect.css';

/**
 * MultiSelect - searchable autocomplete for selecting several values from a
 * suggestion list (e.g. skills, preferred job roles). Type to filter options
 * (case-insensitive substring match); already-selected values are excluded
 * from the suggestion list, which also prevents duplicates structurally.
 * Selected values are shown as removable Chips above the search input.
 *
 * Keyboard support: Tab moves through the input and suggestion buttons
 * normally; Enter adds the first visible matching suggestion when a query is
 * present; Escape clears the query, or closes the suggestion list if the
 * query is already empty. This is not a full ARIA combobox implementation
 * (no arrow-key roving focus within the list).
 *
 * @param {Object} props
 * @param {string} props.name - Field name, used for the label association.
 * @param {string} [props.label] - Field label text.
 * @param {{label: string, value: string}[]} props.options - Full suggestion list.
 * @param {string[]} [props.selected=[]] - Currently selected option values.
 * @param {(value: string) => void} [props.onAdd] - Called with a value when a suggestion is selected.
 * @param {(value: string) => void} [props.onRemove] - Called with a value when its chip is removed.
 * @param {number} [props.min] - Minimum number of selections recommended (surfaced as hint text).
 * @param {string} [props.error] - Error message.
 * @param {string} [props.hint] - Helper text shown when there is no error.
 * @param {string} [props.placeholder='Type to search...'] - Input placeholder.
 * @param {boolean} [props.required=false] - Marks the field as required.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <MultiSelect
 *   label="Skills"
 *   name="skills"
 *   options={SKILL_OPTIONS}
 *   selected={selectedSkills}
 *   onAdd={(value) => setSelectedSkills((prev) => [...prev, value])}
 *   onRemove={(value) => setSelectedSkills((prev) => prev.filter((item) => item !== value))}
 *   min={3}
 * />
 */
export default function MultiSelect({
  name,
  label,
  options,
  selected = [],
  onAdd,
  onRemove,
  min,
  error,
  hint,
  placeholder = 'Type to search...',
  required = false,
  className = '',
}) {
  const [query, setQuery] = useState('');
  const [isOpen, setIsOpen] = useState(false);

  const normalizedQuery = query.trim().toLowerCase();
  const filteredOptions = options.filter(
    (option) => !selected.includes(option.value) && option.label.toLowerCase().includes(normalizedQuery)
  );

  const resolvedHint = hint ?? (min ? `Select at least ${min}` : undefined);
  const classes = ['wp-multi-select', className].filter(Boolean).join(' ');

  const handleSelect = (value) => {
    onAdd && onAdd(value);
    setQuery('');
  };

  const handleKeyDown = (event) => {
    if (event.key === 'Enter') {
      if (normalizedQuery && filteredOptions.length > 0) {
        event.preventDefault();
        handleSelect(filteredOptions[0].value);
      }
    } else if (event.key === 'Escape') {
      if (query) {
        setQuery('');
      } else {
        setIsOpen(false);
      }
    }
  };

  return (
    <div className={classes}>
      {label && (
        <FormLabel htmlFor={name} required={required}>
          {label}
        </FormLabel>
      )}

      {selected.length > 0 && (
        <div className="wp-multi-select__chips">
          {selected.map((value) => {
            const option = options.find((item) => item.value === value);
            return (
              <Chip
                key={value}
                label={option ? option.label : value}
                onRemove={() => onRemove && onRemove(value)}
              />
            );
          })}
        </div>
      )}

      <div className="wp-multi-select__combobox">
        <input
          id={name}
          name={name}
          type="text"
          className="wp-multi-select__input"
          value={query}
          placeholder={placeholder}
          onChange={(event) => {
            setQuery(event.target.value);
            setIsOpen(true);
          }}
          onFocus={() => setIsOpen(true)}
          onBlur={() => setIsOpen(false)}
          onKeyDown={handleKeyDown}
          autoComplete="off"
          role="combobox"
          aria-expanded={isOpen}
          aria-controls={`${name}-listbox`}
          aria-autocomplete="list"
        />

        {isOpen && (
          <ul className="wp-multi-select__suggestions" id={`${name}-listbox`} role="listbox">
            {filteredOptions.length > 0 ? (
              filteredOptions.map((option) => (
                <li key={option.value} role="option" aria-selected="false">
                  <button
                    type="button"
                    className="wp-multi-select__suggestion"
                    onMouseDown={(event) => {
                      event.preventDefault();
                      handleSelect(option.value);
                    }}
                  >
                    {option.label}
                  </button>
                </li>
              ))
            ) : (
              <li className="wp-multi-select__empty">No matching options</li>
            )}
          </ul>
        )}
      </div>

      <ValidationMessage message={error || resolvedHint} variant={error ? 'error' : 'hint'} />
    </div>
  );
}
