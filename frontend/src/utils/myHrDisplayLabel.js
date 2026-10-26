const DISPLAY_LABELS = {
  TAX_PAYROLL: 'Tax & Payroll',
  TO_EMPLOYEE: 'To Employee',
  FROM_EMPLOYEE: 'From Employee',
  DOCUMENT_REQUEST: 'Document Request',
  PROFILE_CHANGE_REQUEST: 'Profile Change Request',
  MY_HR: 'My HR',
  IN_PROGRESS: 'In Progress',
  COMPANY_ADMIN: 'Company Admin',
  HR: 'HR',
  HIGH: 'High',
  PENDING: 'Pending',
  OTHER: 'Other',
};

export function formatDisplayLabel(value) {
  if (value === null || value === undefined || value === '') return '';
  const text = String(value).trim();
  if (DISPLAY_LABELS[text]) return DISPLAY_LABELS[text];
  if (!/^[A-Z0-9]+(?:_[A-Z0-9]+)*$/.test(text)) return text;
  return text.toLowerCase().split('_').map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

export default formatDisplayLabel;
