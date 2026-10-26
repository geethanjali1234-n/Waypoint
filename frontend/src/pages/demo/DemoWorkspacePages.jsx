import { DemoWorkspaceRoute } from './DemoWorkspaceShell';
import './DemoWorkspacePages.css';

const hrMetrics = [
  { label: 'Open roles', value: '12', detail: '+2 this month', trend: 'up' },
  { label: 'New applicants', value: '48', detail: '9 need review', trend: 'up' },
  { label: 'Interviews', value: '08', detail: 'This week', trend: 'neutral' },
  { label: 'Upcoming events', value: '03', detail: 'Next: Aug 24', trend: 'neutral' },
];

const hrPipeline = [
  { initials: 'AK', name: 'Aarav Kumar', role: 'Frontend Engineer', status: 'New applicant', tone: 'new', time: '12 min ago' },
  { initials: 'MS', name: 'Maya Singh', role: 'Product Designer', status: 'Interview scheduled', tone: 'scheduled', time: 'Today, 10:30 AM' },
  { initials: 'JL', name: 'Jordan Lee', role: 'Operations Lead', status: 'Review profile', tone: 'review', time: 'Yesterday' },
];

const hrRoles = [
  { title: 'Frontend Engineer', location: 'Grand Rapids, MI · Hybrid', applicants: '18 applicants', status: 'Active' },
  { title: 'Product Designer', location: 'Remote · United States', applicants: '12 applicants', status: 'Active' },
  { title: 'Operations Lead', location: 'Chicago, IL · On-site', applicants: '7 applicants', status: 'Draft' },
];

const jobMetrics = [
  { label: 'Profile completion', value: '86%', detail: 'Almost there', trend: 'progress' },
  { label: 'Active applications', value: '04', detail: '2 in review', trend: 'up' },
  { label: 'Saved roles', value: '09', detail: '3 new matches', trend: 'up' },
  { label: 'Upcoming events', value: '02', detail: 'Next: Aug 24', trend: 'neutral' },
];

const recommendedRoles = [
  { company: 'Northstar Health', title: 'Junior Software Developer', location: 'Grand Rapids, MI · Hybrid', match: '94% match', tag: 'Recommended', color: 'green' },
  { company: 'Brightline Studio', title: 'Frontend Developer', location: 'Remote · United States', match: '89% match', tag: 'New today', color: 'violet' },
  { company: 'Waypoint Labs', title: 'UI Engineer', location: 'Detroit, MI · Hybrid', match: '83% match', tag: 'Good fit', color: 'blue' },
];

const applications = [
  { company: 'Northstar Health', title: 'Junior Software Developer', status: 'In review', tone: 'review', date: 'Applied Aug 12' },
  { company: 'Waypoint Labs', title: 'UI Engineer', status: 'Interview', tone: 'scheduled', date: 'Updated Aug 10' },
  { company: 'Brightline Studio', title: 'Frontend Developer', status: 'Submitted', tone: 'new', date: 'Applied Aug 06' },
];

function Arrow() {
  return <span aria-hidden="true">→</span>;
}

function MetricGrid({ metrics }) {
  return (
    <div className="demo-page__metrics">
      {metrics.map((metric) => (
        <article className="demo-page__metric" key={metric.label}>
          <p>{metric.label}</p>
          <strong>{metric.value}</strong>
          <span className={`demo-page__metric-detail demo-page__metric-detail--${metric.trend}`}>
            {metric.trend === 'up' && '↗ '}{metric.detail}
          </span>
        </article>
      ))}
    </div>
  );
}

function DemoPageHeading({ eyebrow, title, description, action }) {
  return (
    <section className="demo-page__heading">
      <div>
        <p className="demo-page__eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="demo-page__heading-description">{description}</p>
      </div>
      {action}
    </section>
  );
}

