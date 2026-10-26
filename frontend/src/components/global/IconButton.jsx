import './IconButton.css';

/**
 * IconButton - compact button rendering a single icon, for header/action controls.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.icon - Icon element to render.
 * @param {string} props.ariaLabel - Accessible label describing the action.
 * @param {'sm'|'md'|'lg'} [props.size='md'] - Button size.
 * @param {'ghost'|'filled'} [props.variant='ghost'] - Visual style.
 * @param {boolean} [props.disabled=false] - Disables interaction.
 * @param {() => void} [props.onClick] - Click handler.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <IconButton icon={<BellIcon />} ariaLabel="Notifications" onClick={openMenu} />
 */
export default function IconButton({
  icon,
  ariaLabel,
  size = 'md',
  variant = 'ghost',
  disabled = false,
  onClick,
  className = '',
}) {
  const classes = [
    'wp-icon-button',
    `wp-icon-button--${size}`,
    `wp-icon-button--${variant}`,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      type="button"
      className={classes}
      aria-label={ariaLabel}
      disabled={disabled}
      onClick={onClick}
    >
      {icon}
    </button>
  );
}
