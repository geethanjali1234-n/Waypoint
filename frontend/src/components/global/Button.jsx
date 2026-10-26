import './Button.css';

/**
 * Button - primary call-to-action control with primary/secondary variants.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.children - Button label/content.
 * @param {'primary'|'secondary'} [props.variant='primary'] - Visual style.
 * @param {'button'|'submit'|'reset'} [props.type='button'] - Native button type.
 * @param {boolean} [props.disabled=false] - Disables interaction.
 * @param {boolean} [props.loading=false] - Shows a loading state and disables interaction.
 * @param {boolean} [props.fullWidth=false] - Stretches the button to fill its container.
 * @param {() => void} [props.onClick] - Click handler.
 * @param {string} [props['aria-label']] - Accessible name for icon-only or contextual buttons.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Button variant="primary" onClick={() => {}}>Submit application</Button>
 */
export default function Button({
  children,
  variant = 'primary',
  type = 'button',
  disabled = false,
  loading = false,
  fullWidth = false,
  onClick,
  'aria-label': ariaLabel,
  className = '',
  title,
  ...rest
}) {
  const classes = [
    'wp-button',
    `wp-button--${variant}`,
    fullWidth ? 'wp-button--full-width' : '',
    loading ? 'wp-button--loading' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      type={type}
      className={classes}
      disabled={disabled || loading}
      onClick={onClick}
      aria-label={ariaLabel}
      title={title}
      {...rest}
    >
      {children}
    </button>
  );
}
