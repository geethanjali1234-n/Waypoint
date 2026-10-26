import { Link } from 'react-router-dom';
import WaypointMark from '../branding/WaypointMark';
import ThemeToggle from '../global/ThemeToggle';
import QuickJump from '../navigation/QuickJump';
import './WorkspaceFrame.css';

/** Presentation only: callers keep their data requests, menus and route guards. */
export default function WorkspaceFrame({ home, workspaceLabel, account, sidebar, mobileNav, quickJumpActions, children, className = '', mainClassName = '', assistant }) {
  return <div className={`wp-workspace ${className}`}>
    <a className="wp-workspace__skip" href="#workspace-content">Skip to content</a>
    <header className="wp-workspace__header">
      <Link to={home} className="wp-workspace__brand" aria-label={`Waypoint ${workspaceLabel} home`}><WaypointMark /><span><strong>Waypoint</strong><small>{workspaceLabel}</small></span></Link>
      <QuickJump actions={quickJumpActions} />
      <div className="wp-workspace__account"><ThemeToggle iconOnly />{account}</div>
    </header>
    <div className="wp-workspace__body">{sidebar}<main id="workspace-content" tabIndex={-1} className={`wp-workspace__main ${mainClassName}`}>{children}</main></div>
    {mobileNav}{assistant}
  </div>;
}
