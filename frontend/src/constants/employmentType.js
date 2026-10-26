/**
 * Single shared source of truth for EmploymentType enum display options.
 * Values must match the backend EmploymentType enum constants exactly.
 * Used both as filter options (careers) and preference toggles (job seeker profile).
 */
export const EMPLOYMENT_TYPE_OPTIONS = [
  { label: 'Full-time', value: 'FULL_TIME' },
  { label: 'Part-time', value: 'PART_TIME' },
  { label: 'Contract', value: 'CONTRACT' },
  { label: 'Internship', value: 'INTERNSHIP' },
];
