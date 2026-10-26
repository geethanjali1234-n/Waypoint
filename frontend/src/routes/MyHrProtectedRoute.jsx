import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import myHrAuthApi from '../api/myHrAuthApi';
import {
  clearMyHrAuthentication,
  getAccessToken,
  getMyHrHomePath,
  MY_HR_AUTHENTICATION_CLEARED_EVENT,
  saveMyHrSession,
} from '../auth/myHrSession';

export default function MyHrProtectedRoute({ children, allowedRoles, passwordChangeOnly = false }) {
  const token = getAccessToken();
  const [state, setState] = useState({ loading: Boolean(token), user: null, failed: !token });

  useEffect(() => {
    if (!token) return undefined;
    let active = true;
    myHrAuthApi.currentUser()
      .then((response) => {
        if (!active) return;
        const user = response.data.data;
        saveMyHrSession(user);
        setState({ loading: false, user, failed: false });
      })
      .catch(() => {
        if (!active) return;
        clearMyHrAuthentication();
        setState({ loading: false, user: null, failed: true });
      });
    return () => { active = false; };
  }, [token]);

  useEffect(() => {
    const authenticationCleared = () => {
      setState({ loading: false, user: null, failed: true });
    };
    window.addEventListener(MY_HR_AUTHENTICATION_CLEARED_EVENT, authenticationCleared);
    return () => window.removeEventListener(MY_HR_AUTHENTICATION_CLEARED_EVENT, authenticationCleared);
  }, []);

  if (!token || state.failed) return <Navigate to="/myhr/login" replace />;
  if (state.loading) return <p role="status">Verifying My HR session…</p>;

  const user = state.user;
  if (passwordChangeOnly) {
    return user.mustChangePassword
      ? children
      : <Navigate to={getMyHrHomePath(user.role)} replace />;
  }
  if (user.mustChangePassword) return <Navigate to="/myhr/first-login/change-password" replace />;
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <Navigate to={getMyHrHomePath(user.role)} replace />;
  }
  return children;
}
