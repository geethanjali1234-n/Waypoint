import { useId, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import StrokeIcon from '../../../components/global/StrokeIcon';
import ThemeToggle from '../../../components/global/ThemeToggle';
import { DashboardNavIcon } from './MyHrNavigation';
import { ROLE_LINKS, myHrBasePath } from './myHrNavigationLinks';
import './myHrMobileDrawer.css';

// Phone-only grouping of the same role links the desktop sidebar shows.
// Only links the role already has are shown; empty sections are skipped.
const SECTIONS = [
  ['People', ['employees', 'profile', 'profile-change-requests']],
  ['Time & Payroll', ['timesheets', 'payroll-readiness']],
  ['Documents', ['document-requests', 'offer-letter-approvals']],
  ['Insights', ['reports', 'audit']],
  ['Settings', ['organization']],
];

function DrawerLink({ to, type, title, pathname, onNavigate }) {
  const active = pathname === to || pathname.startsWith(`${to}/`);
  return <Link to={to} className={`myhr-mobile-drawer__link ${active ? 'is-active' : ''}`} aria-current={active ? 'page' : undefined} onClick={onNavigate}>
    <DashboardNavIcon type={type} /><span>{title}</span>
  </Link>;
}

function DrawerSection({ label, links, basePath, pathname, onNavigate }) {
  const id = useId();
  const [open, setOpen] = useState(true);
  return <section className="myhr-mobile-drawer__section">
    <button type="button" className="myhr-mobile-drawer__section-toggle" aria-expanded={open} aria-controls={id} onClick={() => setOpen((value) => !value)}>
      <span>{label}</span><StrokeIcon name="chevron-right" />
    </button>
    <div id={id} className="myhr-mobile-drawer__section-links" hidden={!open}>
      {links.map(([key, title]) => <DrawerLink key={key} to={`${basePath}/${key}`} type={key} title={title} pathname={pathname} onNavigate={onNavigate} />)}
    </div>
  </section>;
}

export default function MyHrMobileDrawer({ role, title, workspaceLabel, onClose }) {
  const { pathname } = useLocation();
  const roleLinks = ROLE_LINKS[role] || [];
  const basePath = myHrBasePath(role);
  const sections = SECTIONS
    .map(([label, keys]) => [label, keys.flatMap((key) => roleLinks.filter(([linkKey]) => linkKey === key))])
    .filter(([, links]) => links.length > 0);

  return <div className="myhr-mobile-drawer">
    <div className="myhr-mobile-drawer__header">
      <div className="myhr-mobile-drawer__title"><strong>{title}</strong>{workspaceLabel && <small>{workspaceLabel}</small>}</div>
      <button type="button" className="myhr-mobile-drawer__close" aria-label="Close navigation" onClick={onClose} data-drawer-initial-focus><StrokeIcon name="close" /></button>
    </div>
    <nav className="myhr-mobile-drawer__nav" aria-label="Workspace navigation">
      {ROLE_LINKS[role] && <DrawerLink to={`${basePath}/dashboard`} type="dashboard" title="Home" pathname={pathname} onNavigate={onClose} />}
      {sections.map(([label, links]) => <DrawerSection key={label} label={label} links={links} basePath={basePath} pathname={pathname} onNavigate={onClose} />)}
    </nav>
    <div className="myhr-mobile-drawer__footer">
      <span>Theme</span><ThemeToggle id="theme-toggle-btn-mobile-drawer" />
    </div>
  </div>;
}
