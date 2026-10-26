import './Divider.css';

/**
 * Divider - visual separator between sections or elements.
 *
 * @param {Object} props
 * @param {'horizontal'|'vertical'} [props.orientation='horizontal'] - Divider direction.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Divider />
 * <Divider orientation="vertical" />
 */
export default function Divider({ orientation = 'horizontal', className = '' }) {
  const classes = ['wp-divider', `wp-divider--${orientation}`, className]
    .filter(Boolean)
    .join(' ');

  return <hr className={classes} />;
}
