/**
 * Detect client operating system and mobile context.
 * Provides detection for Android, iOS (iPhone/iPad/iPod), and Desktop,
 * as well as PWA standalone mode and installation support.
 */

export function getMobileOS() {
  if (typeof window === 'undefined' || !navigator) {
    return 'desktop';
  }

  const userAgent = navigator.userAgent || navigator.vendor || window.opera || '';

  // Windows Phone must come first because its UA also contains "Android"
  if (/windows phone/i.test(userAgent)) {
    return 'windows_phone';
  }

  // Android detection
  if (/android/i.test(userAgent)) {
    return 'android';
  }

  // iOS detection (iPhone, iPod, iPad)
  // iPad on iOS 13+ reports platform as 'MacIntel' with multi-touch
  const isIOSPlatform = /iPad|iPhone|iPod/.test(userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  if (isIOSPlatform) {
    return 'ios';
  }

  return 'desktop';
}

export function isMobileDevice() {
  const os = getMobileOS();
  return os === 'android' || os === 'ios' || os === 'windows_phone';
}

/**
 * Checks whether the current app is already running in standalone (installed PWA / native webview) mode.
 */
export function isStandaloneApp() {
  if (typeof window === 'undefined') return false;

  // Native Capacitor mobile shell
  if (window.Capacitor?.isNativePlatform?.() || window.Capacitor !== undefined) {
    return true;
  }

  const isStandaloneMatch = window.matchMedia && window.matchMedia('(display-mode: standalone)').matches;
  const isIOSStandalone = window.navigator && window.navigator.standalone;
  const isAndroidWebview = document.referrer.includes('android-app://');

  return Boolean(isStandaloneMatch || isIOSStandalone || isAndroidWebview);
}

/**
 * Dynamic download URL for the Android APK based on the current origin.
 * Automatically adapts between the active LAN address, localhost, or future live domains.
 */
export function getApkDownloadUrl() {
  if (typeof window === 'undefined') return '/downloads/waypoint.apk';
  return `${window.location.origin}/downloads/waypoint.apk`;
}
