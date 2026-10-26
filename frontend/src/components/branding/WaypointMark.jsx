import './WaypointMark.css';

/**
 * WaypointMark - the shared Waypoint brand mark used across the authenticated
 * shell and the authentication panel. The filled shape matches the mark used
 * by the integrated job seeker/employer branch header.
 */
export default function WaypointMark({ className = '', title, variant = 'filled' }) {
  const classes = ['wp-waypoint-mark', `wp-waypoint-mark--${variant}`, className]
    .filter(Boolean)
    .join(' ');
  const labelledProps = title
    ? { role: 'img', 'aria-label': title }
    : { 'aria-hidden': 'true', focusable: 'false' };

  return (
    <span className={classes} {...labelledProps}>
      <span className="wp-waypoint-mark__letter">W</span>
    </span>
  );
}
