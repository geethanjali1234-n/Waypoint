const DESIGNATIONS = {
  COMPANY_ADMIN: 'Company Admin',
  HR: 'HR',
  EMPLOYEE: 'Employee',
  WAYPOINT_ADMIN: 'Super Admin',
};

export const NOT_ASSIGNED = 'Not assigned';

// Capitalizes a word only when it was typed all lower or all upper case, so mixed-case names such as "McDonald" survive.
function capitalizeWord(word) {
  if (word !== word.toLowerCase() && word !== word.toUpperCase()) return word;
  return word.toLowerCase().replace(/(^|['-])([a-z])/g, (match, edge, letter) => edge + letter.toUpperCase());
}

export function properName(value) {
  return String(value || '').trim().split(/\s+/).filter(Boolean).map(capitalizeWord).join(' ');
}

export function designationLabel(value) {
  const text = String(value || '').trim();
  if (!text) return '';
  return DESIGNATIONS[text.toUpperCase().replace(/\s+/g, '_')] || properName(text);
}

/**
 * "Full Name (Designation)" for a reporting manager. Only the real name and designation are ever shown: employee IDs,
 * login IDs, database IDs and emails are never used, even as a fallback. A missing manager reads "Not assigned".
 */
export function formatReportingManager(manager) {
  if (!manager) return NOT_ASSIGNED;
  const name = properName(manager.name);
  const designation = designationLabel(manager.designation || manager.roleLabel);
  if (name && designation) return `${name} (${designation})`;
  return name || designation || NOT_ASSIGNED;
}
