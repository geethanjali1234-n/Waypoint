import './Avatar.css';

/**
 * Avatar - circular user/company avatar, showing an image when available
 * and falling back to initials.
 *
 * @param {Object} props
 * @param {string} [props.imageUrl] - Image source; when omitted, initials are shown.
 * @param {string} [props.initials=''] - Fallback initials (e.g. "JT").
 * @param {string} [props.alt=''] - Alt text for the image.
 * @param {'sm'|'md'|'lg'} [props.size='md'] - Avatar size.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Avatar initials="JT" size="md" />
 * <Avatar imageUrl="/company-logo.png" alt="Meridian Labs" />
 */
export default function Avatar({
  imageUrl,
  initials = '',
  alt = '',
  size = 'md',
  className = '',
}) {
  const classes = ['wp-avatar', `wp-avatar--${size}`, className]
    .filter(Boolean)
    .join(' ');

  if (imageUrl) {
    return <img src={imageUrl} alt={alt} className={classes} />;
  }

  return (
    <span className={classes} role="img" aria-label={alt || initials}>
      {initials}
    </span>
  );
}
