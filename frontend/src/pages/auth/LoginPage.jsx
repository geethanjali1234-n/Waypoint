import { Link } from 'react-router-dom';
import { PageHeader, ThemeToggle } from '../../components';
import WaypointMark from '../../components/branding/WaypointMark';
import StrokeIcon from '../../components/global/StrokeIcon';
import MobileAppBanner from '../../components/MobileAppBanner';
import AuthLayout from './components/AuthLayout';
import WorkspaceExplorer from '../../components/branding/WorkspaceExplorer';
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
        formFirst
        sidePanel={<LoginStory />}
      >
        <Link to="/" className="wp-login-mobile-brand" aria-label="Waypoint home"><WaypointMark /><span>Waypoint</span></Link>
        <div className="wp-login-theme"><ThemeToggle id="login-theme-toggle" /></div>
        <PageHeader title="Welcome back" subtitle="Sign in to continue." />
        <LoginForm workspace="MY_HR" />
        <p className="wp-login-register">New to Waypoint? <Link to="/register/organization">Create an organization</Link></p>
        <nav className="wp-login-destinations" aria-label="More from Waypoint">
          {[
            ['/download', 'download', 'Get Mobile App (APK & iOS)', 'login-download-mobile-link'],
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


function LoginStory() {
  return <section className="wp-login-story" aria-labelledby="login-story-title">
    <Link to="/" className="wp-login-story__brand" aria-label="Waypoint home"><WaypointMark /><span>Waypoint</span></Link>
    <div className="wp-login-story__intro">
      <p className="wp-login-story__eyebrow">Workforce &amp; document tracking</p>
      <h1 id="login-story-title">Track your people.<br />Deliver every document.</h1>
      <p className="wp-login-story__description">Timesheets, approvals, and document requests in one clear route, from the first request to the final delivery.</p>
    </div>
    <div className="wp-login-story__actions">
      <Link to="/register/organization" id="login-create-organization-link">Create Organization Account <StrokeIcon name="arrow-right" size={18} /></Link>
      <Link to="/experience">See how Waypoint works <StrokeIcon name="arrow-right" size={18} /></Link>
    </div>
    <div className="wp-login-story__example" aria-label="Example document workflow">
      <p>Example workflow</p>
      <ol>
        <li><span className="wp-login-story__stop wp-login-story__stop--done" aria-hidden="true"><StrokeIcon name="check" size={18} /></span><span>Requested</span></li>
        <li><span className="wp-login-story__stop wp-login-story__stop--current" aria-hidden="true" /><strong>In review</strong></li>
        <li><span className="wp-login-story__stop wp-login-story__stop--next" aria-hidden="true" /><span>Delivered</span></li>
      </ol>
    </div>
    <WorkspaceExplorer />
  </section>;
}
