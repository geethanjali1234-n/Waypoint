import './Chip.css';

/**
 * Chip - compact token used for both static display (skills, tags) and
 * interactive selection (skill/role/location pickers).
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.label - Chip text/content.
 * @param {boolean} [props.selected=false] - Whether the chip is in a selected state.
 * @param {boolean} [props.selectable=false] - Whether the chip responds to clicks to toggle selection.
 * @param {() => void} [props.onClick] - Called when a selectable chip is clicked.
 * @param {() => void} [props.onRemove] - When provided, renders a remove control and is called on click.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Chip label="React" selectable selected={isSelected} onClick={toggle} />
 * <Chip label="TypeScript" onRemove={() => removeSkill('TypeScript')} />
 */
export default function Chip({
  label,
  selected = false,
  selectable = false,
  onClick,
  onRemove,
  className = '',
}) {
  const classes = [
    'wp-chip',
    selected ? 'wp-chip--selected' : '',
    selectable ? 'wp-chip--selectable' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  const Tag = selectable ? 'button' : 'span';

  return (
    <Tag
      type={selectable ? 'button' : undefined}
      className={classes}
      onClick={selectable ? onClick : undefined}
      aria-pressed={selectable ? selected : undefined}
    >
      <span className="wp-chip__label">{label}</span>
      {onRemove && (
        <button
          type="button"
          className="wp-chip__remove"
          aria-label={`Remove ${typeof label === 'string' ? label : 'item'}`}
          onClick={(event) => {
            event.stopPropagation();
            onRemove();
          }}
        >
          ×
        </button>
      )}
    </Tag>
  );
}
