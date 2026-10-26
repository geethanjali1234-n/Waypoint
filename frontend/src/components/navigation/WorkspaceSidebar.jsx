import { useId, useState } from 'react';
import { NavLink } from 'react-router-dom';
import StrokeIcon from '../global/StrokeIcon';
import './WorkspaceSidebar.css';

export default function WorkspaceSidebar({ groups, label = 'Workspace navigation', storageKey = 'waypoint_workspace_sidebar_collapsed' }) {
  const id = useId();
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem(storageKey) === 'true'; } catch { return false; }
  });
  const toggle = () => setCollapsed((value) => {
    try { localStorage.setItem(storageKey, String(!value)); } catch { /* Optional preference. */ }
    return !value;
  });
  return <aside className={`wp-workspace-sidebar ${collapsed ? 'is-collapsed' : ''}`} aria-label={label}>
    <button type="button" className="wp-workspace-sidebar__toggle" aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-expanded={!collapsed} aria-controls={id} onClick={toggle}><StrokeIcon name={collapsed ? 'chevron-right' : 'chevron-left'} /></button>
    <nav id={id} aria-label={`${label} links`}>{groups.map((group) => <div key={group.label || 'Home'} className="wp-workspace-sidebar__section">
      {group.label && <span className="wp-workspace-sidebar__label">{group.label}</span>}
      {group.items.map((item) => <NavLink key={item.path} to={item.path} end={item.end} aria-label={item.label} title={collapsed ? item.label : undefined}><StrokeIcon name={item.icon} /><span>{item.label}</span></NavLink>)}
    </div>)}</nav>
  </aside>;
}
