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
 * @param {string} [props.className=''] - Additional class names on the outer wrapper.
 *
 * @example
 * <AuthLayout sidePanel={<AuthSidePanel heading="Welcome back" />}>
 *   <PageHeader title="Welcome back" />
 *   <LoginForm />
 * </AuthLayout>
 */
export default function AuthLayout({ sidePanel, children, className = '' }) {
  const classes = ['wp-auth-layout', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <TwoColumnLayout
        ratio="1:1"
        className="wp-auth-layout__columns"
        main={<div className="wp-auth-layout__side">{sidePanel}</div>}
        side={
          <div className="wp-auth-layout__main">
            <Card className="wp-auth-layout__card">{children}</Card>
          </div>
        }
      />
    </div>
  );
}
