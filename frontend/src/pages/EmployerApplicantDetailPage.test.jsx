import { describe, expect, it } from 'vitest';
import formatEnum from '../utils/formatEnum';

describe('EmployerApplicantDetailPage helpers', () => {
  it('formats a single enum value', () => {
    expect(formatEnum('FULL_TIME')).toBe('Full Time');
  });

  it('formats multiple employment type values returned by the API', () => {
    expect(formatEnum(['FULL_TIME', 'CONTRACT'])).toBe('Full Time, Contract');
  });

  it('falls back when the value is empty', () => {
    expect(formatEnum([])).toBe('Not specified');
  });
});
