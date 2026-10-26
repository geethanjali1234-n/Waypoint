import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ActionCard, ProfileMenu } from '../../components';
import WaypointMark from '../../components/branding/WaypointMark';
import { clearMyHrSession, getMyHrSession } from './mockDirectory';
import './MyHrAdminPage.css';

const MODULES = {
  hr: {
    title: 'HR Management',
    description: 'Create HR users and manage HR accounts.',
    action: 'Create HR user',
    columns: ['Name', 'Type / Role', 'Detail', 'Status'],
    rows: [
      ['Pragna Rao', 'HR Administrator', 'pragna@tardus.com', 'Active'],
      ['Daniel Kim', 'HR Manager', 'daniel@tardus.com', 'Active'],
      ['Avery Stone', 'HR Specialist', 'avery@tardus.com', 'Pending'],
    ],
  },
  organization: {
    title: 'Organization Settings',
    description: 'Configure organization information and the company payroll period.',
    action: 'Edit organization',
    columns: ['Name', 'Type / Role', 'Detail', 'Status'],
    rows: [
      ['Tardus Inc', 'Company profile', 'Chicago, IL', 'Active'],
      ['Biweekly', 'Payroll period', 'Aug 12 – Aug 25', 'Active'],
    ],
  },
  employees: {
    title: 'Employee Management',
    description: 'View employees and their reporting relationships.',
    action: 'Add employee',
    columns: ['Name', 'Type / Role', 'Detail', 'Status'],
    rows: [
      ['Maya Rodriguez', 'Product Design', 'Daniel Kim', 'Active'],
      ['Noah Williams', 'Engineering', 'Sarah Chen', 'Active'],
      ['Emma Davis', 'Sales', 'Olivia Brown', 'Active'],
    ],
  },
  timesheets: {
    title: 'Timesheets',
    description: 'View HR and Employee timesheets across the organization.',
    action: 'View current period',
    columns: ['Name', 'Type / Role', 'Detail', 'Status'],
    rows: [
      ['Maya Rodriguez', 'Aug 12 – Aug 18', '40 hours', 'Approved'],
      ['Noah Williams', 'Aug 12 – Aug 18', '38 hours', 'Pending'],
      ['Emma Davis', 'Aug 12 – Aug 18', '40 hours', 'Approved'],
    ],
  },
  payroll: {
    title: 'Payroll',
    description: 'Run HR payroll and review organization payroll records.',
    action: 'Run payroll',
  },
  reports: {
    title: 'Reports',
    description: 'Generate workforce, timesheet, and payroll reports.',
    action: 'Generate report',
  },
  jobs: {
    title: 'Job Postings',
    description: 'Select a job posting to view and manage its applicants.',
    action: 'Create job',
  },
  events: {
    title: 'Events',
    description: 'Select an event to view and manage its registrations.',
    action: 'Create event',
  },
  audit: {
    title: 'Audit History',
    description: 'Review significant My HR actions across the organization.',
    action: 'Export audit history',
  },
};

const ADMIN_ACTIONS = [
  ['hr', 'HR Management', 'Create HR users and view, edit, activate, or deactivate HR accounts.'],
  ['organization', 'Organization Settings', 'Configure organization information and the company payroll period.'],
  ['employees', 'Employee Management', 'View all organization employees and their reporting relationships.'],
  ['timesheets', 'Timesheets', 'View HR and Employee timesheets across the organization.'],
  ['payroll', 'Payroll', 'Run HR payroll and review organization payroll records.'],
  ['reports', 'Reports', 'Generate organization-wide workforce, timesheet, and payroll reports.'],
  ['jobs', 'Job Postings', 'Select a posting to view its applicants and update their status.'],
  ['events', 'Events', 'Select an event to view its registrations and participant details.'],
  ['audit', 'Audit History', 'Review significant My HR actions across the organization.'],
];

