/**
 * Prototype-only organization directory for the My HR module.
 * Mirrors the sample dataset from the My HR design doc (Tardus Inc / TD
 * prefix) so every teammate demos against the same accounts and reporting
 * structure. No backend exists for My HR yet - this is a localStorage-backed
 * stand-in that survives navigation and refresh, until real API endpoints are
 * built.
 */

import {
  MY_HR_EMPLOYEE_EMAIL,
  MY_HR_EMPLOYEE_PASSWORD,
  MY_HR_EMPLOYEE_SESSION_TOKEN,
  MY_HR_ADMIN_SESSION_TOKEN,
  MY_HR_HR_SESSION_TOKEN,
} from '../../constants/myHrEmployeeAuth';

const STORAGE_KEY = 'myhrDirectory';
const SESSION_KEY = 'myhrSession';

export const MYHR_ROLES = {
  COMPANY_ADMIN: 'COMPANY_ADMIN',
  HR: 'HR',
  EMPLOYEE: 'EMPLOYEE',
};

export const MYHR_DEMO_PASSWORD = 'Waypoint@123';

export const MYHR_ORGANIZATION = { code: 'TD', name: 'Tardus Inc' };

const SEED_USERS = [
  {
    loginId: 'TDADMIN',
    name: 'Tardus Admin',
    role: MYHR_ROLES.COMPANY_ADMIN,
    reportsTo: null,
    personalEmail: 'admin@tardus.com',
  },
  { loginId: 'TD001', name: 'Pragna', role: MYHR_ROLES.HR, reportsTo: null },
  { loginId: 'TD002', name: 'Vasavi', role: MYHR_ROLES.HR, reportsTo: null },
  {
    loginId: 'TD003',
    name: 'John',
    role: MYHR_ROLES.EMPLOYEE,
    reportsTo: 'TD001',
    personalEmail: 'john@example.com',
    phoneNumber: '',
    designation: 'Software Engineer',
    hourlyRate: '45',
  },
  {
    loginId: 'TD004',
    name: 'Sarah',
    role: MYHR_ROLES.EMPLOYEE,
    reportsTo: 'TD002',
    personalEmail: 'sarah@example.com',
    phoneNumber: '',
    designation: 'Recruiter',
    hourlyRate: '38',
  },
  {
    loginId: 'TD005',
    name: 'David',
    role: MYHR_ROLES.EMPLOYEE,
    reportsTo: 'TD002',
    personalEmail: 'david@example.com',
    phoneNumber: '',
    designation: 'QA Analyst',
    hourlyRate: '40',
  },
];

function readDirectory() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return SEED_USERS;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : SEED_USERS;
  } catch {
    return SEED_USERS;
  }
}

function writeDirectory(users) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(users));
}

export function getAllUsers() {
  return readDirectory();
}

export function findUserByLoginId(loginId) {
  const normalized = (loginId || '').trim().toUpperCase();
  return readDirectory().find((user) => user.loginId === normalized) || null;
}

function findUserByIdentifier(identifier) {
  const normalized = (identifier || '').trim().toLowerCase();
  const loginIdMatch = findUserByLoginId(normalized);

  if (loginIdMatch) return loginIdMatch;

  const emailMatch = readDirectory().find(
    (user) => user.personalEmail && user.personalEmail.trim().toLowerCase() === normalized
  );

  if (emailMatch) return emailMatch;

  const hrEmailAliases = {
    'admin@tardus.com': 'TDADMIN',
    'pragna@tardus.com': 'TD001',
    'vasavi@tardus.com': 'TD002',
  };

  return hrEmailAliases[normalized] ? findUserByLoginId(hrEmailAliases[normalized]) : null;
}

/**
 * Authenticates the local My HR preview accounts used by the split login.
 * This is intentionally explicit until My HR has a backend authentication
 * endpoint. Both organization IDs (TD001/TD002/TD003...) and email aliases
 * are accepted in the login field.
 */
export function authenticateMyHrUser(identifier, password) {
  const normalizedIdentifier = (identifier || '').trim().toLowerCase();

  if (
    normalizedIdentifier === MY_HR_EMPLOYEE_EMAIL.toLowerCase()
    && password === MY_HR_EMPLOYEE_PASSWORD
  ) {
    return {
      user: {
        loginId: 'MYHR-EMPLOYEE',
        name: 'Jhansi Nalla',
        role: MYHR_ROLES.EMPLOYEE,
        reportsTo: 'TD001',
        personalEmail: MY_HR_EMPLOYEE_EMAIL,
        designation: 'Employee',
      },
      accessToken: MY_HR_EMPLOYEE_SESSION_TOKEN,
    };
  }

  const user = findUserByIdentifier(identifier);

  if (!user || password !== MYHR_DEMO_PASSWORD) return null;

  if (user.role === MYHR_ROLES.COMPANY_ADMIN) {
    return { user, accessToken: MY_HR_ADMIN_SESSION_TOKEN };
  }

  if (user.role === MYHR_ROLES.HR) {
    return { user, accessToken: MY_HR_HR_SESSION_TOKEN };
  }

  if (user.role === MYHR_ROLES.EMPLOYEE) {
    return { user, accessToken: MY_HR_EMPLOYEE_SESSION_TOKEN };
  }

  return null;
}

export function getDirectReports(managerLoginId) {
  return readDirectory().filter((user) => user.reportsTo === managerLoginId);
}

export function getReportingManager(loginId) {
  const user = findUserByLoginId(loginId);
  if (!user?.reportsTo) return null;
  return findUserByLoginId(user.reportsTo);
}

export function getEligibleReportingManagers() {
  return readDirectory().filter(
    (user) => user.role === MYHR_ROLES.COMPANY_ADMIN || user.role === MYHR_ROLES.HR
  );
}

/** Next sequential Employee ID for the org, e.g. TD006 after TD001-TD005 exist. */
export function getNextEmployeeId() {
  const numbers = readDirectory()
    .map((user) => user.loginId.match(/^TD(\d{3})$/))
    .filter(Boolean)
    .map((match) => Number.parseInt(match[1], 10));
  const next = (numbers.length > 0 ? Math.max(...numbers) : 0) + 1;
  return `TD${String(next).padStart(3, '0')}`;
}

/**
 * Adds a new Employee to the directory. The creator and the Reporting
 * Manager are independent - the caller must pass reportsTo explicitly
 * per the FRD's "creator does not automatically become manager" rule.
 */
export function addEmployee({
  name,
  personalEmail,
  phoneNumber,
  state,
  city,
  streetAddress,
  zipCode,
  hourlyRate,
  designation,
  reportsTo,
}) {
  const users = readDirectory();
  const loginId = getNextEmployeeId();
  const newEmployee = {
    loginId,
    name,
    role: MYHR_ROLES.EMPLOYEE,
    reportsTo,
    personalEmail,
    phoneNumber,
    state,
    city,
    streetAddress,
    zipCode,
    hourlyRate,
    designation,
  };
  writeDirectory([...users, newEmployee]);
  return newEmployee;
}

export function getMyHrSession() {
  try {
    const raw = window.localStorage.getItem(SESSION_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setMyHrSession(user) {
  window.localStorage.setItem(SESSION_KEY, JSON.stringify(user));
}

export function clearMyHrSession() {
  window.localStorage.removeItem(SESSION_KEY);
}
