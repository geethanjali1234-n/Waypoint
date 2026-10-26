import { useState } from 'react';
import { useUiVariant } from '../../../../../context/uiVariant';
import AnalogTimesheetClock from './AnalogTimesheetClock';
import './TimesheetChronometer.css';

export default function TimesheetChronometer(props) {
  const { activeVariant } = useUiVariant();

  if (activeVariant === 'v1') {
    return <AnalogTimesheetClock {...props} />;
  }

  if (activeVariant === 'v2') {
    return <TactilePunchClock {...props} />;
  }

  return <ExecutiveMinimalistHud {...props} />;
}

/**
 * v2: The Tank Studio - Tactile Punch-Clock Station
 * Inspired by Crèche The Tank: playful physical punch button, bouncy overtime badge, and segmented meter.
 */
function TactilePunchClock({
  hoursLogged = 0,
  hoursTarget = 80,
  periodLabel = 'Current Period',
  onCrownClick,
  interactive = true,
  size = 'medium',
}) {
  const [punched, setPunched] = useState(false);
  const [showBreakdown, setShowBreakdown] = useState(false);

  const numericHours = Math.max(0, Number(hoursLogged) || 0);
  const numericTarget = Math.max(1, Number(hoursTarget) || 80);
  const regularHours = Math.min(numericHours, numericTarget);
  const overtimeHours = Math.max(0, numericHours - numericTarget);

  const percentage = Math.min(100, Math.round((regularHours / numericTarget) * 100));

  const handlePunch = () => {
    setPunched(true);
    if (onCrownClick) onCrownClick();
    setTimeout(() => setPunched(false), 700);
  };

  return (
    <div
      className={`wp-tank-punch wp-tank-punch--${size} ${punched ? 'is-punched' : ''}`}
      role="region"
      aria-label={`Tactile Punch Clock: ${numericHours}h total (${regularHours}h standard, ${overtimeHours}h overtime)`}
    >
      <div className="wp-tank-punch__card">
        {/* Header */}
        <div className="wp-tank-punch__header">
          <div className="wp-tank-punch__stamp-title">
            <span className="wp-tank-punch__icon" aria-hidden="true">⏱️</span>
            <strong>PUNCH CLOCK</strong>
          </div>
          <span className="wp-tank-punch__period">{periodLabel}</span>
        </div>

        {/* Display Readout */}
        <div className="wp-tank-punch__readout">
          <div className="wp-tank-punch__hours-block">
            <span className="wp-tank-punch__number">{numericHours}</span>
            <span className="wp-tank-punch__unit">HRS</span>
          </div>

          <div className="wp-tank-punch__status-col">
            <span className="wp-tank-punch__badge-reg">{regularHours}h Standard</span>
            {overtimeHours > 0 ? (
              <span className="wp-tank-punch__badge-ot">+{overtimeHours}h Overtime</span>
            ) : (
              <span className="wp-tank-punch__badge-target">{percentage}% of {numericTarget}h</span>
            )}
          </div>
        </div>

        {/* 10 Segment Progress Bar */}
        <div className="wp-tank-punch__meter" role="meter" aria-label="Progress toward target" aria-valuenow={regularHours} aria-valuemin={0} aria-valuemax={numericTarget}>
          {Array.from({ length: 10 }).map((_, i) => {
            const filled = (i + 1) * 10 <= percentage;
            return (
              <span
                key={i}
                className={`wp-tank-punch__meter-seg ${filled ? 'is-filled' : ''}`}
              />
            );
          })}
        </div>

        {/* Tactile Actions */}
        <div className="wp-tank-punch__actions">
          <button
            type="button"
            className="wp-tank-punch__lever-btn"
            onClick={handlePunch}
            disabled={!interactive}
          >
            <span className="wp-tank-punch__lever-head" aria-hidden="true" />
            <span>PUNCH TIME</span>
          </button>

          <button
            type="button"
            className="wp-tank-punch__toggle-btn"
            onClick={() => setShowBreakdown((prev) => !prev)}
            aria-expanded={showBreakdown}
          >
            {showBreakdown ? 'Hide details' : 'Audit breakdown'}
          </button>
        </div>

        {showBreakdown && (
          <div className="wp-tank-punch__drawer">
            <div className="wp-tank-punch__drawer-row">
              <span>Standard Hours</span>
              <strong>{regularHours}h</strong>
            </div>
            <div className="wp-tank-punch__drawer-row wp-tank-punch__drawer-row--ot">
              <span>Overtime (+1.5x)</span>
              <strong>+{overtimeHours}h</strong>
            </div>
            <div className="wp-tank-punch__drawer-row">
              <span>Target Standard</span>
              <strong>{numericTarget}h</strong>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * v3: Executive Sleek - Clean & Focused HUD (WP Rocket UX inspired)
 * No visual clutter, clean linear split for regular vs overtime hours, instant single CTA.
 */
function ExecutiveMinimalistHud({
  hoursLogged = 0,
  hoursTarget = 80,
  periodLabel = 'Current Period',
  onCrownClick,
  interactive = true,
  size = 'medium',
}) {
  const numericHours = Math.max(0, Number(hoursLogged) || 0);
  const numericTarget = Math.max(1, Number(hoursTarget) || 80);
  const regularHours = Math.min(numericHours, numericTarget);
  const overtimeHours = Math.max(0, numericHours - numericTarget);

  const regularPercentage = Math.min(100, (regularHours / numericTarget) * 100);
  const overtimePercentage = Math.min(100, (overtimeHours / numericTarget) * 100);

  return (
    <div
      className={`wp-exec-hud wp-exec-hud--${size}`}
      role="region"
      aria-label={`Executive HUD: ${numericHours}h logged of ${numericTarget}h target`}
    >
      <div className="wp-exec-hud__card">
        <div className="wp-exec-hud__top">
          <div className="wp-exec-hud__title-group">
            <span className="wp-exec-hud__live-dot" aria-hidden="true" />
            <strong className="wp-exec-hud__title">Pay Period Meter</strong>
          </div>
          <span className="wp-exec-hud__period">{periodLabel}</span>
        </div>

        <div className="wp-exec-hud__values">
          <div className="wp-exec-hud__num-group">
            <span className="wp-exec-hud__big-num">{numericHours}</span>
            <span className="wp-exec-hud__unit">HRS</span>
          </div>

          <div className="wp-exec-hud__pills">
            <span className="wp-exec-hud__pill wp-exec-hud__pill--reg">
              {regularHours}h Standard
            </span>
            {overtimeHours > 0 && (
              <span className="wp-exec-hud__pill wp-exec-hud__pill--ot">
                +{overtimeHours}h Overtime
              </span>
            )}
          </div>
        </div>

        {/* Linear Dual Progress Bar */}
        <div className="wp-exec-hud__bar-track" aria-hidden="true">
          <div
            className="wp-exec-hud__bar-fill wp-exec-hud__bar-fill--reg"
            style={{ width: `${regularPercentage}%` }}
          />
          {overtimeHours > 0 && (
            <div
              className="wp-exec-hud__bar-fill wp-exec-hud__bar-fill--ot"
              style={{ width: `${overtimePercentage}%` }}
            />
          )}
        </div>

        <div className="wp-exec-hud__footer">
          <small className="wp-exec-hud__target-text">
            Standard: {numericTarget}h{overtimeHours > 0 ? ` • Overtime: +${overtimeHours}h` : ''}
          </small>
          {onCrownClick && interactive && (
            <button
              type="button"
              className="wp-exec-hud__cta-btn"
              onClick={onCrownClick}
            >
              Enter Hours →
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
