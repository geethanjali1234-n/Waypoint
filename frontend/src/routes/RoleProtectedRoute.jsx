import { Navigate } from 'react-router-dom';

/**
 * RoleProtectedRoute - redirects to /login when the required access token is
 * not present.
 * This is a UX convenience only; the backend JwtAuthInterceptor remains the
 * sole authorization boundary for every protected API call.
 *
 * @example
 * <RoleProtectedRoute><EmployerDashboardPage /></RoleProtectedRoute>
 */
export default function RoleProtectedRoute({ children, requiredAccessToken, loginPath = '/login' }) {
  const accessToken = localStorage.getItem('accessToken');

  if (!accessToken || (requiredAccessToken && accessToken !== requiredAccessToken)) {
    return <Navigate to={loginPath} replace />;
  }

  return children;
}
