import { Link } from 'react-router-dom';
import { PageHeader, ThemeToggle } from '../../components';
import StrokeIcon from '../../components/global/StrokeIcon';
import MobileAppBanner from '../../components/MobileAppBanner';
import AuthLayout from './components/AuthLayout';
import AuthSidePanel from './components/AuthSidePanel';
import LoginForm from './components/LoginForm';
import './LoginPage.css';

/**
 * LoginPage - route page for signing in to an existing Waypoint account.
 *
 * @example
 * <Route path="/login" element={<LoginPage />} />
 */
export default function LoginPage() {
  return (
    <>
      <MobileAppBanner />
      <AuthLayout
        className="wp-login-page"
        sidePanel={
          <AuthSidePanel
            workspaceLabel="Waypoint"
            eyebrow="The Waypoint approach"
            heading={
              <>
                A clearer route
                <br />
                to meaningful work.
              </>
            }
            description="Waypoint brings people, employers, and career events into one clear path—so the next step is easier to find."
            showLogo
            showPattern
          />
        }
      >
        <div className="wp-login-theme"><ThemeToggle id="login-theme-toggle" /></div>
        <PageHeader title="Welcome back" subtitle="Sign in to continue." />
        <LoginForm workspace="MY_HR" />
        <nav className="wp-login-destinations" aria-label="More from Waypoint">
          {[
            ['/experience', 'briefcase', 'Explore Next-Gen Interactive Experience', undefined],
            ['/download', 'download', 'Get Mobile App (APK & iOS)', 'login-download-mobile-link'],
            ['/register/organization', 'organization', 'Create Organization Account', 'login-create-organization-link'],
          ].map(([to, icon, label, id]) => (
            <Link key={to} to={to} id={id} className="wp-login-destination">
              <span className="wp-login-destination__icon"><StrokeIcon name={icon} size={20} /></span>
              <span>{label}</span>
              <StrokeIcon name="arrow-right" size={18} />
            </Link>
          ))}
        </nav>
      </AuthLayout>
    </>
  );
}

