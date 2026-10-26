import { useState, useMemo, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { audioEngine } from '../utils/WaypointAudioEngine';

const DAYS = [
  { key: 'mon', name: 'Mon', full: 'Monday' },
  { key: 'tue', name: 'Tue', full: 'Tuesday' },
  { key: 'wed', name: 'Wed', full: 'Wednesday' },
  { key: 'thu', name: 'Thu', full: 'Thursday' },
  { key: 'fri', name: 'Fri', full: 'Friday' },
  { key: 'sat', name: 'Sat', full: 'Saturday' },
  { key: 'sun', name: 'Sun', full: 'Sunday' },
];

export default function TimesheetSimulator() {
  const [hours, setHours] = useState({
    mon: 8,
    tue: 8,
    wed: 8,
    thu: 8,
    fri: 8,
    sat: 0,
    sun: 0,
  });

  const [isApproved, setIsApproved] = useState(false);
  const [approvalStampTime, setApprovalStampTime] = useState(null);

  const totalHours = useMemo(() => {
    return Object.values(hours).reduce((acc, h) => acc + Number(h || 0), 0);
  }, [hours]);

  const regularHours = Math.min(totalHours, 40);
  const overtimeHours = Math.max(0, totalHours - 40);
  const utilization = Math.min(100, Math.round((totalHours / 40) * 100));

  const handleSliderChange = (dayKey, val) => {
    const num = Math.max(0, Math.min(16, Number(val)));
    setHours((prev) => ({ ...prev, [dayKey]: num }));
    audioEngine.playSlider(num / 16);
    setIsApproved(false);
  };

  const handlePreset = (type) => {
    audioEngine.playClick();
    setIsApproved(false);
    if (type === 'standard') {
      setHours({ mon: 8, tue: 8, wed: 8, thu: 8, fri: 8, sat: 0, sun: 0 });
    } else if (type === 'sprint') {
      setHours({ mon: 10, tue: 10, wed: 10, thu: 10, fri: 10, sat: 4, sun: 0 });
    } else if (type === 'clear') {
      setHours({ mon: 0, tue: 0, wed: 0, thu: 0, fri: 0, sat: 0, sun: 0 });
    }
  };

  const handleSimulateApproval = () => {
    audioEngine.playSuccess();
    setIsApproved(true);
    setApprovalStampTime(new Date().toLocaleTimeString());
  };

  const handleDismissApproval = () => {
    audioEngine.playClick();
    setIsApproved(false);
  };

  // Keyboard accessibility: ESC dismisses approval overlay
  useEffect(() => {
    if (!isApproved) return;
    const onKey = (e) => {
      if (e.key === 'Escape') {
        setIsApproved(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isApproved]);

  return (
    <div className="wp-sim-card">
      <div className="wp-sim-header">
        <div className="wp-sim-title-group">
          <span className="wp-sim-badge">INTERACTIVE LAB</span>
          <h3 className="wp-sim-title">Live Timesheet & Compliance Sandbox</h3>
          <p className="wp-sim-desc">
            Drag daily hours below to test real-time payroll calculations, overtime compliance detection, and instant multi-tiered approval.
          </p>
        </div>

        <div className="wp-sim-presets">
          <button type="button" className="wp-sim-preset-btn" onClick={() => handlePreset('standard')}>
            Standard 40h
          </button>
          <button type="button" className="wp-sim-preset-btn" onClick={() => handlePreset('sprint')}>
            Sprint 54h
          </button>
          <button type="button" className="wp-sim-preset-btn" onClick={() => handlePreset('clear')}>
            Reset 0h
          </button>
        </div>
      </div>

      <div className="wp-sim-metrics-bar">
        <div className="wp-sim-metric">
          <span className="wp-sim-metric-label">TOTAL HOURS</span>
          <span className="wp-sim-metric-value">{totalHours.toFixed(1)}<small>h</small></span>
        </div>
        <div className="wp-sim-metric">
          <span className="wp-sim-metric-label">REGULAR</span>
          <span className="wp-sim-metric-value wp-sim-metric-value--cyan">{regularHours.toFixed(1)}<small>h</small></span>
        </div>
        <div className="wp-sim-metric">
          <span className="wp-sim-metric-label">OVERTIME</span>
          <span className={`wp-sim-metric-value ${overtimeHours > 0 ? 'wp-sim-metric-value--gold' : ''}`}>
            {overtimeHours.toFixed(1)}<small>h</small>
          </span>
        </div>
        <div className="wp-sim-metric">
          <span className="wp-sim-metric-label">CAPACITY SCORE</span>
          <span className="wp-sim-metric-value wp-sim-metric-value--emerald">{utilization}%</span>
        </div>
      </div>

      {/* Progress capacity bar */}
      <div className="wp-sim-bar-wrap">
        <div
          className="wp-sim-bar-fill"
          style={{
            width: `${Math.min(100, (totalHours / 60) * 100)}%`,
            background: overtimeHours > 0
              ? 'linear-gradient(90deg, #10b981, #00ff87, #ffb800)'
              : 'linear-gradient(90deg, #0b624f, #00ff87)',
          }}
        />
        <div className="wp-sim-bar-marker" style={{ left: `${(40 / 60) * 100}%` }}>
          <span>40h Threshold</span>
        </div>
      </div>

      {/* Daily Day Sliders */}
      <div className="wp-sim-sliders-grid">
        {DAYS.map((day) => {
          const val = hours[day.key] || 0;
          return (
            <div key={day.key} className={`wp-sim-day-cell ${val > 8 ? 'wp-sim-day-cell--ot' : ''}`}>
              <div className="wp-sim-day-header">
                <span className="wp-sim-day-name">{day.name}</span>
                <span className="wp-sim-day-val">{val}h</span>
              </div>
              <input
                type="range"
                min="0"
                max="14"
                step="0.5"
                value={val}
                onChange={(e) => handleSliderChange(day.key, e.target.value)}
                className="wp-sim-slider"
                aria-label={`Hours for ${day.full}`}
              />
              <div className="wp-sim-day-footer">
                <span className="wp-sim-day-tag">
                  {val === 0 ? 'Off' : val <= 8 ? 'Standard' : 'Extended'}
                </span>
              </div>
            </div>
          );
        })}
      </div>

      {/* Simulated Output & Stamp */}
      <div className="wp-sim-footer">
        <div className="wp-sim-audit-pill">
          <span className="wp-sim-audit-dot" aria-hidden="true" />
          <span>
            {overtimeHours > 0
              ? `Compliance: Overtime policy auto-flagged (+${overtimeHours.toFixed(1)}h 1.5x)`
              : 'Compliance: Within FLSA bi-weekly standard tolerance'}
          </span>
        </div>

        <div className="wp-sim-actions">
          <button
            type="button"
            className="wp-sim-approve-btn"
            onClick={handleSimulateApproval}
          >
            ✦ Simulate 1-Click Approval
          </button>
          <Link to="/myhr/employee/timesheets" className="wp-sim-portal-link">
            Open Real Timesheets Module ↗
          </Link>
        </div>
      </div>

      {/* Floating Approval Stamp Animation with Intuitive Back & Close Controls */}
      {isApproved && (
        <div
          className="wp-sim-stamp-overlay"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              handleDismissApproval();
            }
          }}
          role="dialog"
          aria-modal="true"
          aria-label="Simulation Approval Confirmation"
        >
          <div className="wp-sim-stamp-container">
            <button
              type="button"
              className="wp-sim-stamp-close-btn"
              onClick={handleDismissApproval}
              title="Close and return to sandbox (Esc)"
              aria-label="Close approval stamp"
            >
              ✕
            </button>

            <div className="wp-sim-stamp">
              <div className="wp-sim-stamp-ring">
                <span className="wp-sim-stamp-text">WAYPOINT CERTIFIED</span>
                <span className="wp-sim-stamp-badge">✓ APPROVED</span>
                <span className="wp-sim-stamp-date">{approvalStampTime} • ZERO AUDIT DISCREPANCY</span>
              </div>
            </div>

            <div className="wp-sim-stamp-actions">
              <button
                type="button"
                className="wp-sim-stamp-back-btn"
                onClick={handleDismissApproval}
              >
                ← Back to Sandbox
              </button>
              <button
                type="button"
                className="wp-sim-stamp-reset-btn"
                onClick={() => handlePreset('standard')}
              >
                ↻ Reset 40h
              </button>
              <Link
                to="/myhr/employee/timesheets"
                className="wp-sim-stamp-portal-btn"
                onClick={() => audioEngine.playClick()}
              >
                Open Real Timesheets Module ↗
              </Link>
            </div>

            <span className="wp-sim-stamp-hint">
              Click anywhere outside or press <kbd>Esc</kbd> to return to sandbox
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
