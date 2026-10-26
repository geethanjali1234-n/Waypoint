import { JOB_SALARY_RANGE_OPTIONS } from '../constants/jobSalaryRangeOptions';

export function formatJobSalary(salaryRange, salaryMin, salaryMax) {
  if (salaryRange) {
    return JOB_SALARY_RANGE_OPTIONS.find((option) => option.value === salaryRange)?.label || 'Not listed';
  }

  if (salaryMin == null && salaryMax == null) return 'Not listed';

  const money = (value) => `$${Number(value).toLocaleString()}`;
  if (salaryMin != null && salaryMax != null) return `${money(salaryMin)} – ${money(salaryMax)}`;
  return salaryMin != null ? `From ${money(salaryMin)}` : `Up to ${money(salaryMax)}`;
}

export function formatPostedAt(value) {
  if (!value) return null;

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;

  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}
