import { useState } from 'react';
import { Link } from 'react-router-dom';

const PERSONAS = [
  {
    id: 'COMPANY_ADMIN',
    badge: 'GLOBAL GOVERNANCE',
    title: 'Company Admin',
    tier: 'Tier 1: Global Authority',
    scope: 'Organization-Wide',
    desc: 'Highest administrative authority. Configures organization profile, oversees global policy, inspects SOC-2 audit logs, and monitors all departments.',
    color: '#10b981',
    route: '/myhr/admin',
    stats: [
      { label: 'Audit Records', val: '14,290' },
      { label: 'Active Orgs', val: '100% Synced' },
      { label: 'Security Level', val: 'SOC-2 Type II' },
    ],
    features: ['Audit History Log', 'Organization Settings', 'All-Department Timesheets', 'Workforce Reports'],
  },
  {
    id: 'HR',
    badge: 'PEOPLE OPS',
    title: 'HR Manager',
    tier: 'Tier 2: Operations & Approvals',
    scope: 'All Company Staff',
    desc: 'Directs day-to-day workforce workflows. Dispatches document requests, prepares offer letters, verifies profile changes, and audits timesheets.',
    color: '#059669',
    route: '/myhr/hr',
    stats: [
      { label: 'Direct Reports', val: '28 Assigned' },
      { label: 'Pending Approvals', val: '3 Queued' },
      { label: 'Avg Review Speed', val: '1.4 min' },
    ],
    features: ['Required Actions Workspace', 'Employee Directory', 'Supporting Doc Verification', 'Leave & Profile Approvals'],
  },
  {
    id: 'MANAGER',
    badge: 'TEAM LEADERSHIP',
    title: 'Team Manager',
    tier: 'Tier 3: Direct Reports Oversight',
    scope: 'Assigned Direct Reports',
    desc: 'Oversees team execution. Reviews submitted bi-weekly time cards, receives automatic overtime threshold warnings, and executes 1-click approvals.',
    color: '#0d9488',
    route: '/myhr/manager',
    stats: [
      { label: 'Team Members', val: '12 Active' },
      { label: 'Approval Queue', val: '1 Pending' },
      { label: 'Weekly Pacing', val: '98% on Track' },
    ],
    features: ['Team Hours Review', 'Fast-Path Approval', 'Compliance Flagging', 'Team Performance'],
  },
  {
    id: 'EMPLOYEE',
    badge: 'SELF-SERVICE',
    title: 'Employee',
    tier: 'Tier 4: Individual Self-Service',
    scope: 'Personal Records Only',
    desc: 'Empowered with intuitive self-service. Captures daily hours, uploads receipts or timesheet attachments, and submits transparent profile change requests.',
    color: '#10b981',
    route: '/myhr/employee',
    stats: [
      { label: 'Current Period', val: '40.0 hrs' },
      { label: 'Approval Status', val: 'Submitted' },
      { label: 'Paystub History', val: '100% Up to Date' },
    ],
    features: ['Draft & Quick Submit', 'Document Upload (PDF/Image)', 'Conflicted Period Detection', 'Profile Change Requests'],
  },
  {
    id: 'JOB_SEEKER',
    badge: 'TALENT ACQUISITION',
    title: 'Candidate / Seeker',
    tier: 'Tier 5: External Portal',
    scope: 'Public Requisitions',
    desc: 'External talent engagement. Discovers career openings, submits 1-click applications with resume parsing, and registers for regional career events.',
    color: '#047857',
    route: '/careers',
    stats: [
      { label: 'Open Opportunities', val: '140+ Roles' },
      { label: 'Upcoming Events', val: '12 Hybrid Fairs' },
      { label: 'Match Precision', val: '98.4%' },
    ],
    features: ['Smart Search & Geofilters', 'Resume Upload & Storage', 'Event Registration & ICS', 'Application Dashboard'],
  },
];

