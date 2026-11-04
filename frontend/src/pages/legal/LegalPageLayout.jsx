import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import WaypointMark from '../../components/branding/WaypointMark';
import '../../components/styles/tokens.css';
import { isLegalReviewPending } from './legalConfig';
import './legal.css';

/** A marked gap that only the operator or its counsel can fill in. Visible on purpose. */
export function LegalTodo({ children }) {
  return <span className="wp-legal__todo">[Legal review: {children}]</span>;
}

/** The operator value, or a visible placeholder when it has not been supplied. */
export function Detail({ value, label }) {
  return value?.trim() ? <>{value.trim()}</> : <LegalTodo>{label}</LegalTodo>;
}

/**
 * Shared shell for the public legal pages. No sign-in is needed, and nothing here talks to the backend.
 *
 * @param {string} title - Page heading, also used for the browser tab title.
 * @param {{ operatorName: string, contactEmail: string, effectiveDate: string }} details - Operator-supplied values.
 * @param {{ id: string, label: string }[]} sections - Table of contents entries (ids match the sections in children).
 * @param {{ to: string, label: string }} other - Link to the companion legal page.
 */
export default function LegalPageLayout({ title, details, sections, other, children }) {
  const reviewPending = isLegalReviewPending(details);

  useEffect(() => {
    const previous = document.title;
    document.title = `${title} - WayPoint`;
    return () => { document.title = previous; };
  }, [title]);

  return (
    <div className="wp-legal">
      <header className="wp-legal__header">
        <Link to="/" className="wp-legal__brand" aria-label="WayPoint home">
          <WaypointMark />
          <span className="wp-legal__brand-name">WayPoint</span>
        </Link>
        <nav className="wp-legal__header-links" aria-label="Legal pages">
          <Link to="/privacy">Privacy Policy</Link>
          <Link to="/terms">Terms of Service</Link>
        </nav>
      </header>

      <main className="wp-legal__main" id="main">
        <h1>{title}</h1>
        {reviewPending && (
          <p className="wp-legal__draft" role="note">
            <strong>Draft for legal review.</strong> This page has not yet been approved for publication, and the
            details marked in brackets below are placeholders that the operator of WayPoint must complete.
          </p>
        )}
        <p className="wp-legal__meta">
          Effective date: <Detail value={details?.effectiveDate} label="effective date" />
          <br />
          Operator: <Detail value={details?.operatorName} label="legal name of the operating company" />
        </p>

        <nav className="wp-legal__toc" aria-label="Contents">
          <h2>Contents</h2>
          <ol>
            {sections.map((section) => <li key={section.id}><a href={`#${section.id}`}>{section.label}</a></li>)}
          </ol>
        </nav>

        {children}

        <p className="wp-legal__contact">
          Questions about this page: <Detail value={details?.contactEmail} label="contact email address" />
        </p>
      </main>

      <footer className="wp-legal__footer">
        <Link to="/">Home</Link>
        <Link to={other.to}>{other.label}</Link>
      </footer>
    </div>
  );
}
