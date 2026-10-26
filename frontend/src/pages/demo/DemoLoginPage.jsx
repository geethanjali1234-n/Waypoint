import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import WaypointMark from '../../components/branding/WaypointMark';
import './DemoLoginPage.css';

const DEMO_PERSONAS = {
  'my-hr': {
    label: 'MY HR',
    role: 'Employer workspace',
    email: 'demo@myhr.waypoint.test',
    password: 'myhr-demo',
    title: 'Turn good intentions into a stronger team.',
    description: 'See the hiring side of Waypoint with sample roles, applicants, events, and a clear daily overview.',
    accent: 'green',
  },
  employee: {
    label: 'Employee',
    role: 'My HR workspace',
    email: 'td003@tardus.waypoint.test',
    password: 'employee-demo',
    title: 'Keep your workday moving.',
    description: 'See the employee side of My HR with profile details, manager-routed timesheets, paystubs, and notifications.',
    accent: 'green',
  },
  'job-seeker': {
    label: 'Job Seeker',
    role: 'Candidate workspace',
    email: 'demo@jobseeker.waypoint.test',
    password: 'jobseeker-demo',
    title: 'Find a next step that feels like yours.',
    description: 'Explore the candidate side of Waypoint with sample recommendations, applications, events, and profile progress.',
    accent: 'blue',
  },
};

function getPersona(value) {
  if (value === 'employee') return 'employee';
  return value === 'job-seeker' ? 'job-seeker' : 'my-hr';
}

export default function DemoLoginPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [persona, setPersona] = useState(getPersona(searchParams.get('persona')));
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const profile = DEMO_PERSONAS[persona];

  useEffect(() => {
    setEmail(profile.email);
    setPassword(profile.password);
  }, [profile.email, profile.password]);

  const selectPersona = (nextPersona) => {
    setPersona(nextPersona);
    setSearchParams({ persona: nextPersona });
  };

  const enterDemo = (event) => {
    event.preventDefault();
    localStorage.setItem('waypointDemoPersona', persona);
    navigate(`/demo/${persona}`);
  };

  return (
    <main className={`demo-login demo-login--${profile.accent}`}>
      <div className="demo-login__ambient demo-login__ambient--one" aria-hidden="true" />
      <div className="demo-login__ambient demo-login__ambient--two" aria-hidden="true" />

      <header className="demo-login__header">
        <Link to="/demo" className="demo-login__brand" aria-label="Back to workspace selection">
          <WaypointMark />
          <span>Waypoint</span>
        </Link>
        <span className="demo-login__header-label">Interactive demo</span>
      </header>

      <div className="demo-login__layout">
        <section className="demo-login__story" aria-labelledby="demo-login-story-heading">
          <p className="demo-login__eyebrow">Demo access</p>
          <h1 id="demo-login-story-heading">{profile.title}</h1>
          <p className="demo-login__story-copy">{profile.description}</p>
          <div className="demo-login__story-note">
            <span className="demo-login__story-dot" aria-hidden="true" />
            Sample data only. No backend account required.
          </div>
        </section>

        <section className="demo-login__card" aria-labelledby="demo-login-heading">
          <div className="demo-login__card-topline">
            <span>Waypoint preview</span>
            <span className="demo-login__lock">Demo mode</span>
          </div>
          <h2 id="demo-login-heading">Choose your workspace</h2>
          <p className="demo-login__card-subtitle">Use the prefilled demo access below to step inside.</p>

          <div className="demo-login__persona-switch" role="tablist" aria-label="Choose demo workspace">
            {Object.entries(DEMO_PERSONAS).map(([key, item]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={persona === key}
                className={persona === key ? 'is-active' : ''}
                onClick={() => selectPersona(key)}
              >
                <span>{item.label}</span>
                <small>{item.role}</small>
              </button>
            ))}
          </div>

          <form className="demo-login__form" onSubmit={enterDemo}>
            <label htmlFor="demo-email">Demo email</label>
            <input id="demo-email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} />
            <label htmlFor="demo-password">Demo passcode</label>
            <input id="demo-password" type="text" value={password} onChange={(event) => setPassword(event.target.value)} />
            <button type="submit" className="demo-login__submit">
              Enter {profile.label} demo
              <span aria-hidden="true">→</span>
            </button>
          </form>

          <p className="demo-login__real-account">
            Need the real account flow? <Link to={`/live-login?persona=${persona}`}>Use live sign in</Link>
          </p>
        </section>
      </div>
    </main>
  );
}
