import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import StrokeIcon from '../global/StrokeIcon';
import './DetailPanel.css';

export default function DetailPanel({ open, onClose, title, children, footer, className = '', fullScreen = false }) {
  const titleId = useId();
  const panel = useRef(null);
  const close = useRef(onClose);
  useEffect(() => { close.current = onClose; }, [onClose]);
  useEffect(() => {
    if (!open) return;
    const previousFocus = document.activeElement;
    const overflow = document.body.style.overflow;
    const background = [...document.body.children].filter((child) => !child.contains(panel.current));
    const previousInert = background.map((child) => child.hasAttribute('inert'));
    background.forEach((child) => child.setAttribute('inert', ''));
    document.body.style.overflow = 'hidden';
    const focusable = () => [...panel.current.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')].filter((element) => !element.closest('[hidden], [inert]'));
    (focusable()[0] || panel.current).focus();
    const onKey = (event) => {
      if (event.target.closest?.('[role="dialog"]') !== panel.current) return;
      if (event.key === 'Escape') { event.preventDefault(); close.current?.(); }
      if (event.key === 'Tab') {
        const elements = focusable();
        const first = elements[0];
        const last = elements.at(-1);
        if (!first) { event.preventDefault(); panel.current.focus(); }
        else if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.current)) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
      background.forEach((child, index) => { if (!previousInert[index]) child.removeAttribute('inert'); });
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, [open]);
  if (!open) return null;
  return createPortal(<div className="wp-detail-panel-overlay"><button type="button" className="wp-detail-panel-backdrop" tabIndex={-1} aria-label="Close details" onClick={onClose} /><section ref={panel} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} className={`wp-detail-panel ${fullScreen ? 'wp-detail-panel--full-screen' : ''} ${className}`}><header><h2 id={titleId}>{title}</h2><button type="button" aria-label="Close panel" onClick={onClose}><StrokeIcon name="close" /></button></header><div className="wp-detail-panel__body">{children}</div>{footer && <footer>{footer}</footer>}</section></div>, document.body);
}
