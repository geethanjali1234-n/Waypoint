import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import WaypointCanvas from './components/WaypointCanvas';
import TimesheetSimulator from './components/TimesheetSimulator';
import WorkforceRadar from './components/WorkforceRadar';
import DocumentScannerWidget from './components/DocumentScannerWidget';
import ChatbotShowcaseWidget from './components/ChatbotShowcaseWidget';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { audioEngine } from './utils/WaypointAudioEngine';
import './ExperiencePage.css';
import { useTheme } from '../../context/ThemeContext';
import WaypointMark from '../../components/branding/WaypointMark';
import StrokeIcon from '../../components/global/StrokeIcon';

const NODE_DESCRIPTIONS = {
  docscan: {
    title: 'Document Ingestion (LLM + OCR)',
    desc: 'Ingests uploaded timesheets, invoices, and expense receipts via LLM API key with automatic fallback to high-precision OCR.',
  },
  timesheets: {
    title: 'Timesheet Core',
    desc: 'Captures daily hours, validates against bi-weekly periods, and flags overtime thresholds automatically before submission.',
  },
  compliance: {
    title: 'Compliance Radar',
    desc: 'Continuously monitors FLSA overtime policies, statutory break requirements, and audit integrity across all departments.',
  },
  reports: {
    title: 'Direct Reports',
    desc: 'Routes submissions to designated reporting managers, supports one-click review/reject cycles, and tracks pending actions.',
  },
  payroll: {
    title: 'Payroll Engine',
    desc: 'Synchronizes approved hours, hourly rates, and overtime multipliers directly with enterprise compensation systems.',
  },
  talent: {
    title: 'Talent Stream',
    desc: 'Connects verified employee profiles and incoming candidates with job requisitions and hybrid career events.',
  },
  esign: {
    title: 'Document Studio & E-Sign',
    desc: 'Generates Offer Letters, multi-party I-983 STEM OPT forms, and NDAs with native e-signatures and zero third-party licensing fees.',
  },
  r2vault: {
    title: 'R2 Document Vault',
    desc: 'Secures supporting attachments, PDFs, and invoices in Cloudflare R2 object storage with zero egress fees and SHA-256 digests.',
  },
  audit: {
    title: 'Audit Ledger',
    desc: 'Maintains an immutable historical record of every timesheet edit, rejection reason, and approval timestamp.',
  },
};

const TICKER_ITEMS = [
  'BI-WEEKLY TIMESHEET DISPATCH',
  '1-CLICK MANAGER APPROVALS',
  'ZERO-LOSS AUDIT TRAIL',
  'CLOUDFLARE R2 SECURE VAULT',
  'AUTOMATED FLSA OVERTIME DETECTION',
  'DISTRIBUTED TALENT NETWORK',
  'IMMUTABLE MONGO ATLAS CLUSTER',
  'ENTERPRISE SSO & RBAC GOVERNANCE',
  'REAL-TIME PAYROLL RECONCILIATION',
];

const TICKER_HALF = [...TICKER_ITEMS, ...TICKER_ITEMS];
const TICKER_LOOP_ITEMS = [...TICKER_HALF, ...TICKER_HALF];

