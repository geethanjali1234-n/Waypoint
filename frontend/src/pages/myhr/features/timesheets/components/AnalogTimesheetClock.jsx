import { useState, useEffect, useRef } from 'react';
import './TimesheetChronometer.css';

export default function AnalogTimesheetClock({
  hoursLogged = 0,
  hoursTarget = 80,
  periodLabel = 'Current Period',
  size = 'medium', // 'small' | 'medium' | 'large'
  interactive = true,
  onCrownClick,
}) {
  const [time, setTime] = useState(() => new Date());
  const [isWinding, setIsWinding] = useState(false);
  const [tilt, setTilt] = useState({ x: 0, y: 0 });
  const clockRef = useRef(null);

  // Live real-time tick
  useEffect(() => {
    const timer = setInterval(() => {
      setTime(new Date());
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const seconds = time.getSeconds();
  const minutes = time.getMinutes();
  const hours = time.getHours();

  // Hand rotation degrees
  const secondDeg = seconds * 6;
  const minuteDeg = minutes * 6 + seconds * 0.1;
  const hourDeg = (hours % 12) * 30 + minutes * 0.5;

  // Overtime calculation
  const numericHours = Number(hoursLogged) || 0;
  const numericTarget = Number(hoursTarget) || 80;

  // Regular hours capped at target
  const regularHours = Math.min(numericHours, numericTarget);
  // Overtime is whatever exceeds standard target
  const overtimeHours = Math.max(0, numericHours - numericTarget);

  const regularPercentage = Math.min(100, Math.round((regularHours / numericTarget) * 100));
  // Overtime percentage relative to standard 8-hour overtime block or target
  const overtimePercentage = Math.min(100, Math.round((overtimeHours / 20) * 100));

  // Radii for dual progress rings
  const regularRadius = 88;
  const regularCircumference = 2 * Math.PI * regularRadius;
  const regularOffset = regularCircumference - (regularPercentage / 100) * regularCircumference;

  // Overtime ring sits slightly concentric inside/outside
  const overtimeRadius = 80;
  const overtimeCircumference = 2 * Math.PI * overtimeRadius;
  const overtimeOffset = overtimeCircumference - (overtimePercentage / 100) * overtimeCircumference;

  const handleMouseMove = (e) => {
    if (!interactive || !clockRef.current) return;
    const rect = clockRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left - rect.width / 2;
    const y = e.clientY - rect.top - rect.height / 2;
    setTilt({
      x: (y / (rect.height / 2)) * -5,
      y: (x / (rect.width / 2)) * 5,
    });
  };

  const handleMouseLeave = () => {
    setTilt({ x: 0, y: 0 });
  };

  const handleCrown = () => {
    setIsWinding(true);
    if (onCrownClick) onCrownClick();
    setTimeout(() => setIsWinding(false), 900);
  };

  const formattedHours = Number.isInteger(numericHours) ? numericHours : numericHours.toFixed(1);
  const formattedOvertime = Number.isInteger(overtimeHours) ? overtimeHours : overtimeHours.toFixed(1);

  return (
    <div
      ref={clockRef}
      className={`wp-chronometer wp-chronometer--${size} ${isWinding ? 'is-winding' : ''} ${overtimeHours > 0 ? 'has-overtime' : ''}`}
      onMouseMove={handleMouseMove}
      onMouseLeave={handleMouseLeave}
      style={{
        transform: `perspective(600px) rotateX(${tilt.x}deg) rotateY(${tilt.y}deg)`,
      }}
      aria-label={`Timesheet Chronometer: ${formattedHours} hours total (${regularHours} standard, ${overtimeHours} overtime)`}
    >
      {/* Crown / Chronograph Winding Button */}
      <button
        type="button"
        className="wp-chronometer__crown"
        onClick={handleCrown}
        disabled={!interactive || !onCrownClick}
        title={onCrownClick ? 'Enter hours' : 'Period clock'}
        aria-label={onCrownClick ? 'Enter hours using clock' : 'Period clock'}
      >
        <span className="wp-chronometer__crown-stem" />
        <span className="wp-chronometer__crown-head" />
      </button>

      {/* Main Clock Case */}
      <div className="wp-chronometer__case">
        <svg
          className="wp-chronometer__dial"
          viewBox="0 0 200 200"
          aria-hidden="true"
        >
          {/* Outer Bezel */}
          <circle cx="100" cy="100" r="96" className="wp-dial__bezel" />
          <circle cx="100" cy="100" r="92" className="wp-dial__inner-bezel" />

          {/* Regular Hours Track */}
          <circle
            cx="100"
            cy="100"
            r={regularRadius}
            className="wp-dial__track"
            fill="none"
            strokeWidth="5"
          />

          {/* Regular Hours Progress Arc (Emerald) */}
          <circle
            cx="100"
            cy="100"
            r={regularRadius}
            className="wp-dial__progress wp-dial__progress--regular"
            fill="none"
            strokeWidth="5"
            strokeDasharray={regularCircumference}
            strokeDashoffset={regularOffset}
            strokeLinecap="round"
            transform="rotate(-90 100 100)"
          />

          {/* Overtime Ring (Vibrant Amber / Orange) */}
          {overtimeHours > 0 && (
            <>
              <circle
                cx="100"
                cy="100"
                r={overtimeRadius}
                className="wp-dial__track wp-dial__track--overtime"
                fill="none"
                strokeWidth="4"
              />
              <circle
                cx="100"
                cy="100"
                r={overtimeRadius}
                className="wp-dial__progress wp-dial__progress--overtime"
                fill="none"
                strokeWidth="4"
                strokeDasharray={overtimeCircumference}
                strokeDashoffset={overtimeOffset}
                strokeLinecap="round"
                transform="rotate(-90 100 100)"
              />
            </>
          )}

          {/* Hour & Minute Ticks */}
          {Array.from({ length: 12 }).map((_, i) => {
            const angle = i * 30;
            const isQuarter = i % 3 === 0;
            const r1 = overtimeHours > 0 ? 74 : 80;
            const r2 = isQuarter ? r1 - 8 : r1 - 5;
            const rad = (angle * Math.PI) / 180;
            const x1 = 100 + r1 * Math.sin(rad);
            const y1 = 100 - r1 * Math.cos(rad);
            const x2 = 100 + r2 * Math.sin(rad);
            const y2 = 100 - r2 * Math.cos(rad);

            return (
              <line
                key={angle}
                x1={x1}
                y1={y1}
                x2={x2}
                y2={y2}
                className={isQuarter ? 'wp-dial__tick wp-dial__tick--major' : 'wp-dial__tick'}
              />
            );
          })}

          {/* Subdial / Digital Readout Box */}
          <rect x="54" y="115" width="92" height="30" rx="6" className="wp-dial__screen-bg" />

          {overtimeHours > 0 ? (
            <>
              <text x="100" y="126" textAnchor="middle" className="wp-dial__screen-label wp-dial__screen-label--ot">
                STANDARD {regularHours}h • OVERTIME +{formattedOvertime}h
              </text>
              <text x="100" y="139" textAnchor="middle" className="wp-dial__screen-value wp-dial__screen-value--ot">
                {formattedHours}h TOTAL
              </text>
            </>
          ) : (
            <>
              <text x="100" y="127" textAnchor="middle" className="wp-dial__screen-label">
                LOGGED / TARGET
              </text>
              <text x="100" y="139" textAnchor="middle" className="wp-dial__screen-value">
                {formattedHours}h / {numericTarget}h
              </text>
            </>
          )}

          {/* Brand Mark */}
          <text x="100" y="65" textAnchor="middle" className="wp-dial__brand">
            WAYPOINT
          </text>
          <text x="100" y="73" textAnchor="middle" className="wp-dial__subbrand">
            CHRONO 80/20
          </text>

          {/* Hour Hand */}
          <line
            x1="100"
            y1="100"
            x2="100"
            y2="55"
            className="wp-dial__hand wp-dial__hand--hour"
            transform={`rotate(${hourDeg} 100 100)`}
          />

          {/* Minute Hand */}
          <line
            x1="100"
            y1="100"
            x2="100"
            y2="42"
            className="wp-dial__hand wp-dial__hand--minute"
            transform={`rotate(${minuteDeg} 100 100)`}
          />

          {/* Second Hand with Counterweight */}
          <g transform={`rotate(${secondDeg} 100 100)`}>
            <line x1="100" y1="112" x2="100" y2="35" className="wp-dial__hand wp-dial__hand--second" />
            <circle cx="100" cy="35" r="2.5" className="wp-dial__second-tip" />
          </g>

          {/* Center Jewel & Cap */}
          <circle cx="100" cy="100" r="5" className="wp-dial__center-cap" />
          <circle cx="100" cy="100" r="2" className="wp-dial__center-jewel" />
        </svg>

        {/* Lens reflection glare */}
        <div className="wp-chronometer__lens" aria-hidden="true" />
      </div>

      {/* Footer Pill: Distinguish Standard vs Overtime */}
      <div className="wp-chronometer__footer">
        <div className="wp-chronometer__pills-row">
          <div className="wp-chronometer__stat-pill">
            <span className="wp-chronometer__dot wp-chronometer__dot--reg" />
            <span className="wp-chronometer__pct">{regularHours}h Standard</span>
          </div>

          {overtimeHours > 0 && (
            <div className="wp-chronometer__stat-pill wp-chronometer__stat-pill--ot">
              <span className="wp-chronometer__dot wp-chronometer__dot--ot" />
              <span className="wp-chronometer__pct">+{formattedOvertime}h Overtime</span>
            </div>
          )}
        </div>
        <span className="wp-chronometer__period">{periodLabel}</span>
      </div>
    </div>
  );
}
