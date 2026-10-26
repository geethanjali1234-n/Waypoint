/**
 * WaypointPeopleIllustration - a lightweight, human-centered brand visual for
 * the auth panel. It uses simple vector shapes instead of a data chart so the
 * panel communicates connection and direction without making product claims.
 */
export default function WaypointPeopleIllustration({ className = '' }) {
  return (
    <svg
      className={className}
      viewBox="0 0 520 300"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="394" cy="116" r="94" fill="currentColor" opacity="0.07" />
      <circle cx="394" cy="116" r="65" stroke="currentColor" strokeWidth="1" opacity="0.12" />

      <g opacity="0.9">
        <rect x="30" y="34" width="142" height="78" rx="18" fill="currentColor" opacity="0.12" />
        <circle cx="58" cy="63" r="12" fill="currentColor" opacity="0.5" />
        <path d="M52 65c2-5 10-5 12 0" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.8" />
        <path d="M82 57h55M82 68h40M82 79h28" stroke="currentColor" strokeWidth="3" strokeLinecap="round" opacity="0.52" />
      </g>

      <g opacity="0.82">
        <rect x="352" y="38" width="134" height="72" rx="18" fill="currentColor" opacity="0.1" />
        <path d="M378 69h44M378 80h29" stroke="currentColor" strokeWidth="3" strokeLinecap="round" opacity="0.55" />
        <circle cx="458" cy="70" r="13" stroke="currentColor" strokeWidth="2" opacity="0.72" />
        <path d="m452 70 4 4 8-9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" opacity="0.8" />
      </g>

      <g opacity="0.72">
        <circle cx="126" cy="174" r="25" fill="currentColor" />
        <path d="M82 267c4-49 24-75 44-75s40 26 44 75" fill="currentColor" />
        <path d="M103 235c-16 4-28 16-38 34M149 235c15 4 27 16 37 34" stroke="currentColor" strokeWidth="12" strokeLinecap="round" opacity="0.7" />
      </g>

      <g opacity="0.94">
        <circle cx="265" cy="142" r="34" fill="currentColor" />
        <path d="M204 282c4-68 27-103 61-103s57 35 61 103" fill="currentColor" />
        <path d="M227 229c-20 6-37 22-49 43M303 229c20 6 37 22 49 43" stroke="currentColor" strokeWidth="15" strokeLinecap="round" opacity="0.85" />
      </g>

      <g opacity="0.64">
        <circle cx="402" cy="178" r="23" fill="currentColor" />
        <path d="M365 267c3-43 17-65 37-65s34 22 37 65" fill="currentColor" />
        <path d="M380 237c-13 5-23 16-30 29M424 237c13 5 23 16 30 29" stroke="currentColor" strokeWidth="10" strokeLinecap="round" opacity="0.66" />
      </g>

      <path d="M194 279h143" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.18" />
      <circle cx="177" cy="268" r="4" fill="currentColor" opacity="0.55" />
      <circle cx="350" cy="268" r="4" fill="currentColor" opacity="0.55" />
    </svg>
  );
}
