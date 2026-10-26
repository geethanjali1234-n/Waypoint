import './Footer.css';

/**
 * Footer - common page footer.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.children - Footer content.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Footer>© 2026 Waypoint. Built for job seekers and employers in DFW.</Footer>
 */
export default function Footer({ children, className = '' }) {
  const classes = ['wp-footer', className].filter(Boolean).join(' ');

  return <footer className={classes}>{children}</footer>;
}
