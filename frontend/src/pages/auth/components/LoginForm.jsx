import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { EmailInput, TextInput, PasswordInput, Button, ValidationMessage } from '../../../components';
import authApi from '../../../api/authApi';
import {
  clearMyHrAuthentication,
  clearMyHrSession,
  getWorkspaceHomePath,
  saveMyHrAuthentication,
  WAYPOINT_WORKSPACE_ROLES,
} from '../../../auth/myHrSession';
import { getApiBaseUrl } from '../../../api/apiClient';
import { APP_CURRENT_VERSION } from '../../../constants/appVersion';
import './LoginForm.css';

const KNOWN_FIELDS = ['email', 'loginId', 'password'];

const jobSeekerLoginSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, { message: 'Email is required' })
    .email({ message: 'Enter a valid email address' }),
  password: z.string().min(1, { message: 'Password is required' }),
});

const myHrLoginSchema = z.object({
  loginId: z
    .string()
    .trim()
    .min(1, { message: 'Login ID is required' }),
  password: z.string().min(1, { message: 'Password is required' }),
});

/**
 * LoginForm - authenticates against POST /api/auth/login, stores the
 * returned access token, and routes the selected workspace to its landing
 * surface. MY HR currently lands on the blank development area.
 *
 * @example
 * <LoginForm />
 */
export default function LoginForm({ workspace = 'JOB_SEEKER' }) {
  const navigate = useNavigate();
  const [formError, setFormError] = useState(null);
  const isMyHr = workspace === 'MY_HR';

  const {
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(isMyHr ? myHrLoginSchema : jobSeekerLoginSchema),
    defaultValues: { email: '', loginId: '', password: '' },
  });

  const onSubmit = async (values) => {
    setFormError(null);

    try {
      const payload = isMyHr
        ? { loginId: values.loginId.trim().toUpperCase(), password: values.password }
        : { email: values.email, password: values.password };
      const response = await authApi.login(payload);
      const authenticatedUser = response.data.data;
      const { accessToken, role, mustChangePassword } = authenticatedUser;

      if (isMyHr) {
        if (!WAYPOINT_WORKSPACE_ROLES.includes(role)) {
          clearMyHrAuthentication();
          setFormError('This login is for My HR accounts only.');
          return;
        }
        saveMyHrAuthentication(accessToken, authenticatedUser);
        const firstLoginPath = role === 'WAYPOINT_ADMIN'
          ? '/waypoint-admin/first-login/change-password'
          : '/myhr/first-login/change-password';
        navigate(mustChangePassword ? firstLoginPath : getWorkspaceHomePath(role));
        return;
      }

      clearMyHrSession();

      if (role !== 'JOB_SEEKER') {
        localStorage.removeItem('accessToken');
        setFormError('This login is for Job Seeker accounts only.');
        return;
      }

      localStorage.setItem('accessToken', accessToken);
      navigate('/job-seeker/dashboard');
    } catch (submitError) {
      const responseBody = submitError?.response?.data;

      if (responseBody?.data && typeof responseBody.data === 'object') {
        Object.entries(responseBody.data).forEach(([field, message]) => {
          if (KNOWN_FIELDS.includes(field)) {
            setError(field, { type: 'server', message });
          } else {
            setFormError(message);
          }
        });
      } else if (responseBody?.message) {
        setFormError(responseBody.message);
      } else if (submitError?.code === 'ERR_NETWORK' || submitError?.message?.includes('Network Error')) {
        setFormError(`Cannot connect to server at ${getApiBaseUrl()}. Please ensure your phone is connected to the same Wi-Fi.`);
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    }
  };

  return (
    <form className="wp-login-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {formError && <ValidationMessage message={formError} variant="error" />}

      {isMyHr ? (
        <Controller
          name="loginId"
          control={control}
          render={({ field }) => (
            <TextInput label="Login ID" name="loginId" value={field.value}
              onChange={field.onChange} placeholder="TDADMIN or TD001"
              error={errors.loginId?.message} required />
          )}
        />
      ) : (
        <Controller
          name="email"
          control={control}
          render={({ field }) => (
            <EmailInput label="Email" name="email" value={field.value}
              onChange={field.onChange} error={errors.email?.message} required />
          )}
        />
      )}

      <Controller
        name="password"
        control={control}
        render={({ field }) => (
          <PasswordInput
            label="Password"
            name="password"
            value={field.value}
            onChange={field.onChange}
            error={errors.password?.message}
            required
          />
        )}
      />

      <Button type="submit" variant="primary" fullWidth loading={isSubmitting}>
        Sign in
      </Button>

      <div className="wp-login-form__links">
        <Link to="/forgot-password" className="wp-login-form__link">
          Forgot password?
        </Link>
        {!isMyHr && <><span className="wp-login-form__divider">·</span>
          <Link to="/register/job-seeker" className="wp-login-form__link">Create account</Link></>}
      </div>

      {typeof window !== 'undefined' && (window.Capacitor?.isNativePlatform?.() || window.location.hostname !== 'localhost') && (
        <div style={{ marginTop: '1.25rem', textAlign: 'center', fontSize: '0.8rem', color: 'var(--wp-color-text-muted, #cbd5e1)' }}>
          <span>API Server: <code style={{ color: 'var(--wp-color-accent-cyan, #00ff87)', background: 'rgba(0, 255, 135, 0.1)', padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>{getApiBaseUrl()}</code></span>
          <button
            type="button"
            onClick={() => {
              const current = localStorage.getItem('waypoint_api_url') || getApiBaseUrl();
              let newUrl = window.prompt('Configure Backend API URL (use http:// for local dev):', current);
              if (newUrl !== null && newUrl.trim() !== '') {
                newUrl = newUrl.trim();
                if (newUrl.startsWith('https://') && (newUrl.includes(':8081') || newUrl.includes('192.168.') || newUrl.includes('10.') || newUrl.includes('localhost'))) {
                  newUrl = newUrl.replace('https://', 'http://');
                }
                localStorage.setItem('waypoint_api_url', newUrl);
                window.location.reload();
              }
            }}
            style={{
              marginLeft: '0.5rem',
              background: 'none',
              border: 'none',
              color: '#60a5fa',
              cursor: 'pointer',
              textDecoration: 'underline',
              fontSize: '0.8rem',
              fontWeight: 600
            }}
          >
            Change
          </button>
          {localStorage.getItem('waypoint_api_url') && (
            <button
              type="button"
              onClick={() => {
                localStorage.removeItem('waypoint_api_url');
                window.location.reload();
              }}
              style={{
                marginLeft: '0.5rem',
                background: 'none',
                border: 'none',
                color: '#f87171',
                cursor: 'pointer',
                textDecoration: 'underline',
                fontSize: '0.8rem',
                fontWeight: 600
              }}
            >
              Reset
            </button>
          )}
          <div style={{ marginTop: '0.45rem', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem', opacity: 0.85 }}>
            <span>App: <strong style={{ color: '#f8fafc' }}>v{APP_CURRENT_VERSION.versionName}</strong></span>
            <span>·</span>
            <button
              type="button"
              id="login-check-updates-btn"
              onClick={() => window.dispatchEvent(new CustomEvent('waypoint:check-update'))}
              style={{
                background: 'none',
                border: 'none',
                color: 'var(--wp-color-accent-cyan, #00ff87)',
                cursor: 'pointer',
                textDecoration: 'underline',
                fontSize: '0.8rem',
                fontWeight: 600,
                padding: 0
              }}
            >
              Check for updates
            </button>
          </div>
        </div>
      )}
    </form>
  );
}
