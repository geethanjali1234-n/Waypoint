import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ActionCard, Card, EmptyState, Loader, WelcomeBanner } from '../components';
import jobSeekerDashboardApi from '../api/jobSeekerDashboardApi';
import './EmployerDashboardPage.css';
import './JobSeekerDashboardPage.css';

const jobSeekerActions = [
  {
    title: 'Browse careers',
    description: 'Explore roles matched to your interests and experience.',
    action: 'Explore careers →',
    href: '/careers',
  },
  {
    title: 'Find events',
    description: 'Connect with employers at upcoming career events.',
    action: 'Browse events →',
    href: '/events',
  },
  {
    title: 'View your profile',
    description: 'See your profile, completion status, and recent activity.',
    action: 'View profile →',
    href: '/job-seeker/profile',
  },
];

const jobSeekerSteps = [
  { number: '1', title: 'Build', description: 'Complete your profile with the skills you bring.' },
  { number: '2', title: 'Discover', description: 'Explore relevant jobs and nearby career events.' },
  { number: '3', title: 'Connect', description: 'Apply for roles or register to meet employers.' },
];

export default function JobSeekerDashboardPage() {
  const navigate = useNavigate();
  const [dashboard, setDashboard] = useState(null);
  const [status, setStatus] = useState('loading');

  useEffect(() => {
    jobSeekerDashboardApi.getDashboard()
      .then((response) => {
        setDashboard(response.data.data);
        setStatus('success');
      })
      .catch(() => setStatus('error'));
  }, []);

  if (status === 'loading') {
    return <div className="employer-dashboard__container"><Loader size="lg" label="Loading your dashboard…" /></div>;
  }

  if (status === 'error') {
    return (
      <div className="employer-dashboard__container">
        <EmptyState title="Unable to load dashboard" description="Please refresh the page and try again." />
      </div>
    );
  }

  return (
    <div className="employer-dashboard__container">
      <section className="employer-dashboard__greeting job-seeker-dashboard__greeting" aria-labelledby="job-seeker-dashboard-greeting">
        <div>
          <p className="employer-dashboard__persona">Job Seeker workspace</p>
          <h1 id="job-seeker-dashboard-greeting">
            Hello{dashboard?.firstName ? `, ${dashboard.firstName}` : ''}
          </h1>
          <p>Here’s an overview of your career journey.</p>
        </div>
      </section>

      <WelcomeBanner
        className="employer-dashboard__quote"
        title="The right opportunity starts with a meaningful connection."
        subtitle="Find your next step with Waypoint."
      />

      <section className="employer-dashboard__feature-grid" aria-label="Job Seeker guidance">
        <Card title="Get Started" className="employer-dashboard__feature-card">
          <div className="employer-dashboard__action-list">
            {jobSeekerActions.map((item, index) => <ActionCard
              key={item.title}
              eyebrow={`Step ${index + 1}`}
              title={item.title}
              description={item.description}
              actionLabel={item.action.replace(' →', '')}
              onClick={() => navigate(item.href)}
            />)}
          </div>
        </Card>

        <Card title="How Waypoint Works" className="employer-dashboard__feature-card">
          <div className="employer-dashboard__steps">
            {jobSeekerSteps.map((step) => (
              <div className="employer-dashboard__step" key={step.title}>
                <span className="employer-dashboard__step-number">{step.number}</span>
                <div><h3>{step.title}</h3><p>{step.description}</p></div>
              </div>
            ))}
          </div>
        </Card>
      </section>
    </div>
  );
}
