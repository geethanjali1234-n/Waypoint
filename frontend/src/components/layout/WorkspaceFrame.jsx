import { useState } from 'react';
import { Link } from 'react-router-dom';
import WaypointMark from '../branding/WaypointMark';
import ThemeToggle from '../global/ThemeToggle';
import QuickJump from '../navigation/QuickJump';
import './WorkspaceFrame.css';

/** Presentation only: callers keep their data requests, menus and route guards. */
export default function WorkspaceFrame({ home, workspaceLabel, account, sidebar, mobileNav, quickJumpActions, children, className = '', mainClassName = '', assistant }) {
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  return <div className={`wp-workspace ${className}`}>
    <a className="wp-workspace__skip" href="#workspace-content">Skip to content</a>
    <header className="wp-workspace__header">
      <Link to={home} className="wp-workspace__brand" aria-label={`Waypoint ${workspaceLabel} home`}><WaypointMark /><span><strong>Waypoint</strong><small>{workspaceLabel}</small></span></Link>
      {sidebar && <button type="button" className="wp-workspace__mobile-menu" aria-label={mobileNavigationOpen ? 'Close workspace navigation' : 'Open workspace navigation'} aria-expanded={mobileNavigationOpen} aria-controls="workspace-mobile-navigation" onClick={() => setMobileNavigationOpen((open) => !open)}><span /><span /><span /></button>}
      <QuickJump actions={quickJumpActions} />
      <div className="wp-workspace__account"><ThemeToggle iconOnly />{account}</div>
    </header>
    {sidebar && <><button type="button" aria-label="Close workspace navigation" className={`wp-workspace__drawer-backdrop ${mobileNavigationOpen ? 'is-open' : ''}`} onClick={() => setMobileNavigationOpen(false)} /><div id="workspace-mobile-navigation" aria-hidden={!mobileNavigationOpen} className={`wp-workspace__mobile-drawer ${mobileNavigationOpen ? 'is-open' : ''}`} onClick={(event) => { if (event.target.closest('a')) setMobileNavigationOpen(false); }}>{sidebar}</div></>}
    <div className="wp-workspace__body">{sidebar}<main id="workspace-content" tabIndex={-1} className={`wp-workspace__main ${mainClassName}`}>{children}</main></div>
    {mobileNav}{assistant}
  </div>;
}