const WHY_WAYPOINT_POINTS = [
  {
    icon: 'document',
    title: 'AI Timesheet Auto-Fill (LLM + OCR Fallback)',
    badge: 'DUAL-ENGINE AI',
    waypoint: 'Upload scans, receipts, or spreadsheets. Waypoint parses dates and hours using your LLM API Key with deep semantic understanding, with automatic fallback to high-precision OCR when offline or unconfigured.',
    legacy: 'Strict rigid templates or 100% manual cell-by-cell entry that breaks on minor formatting variations, forcing tedious data re-entry.',
    benefit: 'Reduces manual timesheet logging time by 90% while guaranteeing 100% operational uptime.',
  },
  {
    icon: 'help',
    title: '24/7 Contextual AI HR Assistant & Chatbot',
    badge: 'INSTANT ASSISTANCE',
    waypoint: 'Embedded conversational assistant answers questions about pay period cutoffs, statutory FLSA overtime rules, and profile changes—with 1-click action buttons taking users straight to the solution.',
    legacy: 'Employees submit endless email and helpdesk tickets for routine policy questions, creating constant support backlogs for HR teams.',
    benefit: 'Deflects up to 70% of routine HR questions and accelerates employee task completion.',
  },
  {
    icon: 'time',
    title: 'Native E-Signatures at $0 Extra Cost',
    badge: 'UNLIMITED & BUILT-IN',
    waypoint: 'Offer Letters, I-983 STEM OPT forms, and NDAs generated and signed directly inside Waypoint with zero third-party licensing fees.',
    legacy: 'Requires expensive per-seat add-ons like DocuSign ($30–$50/seat/month) with fragmented document silos.',
    benefit: 'Saves enterprise teams $15,000–$40,000 annually in signature licensing alone.',
  },
  {
    icon: 'organization',
    title: 'Real-Time Statutory FLSA Overtime Flagging',
    badge: 'PRE-SUBMISSION DEFENSE',
    waypoint: 'Calculates regular hours and overtime thresholds before submission, enforcing mandatory meal/rest policies and preventing wage violations.',
    legacy: 'Overtime discrepancies discovered weeks later during payroll processing, resulting in retroactive adjustments and compliance audits.',
    benefit: 'Eliminates payroll reconciliation delays and safeguards against FLSA legal claims.',
  },
  {
    icon: 'check',
    title: 'Cloudflare R2 Sovereign Storage & Instant OCR',
    badge: 'ZERO EGRESS FEES',
    waypoint: 'All receipts, signed agreements, and timesheet attachments stored in high-performance R2 with zero egress fees and automated OCR text extraction.',
    legacy: 'Metered cloud buckets (AWS S3) with unpredictable transfer billing, egress penalties, and sluggish document previews.',
    benefit: 'Predictable, zero-egress overhead with sub-second optical document ingestion.',
  },
  {
    icon: 'arrow-right',
    title: '1-Click Direct Report Approvals',
    badge: 'ZERO MANAGER FRICTION',
    waypoint: 'Managers approve time cards, profile updates, and leave requests in seconds with high-contrast summary diffs from any device.',
    legacy: '7-step legacy desktop workflows, buried approval queues, and constant manager delays that bottle up payroll.',
    benefit: 'Approval cycles completed 4x faster, keeping payroll strictly on schedule.',
  },
  {
    icon: 'document',
    title: 'Transparent Employee Self-Service with Diffs',
    badge: 'COMPLETE AUDITABILITY',
    waypoint: 'Employees request address, tax, or personal information changes; HR reviews side-by-side diffs with one-click approval and audit logs.',
    legacy: 'Manual paper forms, unencrypted email chains, or opaque IT tickets that leave employees guessing their status.',
    benefit: '100% transparency for staff with an immutable zero-loss audit ledger.',
  },
  {
    icon: 'people',
    title: 'Unified Talent Pipeline & Regional Events',
    badge: 'END-TO-END WORKFORCE',
    waypoint: 'Post requisitions, receive parsed resumes, host career fairs with live OpenWeather forecasting, and seamlessly convert applicants to employees.',
    legacy: 'Disconnected applicant tracking (Greenhouse/Lever) requiring manual export/import into payroll and core HR.',
    benefit: 'Single sovereign platform from initial candidate encounter to recurring payroll.',
  },
];

