import './AuthSidePanel.css';
import WaypointMark from '../../../components/branding/WaypointMark';
import WorkspaceExplorer from '../../../components/branding/WorkspaceExplorer';

/**
 * AuthSidePanel - Waypoint brand panel shown alongside auth forms (sign in,
 * employer/job seeker registration), organized around the Waypoint mark,
 * headline copy, and a quiet watermark. Feature-specific to the auth pages,
 * not part of the shared component library.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.heading - Main headline content (a string, or a fragment with a manual line break).
 * @param {string} [props.description] - Supporting description text.
 * @param {string} [props.eyebrow] - Small brand statement above the headline.
 * @param {boolean} [props.showLogo=true] - Renders the Waypoint brand mark above the heading.
 * @param {boolean} [props.showPattern=true] - Renders a very subtle ambient background texture across the whole panel.
 * @param {string} [props.workspaceLabel] - Optional role/workspace label shown under the Waypoint brand.
 * @param {import('react').ReactNode} [props.illustration] - Optional additional illustration/graphic.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <AuthSidePanel
 *   heading={<>The jobs and events<br />actually worth your commute.</>}
 *   description="Waypoint matches you to opportunities based on your skills, not just your keywords."
 *   showLogo
 *   showPattern
 * />
 */
export default function AuthSidePanel({
  heading,
  description,
  eyebrow,
  showLogo = true,
  showPattern = true,
  workspaceLabel,
  illustration,
  className = '',
}) {
  const classes = ['wp-auth-side-panel', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {showPattern && <div className="wp-auth-side-panel__pattern" aria-hidden="true" />}

      <div className="wp-auth-side-panel__zones">
        {showLogo && (
          <div className="wp-auth-side-panel__logo">
            <WaypointMark className="wp-auth-side-panel__logo-mark" />
            <span className="wp-auth-side-panel__logo-text wp-waypoint-wordmark">Waypoint</span>
          </div>
        )}

        {workspaceLabel && (
          <span className="wp-auth-side-panel__workspace-label">
            {workspaceLabel} workspace
          </span>
        )}

        <div className="wp-auth-side-panel__content">
          {eyebrow && <p className="wp-auth-side-panel__eyebrow">{eyebrow}</p>}
          <h1 className="wp-auth-side-panel__heading">{heading}</h1>
          {description && <p className="wp-auth-side-panel__description">{description}</p>}
        </div>

        <WorkspaceExplorer />

      </div>

      <WaypointMark className="wp-auth-side-panel__watermark" variant="outline" />

      {illustration && <div className="wp-auth-side-panel__illustration">{illustration}</div>}
    </div>
  );
}
