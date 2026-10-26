import { Fragment, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import './myHrNavigation.css';
import StrokeIcon from '../../../components/global/StrokeIcon';

const NAV_ICON_PATHS = {
  dashboard: ['M3 3h7v7H3z', 'M14 3h7v7h-7z', 'M3 14h7v7H3z', 'M14 14h7v7h-7z'],
  employees: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  'profile-change-requests': ['M15 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v5', 'M7 7h6M7 11h3', 'm14 16 5-5 3 3-5 5-4 1z'],
  timesheets: ['M8 2v4M16 2v4', 'M3 10h18', 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2', 'm8 16 3 3 5-5'],
  'document-requests': ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M8 13h8M8 17h5'],
  reports: ['M3 3v18h18', 'M7 16v-5M12 16V7M17 16v-8', 'm7 7 5-4 5 2 4-3'],
  audit: ['M3 11a9 9 0 1 1 2.6 7', 'M3 4v7h7', 'M12 7v5l3 2'],
  organization: ['M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z', 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8'],
  profile: ['M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'M4 21v-2a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v2'],
  'offer-letter-approvals': ['M9 3h6v4H9z', 'M9 5H5v17h14V5h-4', 'm8 14 3 3 5-5'],
  'payroll-readiness': ['M12 3 3 7v6c0 5 9 9 9 9s9-4 9-9V7z', 'm8 12 3 3 5-5'],
};

export function DashboardNavIcon({ type }) {
  return <span className="workspace-dashboard__nav-icon" aria-hidden="true">
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      {(NAV_ICON_PATHS[type] || NAV_ICON_PATHS.dashboard).map((path) => <path key={path} d={path} />)}
    </svg>
  </span>;
}

const ROLE_LINKS = {
  COMPANY_ADMIN: [['employees', 'Employee Management'], ['profile-change-requests', 'Profile Change Requests'], ['timesheets', 'Timesheets'], ['document-requests', 'Request Documents'], ['reports', 'Reports'], ['audit', 'Audit History'], ['organization', 'Organization Settings'], ['payroll-readiness', 'Payroll Readiness'], ['offer-letter-approvals', 'Document Approvals']],
  HR: [['employees', 'Employee Management'], ['profile-change-requests', 'Profile Change Requests'], ['timesheets', 'Timesheets'], ['document-requests', 'Request Documents'], ['reports', 'Reports'], ['payroll-readiness', 'Payroll Readiness']],
  EMPLOYEE: [['profile', 'My Profile'], ['profile-change-requests', 'Profile Change Requests'], ['timesheets', 'My Timesheets'], ['document-requests', 'Document Requests']],
};

export default function MyHrNavigation({ role }) {
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('waypoint_sidebar_collapsed') === 'true'; } catch { return false; }
  });
  const [peeking, setPeeking] = useState(false);
  if (!ROLE_LINKS[role]) return null;
  const basePath = `/myhr/${role === 'COMPANY_ADMIN' ? 'admin' : role === 'HR' ? 'hr' : 'employee'}`;
  const payrollActive = location.pathname === `${basePath}/timesheets` && new URLSearchParams(location.search).get('view') === 'payroll';
  const toggle = () => {
    setPeeking(false);
    setCollapsed((previous) => {
    try { localStorage.setItem('waypoint_sidebar_collapsed', String(!previous)); } catch { /* Navigation works without storage. */ }
      return !previous;
    });
  };
  const groups = [
    ['', ['dashboard']],
    ['People', ['employees', 'profile', 'profile-change-requests']],
    ['Time & pay', ['timesheets', 'payroll-readiness', 'reports']],
    ['Documents', ['document-requests', 'offer-letter-approvals']],
    ['Governance', ['audit', 'organization']],
  ];
  const available = [['dashboard', 'Dashboard'], ...ROLE_LINKS[role]];
  const links = groups.flatMap(([group, paths]) => paths.flatMap((path) => available.filter(([key]) => key === path).map(([key, title]) => [key, title, group])));
  return <aside className={`myhr-navigation ${role === 'EMPLOYEE' ? 'myhr-navigation--employee' : ''} ${collapsed ? 'is-collapsed' : ''} ${collapsed && peeking ? 'is-peeking' : ''}`} aria-label="Workspace navigation"
    onMouseEnter={() => { if (collapsed) setPeeking(true); }}
    onMouseLeave={(event) => {
      if (!event.currentTarget.querySelector('a:focus')) setPeeking(false);
    }}
    onFocusCapture={(event) => { if (event.target.closest('a')) setPeeking(true); }}
    onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setPeeking(false); }}>
    <button type="button" className="myhr-navigation__toggle" onClick={toggle} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-expanded={!collapsed} aria-controls="myhr-navigation-links"><StrokeIcon name={collapsed ? 'chevron-right' : 'chevron-left'} /></button>
    <nav id="myhr-navigation-links" aria-label="Dashboard tools">{links.map(([path, title, group], index) => {
      const to = path === 'payroll-readiness' ? `${basePath}/timesheets?view=payroll` : `${basePath}/${path}`;
      const active = path === 'payroll-readiness' ? payrollActive : path === 'timesheets' ? location.pathname.startsWith(`${basePath}/timesheets`) && !payrollActive : location.pathname === to || location.pathname.startsWith(`${to}/`);
      return <Fragment key={path}>{group && group !== links[index - 1]?.[2] && <span className="myhr-navigation__group">{group}</span>}<Link to={to} className={active ? 'is-active' : ''} aria-current={active ? 'page' : undefined} aria-label={title} title={title}><DashboardNavIcon type={path} /><span className="myhr-navigation__label">{title}</span></Link></Fragment>;
    })}</nav>
  </aside>;
}
