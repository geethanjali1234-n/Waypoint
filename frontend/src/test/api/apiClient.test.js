import { describe, it, expect, beforeEach, vi } from 'vitest';
import apiClient from '../../api/apiClient';
import { getMyHrSession, saveMyHrAuthentication } from '../../auth/myHrSession';

describe('apiClient', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('uses the configured API base URL outside a live Vite page', () => {
    expect(apiClient.defaults.baseURL).toBe(import.meta.env.VITE_API_BASE_URL || '/api');
  });

  it('sets the Content-Type header to application/json', () => {
    expect(apiClient.defaults.headers['Content-Type']).toBe('application/json');
  });

  it('attaches an Authorization header when an access token is present', async () => {
    localStorage.setItem('accessToken', 'my-token');

    const interceptor = apiClient.interceptors.request.handlers[0].fulfilled;
    const config = await interceptor({ headers: {} });

    expect(config.headers.Authorization).toBe('Bearer my-token');
  });

  it('does not set an Authorization header when no access token is present', async () => {
    const interceptor = apiClient.interceptors.request.handlers[0].fulfilled;
    const config = await interceptor({ headers: {} });

    expect(config.headers.Authorization).toBeUndefined();
  });

  it('rejects the request when the interceptor error handler is invoked', async () => {
    const errorHandler = apiClient.interceptors.request.handlers[0].rejected;
    const error = new Error('boom');

    await expect(errorHandler(error)).rejects.toThrow('boom');
  });

  it('clears stale My HR authentication after a protected My HR 401', async () => {
    saveMyHrAuthentication('expired-token', { loginId: 'TD001', role: 'HR' });
    const listener = vi.fn();
    window.addEventListener('waypoint:myhr-authentication-cleared', listener);
    const errorHandler = apiClient.interceptors.response.handlers[0].rejected;
    const error = { config: { url: '/myhr/auth/me' }, response: { status: 401 } };

    await expect(errorHandler(error)).rejects.toBe(error);

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(getMyHrSession()).toBeNull();
    expect(listener).toHaveBeenCalledOnce();
    window.removeEventListener('waypoint:myhr-authentication-cleared', listener);
  });

  it('does not redirect or clear authentication for a public auth 401', async () => {
    saveMyHrAuthentication('current-token', { loginId: 'TD001', role: 'HR' });
    const listener = vi.fn();
    window.addEventListener('waypoint:myhr-authentication-cleared', listener);
    const errorHandler = apiClient.interceptors.response.handlers[0].rejected;
    const error = { config: { url: '/auth/login' }, response: { status: 401 } };

    await expect(errorHandler(error)).rejects.toBe(error);

    expect(localStorage.getItem('accessToken')).toBe('current-token');
    expect(getMyHrSession()).toEqual({ loginId: 'TD001', role: 'HR' });
    expect(listener).not.toHaveBeenCalled();
    window.removeEventListener('waypoint:myhr-authentication-cleared', listener);
  });

  it('clears My HR authentication when the authenticated change-password endpoint returns 401', async () => {
    saveMyHrAuthentication('expired-token', { loginId: 'TD001', role: 'HR' });
    const errorHandler = apiClient.interceptors.response.handlers[0].rejected;
    const error = { config: { url: '/auth/change-password' }, response: { status: 401 } };

    await expect(errorHandler(error)).rejects.toBe(error);

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(getMyHrSession()).toBeNull();
  });

  it.each(['/auth/login', '/auth/forgot-password', '/auth/verify-reset-otp', '/auth/reset-password'])(
    'does not clear My HR authentication for public endpoint %s',
    async (url) => {
      saveMyHrAuthentication('current-token', { loginId: 'TD001', role: 'HR' });
      const errorHandler = apiClient.interceptors.response.handlers[0].rejected;
      const error = { config: { url }, response: { status: 401 } };

      await expect(errorHandler(error)).rejects.toBe(error);

      expect(localStorage.getItem('accessToken')).toBe('current-token');
      expect(getMyHrSession()).toEqual({ loginId: 'TD001', role: 'HR' });
    });

  describe('getApiBaseUrl', () => {
    const originalLocation = window.location;

    afterEach(() => {
      delete window.Capacitor;
      Object.defineProperty(window, 'location', {
        value: originalLocation,
        writable: true,
      });
    });

    it('returns /api when loaded from Vite dev server on port 5173 even if running on native Capacitor', async () => {
      const { getApiBaseUrl } = await import('../../api/apiClient');
      window.Capacitor = { isNativePlatform: () => true };
      Object.defineProperty(window, 'location', {
        value: { hostname: '192.168.1.219', port: '5173', protocol: 'http:' },
        writable: true,
      });

      expect(getApiBaseUrl()).toBe('/api');
    });

    it('clears a custom API URL when loaded on port 5173', async () => {
      const { getApiBaseUrl } = await import('../../api/apiClient');
      localStorage.setItem('waypoint_api_url', 'http://192.168.1.219:8081/api');
      Object.defineProperty(window, 'location', {
        value: { hostname: '192.168.1.219', port: '5173', protocol: 'http:' },
        writable: true,
      });

      expect(getApiBaseUrl()).toBe('/api');
      expect(localStorage.getItem('waypoint_api_url')).toBeNull();
    });

    it('uses the configured mobile API URL for a standalone native APK', async () => {
      const { getApiBaseUrl } = await import('../../api/apiClient');
      window.Capacitor = { isNativePlatform: () => true };
      Object.defineProperty(window, 'location', {
        value: { hostname: 'localhost', port: '', protocol: 'capacitor:' },
        writable: true,
      });

      expect(getApiBaseUrl()).toBe(import.meta.env.VITE_MOBILE_API_BASE_URL || import.meta.env.VITE_API_BASE_URL || '/api');
    });
  });
});