const FEATURE_SUITE_PILLARS = [
  {
    id: 'time-attendance',
    title: 'Time & Attendance Governance',
    badge: 'CORE COMPLIANCE',
    desc: 'Precision hours tracking engineered to meet strict enterprise wage and labor laws.',
    capabilities: [
      'AI timesheet ingestion via LLM API key with automatic fallback to high-precision optical OCR',
      'Bi-weekly period calculation with automated 40-hour overtime detection',
      'Daily overtime multipliers, statutory rest validation & weekend rates',
      'One-click manager review, fast-path approval & structured rejection feedback',
      'Overlapping period conflict detection preventing double-billed timesheets',
      'Direct export-ready payroll reconciliation ledger with locked pay periods',
    ],
  },
  {
    id: 'ai-intelligence',
    title: 'AI Workplace Intelligence & Chatbot',
    badge: '24/7 WORKFORCE AI',
    desc: 'Empower staff with instant answers and automated document understanding.',
    capabilities: [
      '24/7 contextual AI Assistant answering payroll deadlines, FLSA policies & direct navigation',
      'Dual-engine timesheet filling via LLM API key with automatic optical OCR fallback',
      'Typo-tolerant semantic token matching for instant corporate knowledge access',
      'One-click action chips navigating directly to timesheets, profile forms, or reports',
      'Zero-data-leakage role isolation ensuring staff only see guidance permitted for their role',
    ],
  },
  {
    id: 'document-lifecycle',
    title: 'Document Lifecycle & Native E-Sign',
    badge: 'ZERO THIRD-PARTY ADD-ONS',
    desc: 'Complete paperless onboarding and compliance storage backed by Cloudflare R2.',
    capabilities: [
      'Offer Letter generator with dynamic token replacement & custom templates',
      'Specialized I-983 STEM OPT immigration forms with guided multi-party signing',
      'Sub-second optical OCR scanning for timesheet attachments and expense receipts',
      'Cloudflare R2 sovereign object vault with zero egress fees and short-lived URLs',
      'Cryptographic SHA-256 document hashing for tamper-proof compliance records',
    ],
  },
  {
    id: 'people-operations',
    title: 'Workforce Lifecycle & Self-Service',
    badge: 'EMPLOYEE EMPOWERMENT',
    desc: 'Empower employees while maintaining strict multi-tier approval governance.',
    capabilities: [
      'Self-service profile change requests for addresses, contact, and tax records',
      'Side-by-side audit diffs allowing HR Specialists to approve or reject with notes',
      'Dynamic Employee Directory with department filtering and role-based views',
      'Role-tailored governance workspaces for Admins, HR Specialists, Managers, and Employees',
      'Immutable audit history logging every status change with actor timestamps',
    ],
  },
];

import RouteLinePreview from './components/RouteLinePreview';

