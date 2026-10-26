import { APP_CURRENT_VERSION } from '../constants/appVersion';
import { getApiBaseUrl } from '../api/apiClient';

/**
 * Derives the base URL of the web server where the update artifacts
 * (version.json and waypoint.apk) are hosted.
 */
export function getWebServerBaseUrl() {
  if (typeof window === 'undefined') {
    return import.meta.env.VITE_UPDATE_BASE_URL || '';
  }

  // If in browser pointing to Vite dev server or public origin
  if (window.location.port === '5173' || (window.location.hostname && window.location.hostname !== 'localhost')) {
    return window.location.origin;
  }

  // In native Capacitor, derive the update host from the configured API URL.
  const apiBase = getApiBaseUrl();
  if (apiBase && apiBase.startsWith('http')) {
    try {
      const parsed = new URL(apiBase);
      return `${parsed.protocol}//${parsed.hostname}:5173`;
    } catch {
      // ignore
    }
  }

  return import.meta.env.VITE_UPDATE_BASE_URL || '';
}

/**
 * Checks the update server for a newer version of the application.
 */
export async function checkForAppUpdate() {
  try {
    const webBase = getWebServerBaseUrl();
    const versionUrl = `${webBase}/version.json?_t=${Date.now()}`;

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 6000);

    const response = await fetch(versionUrl, {
      signal: controller.signal,
      headers: { 'Cache-Control': 'no-cache' }
    });
    clearTimeout(timeoutId);

    if (!response.ok) {
      throw new Error(`Server returned HTTP ${response.status}`);
    }

    const remote = await response.json();

    const hasUpdate = (
      (remote.versionCode && remote.versionCode > APP_CURRENT_VERSION.versionCode) ||
      (remote.versionName && remote.versionName !== APP_CURRENT_VERSION.versionName)
    );

    // Ensure full download URL
    let downloadUrl = remote.downloadUrl;
    if (downloadUrl && downloadUrl.startsWith('/')) {
      downloadUrl = `${webBase}${downloadUrl}`;
    }

    return {
      hasUpdate: Boolean(hasUpdate),
      remoteVersion: {
        ...remote,
        downloadUrl: downloadUrl || `${webBase}/downloads/waypoint.apk`
      },
      currentVersion: APP_CURRENT_VERSION,
      error: null
    };
  } catch (err) {
    return {
      hasUpdate: false,
      remoteVersion: null,
      currentVersion: APP_CURRENT_VERSION,
      error: err.message || 'Unable to check for updates'
    };
  }
}

/**
 * Triggers the download and update of the APK file.
 * On Android devices, downloading the APK prompts the Android PackageInstaller
 * which performs an in-place update without uninstalling.
 */
export function triggerAppUpdate(downloadUrl) {
  const targetUrl = downloadUrl || `${getWebServerBaseUrl()}/downloads/waypoint.apk`;

  if (typeof window === 'undefined') return;

  // On native Capacitor, use system browser to handle APK download & package installation
  if (window.Capacitor?.isNativePlatform?.()) {
    window.open(targetUrl, '_system');
    return;
  }

  // Standard web download trigger
  const a = document.createElement('a');
  a.href = targetUrl;
  a.setAttribute('download', 'waypoint.apk');
  a.target = '_blank';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}