export default function WorkforceRadar() {
  const [activePersonaId, setActivePersonaId] = useState('COMPANY_ADMIN');
  const active = PERSONAS.find((p) => p.id === activePersonaId) || PERSONAS[0];

  const handleSelect = (id) => {
    setActivePersonaId(id);
  };

  return (
    <div className="wp-radar-wrap">
      <div className="wp-radar-heading">
        <span className="wp-radar-badge">ROLE-BASED ACCESS CONTROL (RBAC)</span>
        <h3 className="wp-radar-title">Dedicated Workspaces for Every Role</h3>
        <span className="wp-sr-only">Workforce Orbit & Persona Ecosystem</span>
        <p className="wp-radar-desc">
          Waypoint isolates duties and permissions through purpose-built interfaces. Select any role below to explore their dedicated dashboard, permissions, and operational capabilities.
        </p>
      </div>

      <div className="wp-radar-nav" role="tablist" aria-label="Persona Selector">
        {PERSONAS.map((p) => {
          const isSelected = p.id === activePersonaId;
          return (
            <button
              key={p.id}
              type="button"
              role="tab"
              aria-selected={isSelected}
              className={`wp-radar-tab ${isSelected ? 'wp-radar-tab--active' : ''}`}
              style={{ '--tab-accent': p.color }}
              onClick={() => handleSelect(p.id)}
            >
              <span className="wp-radar-tab-tag">{p.badge}</span>
              <span className="wp-radar-tab-name">{p.title}</span>
            </button>
          );
        })}
      </div>

      <div className="wp-radar-stage">
        {/* Role Governance & Permissions Hierarchy Stack (Replaces abstract radar) */}
        <div className="wp-rbac-hierarchy">
          <div className="wp-rbac-hierarchy-header">
            <span className="wp-rbac-hierarchy-title">ORGANIZATIONAL ACCESS HIERARCHY</span>
            <span className="wp-rbac-hierarchy-pill">STRICT RBAC ISOLATION</span>
          </div>

          <div className="wp-rbac-tiers-list">
            {PERSONAS.map((p) => {
              const isActive = p.id === activePersonaId;
              return (
                <div
                  key={p.id}
                  className={`wp-rbac-tier-card ${isActive ? 'wp-rbac-tier-card--active' : ''}`}
                  onClick={() => handleSelect(p.id)}
                  style={{ '--tier-accent': p.color }}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') handleSelect(p.id);
                  }}
                >
                  <div className="wp-rbac-tier-left">
                    <span className="wp-rbac-tier-dot" />
                    <div>
                      <strong className="wp-rbac-tier-name">{p.title}</strong>
                      <span className="wp-rbac-tier-sub">{p.tier}</span>
                    </div>
                  </div>
                  <div className="wp-rbac-tier-right">
                    <span className="wp-rbac-tier-scope">{p.scope}</span>
                    {isActive && <span className="wp-rbac-tier-status">ACTIVE</span>}
                  </div>
                </div>
              );
            })}
          </div>

          <div className="wp-rbac-guarantee">
            <span className="wp-rbac-lock-icon" aria-hidden="true">🔒</span>
            <p>
              <strong>Data Boundary Policy:</strong> Each user only accesses records permitted by their role. Managers cannot access other teams; employees only see personal records.
            </p>
          </div>
        </div>

        {/* Selected Role Telemetry & Action Card */}
        <div className="wp-radar-details" style={{ '--card-accent': active.color }}>
          <div className="wp-radar-details-top">
            <div className="wp-radar-details-badges">
              <span className="wp-radar-detail-badge" style={{ color: active.color, borderColor: active.color }}>
                {active.badge}
              </span>
              <span className="wp-radar-scope-badge">
                SCOPE: {active.scope}
              </span>
            </div>
            <h4 className="wp-radar-detail-title">{active.title} Workspace</h4>
            <p className="wp-radar-detail-desc">{active.desc}</p>
          </div>

          <div className="wp-radar-stats-grid">
            {active.stats.map((st) => (
              <div key={st.label} className="wp-radar-stat-item">
                <span className="wp-radar-stat-val">{st.val}</span>
                <span className="wp-radar-stat-lbl">{st.label}</span>
              </div>
            ))}
          </div>

          <div className="wp-radar-features">
            <span className="wp-radar-features-title">Core Role Capabilities:</span>
            <ul className="wp-radar-feature-list">
              {active.features.map((f) => (
                <li key={f} className="wp-radar-feature-item">
                  <span className="wp-radar-check" style={{ color: active.color }} aria-hidden="true">✓</span>
                  <span>{f}</span>
                </li>
              ))}
            </ul>
          </div>

          <div className="wp-radar-cta">
            <Link to={active.route} className="wp-radar-enter-btn" style={{ background: active.color }}>
              Launch Live {active.title} View →
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
