import { describe, expect, it } from 'vitest';
import { formatDisplayLabel } from '../../utils/myHrDisplayLabel';

describe('formatDisplayLabel', () => {
  it.each([
    ['TAX_PAYROLL', 'Tax & Payroll'],
    ['TO_EMPLOYEE', 'To Employee'],
    ['FROM_EMPLOYEE', 'From Employee'],
    ['DOCUMENT_REQUEST', 'Document Request'],
    ['PROFILE_CHANGE_REQUEST', 'Profile Change Request'],
    ['MY_HR', 'My HR'],
    ['OTHER', 'Other'],
    ['UNRECOGNIZED_CODE', 'Unrecognized Code'],
  ])('formats %s as %s', (value, expected) => {
    expect(formatDisplayLabel(value)).toBe(expected);
  });
});
