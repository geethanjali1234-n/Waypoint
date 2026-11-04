import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import WaypointMark from '../branding/WaypointMark';
import ThemeToggle from '../global/ThemeToggle';
import QuickJump from '../navigation/QuickJump';
import './WorkspaceFrame.css';

/**
 * Presentation only: callers keep their data requests, menus and route guards.
 * Optional `mobileTitle` + `mobileDrawer` turn on the compact phone header (<=768px):
 * menu, title, search, bell, avatar. `mobileDrawer({ close })` renders the drawer content.
 * Shells that pass neither keep the original phone header and drawer.
 */
export default function WorkspaceFrame({ home, workspaceLabel, account, sidebar, mobileNav, quickJumpActions, children, className = '', mainClassName = '', assistant, mobileTitle, mobileDrawer }) {
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const menuButtonRef = useRef(null);
  const drawerRef = useRef(null);
  const compactMobile = Boolean(mobileTitle && mobileDrawer);
  const hasDrawer = Boolean(sidebar || mobileDrawer);

  const closeDrawer = useCallback(() => setMobileNavigationOpen(false), []);

  // Compact drawer: lock background scroll, close on Escape, move focus in and back out.
  useEffect(() => {
    if (!compactMobile || !mobileNavigationOpen) return undefined;
    const { body } = document;
    const previousOverflow = body.style.overflow;
    body.style.overflow = 'hidden';
    const closeOnEscape = (event) => { if (event.key === 'Escape') setMobileNavigationOpen(false); };
    document.addEventListener('keydown', closeOnEscape);
    drawerRef.current?.querySelector('[data-drawer-initial-focus]')?.focus();
    const menuButton = menuButtonRef.current;
    return () => {
      body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', closeOnEscape);
      menuButton?.focus();
    };
  }, [compactMobile, mobileNavigationOpen]);

  // Close the compact drawer if the screen grows past the phone breakpoint.
  useEffect(() => {
    if (!compactMobile || typeof window.matchMedia !== 'function') return undefined;
    const query = window.matchMedia('(max-width: 768px)');
    const update = () => { if (!query.matches) setMobileNavigationOpen(false); };
    query.addEventListener?.('change', update);
    return () => query.removeEventListener?.('change', update);
  }, [compactMobile]);

  return <div className={`wp-workspace ${compactMobile ? 'wp-workspace--compact-mobile' : ''} ${className}`}>
    <a className="wp-workspace__skip" href="#workspace-content">Skip to content</a>
    <header className="wp-workspace__header">
      <Link to={home} className="wp-workspace__brand" aria-label={`Waypoint ${workspaceLabel} home`}><WaypointMark /><span><strong>Waypoint</strong><small>{workspaceLabel}</small></span>{compactMobile && <span className="wp-workspace__mobile-title" aria-hidden="true">{mobileTitle}</span>}</Link>
      {hasDrawer && <button ref={menuButtonRef} type="button" className="wp-workspace__mobile-menu" aria-label={mobileNavigationOpen ? 'Close workspace navigation' : 'Open workspace navigation'} aria-expanded={mobileNavigationOpen} aria-controls="workspace-mobile-navigation" onClick={() => setMobileNavigationOpen((open) => !open)}><span /><span /><span /></button>}
      <QuickJump actions={quickJumpActions} />
      <div className="wp-workspace__account"><ThemeToggle iconOnly />{account}</div>
    </header>
    {compactMobile
      ? <><button type="button" tabIndex={-1} aria-label="Close workspace navigation" className={`wp-workspace__drawer-backdrop ${mobileNavigationOpen ? 'is-open' : ''}`} onClick={closeDrawer} />
        <div id="workspace-mobile-navigation" ref={drawerRef} role="dialog" aria-modal="true" aria-label={`${mobileTitle} navigation`} aria-hidden={!mobileNavigationOpen} inert={!mobileNavigationOpen} className={`wp-workspace__compact-drawer ${mobileNavigationOpen ? 'is-open' : ''}`}>{mobileDrawer({ close: closeDrawer })}</div></>
      : sidebar && <><button type="button" aria-label="Close workspace navigation" className={`wp-workspace__drawer-backdrop ${mobileNavigationOpen ? 'is-open' : ''}`} onClick={() => setMobileNavigationOpen(false)} /><div id="workspace-mobile-navigation" aria-hidden={!mobileNavigationOpen} className={`wp-workspace__mobile-drawer ${mobileNavigationOpen ? 'is-open' : ''}`} onClick={(event) => { if (event.target.closest('a')) setMobileNavigationOpen(false); }}>{sidebar}</div></>}
    <div className="wp-workspace__body">{sidebar}<main id="workspace-content" tabIndex={-1} className={`wp-workspace__main ${mainClassName}`}>{children}</main></div>
    {mobileNav}{assistant}
  </div>;
}
