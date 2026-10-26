import { Link, useSearchParams } from 'react-router-dom';
import { PageHeader, ValidationMessage } from '../../components';
import AuthLayout from './components/AuthLayout';
import AuthSidePanel from './components/AuthSidePanel';
import ResetPasswordForm from './components/ResetPasswordForm';
import './ResetPasswordPage.css';

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const email = searchParams.get('email')?.trim() ?? '';

  return (
    <AuthLayout
      className="wp-reset-password-page"
      sidePanel={
        <AuthSidePanel
          eyebrow="Secure reset"
          heading={
            <>
              A fresh password.
              <br />
              The same clear path.
            </>
          }
          description="Confirm the six-digit code from your email, then create a password that only you know."
          showLogo
          showPattern
        />
      }
    >
      {email ? (
        <>
          <PageHeader title="Create a new password" subtitle={`We sent a six-digit code to ${email}.`} />
          <ResetPasswordForm email={email} />
        </>
      ) : (
        <div className="wp-reset-password-page__missing-email">
          <PageHeader title="Reset link incomplete" subtitle="Start again so we know where to send your secure code." />
          <ValidationMessage message="This reset link does not include an email address." variant="error" />
          <Link to="/forgot-password" className="wp-reset-password-page__link">
            Request a new reset code
          </Link>
        </div>
      )}
    </AuthLayout>
  );
}
