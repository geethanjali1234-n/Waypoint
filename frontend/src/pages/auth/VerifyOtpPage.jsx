import { useSearchParams } from 'react-router-dom';
import { TwoColumnLayout, PageHeader } from '../../components';
import AuthSidePanel from './components/AuthSidePanel';
import VerifyOtpForm from './components/VerifyOtpForm';
import './VerifyOtpPage.css';

/**
 * VerifyOtpPage - route page for confirming the email verification code sent
 * during registration. Reads the email (and userId, carried for reference)
 * from the query string set by the registration flow.
 *
 * @example
 * <Route path="/verify-email" element={<VerifyOtpPage />} />
 */
export default function VerifyOtpPage() {
  const [searchParams] = useSearchParams();
  const email = searchParams.get('email') ?? '';

  return (
    <div className="wp-verify-otp-page">
      <TwoColumnLayout
        ratio="1:1"
        className="wp-verify-otp-page__columns"
        main={
          <AuthSidePanel
            heading="Almost there."
            description="Verify your email to activate your Waypoint account and start posting jobs and events."
          />
        }
        side={
          <div className="wp-verify-otp-page__form-panel">
            <PageHeader
              title="Verify your email"
              subtitle={
                email
                  ? `Enter the 6-digit code we sent to ${email}`
                  : 'Enter the 6-digit code we sent to your email'
              }
            />
            <VerifyOtpForm email={email} />
          </div>
        }
      />
    </div>
  );
}
