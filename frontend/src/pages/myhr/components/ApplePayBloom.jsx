import { useEffect } from 'react';
import './ApplePayBloom.css';

/**
 * ApplePayBloom
 * High-end Apple Pay style micro-interaction confirmation modal.
 * Features concentric glowing rings, spring-animated checkmark SVG bloom,
 * sub-second speed metric badge, and tactile confirmation button.
 */
export default function ApplePayBloom({
  visible,
  title = 'Verified & Approved',
  subtitle = 'Executed in 0.18s',
  detail = 'Transaction certified compliant and recorded to audit ledger.',
  onClose,
  autoCloseMs = 3500,
}) {
  useEffect(() => {
    if (!visible || !onClose || !autoCloseMs) return undefined;
    const timer = setTimeout(() => {
      onClose();
    }, autoCloseMs);
    return () => clearTimeout(timer);
  }, [visible, onClose, autoCloseMs]);

  if (!visible) return null;

  return (
    <div className="apple-bloom__backdrop" role="dialog" aria-modal="true" aria-label={title} onClick={onClose}>
      <div className="apple-bloom__card" onClick={(e) => e.stopPropagation()}>
        {/* Ambient background glow */}
        <div className="apple-bloom__ambient-glow" aria-hidden="true" />

        {/* Concentric pulsing rings */}
        <div className="apple-bloom__rings" aria-hidden="true">
          <span className="apple-bloom__ring apple-bloom__ring--1" />
          <span className="apple-bloom__ring apple-bloom__ring--2" />
          <span className="apple-bloom__ring apple-bloom__ring--3" />
        </div>

        {/* Tactile checkmark emblem */}
        <div className="apple-bloom__icon-container">
          <svg className="apple-bloom__check-svg" viewBox="0 0 52 52" aria-hidden="true">
            <circle className="apple-bloom__check-circle" cx="26" cy="26" r="24" fill="none" />
            <path className="apple-bloom__check-path" fill="none" d="M14.1 27.2l7.1 7.2 16.7-16.8" />
          </svg>
        </div>

        {/* Content */}
        <div className="apple-bloom__content">
          <div className="apple-bloom__speed-badge">
            <span className="apple-bloom__bolt">⚡</span>
            <span>{subtitle}</span>
          </div>
          <h3 className="apple-bloom__title">{title}</h3>
          {detail && <p className="apple-bloom__detail">{detail}</p>}
        </div>

        {/* Done button */}
        <button
          type="button"
          className="apple-bloom__done-btn"
          onClick={onClose}
          autoFocus
        >
          Done
        </button>
      </div>
    </div>
  );
}
