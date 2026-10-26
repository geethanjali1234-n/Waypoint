import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import WaypointMark from '../../components/branding/WaypointMark';
import './DemoWorkspaceShell.css';

const NAVIGATION = {
  'my-hr': [
    { label: 'Overview', href: '/demo/my-hr' },
    { label: 'Open roles', href: '/demo/my-hr#roles' },
    { label: 'Applicants', href: '/demo/my-hr#pipeline' },
    { label: 'Events', href: '/demo/my-hr#events' },
  ],
  'job-seeker': [
    { label: 'Overview', href: '/demo/job-seeker' },
    { label: 'Recommended roles', href: '/demo/job-seeker#recommended' },
    { label: 'Applications', href: '/demo/job-seeker#applications' },
    { label: 'Events', href: '/demo/job-seeker#events' },
  ],
  employee: [
    { label: 'Overview', href: '/demo/employee' },
    { label: 'My profile', href: '/demo/employee#profile' },
    { label: 'Timesheets', href: '/demo/employee#timesheets' },
    { label: 'Paystubs', href: '/demo/employee#paystubs' },
    { label: 'Notifications', href: '/demo/employee#notifications' },
  ],
};

const PERSONAS = {
  'my-hr': {
    label: 'MY HR',
    role: 'Employer workspace',
    name: 'Meridian Labs',
    initials: 'ML',
    accent: 'green',
  },
  'job-seeker': {
    label: 'Job Seeker',
    role: 'Candidate workspace',
    name: 'Ramya',
    initials: 'R',
    accent: 'blue',
  },
  employee: {
    label: 'EMPLOYEE',
    role: 'My HR workspace',
    name: 'John Carter',
    initials: 'JC',
    accent: 'green',
  },
};

function GridIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="3" width="5" height="5" rx="1" /><rect x="12" y="3" width="5" height="5" rx="1" /><rect x="3" y="12" width="5" height="5" rx="1" /><rect x="12" y="12" width="5" height="5" rx="1" /></svg>;
}

function BriefcaseIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="6" width="14" height="10" rx="2" fill="none" /><path d="M7 6V4.5A1.5 1.5 0 0 1 8.5 3h3A1.5 1.5 0 0 1 13 4.5V6M3 10h14M8.5 10v2h3v-2" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.4" /></svg>;
}

function UsersIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="7" cy="7" r="2.5" fill="none" /><circle cx="14" cy="8" r="2" fill="none" /><path d="M2.8 16a4.2 4.2 0 0 1 8.4 0M12 14.5a3.6 3.6 0 0 1 5.2 1.2" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="1.4" /></svg>;
}

function CalendarIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="4.5" width="14" height="12" rx="2" fill="none" /><path d="M6.5 3v3M13.5 3v3M3 8h14" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="1.4" /><path d="M6.5 11h.01M10 11h.01M13.5 11h.01M6.5 14h.01M10 14h.01" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="2" /></svg>;
}

function DocumentIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 2.8h6l4 4v10.4H5z" fill="none" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.4" /><path d="M11 2.8v4h4M7.5 10h5M7.5 13h5" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="1.4" /></svg>;
}

function BellIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5.2 14.5h9.6l-1.1-1.6V9a3.7 3.7 0 0 0-7.4 0v3.9z" fill="none" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.4" /><path d="M8.3 16.1a1.8 1.8 0 0 0 3.4 0" fill="none" stroke="currentColor" strokeLinecap="round" strokeWidth="1.4" /></svg>;
}

function navIcon(label) {
  if (label === 'Overview') return <GridIcon />;
  if (label === 'Open roles' || label === 'Recommended roles') return <BriefcaseIcon />;
  if (label === 'Applicants' || label === 'Applications') return <UsersIcon />;
  if (label === 'My profile') return <UsersIcon />;
  if (label === 'Paystubs') return <DocumentIcon />;
  if (label === 'Notifications') return <BellIcon />;
  return <CalendarIcon />;
}

export function DemoWorkspaceRoute({ persona, children }) {
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (localStorage.getItem('waypointDemoPersona') !== persona) {
      navigate(`/demo/login?persona=${persona}`, { replace: true });
      return;
    }

    setReady(true);
  }, [navigate, persona]);

  if (!ready) return null;

  return <DemoWorkspaceShell persona={persona}>{children}</DemoWorkspaceShell>;
}

export default function DemoWorkspaceShell({ persona, children }) {
  const navigate = useNavigate();
  const location = useLocation();
  const profile = PERSONAS[persona];

  const exitDemo = () => {
    localStorage.removeItem('waypointDemoPersona');
    navigate(`/demo/login?persona=${persona}`);
  };

  return (
    <div className={`demo-workspace demo-workspace--${profile.accent}`}>
      <aside className="demo-workspace__sidebar">
        <Link to={`/demo/${persona}`} className="demo-workspace__brand" aria-label={`Go to ${profile.label} demo overview`}>
          <WaypointMark />
          <span>
            <strong>{profile.label}</strong>
            <small>Waypoint demo</small>
          </span>
        </Link>

        <div className="demo-workspace__sidebar-label">Workspace</div>
        <nav className="demo-workspace__nav" aria-label={`${profile.label} demo navigation`}>
          {NAVIGATION[persona].map((item) => {
            const isActive = item.label === 'Overview'
              ? location.pathname === `/demo/${persona}` && !location.hash
              : location.hash === item.href.split('#')[1];

            return (
              <a key={item.label} href={item.href} className={isActive ? 'is-active' : ''}>
                {navIcon(item.label)}
                <span>{item.label}</span>
              </a>
            );
          })}
        </nav>

        <div className="demo-workspace__sidebar-bottom">
          <div className="demo-workspace__sidebar-label">Preview</div>
          <p>Sample data is used throughout this workspace.</p>
          <button type="button" onClick={exitDemo} className="demo-workspace__exit">Exit demo</button>
        </div>
      </aside>

      <div className="demo-workspace__main">
        <header className="demo-workspace__header">
          <div className="demo-workspace__breadcrumb">
            <span>Waypoint</span><b>/</b><strong>{profile.label}</strong>
          </div>
          <div className="demo-workspace__header-actions">
            <span className="demo-workspace__demo-pill"><i aria-hidden="true" /> Demo mode</span>
            <button type="button" className="demo-workspace__notification" aria-label="Notifications">
              <span aria-hidden="true">♧</span>
              <b>2</b>
            </button>
            <button type="button" className="demo-workspace__account" onClick={exitDemo} title="Exit demo">
              <span className="demo-workspace__avatar">{profile.initials}</span>
              <span className="demo-workspace__account-copy">
                <strong>{profile.name}</strong>
                <small>{profile.role}</small>
              </span>
              <span className="demo-workspace__account-chevron" aria-hidden="true">⌄</span>
            </button>
          </div>
        </header>

        <main className="demo-workspace__content">{children}</main>
      </div>
    </div>
  );
}

export { PERSONAS };
