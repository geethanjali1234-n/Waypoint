import { describe, expect, it, vi } from 'vitest';
import {
  clearMyHrAuthentication,
  expireMyHrAuthentication,
  getAccessToken,
  getMyHrHomePath,
  getMyHrSession,
  MY_HR_AUTHENTICATION_CLEARED_EVENT,
  saveMyHrAuthentication,
} from '../../auth/myHrSession';

describe('getMyHrHomePath', () => {
  it.each([
    ['COMPANY_ADMIN', '/myhr/admin'],
    ['HR', '/myhr/hr'],
    ['EMPLOYEE', '/myhr/employee'],
  ])('maps %s to %s', (role, expected) => {
    expect(getMyHrHomePath(role)).toBe(expected);
  });
});

describe('My HR authentication storage', () => {
  it('stores and clears the access token and cached authenticated user together', () => {
    const user = { loginId: 'TD001', role: 'HR' };
    saveMyHrAuthentication('signed-jwt', user);

    expect(getAccessToken()).toBe('signed-jwt');
    expect(getMyHrSession()).toEqual(user);

    clearMyHrAuthentication();
    expect(getAccessToken()).toBeNull();
    expect(getMyHrSession()).toBeNull();
  });

  it('notifies protected routes when authentication expires', () => {
    const listener = vi.fn();
    window.addEventListener(MY_HR_AUTHENTICATION_CLEARED_EVENT, listener);
    saveMyHrAuthentication('expired-jwt', { loginId: 'TD001', role: 'HR' });

    expireMyHrAuthentication();

    expect(listener).toHaveBeenCalledOnce();
    expect(getAccessToken()).toBeNull();
    expect(getMyHrSession()).toBeNull();
    window.removeEventListener(MY_HR_AUTHENTICATION_CLEARED_EVENT, listener);
  });
});
