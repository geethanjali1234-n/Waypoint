import { describe, it, expect, afterEach } from 'vitest';
import { getMobileOS, isMobileDevice, isStandaloneApp, getApkDownloadUrl } from './deviceDetection';

describe('deviceDetection', () => {
  const originalNavigator = window.navigator;

  afterEach(() => {
    Object.defineProperty(window, 'navigator', {
      value: originalNavigator,
      writable: true,
    });
  });

  it('detects Android correctly', () => {
    Object.defineProperty(window, 'navigator', {
      value: {
        userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        platform: 'Linux armv8l',
        maxTouchPoints: 5,
      },
      writable: true,
    });

    expect(getMobileOS()).toBe('android');
    expect(isMobileDevice()).toBe(true);
  });

  it('detects iPhone / iOS correctly', () => {
    Object.defineProperty(window, 'navigator', {
      value: {
        userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1',
        platform: 'iPhone',
        maxTouchPoints: 5,
      },
      writable: true,
    });

    expect(getMobileOS()).toBe('ios');
    expect(isMobileDevice()).toBe(true);
  });

  it('detects iPad OS with desktop UA correctly', () => {
    Object.defineProperty(window, 'navigator', {
      value: {
        userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
        platform: 'MacIntel',
        maxTouchPoints: 5,
      },
      writable: true,
    });

    expect(getMobileOS()).toBe('ios');
    expect(isMobileDevice()).toBe(true);
  });

  it('detects Desktop correctly', () => {
    Object.defineProperty(window, 'navigator', {
      value: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
        platform: 'Win32',
        maxTouchPoints: 0,
      },
      writable: true,
    });

    expect(getMobileOS()).toBe('desktop');
    expect(isMobileDevice()).toBe(false);
  });

  it('generates dynamic APK download URL based on window.location.origin', () => {
    const url = getApkDownloadUrl();
    expect(url).toContain('/downloads/waypoint.apk');
    expect(url).toContain(window.location.origin);
  });

  it('detects standalone mode correctly', () => {
    expect(isStandaloneApp()).toBe(false);

    Object.defineProperty(window.navigator, 'standalone', {
      value: true,
      configurable: true,
    });
    expect(isStandaloneApp()).toBe(true);
  });
});
