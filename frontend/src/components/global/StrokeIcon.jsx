const paths = {
  download: ['M12 3v12m-4-4 4 4 4-4M5 16v5h14v-5'],
  copy: ['M9 9h12v12H9V9ZM5 15H3V3h12v2'],
  table: ['M3 3h18v18H3V3ZM3 9h18M9 9v12'],
  cards: ['M3 3h7v7H3V3ZM14 3h7v7h-7V3ZM3 14h7v7H3v-7ZM14 14h7v7h-7v-7'],
  filter: ['M4 7h16M4 12h16M4 17h16M8 5v4M16 10v4M10 15v4'],
  check: ['m5 12 4 4L19 6'],
  close: ['m6 6 12 12M18 6 6 18'],
  trash: ['M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7'],
  send: ['m3 3 18 9-18 9 4-9-4-9ZM7 12h14'],
  search: ['M19 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Zm-3 6 5 5'],
  home: ['m3 10 9-7 9 7v11h-6v-7H9v7H3V10Z'],
  people: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-4M16 3a4 4 0 0 1 0 8'],
  profile: ['M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M4 21v-2a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v2'],
  time: ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0ZM12 6v6l4 2'],
  document: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6ZM14 2v6h6M8 13h8M8 17h5'],
  briefcase: ['M8 7V3h8v4M3 7h18v14H3V7ZM3 12h18M10 12v3h4v-3'],
  calendar: ['M8 2v4M16 2v4M3 10h18M3 4h18v18H3V4Z'],
  organization: ['M3 21V3h12v18M15 9h6v12M7 7h4M7 11h4M7 15h4M3 21h18'],
  bell: ['M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4'],
  sun: ['M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0ZM12 1v2M12 21v2M1 12h2M21 12h2M4 4l2 2M18 18l2 2M4 20l2-2M18 6l2-2'],
  moon: ['M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10Z'],
  help: ['M9 8a3 3 0 1 1 5 2c-1.5 1-2 1.5-2 3M12 17h.01', 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20'],
  'arrow-right': ['M4 12h16m-6-6 6 6-6 6'],
  'chevron-left': ['m15 5-7 7 7 7'],
  'chevron-right': ['m9 5 7 7-7 7'],
  sort: ['m8 9 4-4 4 4M8 15l4 4 4-4'],
};

/** Decorative stroke icons. Label the surrounding control, not its SVG. */
export default function StrokeIcon({ name, size = 24, className = '' }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" className={className}>
    {(paths[name] || paths.help).map((d) => <path key={d} d={d} />)}
  </svg>;
}