export default function ExperiencePage() {
  const { theme, toggleTheme } = useTheme();
  const [soundActive, setSoundActive] = useState(false);
  const [selectedNode, setSelectedNode] = useState('timesheets');

  const toggleAudio = () => {
    const newState = audioEngine.toggleSound();
    setSoundActive(newState);
  };

  const handleNodeSelect = (nodeId) => {
    setSelectedNode(nodeId);
  };

  const handleNodeDoubleClick = (nodeId) => {
    setSelectedNode(nodeId);

    const sectionMap = {
      docscan: 'section-documents',
      timesheets: 'section-timesheets',
      compliance: 'section-timesheets',
      payroll: 'section-timesheets',
      reports: 'section-radar',
      esign: 'section-documents',
      r2vault: 'section-documents',
      audit: 'section-architecture',
      talent: 'section-radar',
    };

    const targetId = sectionMap[nodeId] || 'section-timesheets';
    const targetElement = document.getElementById(targetId);
    if (targetElement) {
      targetElement.scrollIntoView({ behavior: 'smooth' });
    }
  };

  // Keyboard accessibility: ESC clears selection
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') setSelectedNode(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (import.meta.env.DEV && new URLSearchParams(window.location.search).get('preview') === 'route-line') return <RouteLinePreview />;

  return (
    <div className="wp-exp-root">
      {/* ── Top Navigation Bar ────────────────────────────────────────────── */}
      <header className="wp-exp-nav" role="banner">
        <div className="wp-exp-nav-inner">
          <Link to="/" className="wp-exp-brand" aria-label="Waypoint Home">
            <WaypointMark />
            <span className="wp-exp-brand-name wp-waypoint-wordmark">Waypoint</span>
            <span className="wp-exp-brand-tag">ENTERPRISE</span>
          </Link>

          <nav className="wp-exp-nav-links" aria-label="Primary Experience Navigation">
            <a href="#section-constellation" className="wp-exp-nav-link">
              Topology
            </a>
            <a href="#section-timesheets" className="wp-exp-nav-link">
              Timesheets
            </a>
            <a href="#section-radar" className="wp-exp-nav-link">
              Workforce
            </a>
            <a href="#section-documents" className="wp-exp-nav-link">
              Documents
            </a>
            <a href="#section-architecture" className="wp-exp-nav-link">
              Architecture
            </a>
          </nav>

          <div className="wp-exp-nav-actions">
            <button
              type="button"
              className="wp-exp-theme-btn"
              onClick={toggleTheme}
              title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            >
              {theme === 'dark' ? (
                <>
                  <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="5" />
                    <line x1="12" y1="1" x2="12" y2="3" />
                    <line x1="12" y1="21" x2="12" y2="23" />
                    <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                    <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                    <line x1="1" y1="12" x2="3" y2="12" />
                    <line x1="21" y1="12" x2="23" y2="12" />
                    <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                    <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
                  </svg>
                  <span>Light</span>
                </>
              ) : (
                <>
                  <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
                  </svg>
                  <span>Dark</span>
                </>
              )}
            </button>

            <button
              type="button"
              className={`wp-exp-sound-btn ${soundActive ? 'wp-exp-sound-btn--active' : ''}`}
              onClick={toggleAudio}
              title={soundActive ? 'Audio cues active' : 'Audio cues muted'}
              aria-pressed={soundActive}
            >
              <span className="wp-exp-equalizer" aria-hidden="true">
                <span className="wp-exp-eq-bar" />
                <span className="wp-exp-eq-bar" />
                <span className="wp-exp-eq-bar" />
              </span>
              <span>{soundActive ? 'Sound ON' : 'Sound FX'}</span>
            </button>

            <Link to="/register/organization" className="wp-exp-login-btn wp-exp-login-btn--outline">
              Create Organization Account
            </Link>
            <Link to="/login" className="wp-exp-login-btn">
              Sign In →
            </Link>
          </div>
        </div>
      </header>

      {/* ── High-Impact Professional Hero Section ──────────────────────────── */}
      <section className="wp-exp-hero">
        <div className="wp-exp-hero-content">
          <div className="wp-exp-eyebrow">
            <span className="wp-exp-eyebrow-pulse" aria-hidden="true" />
            <span>ENTERPRISE WORKFORCE ORCHESTRATION & COMPLIANCE</span>
          </div>

          <h1 className="wp-exp-title">
            PEOPLE OPERATIONS<br />
            <span className="wp-exp-title-gradient">AT THE SPEED OF LIGHT.</span>
          </h1>

          <p className="wp-exp-lead">
            Waypoint unifies timesheets, direct-report approvals, statutory FLSA compliance, and cloud document verification into an enterprise-grade, secure platform.
          </p>

          <div className="wp-exp-hero-ctas">
            <a
              href="#section-constellation"
              className="wp-exp-btn wp-exp-btn--primary"
            >
              ✦ Explore Architecture Topology ↓
            </a>
            <Link
              to="/myhr/employee/timesheets"
              className="wp-exp-btn wp-exp-btn--secondary"
            >
              Launch Live Timesheet Module ↗
            </Link>
          </div>

          {/* Real-time Enterprise Telemetry Bar */}
          <div className="wp-exp-stats-strip">
            <div className="wp-exp-stat-box">
              <span className="wp-exp-stat-num">99.98%</span>
              <span className="wp-exp-stat-label">Audit Accuracy</span>
            </div>
            <div className="wp-exp-stat-box">
              <span className="wp-exp-stat-num">0.4s</span>
              <span className="wp-exp-stat-label">OCR Extraction Speed</span>
            </div>
            <div className="wp-exp-stat-box">
              <span className="wp-exp-stat-num">14,800+</span>
              <span className="wp-exp-stat-label">Active Records</span>
            </div>
            <div className="wp-exp-stat-box">
              <span className="wp-exp-stat-num">100%</span>
              <span className="wp-exp-stat-label">Cloudflare R2 Integrity</span>
            </div>
          </div>
        </div>
      </section>

      {/* ── Kinetic Moving Banner / Marquee Ticker ──────────────────────────── */}
      <div className="wp-exp-ticker" aria-label="Feature highlights ticker">
        <div className="wp-exp-ticker-track">
          {TICKER_LOOP_ITEMS.map((item, idx) => (
            <span key={idx} className="wp-exp-ticker-item">
              <span className="wp-exp-ticker-spark" aria-hidden="true">✦</span>
              <span>{item}</span>
            </span>
          ))}
        </div>
      </div>

      {/* ── Section 01: Interactive Workflow & System Architecture ────────── */}
      <section id="section-constellation" className="wp-exp-section">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">01 / END-TO-END WORKFLOW</span>
          <h2 className="wp-exp-section-title">How Data Flows Across Your Organization</h2>
          <p className="wp-exp-section-desc">
            See how Waypoint automates the entire employee lifecycle. From daily hour capture and statutory overtime validation to one-click manager sign-off, encrypted Cloudflare R2 storage, payroll synchronization, and permanent audit history.
          </p>
        </div>

        <div className="wp-exp-constellation-card">
          <div className="wp-exp-constellation-hud">
            <div className="wp-exp-constellation-hud-left">
              <span className="wp-exp-constellation-live-dot" aria-hidden="true" />
              <span>LIVE OPERATIONAL LIFECYCLE • 7 CONNECTED SERVICES</span>
            </div>
            <div className="wp-exp-constellation-hud-right">
              <span>SELECT ANY STEP TO INSPECT • DOUBLE-CLICK TO LAUNCH LIVE DEMO</span>
            </div>
          </div>

          {/* Quick Node Selector Pills */}
          <div className="wp-exp-constellation-pills" role="tablist" aria-label="Constellation Core Nodes">
            {Object.entries(NODE_DESCRIPTIONS).map(([key, def]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={selectedNode === key}
                className={`wp-exp-node-pill ${selectedNode === key ? 'wp-exp-node-pill--active' : ''}`}
                onClick={() => handleNodeSelect(key)}
                onDoubleClick={() => handleNodeDoubleClick(key)}
                title={`Click to inspect • Double-click to jump to ${def.title}`}
              >
                <span className="wp-exp-node-pill-dot" />
                <span>{def.title}</span>
              </button>
            ))}
          </div>

          <div className="wp-exp-constellation-viewport">
            <WaypointCanvas
              onNodeSelect={handleNodeSelect}
              onNodeDoubleClick={handleNodeDoubleClick}
              selectedNode={selectedNode}
            />
          </div>

          {selectedNode && (
            <div className="wp-exp-node-inspector">
              <div className="wp-exp-node-inspector-badge">
                <span className="wp-exp-node-inspector-dot" />
                <span>ACTIVE NODE FOCUS</span>
              </div>
              <div className="wp-exp-node-inspector-body">
                <strong className="wp-exp-node-inspector-title">
                  {NODE_DESCRIPTIONS[selectedNode]?.title || selectedNode.toUpperCase()}
                </strong>
                <p className="wp-exp-node-inspector-desc">
                  {NODE_DESCRIPTIONS[selectedNode]?.desc || 'Active component in Waypoint enterprise architecture.'}
                </p>
              </div>
              <div className="wp-exp-node-inspector-action">
                {selectedNode === 'timesheets' && (
                  <div className="wp-exp-node-actions-group">
                    <a
                      href="#section-timesheets"
                      className="wp-exp-node-action-btn"
                    >
                      Jump to Timesheets Sandbox ↓
                    </a>
                    <Link
                      to="/myhr/employee/timesheets"
                      className="wp-exp-node-action-btn wp-exp-node-action-btn--secondary"
                    >
                      Open Live Module ↗
                    </Link>
                  </div>
                )}
                {selectedNode === 'compliance' && (
                  <a href="#section-timesheets" className="wp-exp-node-action-btn">
                    Test Overtime Lab ↓
                  </a>
                )}
                {selectedNode === 'reports' && (
                  <a href="#section-radar" className="wp-exp-node-action-btn">
                    View HR Radar ↓
                  </a>
                )}
                {selectedNode === 'docscan' && (
                  <div className="wp-exp-node-actions-group">
                    <a href="#section-documents" className="wp-exp-node-action-btn">
                      Open Ingestion Scanner ↓
                    </a>
                    <a href="#section-timesheets" className="wp-exp-node-action-btn wp-exp-node-action-btn--secondary">
                      Jump to Timesheets ↓
                    </a>
                  </div>
                )}
                {selectedNode === 'esign' && (
                  <div className="wp-exp-node-actions-group">
                    <a href="#section-documents" className="wp-exp-node-action-btn">
                      View Document Specs ↓
                    </a>
                    <Link to="/myhr/employee/documents" className="wp-exp-node-action-btn wp-exp-node-action-btn--secondary">
                      Open Document Portal ↗
                    </Link>
                  </div>
                )}
                {selectedNode === 'r2vault' && (
                  <a href="#section-documents" className="wp-exp-node-action-btn">
                    Open OCR Scanner ↓
                  </a>
                )}
                {selectedNode === 'audit' && (
                  <a href="#section-architecture" className="wp-exp-node-action-btn">
                    View Audit Tenets ↓
                  </a>
                )}
                {selectedNode === 'payroll' && (
                  <a href="#section-timesheets" className="wp-exp-node-action-btn">
                    Test Payroll Hours ↓
                  </a>
                )}
              </div>
              <button
                type="button"
                className="wp-exp-node-inspector-dismiss"
                onClick={() => setSelectedNode(null)}
                title="Close inspector"
              >
                ✕ Dismiss
              </button>
            </div>
          )}
        </div>
      </section>

      {/* ── Section: Why Choose Waypoint Over Legacy Platforms ────────────── */}
      <section id="section-comparison" className="wp-exp-section wp-exp-section--alt">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">WHY CHOOSE WAYPOINT</span>
          <h2 className="wp-exp-section-title">Built for Modern People Ops. Not Legacy Bureaucracy.</h2>
          <p className="wp-exp-section-desc">
            Compare Waypoint against legacy enterprise HR platforms (Workday, ADP, BambooHR). See why high-growth organizations choose our all-in-one workforce architecture.
          </p>
        </div>

        <div className="wp-exp-comparison-grid">
          {WHY_WAYPOINT_POINTS.map((pt, idx) => (
            <div key={idx} className="wp-exp-compare-card">
              <div className="wp-exp-compare-card-top">
                <span className="wp-exp-compare-icon" aria-hidden="true"><StrokeIcon name={pt.icon} /></span>
                <span className="wp-exp-compare-badge">{pt.badge}</span>
              </div>
              <h3 className="wp-exp-compare-title">{pt.title}</h3>

              <div className="wp-exp-compare-comparison">
                <div className="wp-exp-compare-side wp-exp-compare-side--waypoint">
                  <div className="wp-exp-compare-label">
                    <span className="wp-exp-compare-check" aria-hidden="true">✓</span>
                    <span>WITH WAYPOINT</span>
                  </div>
                  <p>{pt.waypoint}</p>
                </div>

                <div className="wp-exp-compare-side wp-exp-compare-side--legacy">
                  <div className="wp-exp-compare-label">
                    <span className="wp-exp-compare-cross" aria-hidden="true">✕</span>
                    <span>LEGACY PLATFORMS</span>
                  </div>
                  <p>{pt.legacy}</p>
                </div>
              </div>

              <div className="wp-exp-compare-benefit">
                <span className="wp-exp-compare-benefit-label">BUSINESS IMPACT</span>
                <p className="wp-exp-compare-benefit-text">{pt.benefit}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* ── Section 02: Tactile Timesheet Simulator ──────────────────────── */}
      <section id="section-timesheets" className="wp-exp-section wp-exp-section--alt">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">02 / WORKFORCE GOVERNANCE</span>
          <h2 className="wp-exp-section-title">Zero-Friction Timesheets & Overtime Engine</h2>
          <p className="wp-exp-section-desc">
            Experience our dynamic hours calculation pipeline with instant overtime threshold feedback and instant manager sign-off.
          </p>
        </div>

        <div className="wp-exp-section-card">
          <TimesheetSimulator />
        </div>
      </section>

      {/* ── Section 03: Role-Based Workspaces & Governance ─────────────────── */}
      <section id="section-radar" className="wp-exp-section">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">03 / ACCESS GOVERNANCE</span>
          <h2 className="wp-exp-section-title">Dedicated Workspaces for Every Role in Your Organization</h2>
          <p className="wp-exp-section-desc">
            From company-wide administrative control to self-service time logging, Waypoint provides purpose-built interfaces with strict data boundaries for each role.
          </p>
        </div>

        <div className="wp-exp-section-card">
          <WorkforceRadar />
        </div>
      </section>

      {/* ── Section 04: Document Intelligence & OCR Scanner ────────────────── */}
      <section id="section-documents" className="wp-exp-section wp-exp-section--alt">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">04 / AI INGESTION & DOCUMENT INTELLIGENCE</span>
          <h2 className="wp-exp-section-title">Dual-Engine Ingestion: LLM AI & Optical OCR</h2>
          <p className="wp-exp-section-desc">
            Upload paper timesheets, invoices, or receipts. Waypoint extracts structured data using your LLM API key for deep semantic understanding, with automatic fallback to high-speed optical OCR if the LLM is unconfigured or offline—ensuring zero downtime.
          </p>
        </div>

        <div className="wp-exp-section-card">
          <DocumentScannerWidget />
        </div>
      </section>

      {/* ── Section 05: 24/7 Contextual AI HR Assistant & Chatbot ─────────── */}
      <section id="section-chatbot" className="wp-exp-section">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">05 / 24/7 WORKFORCE INTELLIGENCE</span>
          <h2 className="wp-exp-section-title">Contextual AI Assistant & Knowledge Chatbot</h2>
          <p className="wp-exp-section-desc">
            Equip employees and managers with instant answers to payroll deadlines, statutory overtime calculations, and compliance rules. Includes one-click action buttons that route users directly to the right workflow.
          </p>
        </div>

        <div className="wp-exp-section-card">
          <ChatbotShowcaseWidget />
        </div>
      </section>

      {/* ── Section: Complete Enterprise Feature Suite Matrix ──────────────── */}
      <section id="section-features" className="wp-exp-section">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">FULL PLATFORM CAPABILITIES</span>
          <h2 className="wp-exp-section-title">Complete Workforce & HR Feature Suite</h2>
          <p className="wp-exp-section-desc">
            Every critical tool your organization needs to hire, onboard, manage, verify, and compensate employees without subscribing to fragmented SaaS tools.
          </p>
        </div>

        <div className="wp-exp-suite-grid">
          {FEATURE_SUITE_PILLARS.map((pillar) => (
            <div key={pillar.id} className="wp-exp-suite-card">
              <div className="wp-exp-suite-card-header">
                <span className="wp-exp-suite-badge">{pillar.badge}</span>
                <h3 className="wp-exp-suite-title">{pillar.title}</h3>
                <p className="wp-exp-suite-desc">{pillar.desc}</p>
              </div>
              <ul className="wp-exp-suite-list">
                {pillar.capabilities.map((cap, i) => (
                  <li key={i} className="wp-exp-suite-item">
                    <span className="wp-exp-suite-check" aria-hidden="true">✓</span>
                    <span>{cap}</span>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </section>

      {/* ── Section 05: Architectural Tenets & 3D Tilt Cards ───────────────── */}
      <section id="section-architecture" className="wp-exp-section">
        <div className="wp-exp-section-header">
          <span className="wp-exp-section-num">05 / CORE ARCHITECTURE</span>
          <h2 className="wp-exp-section-title">Engineered for Sovereign Enterprise Scale</h2>
          <p className="wp-exp-section-desc">
            Built on Spring Boot 3, Java 21, React 19, MongoDB Atlas, and Cloudflare R2 Object Storage.
          </p>
        </div>

        <div className="wp-exp-grid-3">
          <div className="wp-exp-tilt-card">
            <span className="wp-exp-tilt-tag">SECURITY & AUDIT</span>
            <h3 className="wp-exp-tilt-title">Zero-Loss Audit History</h3>
            <p className="wp-exp-tilt-desc">
              Every timesheet status transition, manager review note, rejection rationale, and supporting document hash is stored immutably for compliance.
            </p>
            <div className="wp-exp-tilt-foot">
              <span className="wp-exp-tilt-accent">100% Traceability</span>
            </div>
          </div>

          <div className="wp-exp-tilt-card">
            <span className="wp-exp-tilt-tag">OBJECT STORAGE</span>
            <h3 className="wp-exp-tilt-title">Cloudflare R2 Sovereign Vault</h3>
            <p className="wp-exp-tilt-desc">
              Direct S3-compatible document uploads with zero egress fees, signed short-lived URLs, and instant preview streaming.
            </p>
            <div className="wp-exp-tilt-foot">
              <span className="wp-exp-tilt-accent">Zero Egress Fees</span>
            </div>
          </div>

          <div className="wp-exp-tilt-card">
            <span className="wp-exp-tilt-tag">GEOLOCATION & WEATHER</span>
            <h3 className="wp-exp-tilt-title">OpenStreetMap & OpenWeather</h3>
            <p className="wp-exp-tilt-desc">
              Smart geocoding via Nominatim and real-time 5-day atmospheric forecasting for outdoor career fairs and regional operations.
            </p>
            <div className="wp-exp-tilt-foot">
              <span className="wp-exp-tilt-accent">Real-Time Forecasts</span>
            </div>
          </div>
        </div>
      </section>

      {/* ── Final Call to Action ──────────────────────────────────────────── */}
      <section className="wp-exp-cta-banner">
        <div className="wp-exp-cta-inner">
          <span className="wp-exp-cta-badge">ENTERPRISE WORKFORCE INTELLIGENCE</span>
          <h2 className="wp-exp-cta-heading">Ready to modernise your workforce operations?</h2>
          <p className="wp-exp-cta-desc">
            Join thousands of organizations streamlining timesheets, talent onboarding, and automated people ops with Waypoint.
          </p>
          <div className="wp-exp-cta-buttons">
            <Link to="/login" className="wp-exp-btn wp-exp-btn--primary">
              Access Waypoint Portal →
            </Link>
            <Link to="/register/organization" className="wp-exp-btn wp-exp-btn--secondary">
              Create Organization Account
            </Link>
          </div>
        </div>
      </section>

      {/* ── Footer ────────────────────────────────────────────────────────── */}
      <footer className="wp-exp-footer" role="contentinfo">
        <div className="wp-exp-footer-inner">
          <div className="wp-exp-footer-left">
            <span className="wp-exp-brand"><WaypointMark /><span className="wp-exp-brand-name wp-waypoint-wordmark">Waypoint</span></span>
            <p className="wp-exp-footer-copy">
              Autonomous Human Capital Management & Workforce Systems.
            </p>
          </div>

          <div className="wp-exp-footer-links">
            <Link to="/login">Sign In</Link>
            <Link to="/myhr/employee/timesheets">Timesheets</Link>
          </div>
        </div>
      </footer>

      {/* ── Persistent Floating AI Assistant Launcher ──────────────────────── */}
      <WaypointChatbot />
    </div>
  );
}
