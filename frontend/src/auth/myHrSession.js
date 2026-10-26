const MY_HR_SESSION_KEY = 'myHrAuthenticatedUser';
const ACCESS_TOKEN_KEY = 'accessToken';

export const MY_HR_AUTHENTICATION_CLEARED_EVENT = 'waypoint:myhr-authentication-cleared';

export const MY_HR_ROLES = ['COMPANY_ADMIN', 'HR', 'EMPLOYEE'];
export const WAYPOINT_WORKSPACE_ROLES = [...MY_HR_ROLES, 'WAYPOINT_ADMIN'];

export function getMyHrHomePath(role) {
  if (role === 'COMPANY_ADMIN') return '/myhr/admin';
  if (role === 'HR') return '/myhr/hr';
  if (role === 'EMPLOYEE') return '/myhr/employee';
  return '/myhr/login';
}

export function getWorkspaceHomePath(role) {
  if (role === 'WAYPOINT_ADMIN') return '/waypoint-admin/dashboard';
  return getMyHrHomePath(role);
}

export function saveMyHrSession(user) {
  localStorage.setItem(MY_HR_SESSION_KEY, JSON.stringify(user));
}

export function saveMyHrAuthentication(accessToken, user) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  saveMyHrSession(user);
}

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getMyHrSession() {
  try {
    const value = localStorage.getItem(MY_HR_SESSION_KEY);
    return value ? JSON.parse(value) : null;
  } catch {
    return null;
  }
}

export function clearMyHrSession() {
  localStorage.removeItem(MY_HR_SESSION_KEY);
}

export function clearMyHrAuthentication() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  clearMyHrSession();
}

export function expireMyHrAuthentication() {
  clearMyHrAuthentication();
  window.dispatchEvent(new Event(MY_HR_AUTHENTICATION_CLEARED_EVENT));
}