export function DemoMyHrPage() {
  return (
    <DemoWorkspaceRoute persona="my-hr">
      <div className="demo-page demo-page--hr">
        <DemoPageHeading
          eyebrow="MY HR · Tuesday, August 19"
          title="Good morning, Meridian Labs."
          description="Here is the clearest view of what is moving in your hiring workspace."
          action={<a href="#roles" className="demo-page__primary-button">Post a new role <Arrow /></a>}
        />

        <MetricGrid metrics={hrMetrics} />

        <section className="demo-page__callout" aria-label="Hiring workspace message">
          <div>
            <p className="demo-page__callout-label">A better hiring rhythm</p>
            <h2>Good teams begin with a clear opportunity and a meaningful connection.</h2>
          </div>
          <span className="demo-page__callout-mark" aria-hidden="true">W</span>
        </section>

        <div className="demo-page__two-column">
          <section id="pipeline" className="demo-page__panel">
            <div className="demo-page__panel-heading">
              <div><p className="demo-page__eyebrow">Keep moving</p><h2>Hiring pipeline</h2></div>
              <a href="#roles">View all <Arrow /></a>
            </div>
            <div className="demo-page__pipeline-list">
              {hrPipeline.map((candidate) => (
                <article className="demo-page__pipeline-row" key={candidate.name}>
                  <span className="demo-page__person-avatar">{candidate.initials}</span>
                  <div className="demo-page__pipeline-copy"><strong>{candidate.name}</strong><span>{candidate.role}</span></div>
                  <div className="demo-page__pipeline-status"><span className={`demo-page__status demo-page__status--${candidate.tone}`}>{candidate.status}</span><small>{candidate.time}</small></div>
                </article>
              ))}
            </div>
          </section>

          <section id="events" className="demo-page__panel demo-page__events-panel">
            <div className="demo-page__panel-heading">
              <div><p className="demo-page__eyebrow">Coming up</p><h2>Career events</h2></div>
              <a href="#events">See all <Arrow /></a>
            </div>
            <article className="demo-page__event-card">
              <div className="demo-page__event-date"><strong>24</strong><span>AUG</span></div>
              <div><strong>Tech & Talent Meetup</strong><p>Meridian Hall · 6:00 PM</p><span className="demo-page__status demo-page__status--scheduled">12 registered</span></div>
            </article>
            <article className="demo-page__event-card">
              <div className="demo-page__event-date"><strong>31</strong><span>AUG</span></div>
              <div><strong>Women in Product panel</strong><p>Virtual · 12:00 PM</p><span className="demo-page__status demo-page__status--review">Planning</span></div>
            </article>
          </section>
        </div>

        <section id="roles" className="demo-page__panel demo-page__roles-panel">
          <div className="demo-page__panel-heading">
            <div><p className="demo-page__eyebrow">Your workspace</p><h2>Open roles</h2></div>
            <a href="#pipeline">Manage postings <Arrow /></a>
          </div>
          <div className="demo-page__role-list">
            {hrRoles.map((role) => (
              <article className="demo-page__role-row" key={role.title}>
                <span className="demo-page__role-icon" aria-hidden="true">↗</span>
                <div><strong>{role.title}</strong><span>{role.location}</span></div>
                <span className="demo-page__role-applicants">{role.applicants}</span>
                <span className={`demo-page__status demo-page__status--${role.status === 'Active' ? 'scheduled' : 'review'}`}>{role.status}</span>
              </article>
            ))}
          </div>
        </section>
      </div>
    </DemoWorkspaceRoute>
  );
}

export function DemoJobSeekerPage() {
  return (
    <DemoWorkspaceRoute persona="job-seeker">
      <div className="demo-page demo-page--job-seeker">
        <DemoPageHeading
          eyebrow="JOB SEEKER · Tuesday, August 19"
          title="Good morning, Ramya."
          description="Your next step is taking shape. Here is what is worth your attention today."
          action={<a href="#recommended" className="demo-page__primary-button">Explore careers <Arrow /></a>}
        />

        <MetricGrid metrics={jobMetrics} />

        <section className="demo-page__candidate-callout" aria-label="Job seeker workspace message">
          <div>
            <p className="demo-page__callout-label">Your path, your pace</p>
            <h2>The right opportunity starts with a meaningful connection.</h2>
            <a href="#recommended">See your recommended roles <Arrow /></a>
          </div>
          <div className="demo-page__progress-ring" aria-label="Profile completion 86 percent"><strong>86%</strong><span>profile<br />complete</span></div>
        </section>

        <section id="recommended" className="demo-page__panel demo-page__recommended-panel">
          <div className="demo-page__panel-heading">
            <div><p className="demo-page__eyebrow">Picked for you</p><h2>Recommended roles</h2></div>
            <a href="#recommended">Browse careers <Arrow /></a>
          </div>
          <div className="demo-page__recommended-grid">
            {recommendedRoles.map((role) => (
              <article className="demo-page__recommended-card" key={role.title}>
                <div className="demo-page__company-line"><span className={`demo-page__company-logo demo-page__company-logo--${role.color}`}>{role.company.charAt(0)}</span><span>{role.company}</span></div>
                <span className="demo-page__match">{role.match}</span>
                <h3>{role.title}</h3>
                <p>{role.location}</p>
                <span className="demo-page__recommended-tag">{role.tag}</span>
                <a href="#applications" className="demo-page__card-link">View role <Arrow /></a>
              </article>
            ))}
          </div>
        </section>

        <div className="demo-page__two-column">
          <section id="applications" className="demo-page__panel">
            <div className="demo-page__panel-heading">
              <div><p className="demo-page__eyebrow">Stay in the loop</p><h2>Your applications</h2></div>
              <a href="#applications">See all <Arrow /></a>
            </div>
            <div className="demo-page__application-list">
              {applications.map((application) => (
                <article className="demo-page__application-row" key={`${application.company}-${application.title}`}>
                  <div><strong>{application.title}</strong><span>{application.company}</span></div>
                  <div><span className={`demo-page__status demo-page__status--${application.tone}`}>{application.status}</span><small>{application.date}</small></div>
                </article>
              ))}
            </div>
          </section>

          <section id="events" className="demo-page__panel demo-page__job-events-panel">
            <div className="demo-page__panel-heading">
              <div><p className="demo-page__eyebrow">Meet people</p><h2>Upcoming events</h2></div>
              <a href="#events">Browse all <Arrow /></a>
            </div>
            <article className="demo-page__job-event"><span className="demo-page__event-date"><strong>24</strong><span>AUG</span></span><div><strong>Tech & Talent Meetup</strong><p>Meridian Hall · 6:00 PM</p></div><Arrow /></article>
            <article className="demo-page__job-event"><span className="demo-page__event-date"><strong>31</strong><span>AUG</span></span><div><strong>Women in Product panel</strong><p>Virtual · 12:00 PM</p></div><Arrow /></article>
          </section>
        </div>
      </div>
    </DemoWorkspaceRoute>
  );
}