const JOB_POSTINGS = [
  {
    id: 'senior-product-designer',
    title: 'Senior Product Designer',
    department: 'Design',
    location: 'Chicago, IL',
    status: 'Pending',
    applicants: [
      { name: 'Jordan Lee', email: 'jordan.lee@example.com', stage: 'Interview', applied: 'Aug 22, 2026' },
      { name: 'Casey Patel', email: 'casey.patel@example.com', stage: 'Review', applied: 'Aug 23, 2026' },
    ],
  },
  {
    id: 'data-engineer',
    title: 'Data Engineer',
    department: 'Engineering',
    location: 'Remote',
    status: 'Active',
    applicants: [
      { name: 'Taylor Smith', email: 'taylor.smith@example.com', stage: 'Screening', applied: 'Aug 21, 2026' },
    ],
  },
  {
    id: 'hr-business-partner',
    title: 'HR Business Partner',
    department: 'People',
    location: 'Dallas, TX',
    status: 'Active',
    applicants: [
      { name: 'Morgan Davis', email: 'morgan.davis@example.com', stage: 'Review', applied: 'Aug 20, 2026' },
    ],
  },
];

const EVENTS = [
  {
    id: 'career-networking-night',
    title: 'Career Networking Night',
    date: 'Sep 10, 2026',
    location: 'Chicago, IL',
    status: 'Active',
    registrations: [
      { name: 'Alex Johnson', email: 'alex.johnson@example.com', type: 'Job Seeker', registered: 'Aug 20, 2026' },
      { name: 'Sam Rivera', email: 'sam.rivera@example.com', type: 'Job Seeker', registered: 'Aug 21, 2026' },
    ],
  },
  {
    id: 'engineering-open-house',
    title: 'Engineering Open House',
    date: 'Sep 18, 2026',
    location: 'Remote',
    status: 'Active',
    registrations: [
      { name: 'Jamie Chen', email: 'jamie.chen@example.com', type: 'Job Seeker', registered: 'Aug 22, 2026' },
    ],
  },
  {
    id: 'resume-workshop',
    title: 'Resume Workshop',
    date: 'Oct 2, 2026',
    location: 'Dallas, TX',
    status: 'Pending',
    registrations: [],
  },
];

function getInitials(name) {
  return (name || 'Admin')
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word[0])
    .join('')
    .toUpperCase();
}

function StatusSelect({ initialValue, options, label }) {
  const [value, setValue] = useState(initialValue);

  return (
    <select
      className={`myhr-admin__status-select status-${value.toLowerCase().replace(/\s+/g, '-')}`}
      value={value}
      aria-label={label}
      onClick={(event) => event.stopPropagation()}
      onChange={(event) => setValue(event.target.value)}
    >
      {options.map((option) => <option key={option} value={option}>{option}</option>)}
    </select>
  );
}

function moduleStatusOptions(moduleKey) {
  if (moduleKey === 'timesheets') return ['Pending', 'Approved', 'Rejected'];
  return ['Pending', 'Active', 'Inactive'];
}

