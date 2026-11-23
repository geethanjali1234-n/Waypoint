// This is the fixed account for the document-backed MY HR employee prototype.
// A real deployment should validate these credentials on the backend instead.
export const MY_HR_EMPLOYEE_EMAIL = 'jhansinalla11@gmail.com';
export const MY_HR_EMPLOYEE_PASSWORD = 'Jhansi@#123';
export const MY_HR_EMPLOYEE_SESSION_TOKEN = 'my-hr-prototype-token';

// Prototype-only HR session token. The My HR API is not wired yet, so the
// client uses separate tokens to keep the HR and Employee previews isolated.
export const MY_HR_HR_SESSION_TOKEN = 'my-hr-hr-prototype-token';

// Prototype-only Company Admin session token. Admin uses its own token so
// the HR and Employee route guards continue to reject the wrong portal.
export const MY_HR_ADMIN_SESSION_TOKEN = 'my-hr-admin-prototype-token';
