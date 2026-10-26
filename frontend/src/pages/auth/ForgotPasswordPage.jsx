import { PageHeader } from '../../components';
import AuthLayout from './components/AuthLayout';
import AuthSidePanel from './components/AuthSidePanel';
import ForgotPasswordForm from './components/ForgotPasswordForm';

export default function ForgotPasswordPage() {
  return (
    <AuthLayout
      className="wp-forgot-password-page"
      sidePanel={
        <AuthSidePanel
          eyebrow="Account recovery"
          heading={
            <>
              Find your way
              <br />
              back to Waypoint.
            </>
          }
          description="We’ll send a secure, time-limited code so you can choose a new password and keep moving forward."
          showLogo
          showPattern
        />
      }
    >
      <PageHeader title="Forgot your password?" subtitle="Enter the email connected to your Waypoint account." />
      <ForgotPasswordForm />
    </AuthLayout>
  );
}
