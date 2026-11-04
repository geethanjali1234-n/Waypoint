/**
 * Whether the page is running inside the installed WayPoint app (Capacitor Android/iOS WebView) rather than in a normal
 * web browser. Uses the same signal as the rest of the project (`window.Capacitor.isNativePlatform()`), plus the
 * custom URL schemes Capacitor and Ionic serve apps from, in case the plugin object is not available.
 *
 * Google refuses OAuth sign-in inside embedded WebViews, so anything that sends the user to Google must not start from
 * here; it has to happen in the device's own browser.
 */
export function isNativeApp() {
  if (typeof window === 'undefined') return false;
  if (window.Capacitor?.isNativePlatform?.()) return true;
  const protocol = window.location?.protocol;
  return protocol === 'capacitor:' || protocol === 'ionic:';
}
