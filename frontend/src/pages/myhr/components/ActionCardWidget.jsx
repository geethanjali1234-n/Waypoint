import { useEffect, useState } from 'react';
import './ActionCardWidget.css';

/**
 * Interactive micro-widget for action cards on the My HR landing page and feature headers.
 * Sizing options:
 * - 'default' / 'compact': original corner / standalone size (40px)
 * - 'featured': prominent, centered visual stage inside the action card body (56-64px)
 * - 'banner': sleek telemetry badge for feature page headers (40-44px)
 *
 * Renders domain-tailored interactive components:
 * - timesheets: Live ticking analog chronometer/clock
 * - profile-change-requests: Holographic identity pass
 * - employees: Live team roster radar & pulse ring
 * - document-requests: Interactive document stack with verification check
 * - reports: Live animated sparkline / metric bar chart
 * - audit: Rotating security radar sweep with event pings
 * - organization: Rotating blueprint gear emblem
 */
export function ActionCardWidget({ type, variant = 'v1', size = 'default' }) {
  const [time, setTime] = useState(() => new Date());

  useEffect(() => {
    if (type !== 'timesheets') return undefined;
    const interval = setInterval(() => {
      setTime(new Date());
    }, 1000);
    return () => clearInterval(interval);
  }, [type]);

  const sizeClass = `wp-action-widget--size-${size}`;
  const variantClass = `wp-action-widget--${variant}`;

  if (type === 'timesheets') {
    const seconds = time.getSeconds();
    const minutes = time.getMinutes();
    const hours = time.getHours() % 12;

    const secondDeg = seconds * 6;
    const minuteDeg = minutes * 6 + seconds * 0.1;
    const hourDeg = hours * 30 + minutes * 0.5;

    return (
      <div className={`wp-action-widget wp-action-widget--clock ${sizeClass} ${variantClass}`} aria-hidden="true" title="Live timesheet chronometer">
        <div className="wp-action-clock">
          <div className="wp-action-clock__dial">
            <span className="wp-action-clock__tick wp-action-clock__tick--12" />
            <span className="wp-action-clock__tick wp-action-clock__tick--3" />
            <span className="wp-action-clock__tick wp-action-clock__tick--6" />
            <span className="wp-action-clock__tick wp-action-clock__tick--9" />
            {size === 'featured' && (
              <>
                <span className="wp-action-clock__tick wp-action-clock__tick--1" />
                <span className="wp-action-clock__tick wp-action-clock__tick--2" />
                <span className="wp-action-clock__tick wp-action-clock__tick--4" />
                <span className="wp-action-clock__tick wp-action-clock__tick--5" />
                <span className="wp-action-clock__tick wp-action-clock__tick--7" />
                <span className="wp-action-clock__tick wp-action-clock__tick--8" />
                <span className="wp-action-clock__tick wp-action-clock__tick--10" />
                <span className="wp-action-clock__tick wp-action-clock__tick--11" />
              </>
            )}
            <div className="wp-action-clock__hand wp-action-clock__hand--hour" style={{ transform: `rotate(${hourDeg}deg)` }} />
            <div className="wp-action-clock__hand wp-action-clock__hand--min" style={{ transform: `rotate(${minuteDeg}deg)` }} />
            <div className="wp-action-clock__hand wp-action-clock__hand--sec" style={{ transform: `rotate(${secondDeg}deg)` }} />
            <div className="wp-action-clock__cap" />
          </div>
        </div>
      </div>
    );
  }

  if (type === 'profile-change-requests') {
    return (
      <div className={`wp-action-widget wp-action-widget--badge ${sizeClass} ${variantClass}`} aria-hidden="true" title="Holographic ID badge">
        <div className="wp-action-badge">
          <div className="wp-action-badge__clip" />
          <div className="wp-action-badge__shimmer" />
          <div className="wp-action-badge__avatar" />
          <div className="wp-action-badge__lines">
            <span />
            <span />
            {size === 'featured' && <span />}
          </div>
          <span className="wp-action-badge__chip" />
        </div>
      </div>
    );
  }

  if (type === 'employees') {
    return (
      <div className={`wp-action-widget wp-action-widget--team ${sizeClass} ${variantClass}`} aria-hidden="true" title="Active team pulse">
        <div className="wp-action-team">
          <div className="wp-action-team__ring" />
          {size === 'featured' && <div className="wp-action-team__ring-inner" />}
          <div className="wp-action-team__pulse" />
          <div className="wp-action-team__node wp-action-team__node--1" />
          <div className="wp-action-team__node wp-action-team__node--2" />
          <div className="wp-action-team__node wp-action-team__node--3" />
          {size === 'featured' && <div className="wp-action-team__node wp-action-team__node--4" />}
          <div className="wp-action-team__core">
            <span className="wp-action-team__num">●</span>
          </div>
        </div>
      </div>
    );
  }

  if (type === 'document-requests') {
    return (
      <div className={`wp-action-widget wp-action-widget--docs ${sizeClass} ${variantClass}`} aria-hidden="true" title="Document tracker">
        <div className="wp-action-docs">
          <div className="wp-action-docs__sheet wp-action-docs__sheet--back" />
          <div className="wp-action-docs__sheet wp-action-docs__sheet--front">
            <span className="wp-action-docs__line wp-action-docs__line--1" />
            <span className="wp-action-docs__line wp-action-docs__line--2" />
            {size === 'featured' && <span className="wp-action-docs__line wp-action-docs__line--3" />}
            <span className="wp-action-docs__check">✓</span>
          </div>
        </div>
      </div>
    );
  }

  if (type === 'reports') {
    return (
      <div className={`wp-action-widget wp-action-widget--chart ${sizeClass} ${variantClass}`} aria-hidden="true" title="Live analytics sparkline">
        <div className="wp-action-chart">
          <span className="wp-action-chart__bar wp-action-chart__bar--1" />
          <span className="wp-action-chart__bar wp-action-chart__bar--2" />
          <span className="wp-action-chart__bar wp-action-chart__bar--3" />
          <span className="wp-action-chart__bar wp-action-chart__bar--4" />
          {size === 'featured' && <span className="wp-action-chart__bar wp-action-chart__bar--5" />}
          <span className="wp-action-chart__dot" />
        </div>
      </div>
    );
  }

  if (type === 'audit') {
    return (
      <div className={`wp-action-widget wp-action-widget--radar ${sizeClass} ${variantClass}`} aria-hidden="true" title="Security audit radar">
        <div className="wp-action-radar">
          <div className="wp-action-radar__sweep" />
          <div className="wp-action-radar__ping wp-action-radar__ping--1" />
          <div className="wp-action-radar__ping wp-action-radar__ping--2" />
          {size === 'featured' && <div className="wp-action-radar__ping wp-action-radar__ping--3" />}
          <div className="wp-action-radar__center" />
        </div>
      </div>
    );
  }

  if (type === 'organization') {
    return (
      <div className={`wp-action-widget wp-action-widget--org ${sizeClass} ${variantClass}`} aria-hidden="true" title="Organization blueprint">
        <div className="wp-action-org">
          <div className="wp-action-org__gear">
            <span className="wp-action-org__tooth wp-action-org__tooth--1" />
            <span className="wp-action-org__tooth wp-action-org__tooth--2" />
            <span className="wp-action-org__tooth wp-action-org__tooth--3" />
            <div className="wp-action-org__inner" />
          </div>
        </div>
      </div>
    );
  }

  return null;
}
