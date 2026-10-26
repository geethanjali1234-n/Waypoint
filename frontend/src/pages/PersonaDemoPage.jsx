import { Link } from 'react-router-dom';
import WaypointMark from '../components/branding/WaypointMark';
import './PersonaDemoPage.css';

const workspaces = [
  {
    key: 'my-hr',
    eyebrow: 'Employer workspace',
    title: 'MY HR',
    description: 'A focused hiring desk for teams that want to post clearly, review applicants, and build real connections.',
    bullets: ['Hiring dashboard and live counts', 'Job postings and applicant review', 'Career events and registrants'],
    signInHref: '/demo/login?persona=my-hr',
    registerHref: '/register/employer',
  },
  {
    key: 'employee',
    eyebrow: 'My HR employee workspace',
    title: 'Employee',
    description: 'A focused home for employee details, timesheets, paystubs, profile requests, and manager updates.',
    bullets: ['Timesheets routed to your manager', 'Paystub preview and download', 'Profile requests and notifications'],
    signInHref: '/demo/login?persona=employee',
    registerHref: '/live-login?persona=employee',
  },
  {
    key: 'job-seeker',
    eyebrow: 'Candidate workspace',
    title: 'Job Seeker',
    description: 'A clear path to nearby opportunities, career events, applications, and a profile that represents you well.',
    bullets: ['Career search and job details', 'Applications and status updates', 'Profile, resume, and events'],
    signInHref: '/demo/login?persona=job-seeker',
    registerHref: '/register/job-seeker',
  },
];

function ArrowIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="M4 10h11M10.5 5.5 15 10l-4.5 4.5" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.7" />
    </svg>
  );
}

export default function PersonaDemoPage() {
  return (
    <main className="persona-demo">
      <div className="persona-demo__glow persona-demo__glow--top" aria-hidden="true" />
      <div className="persona-demo__glow persona-demo__glow--bottom" aria-hidden="true" />

      <header className="persona-demo__header">
        <Link to="/demo" className="persona-demo__brand" aria-label="Back to the Waypoint demo">
          <WaypointMark />
          <span>Waypoint</span>
        </Link>
        <div className="persona-demo__header-note">
          <span className="persona-demo__status-dot" aria-hidden="true" />
          Two connected workspaces
        </div>
      </header>

      <section className="persona-demo__hero" aria-labelledby="persona-demo-title">
        <div className="persona-demo__hero-copy">
          <p className="persona-demo__eyebrow">Waypoint workspace demo</p>
          <h1 id="persona-demo-title">One platform.<br /><em>Two clear paths.</em></h1>
          <p className="persona-demo__lede">
            Choose the experience that matches your next step. The demo login opens a complete role-based workspace with sample data, while the live account flow stays one click away.
          </p>
          <div className="persona-demo__hero-links">
            <a href="#workspaces" className="persona-demo__text-link">Explore workspaces <ArrowIcon /></a>
            <Link to="/live-login" className="persona-demo__quiet-link">Already have an account?</Link>
          </div>
        </div>

        <div className="persona-demo__preview" aria-label="Workspace preview">
          <div className="persona-demo__preview-topline">
            <span>Live workspace preview</span>
            <span className="persona-demo__preview-menu" aria-hidden="true">•••</span>
          </div>
          <div className="persona-demo__preview-body">
            <div className="persona-demo__preview-heading">
              <span className="persona-demo__mini-mark">W</span>
              <div>
                <p>Connected by Waypoint</p>
                <strong>People meet opportunity here.</strong>
              </div>
            </div>
            <div className="persona-demo__preview-metrics">
              <div><strong>MY HR</strong><span>hire with intention</span></div>
              <div><strong>JOB SEEKER</strong><span>find your next step</span></div>
            </div>
            <div className="persona-demo__preview-path" aria-hidden="true">
              <span />
              <span />
              <span />
              <span />
            </div>
          </div>
        </div>
      </section>

      <section id="workspaces" className="persona-demo__workspaces" aria-labelledby="workspace-heading">
        <div className="persona-demo__section-heading">
          <div>
            <p className="persona-demo__eyebrow">Select your path</p>
            <h2 id="workspace-heading">Where are you headed?</h2>
          </div>
          <p>Use the same Waypoint account system for a role-specific experience.</p>
        </div>

        <div className="persona-demo__cards">
          {workspaces.map((workspace) => (
            <article className={`persona-demo__card persona-demo__card--${workspace.key}`} key={workspace.key}>
              <div className="persona-demo__card-topline">
                <span className="persona-demo__card-icon" aria-hidden="true">
                  {workspace.key === 'my-hr' ? 'H' : 'J'}
                </span>
                <span>{workspace.eyebrow}</span>
              </div>
              <h3>{workspace.title}</h3>
              <p className="persona-demo__card-description">{workspace.description}</p>
              <ul>
                {workspace.bullets.map((bullet) => <li key={bullet}>{bullet}</li>)}
              </ul>
              <div className="persona-demo__card-actions">
                <Link to={workspace.signInHref} className="persona-demo__primary-action">Open demo <ArrowIcon /></Link>
                <Link to={workspace.registerHref} className="persona-demo__secondary-action">Create account</Link>
              </div>
            </article>
          ))}
        </div>
      </section>

      <footer className="persona-demo__footer">
        <span>MY HR</span>
        <i aria-hidden="true" />
        <span>Job Seeker</span>
        <i aria-hidden="true" />
        <span>Waypoint</span>
      </footer>
    </main>
  );
}
