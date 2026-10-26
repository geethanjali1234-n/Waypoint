import { useEffect } from 'react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { ActionCard, Card, DashboardGrid, EmptyState, StatCard, WelcomeBanner } from '../components';
import './EmployerDashboardPage.css';

const gettingStartedItems = [
  {
    title: 'Post a job',
    description: 'Reach ranked candidate matches',
    action: 'New posting →',
    href: '/employer/jobs/new',
  },
  {
    title: 'Schedule an event',
    description: 'Meet candidates near you',
    action: 'New event →',
    href: '/employer/events/new',
  },
  {
    title: 'Complete your profile',
    description: 'Help candidates learn about your company',
    action: 'View profile →',
    href: '/employer/profile',
  },
];

const waypointSteps = [
  { number: '1', title: 'Post', description: 'Publish a role with the skills you need.' },
  { number: '2', title: 'Match', description: 'Review applications related to your jobs.' },
  { number: '3', title: 'Hire', description: 'Move qualified candidates forward.' },
];

/**
 * EmployerDashboardPage - content-only dashboard view, rendered inside
 * EmployerLayout's <Outlet>. Dashboard data (company info, job/application
 * counts) is fetched once by EmployerLayout and passed down via Outlet
 * context, so this component issues no API requests of its own.
 */
export default function EmployerDashboardPage() {
  const navigate = useNavigate();
  const dashboard = useOutletContext();
  const { refreshDashboard } = dashboard;
  const count = (value) => Number.isFinite(Number(value)) ? Number(value) : 0;

  useEffect(() => {
    refreshDashboard();
  }, [refreshDashboard]);

  const primarySummary = [
    { label: 'Open Postings', value: count(dashboard.openJobsCount) },
    { label: 'Total Applications', value: count(dashboard.totalApplicationsCount) },
    { label: 'Upcoming Events', value: count(dashboard.upcomingEventsCount) },
    { label: 'Event Registrants', value: count(dashboard.totalRegistrationsCount) },
  ];

  return (
    <div className="employer-dashboard__container">
      <section className="employer-dashboard__greeting" aria-labelledby="dashboard-greeting">
        <div>
          <p className="employer-dashboard__persona">MY HR workspace</p>
          <h1 id="dashboard-greeting">
            Hello{dashboard.companyName ? `, ${dashboard.companyName}` : ''}
          </h1>
          <p>Here’s an overview of your hiring activity.</p>
        </div>
      </section>

      <WelcomeBanner
        className="employer-dashboard__quote"
        title="Great teams begin with a clear opportunity and a meaningful connection."
        subtitle="Build your next team with Waypoint."
      />

      <section className="employer-dashboard__section" aria-label="Hiring summary">
        <DashboardGrid columns={4}>
          {primarySummary.map((stat) => (
            <StatCard key={stat.label} value={stat.value} label={stat.label} />
          ))}
        </DashboardGrid>
      </section>

      <section className="employer-dashboard__feature-grid" aria-label="Employer guidance">
        <Card title="Get Started" className="employer-dashboard__feature-card">
          <div className="employer-dashboard__action-list">
            {gettingStartedItems.map((item, index) => <ActionCard
              key={item.title}
              eyebrow={`Step ${index + 1}`}
              title={item.title}
              description={item.description}
              actionLabel={item.action.replace(' →', '')}
              onClick={item.href ? () => navigate(item.href) : undefined}
              disabled={!item.href}
            />)}
          </div>
        </Card>

        <Card title="How Waypoint Works" className="employer-dashboard__feature-card">
          <div className="employer-dashboard__steps">
            {waypointSteps.map((step) => (
              <div className="employer-dashboard__step" key={step.title}>
                <span className="employer-dashboard__step-number">{step.number}</span>
                <div>
                  <h3>{step.title}</h3>
                  <p>{step.description}</p>
                </div>
              </div>
            ))}
          </div>
        </Card>
      </section>

      <section className="employer-dashboard__details" aria-labelledby="recent-activity-heading">
        <h2 id="recent-activity-heading">Recent activity</h2>
        <div className="employer-dashboard__recent-grid">
          <RecentItemsCard title="Recent Job Postings" items={dashboard.recentJobs} emptyTitle="No job postings to display" />
          <RecentItemsCard title="Recent Events" items={dashboard.recentEvents} emptyTitle="No events to display" />
          <RecentItemsCard title="Recent Notifications" items={dashboard.recentNotifications} emptyTitle="No notifications to display" />
        </div>
      </section>
    </div>
  );
}

function RecentItemsCard({ title, items, emptyTitle }) {
  return (
    <Card title={title}>
      {items?.length ? (
        <ul className="employer-dashboard__recent-list">
          {items.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
        </ul>
      ) : (
        <EmptyState title={emptyTitle} description="New activity will appear here." />
      )}
    </Card>
  );
}
