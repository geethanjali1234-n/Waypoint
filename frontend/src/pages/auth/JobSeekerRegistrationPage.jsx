import { useNavigate } from 'react-router-dom';
import { TwoColumnLayout, PageHeader, SegmentedControl } from '../../components';
import AuthSidePanel from './components/AuthSidePanel';
import JobSeekerRegistrationForm from './components/JobSeekerRegistrationForm';
import './JobSeekerRegistrationPage.css';

const REGISTRATION_TYPE_OPTIONS = [
  { label: 'Job Seeker', value: 'JOB_SEEKER' },
  { label: 'MY HR', value: 'EMPLOYER' },
];

/**
 * JobSeekerRegistrationPage - route page for job seeker account creation.
 * Includes the Job Seeker/Employer toggle at the top of the form panel - the
 * URL stays the source of truth, so switching the toggle navigates to
 * /register/employer rather than swapping content in place.
 *
 * @example
 * <Route path="/register/job-seeker" element={<JobSeekerRegistrationPage />} />
 */
export default function JobSeekerRegistrationPage() {
  const navigate = useNavigate();

  return (
    <div className="wp-job-seeker-registration-page">
      <TwoColumnLayout
        ratio="1:1"
        className="wp-job-seeker-registration-page__columns"
        main={
          <AuthSidePanel
            workspaceLabel="Job Seeker"
            heading="Tell us what you do. We'll find what's nearby."
            description="Add your skills once and get matched to roles and events near you - not just keyword matches."
          />
        }
        side={
          <div className="wp-job-seeker-registration-page__form-panel">
            <SegmentedControl
              options={REGISTRATION_TYPE_OPTIONS}
              value="JOB_SEEKER"
              onChange={(value) => {
                if (value === 'EMPLOYER') {
                  navigate('/register/employer');
                }
              }}
              className="wp-job-seeker-registration-page__toggle"
            />
            <PageHeader
              title="Create your account"
              subtitle="Tell us about yourself - then start matching with roles nearby."
            />
            <JobSeekerRegistrationForm />
          </div>
        }
      />
    </div>
  );
}
