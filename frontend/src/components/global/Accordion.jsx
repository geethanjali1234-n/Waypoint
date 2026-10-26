import { useState } from 'react';
import './Accordion.css';

/**
 * Accordion - expandable/collapsible section, used for FAQ-style content.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.title - Header text shown when collapsed or expanded.
 * @param {import('react').ReactNode} props.children - Content revealed when expanded.
 * @param {boolean} [props.defaultOpen=false] - Whether the section starts expanded.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Accordion title="Why am I not seeing job matches?">
 *   Matches come from the skills on your resume.
 * </Accordion>
 */
export default function Accordion({ title, children, defaultOpen = false, className = '' }) {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  return (
    <div className={['wp-accordion', className].filter(Boolean).join(' ')}>
      <button
        type="button"
        className="wp-accordion__header"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span className="wp-accordion__title">{title}</span>
        <span className={`wp-accordion__icon ${isOpen ? 'wp-accordion__icon--open' : ''}`}>
          ⌄
        </span>
      </button>
      {isOpen && <div className="wp-accordion__content">{children}</div>}
    </div>
  );
}
