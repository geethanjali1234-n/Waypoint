import { describe, expect, it, vi } from 'vitest';
import apiClient from '../../api/apiClient';
import myHrAccountApi from '../../api/myHrAccountApi';

vi.mock('../../api/apiClient');

describe('myHrAccountApi', () => {
  it('uses the existing authenticated My HR account endpoints', () => {
    const profile = { firstName: 'John' };
    myHrAccountApi.createHrAccount(profile);
    myHrAccountApi.createEmployeeAccount(profile, 'hr-2');
    myHrAccountApi.getEligibleReportingManagers();

    expect(apiClient.post).toHaveBeenNthCalledWith(1, '/myhr/accounts/hr', { profile });
    expect(apiClient.post).toHaveBeenNthCalledWith(2, '/myhr/accounts/employees', {
      profile, reportingManagerUserId: 'hr-2',
    });
    expect(apiClient.get).toHaveBeenCalledWith('/myhr/accounts/reporting-managers');
  });
});
