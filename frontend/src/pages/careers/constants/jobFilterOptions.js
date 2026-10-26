import { EMPLOYMENT_TYPE_OPTIONS as BASE_EMPLOYMENT_TYPE_OPTIONS } from '../../../constants/employmentType';

/**
 * Static option lists for the Careers page filter controls.
 * Values must match the backend EmploymentType/WorkMode enum constants exactly.
 */

export const EMPLOYMENT_TYPE_OPTIONS = [
  ...BASE_EMPLOYMENT_TYPE_OPTIONS,
  { label: 'No preference', value: '' },
];

export const WORK_MODE_OPTIONS = [
  { label: 'Onsite', value: 'ONSITE' },
  { label: 'Remote', value: 'REMOTE' },
  { label: 'Hybrid', value: 'HYBRID' },
  { label: 'No preference', value: '' },
];

/**
 * Date Posted is a rolling window computed client-side (see
 * resolvePostedAfterDate below) and sent through the existing `postedAfter`
 * query param - no backend change needed, since the jobs API only accepts a
 * plain cutoff date, not a preset keyword like the events API does.
 */
export const DATE_POSTED_OPTIONS = [
  { label: 'Last 24 hours', value: 'LAST_24_HOURS' },
  { label: 'This week', value: 'THIS_WEEK' },
  { label: 'This month', value: 'THIS_MONTH' },
  { label: 'No preference', value: '' },
];

const DATE_POSTED_WINDOW_DAYS = {
  LAST_24_HOURS: 1,
  THIS_WEEK: 7,
  THIS_MONTH: 30,
};

/**
 * Resolves a DATE_POSTED_OPTIONS preset value into an ISO date string
 * (yyyy-MM-dd) suitable for the jobs API's `postedAfter` param, or '' when
 * no preset (or "No preference") is selected.
 */
export function resolvePostedAfterDate(datePostedPreset) {
  const windowDays = DATE_POSTED_WINDOW_DAYS[datePostedPreset];
  if (!windowDays) return '';

  const cutoff = new Date();
  cutoff.setDate(cutoff.getDate() - windowDays);
  return cutoff.toISOString().slice(0, 10);
}
