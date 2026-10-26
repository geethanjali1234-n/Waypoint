import { NavLink } from 'react-router-dom';
import { getMyHrSession } from '../../auth/myHrSession';
import { getRoleRouteSegment, resolveEffectiveRole } from '../../services/navigation/navigationRegistry';
import { useLocation } from 'react-router-dom';
import StrokeIcon from '../global/StrokeIcon';
import './MobileBottomNav.css';

export default function MobileBottomNav({ items }) {
  const location = useLocation();
  const role = resolveEffectiveRole(location.pathname, getMyHrSession());
  const base = '/myhr/' + getRoleRouteSegment(role);
  const links = items || [
    { label: 'Home', path: base + '/dashboard', icon: 'home' },
    { label: 'Time', path: base + '/timesheets', icon: 'time' },
    { label: 'Docs', path: base + '/document-requests', icon: 'document' },
    { label: 'Me', path: base + '/profile', icon: 'profile' },
  ];
  return <nav className="wp-mobile-bottom-nav" aria-label="Mobile Bottom Navigation"><div className="wp-mobile-bottom-nav__dock">
    {links.map((item) => <NavLink key={item.path} to={item.path} end={item.end} aria-label={'Mobile ' + item.label} className="wp-mobile-bottom-nav__item"><StrokeIcon name={item.icon} /><span>{item.label}</span></NavLink>)}
  </div></nav>;
}