export default function MyHrAdminPage() {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const [activeModule, setActiveModule] = useState(null);
  const [selectedJob, setSelectedJob] = useState(null);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [toast, setToast] = useState('');

  const showToast = (message) => {
    setToast(message);
    window.clearTimeout(window.myHrAdminToastTimer);
    window.myHrAdminToastTimer = window.setTimeout(() => setToast(''), 2500);
  };

  const handleSignOut = () => {
    clearMyHrSession();
    localStorage.removeItem('accessToken');
    navigate('/login?persona=my-hr', { replace: true });
  };

  const openModule = (moduleKey) => {
    setActiveModule(moduleKey);
    setSelectedJob(null);
    setSelectedEvent(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const goHome = () => {
    setActiveModule(null);
    setSelectedJob(null);
    setSelectedEvent(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const currentModule = activeModule ? MODULES[activeModule] : null;

  return (
    <div className="myhr-admin">
      <header className="myhr-admin__topbar">
        <button type="button" className="myhr-admin__brand" onClick={goHome}>
          <WaypointMark />
          <strong>Waypoint Admin</strong>
        </button>
        <ProfileMenu
          user={{
            name: session?.name || 'Tardus Admin',
            subtitle: 'Administrator · Tardus Inc',
            initials: getInitials(session?.name),
          }}
          items={[
            { label: 'My profile', onClick: () => showToast('My profile opened in prototype mode.') },
            { label: 'Sign out', onClick: handleSignOut },
          ]}
          className="myhr-admin__profile-menu"
        />
      </header>

      <main className="myhr-admin__shell">
        <section className="myhr-admin__hero">
          <p className="myhr-admin__eyebrow">ADMIN WORKSPACE</p>
          <h1>Welcome, {session?.name || 'Tardus Admin'}</h1>
          <p>Administrator · Tardus Inc</p>
          <div className="myhr-admin__summary">
            {[
              ['employees', '248', 'ACTIVE EMPLOYEES'],
              ['timesheets', '12', 'PENDING TIMESHEETS'],
              ['jobs', '36', 'OPEN JOBS'],
            ].map(([moduleKey, value, label]) => (
              <button key={moduleKey} type="button" onClick={() => openModule(moduleKey)}>
                <strong>{value}</strong>
                <span>{label}</span>
              </button>
            ))}
          </div>
        </section>

        {!currentModule ? (
          <section className="myhr-admin__home">
            <div className="myhr-admin__section-heading">
              <div>
                <h2>Admin Actions</h2>
                <p>Manage HR operations across your organization.</p>
              </div>
            </div>
            <div className="myhr-admin__card-grid">
              {ADMIN_ACTIONS.map(([moduleKey, title, description]) => (
                <ActionCard
                  key={moduleKey}
                  eyebrow="Administration"
                  title={title}
                  description={description}
                  actionLabel="Open"
                  onClick={() => openModule(moduleKey)}
                />
              ))}
            </div>
          </section>
        ) : (
          <section className="myhr-admin__detail">
            <button type="button" className="myhr-admin__back" onClick={goHome}>
              ← Back to administration
            </button>
            <div className="myhr-admin__detail-card">
              <div className="myhr-admin__detail-head">
                <div>
                  <p className="myhr-admin__eyebrow">ADMINISTRATION</p>
                  <h2>{selectedJob?.title || selectedEvent?.title || currentModule.title}</h2>
                  <p>{selectedJob
                    ? `${selectedJob.applicants.length} applicant${selectedJob.applicants.length === 1 ? '' : 's'} for this posting`
                    : selectedEvent
                      ? `${selectedEvent.registrations.length} registration${selectedEvent.registrations.length === 1 ? '' : 's'} for this event`
                      : currentModule.description}</p>
                </div>
                <button
                  type="button"
                  className="myhr-admin__primary"
                  onClick={() => showToast(`${currentModule.action} opened in prototype mode.`)}
                >
                  {selectedJob ? 'Manage posting' : selectedEvent ? 'Manage event' : currentModule.action}
                </button>
              </div>
              {activeModule === 'jobs' && !selectedJob ? (
                <div className="myhr-admin__table-wrap">
                  <table aria-label="Job postings">
                    <thead><tr><th>Job posting</th><th>Department</th><th>Location</th><th>Applicants</th><th>Status</th></tr></thead>
                    <tbody>
                      {JOB_POSTINGS.map((job) => (
                        <tr key={job.id} className="myhr-admin__clickable-row" onClick={() => setSelectedJob(job)}>
                          <td><button type="button" className="myhr-admin__job-link" onClick={() => setSelectedJob(job)}>{job.title}</button></td>
                          <td>{job.department}</td><td>{job.location}</td><td>{job.applicants.length}</td>
                          <td><StatusSelect initialValue={job.status} options={['Pending', 'Active', 'Closed']} label={`Status for ${job.title}`} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : activeModule === 'jobs' && selectedJob ? (
                <div className="myhr-admin__applicants">
                  <button type="button" className="myhr-admin__back myhr-admin__back--inside" onClick={() => setSelectedJob(null)}>← All job postings</button>
                  <div className="myhr-admin__table-wrap">
                    <table aria-label={`Applicants for ${selectedJob.title}`}>
                      <thead><tr><th>Applicant</th><th>Email</th><th>Applied</th><th>Stage</th></tr></thead>
                      <tbody>{selectedJob.applicants.map((applicant) => (
                        <tr key={applicant.email}><td><strong>{applicant.name}</strong></td><td>{applicant.email}</td><td>{applicant.applied}</td><td><StatusSelect initialValue={applicant.stage} options={['Review', 'Screening', 'Interview', 'Offered', 'Hired', 'Rejected']} label={`Stage for ${applicant.name}`} /></td></tr>
                      ))}</tbody>
                    </table>
                  </div>
                </div>
              ) : activeModule === 'events' && !selectedEvent ? (
                <div className="myhr-admin__table-wrap">
                  <table aria-label="Events">
                    <thead><tr><th>Event</th><th>Date</th><th>Location</th><th>Registrations</th><th>Status</th></tr></thead>
                    <tbody>{EVENTS.map((event) => (
                      <tr key={event.id} className="myhr-admin__clickable-row" onClick={() => setSelectedEvent(event)}>
                        <td><button type="button" className="myhr-admin__job-link" onClick={() => setSelectedEvent(event)}>{event.title}</button></td>
                        <td>{event.date}</td><td>{event.location}</td><td>{event.registrations.length}</td>
                        <td><StatusSelect initialValue={event.status} options={['Pending', 'Active', 'Completed', 'Cancelled']} label={`Status for ${event.title}`} /></td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              ) : activeModule === 'events' && selectedEvent ? (
                <div className="myhr-admin__applicants">
                  <button type="button" className="myhr-admin__back myhr-admin__back--inside" onClick={() => setSelectedEvent(null)}>← All events</button>
                  {selectedEvent.registrations.length ? (
                    <div className="myhr-admin__table-wrap">
                      <table aria-label={`Registrations for ${selectedEvent.title}`}>
                        <thead><tr><th>Participant</th><th>Email</th><th>Type</th><th>Registered</th><th>Status</th></tr></thead>
                        <tbody>{selectedEvent.registrations.map((registration) => (
                          <tr key={registration.email}><td><strong>{registration.name}</strong></td><td>{registration.email}</td><td>{registration.type}</td><td>{registration.registered}</td><td><StatusSelect initialValue="Registered" options={['Registered', 'Checked In', 'Attended', 'Cancelled']} label={`Registration status for ${registration.name}`} /></td></tr>
                        ))}</tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="myhr-admin__empty-state"><strong>No registrations yet</strong><span>Registrations for this event will appear here.</span></div>
                  )}
                </div>
              ) : currentModule.rows ? (
                <div className="myhr-admin__table-wrap">
                  <table>
                    <thead>
                      <tr>
                        {currentModule.columns.map((column) => <th key={column}>{column}</th>)}
                      </tr>
                    </thead>
                    <tbody>
                      {currentModule.rows.map((row) => (
                        <tr key={row.join('-')}>
                          {row.map((cell, index) => (
                            <td key={`${cell}-${index}`}>
                              {index === 3 ? (
                                <StatusSelect
                                  initialValue={cell}
                                  options={moduleStatusOptions(activeModule)}
                                  label={`Status for ${row[0]}`}
                                />
                              ) : cell}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="myhr-admin__empty-state">
                  <strong>{currentModule.title} is ready to configure</strong>
                  <span>Use “{currentModule.action}” to continue in this prototype.</span>
                </div>
              )}
            </div>
          </section>
        )}
      </main>

      <div className={`myhr-admin__toast${toast ? ' is-visible' : ''}`} role="status">
        {toast}
      </div>
    </div>
  );
}
