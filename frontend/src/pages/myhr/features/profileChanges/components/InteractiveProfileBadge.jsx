import { useState } from 'react';
import './InteractiveProfileBadge.css';

/**
 * Profile Change Request Details Card
 * Simple, clean, non-flippable, accessible details card.
 * All information is directly visible with full text selectability and 1-click copy support.
 */
export default function InteractiveProfileBadge({
  employeeId = 'EMP-001',
  employeeName = 'Employee',
  fieldName = 'Profile Update',
  existingValue = '—',
  requestedValue = '—',
  status = 'PENDING',
  manager = 'Unassigned',
  requestedAt = '—',
  reason = '',
  processingComment = '',
  processedAt = '',
  onInspect,
  role = 'Staff',
  size = 'regular',
}) {
  const [copiedKey, setCopiedKey] = useState(null);

  const initials = (employeeName || 'EM')
    .split(' ')
    .filter(Boolean)
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  const handleCopy = (text, key) => {
    if (!text || text === '—') return;
    try {
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text);
      }
    } catch {
      // Ignore clipboard write failures in restricted environments
    }
    setCopiedKey(key);
    setTimeout(() => {
      setCopiedKey((curr) => (curr === key ? null : curr));
    }, 2000);
  };

  const statusTone =
    status === 'APPROVED' ? 'approved' : status === 'REJECTED' ? 'rejected' : 'pending';
  const statusLabel = status === 'APPROVED' ? 'Completed' : status;

  return (
    <div
      className={`wp-profile-card wp-profile-card--${size}`}
      role="region"
      aria-label={`Profile change details for ${employeeName}`}
    >
      {/* Header: Employee Identity + Status Tag */}
      <div className="wp-profile-card__header">
        <div className="wp-profile-card__identity">
          <div className="wp-profile-card__avatar" aria-hidden="true">
            <span>{initials}</span>
          </div>
          <div className="wp-profile-card__emp-info">
            <strong className="wp-profile-card__name">{employeeName}</strong>
            <span className="wp-profile-card__meta">
              <span className="wp-profile-card__id">{employeeId}</span>
              {role && <span className="wp-profile-card__role"> · {role}</span>}
            </span>
          </div>
        </div>
        <span className={`wp-profile-card__status wp-profile-card__status--${statusTone}`}>
          {statusLabel}
        </span>
      </div>

      {/* Requested Update: Field & Side-by-side Diff */}
      <div className="wp-profile-card__diff-section">
        <div className="wp-profile-card__diff-header">
          <span className="wp-profile-card__kicker">REQUESTED UPDATE</span>
          <strong className="wp-profile-card__field-name">{fieldName}</strong>
        </div>

        <div className="wp-profile-card__diff-grid">
          {/* FROM Value */}
          <div className="wp-profile-card__val-panel wp-profile-card__val-panel--from">
            <div className="wp-profile-card__panel-top">
              <span className="wp-profile-card__panel-tag">CURRENT (FROM)</span>
              {existingValue && existingValue !== '—' && (
                <button
                  type="button"
                  className="wp-profile-card__copy-btn"
                  onClick={() => handleCopy(String(existingValue), 'from')}
                  aria-label="Copy current value"
                  title="Copy current value to clipboard"
                >
                  {copiedKey === 'from' ? '✓ Copied' : '📋 Copy'}
                </button>
              )}
            </div>
            <div className="wp-profile-card__val-text" title={String(existingValue)}>
              {String(existingValue)}
            </div>
          </div>

          <div className="wp-profile-card__arrow" aria-hidden="true">
            →
          </div>

          {/* TO Value */}
          <div className="wp-profile-card__val-panel wp-profile-card__val-panel--to">
            <div className="wp-profile-card__panel-top">
              <span className="wp-profile-card__panel-tag">NEW (TO)</span>
              {requestedValue && requestedValue !== '—' && (
                <button
                  type="button"
                  className="wp-profile-card__copy-btn"
                  onClick={() => handleCopy(String(requestedValue), 'to')}
                  aria-label="Copy requested value"
                  title="Copy requested value to clipboard"
                >
                  {copiedKey === 'to' ? '✓ Copied' : '📋 Copy'}
                </button>
              )}
            </div>
            <div
              className="wp-profile-card__val-text wp-profile-card__val-text--highlight"
              title={String(requestedValue)}
            >
              {String(requestedValue)}
            </div>
          </div>
        </div>
      </div>

      {/* Audit & Request Details - Direct, visible, no flipping required */}
      <div className="wp-profile-card__audit-section">
        <div className="wp-profile-card__meta-grid">
          <div className="wp-profile-card__meta-item">
            <span className="wp-profile-card__meta-label">Reporting Manager</span>
            <strong className="wp-profile-card__meta-val">{manager}</strong>
          </div>
          <div className="wp-profile-card__meta-item">
            <span className="wp-profile-card__meta-label">Submitted On</span>
            <strong className="wp-profile-card__meta-val">{requestedAt}</strong>
          </div>
          {processedAt && (
            <div className="wp-profile-card__meta-item">
              <span className="wp-profile-card__meta-label">Processed On</span>
              <strong className="wp-profile-card__meta-val">{processedAt}</strong>
            </div>
          )}
        </div>

        {reason && (
          <div className="wp-profile-card__callout">
            <div className="wp-profile-card__callout-top">
              <span className="wp-profile-card__meta-label">Reason for Request</span>
              <button
                type="button"
                className="wp-profile-card__copy-btn"
                onClick={() => handleCopy(reason, 'reason')}
                aria-label="Copy reason"
                title="Copy reason to clipboard"
              >
                {copiedKey === 'reason' ? '✓ Copied' : '📋 Copy'}
              </button>
            </div>
            <p className="wp-profile-card__callout-text">{reason}</p>
          </div>
        )}

        {processingComment && (
          <div className="wp-profile-card__callout wp-profile-card__callout--comment">
            <div className="wp-profile-card__callout-top">
              <span className="wp-profile-card__meta-label">Reviewer Comment</span>
              <button
                type="button"
                className="wp-profile-card__copy-btn"
                onClick={() => handleCopy(processingComment, 'comment')}
                aria-label="Copy processing comment"
                title="Copy comment to clipboard"
              >
                {copiedKey === 'comment' ? '✓ Copied' : '📋 Copy'}
              </button>
            </div>
            <p className="wp-profile-card__callout-text">{processingComment}</p>
          </div>
        )}
      </div>

      {onInspect && (
        <div className="wp-profile-card__actions">
          <button
            type="button"
            className="wp-profile-card__action-btn"
            onClick={onInspect}
          >
            Inspect Request Details →
          </button>
        </div>
      )}
    </div>
  );
}

// Backwards-compatible export alias for any legacy imports
export function FlipExecutivePass(props) {
  return <InteractiveProfileBadge {...props} />;
}
