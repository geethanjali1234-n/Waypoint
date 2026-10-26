import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import waypointAdminApi from '../api/waypointAdminApi';
import {
  clearMyHrAuthentication,
  getAccessToken,
  getWorkspaceHomePath,
  MY_HR_AUTHENTICATION_CLEARED_EVENT,
  saveMyHrSession,
} from '../auth/myHrSession';

export default function WaypointAdminProtectedRoute({ children, passwordChangeOnly = false }) {
  const token = getAccessToken();
  const [state, setState] = useState({ loading: Boolean(token), user: null, failed: !token });

  useEffect(() => {
    if (!token) return undefined;
    let active = true;
    waypointAdminApi.getDashboard()
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

  if (!token || state.failed) return <Navigate to="/login" replace />;
  if (state.loading) return <p role="status">Verifying Waypoint Admin session…</p>;

  if (passwordChangeOnly) {
    return state.user.mustChangePassword
      ? children
      : <Navigate to={getWorkspaceHomePath(state.user.role)} replace />;
  }
  if (state.user.mustChangePassword) {
    return <Navigate to="/waypoint-admin/first-login/change-password" replace />;
  }
  return children;
}
