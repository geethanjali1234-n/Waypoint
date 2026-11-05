import { Card, TwoColumnLayout } from '../../../components';
import './AuthLayout.css';

/**
 * AuthLayout - reusable full-viewport split-screen shell for authentication
 * pages (login, registration, verification, and password recovery). Renders the branding panel on the
 * left and wraps the page-specific content (a PageHeader + the existing,
 * unmodified form) in the shared Card component on the right.
 *
 * Both columns are full-height; the card is centered within the right
 * column rather than the page being a small centered box floating in
 * empty space - it's meant to feel like a full, substantial surface.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.sidePanel - Branding panel content, typically an AuthSidePanel.
 * @param {import('react').ReactNode} props.children - Page-specific content (PageHeader + form) rendered inside the Card.
 * @param {boolean} [props.formFirst=false] - Place authentication before promotional links in document order.
 * @param {string} [props.className=''] - Additional class names on the outer wrapper.
 *
 * @example
 * <AuthLayout sidePanel={<AuthSidePanel heading="Welcome back" />}>
 *   <PageHeader title="Welcome back" />
 *   <LoginForm />
 * </AuthLayout>
 */
export default function AuthLayout({ sidePanel, children, className = '', formFirst = false }) {
  const classes = ['wp-auth-layout', formFirst && 'wp-auth-layout--form-first', className].filter(Boolean).join(' ');
  const brand = <div className="wp-auth-layout__side">{sidePanel}</div>;
  const form = <div className="wp-auth-layout__main"><Card className="wp-auth-layout__card">{children}</Card></div>;

  return (
    <div className={classes}>
      <TwoColumnLayout ratio="1:1" className="wp-auth-layout__columns"
        main={formFirst ? form : brand} side={formFirst ? brand : form} />
    </div>
  );
}
