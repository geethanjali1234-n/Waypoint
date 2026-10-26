import { useNavigate } from 'react-router-dom';
import { TwoColumnLayout, PageHeader, SegmentedControl } from '../../components';
import AuthSidePanel from './components/AuthSidePanel';
import EmployerRegistrationForm from './components/EmployerRegistrationForm';
import './EmployerRegistrationPage.css';

const REGISTRATION_TYPE_OPTIONS = [
  { label: 'Job Seeker', value: 'JOB_SEEKER' },
  { label: 'MY HR', value: 'EMPLOYER' },
];

/**
 * EmployerRegistrationPage - route page for employer account creation.
 * Includes the Job Seeker/Employer toggle at the top of the form panel - the
 * URL stays the source of truth, so switching the toggle navigates to
 * /register/job-seeker rather than swapping content in place.
 *
 * @example
 * <Route path="/register/employer" element={<EmployerRegistrationPage />} />
 */
export default function EmployerRegistrationPage() {
  const navigate = useNavigate();

  return (
    <div className="wp-employer-registration-page">
      <TwoColumnLayout
        ratio="1:1"
        className="wp-employer-registration-page__columns"
        main={
          <AuthSidePanel
            workspaceLabel="MY HR"
            heading="Reach the right candidates, not just more of them."
            description="Post once, meet qualified candidates nearby, and pair postings with local hiring events to shorten the path to a first conversation."
          />
        }
        side={
          <div className="wp-employer-registration-page__form-panel">
            <SegmentedControl
              options={REGISTRATION_TYPE_OPTIONS}
              value="EMPLOYER"
              onChange={(value) => {
                if (value === 'JOB_SEEKER') {
                  navigate('/register/job-seeker');
                }
              }}
              className="wp-employer-registration-page__toggle"
            />
            <PageHeader
              title="Create your account"
              subtitle="Company details first - then post your first opening in minutes."
            />
            <EmployerRegistrationForm />
          </div>
        }
      />
    </div>
  );
}
