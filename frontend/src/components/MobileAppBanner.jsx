import { useState, useEffect } from 'react';
import { getMobileOS, getApkDownloadUrl, isStandaloneApp, isMobileDevice } from '../utils/deviceDetection';
import MobileAppModal from './MobileAppModal';
import './MobileAppBanner.css';

/**
 * MobileAppBanner - Smart install & download banner for mobile web users.
 * Automatically customizes text and action for Android vs iOS.
 */
export default function MobileAppBanner({ forceShow = false }) {
  const [isVisible, setIsVisible] = useState(false);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const currentOS = getMobileOS();

  useEffect(() => {
    // If running in standalone installed app, do not display banner
    if (isStandaloneApp()) {
      setIsVisible(false);
      return;
    }

    // Check if dismissed in this session
    const isDismissed = sessionStorage.getItem('waypoint_app_banner_dismissed') === 'true';
    if (isDismissed && !forceShow) {
      setIsVisible(false);
      return;
    }

    // Show for mobile devices or if forceShow is enabled
    if (isMobileDevice() || forceShow) {
      setIsVisible(true);
    }
  }, [forceShow]);

  const handleDismiss = () => {
    sessionStorage.setItem('waypoint_app_banner_dismissed', 'true');
    setIsVisible(false);
  };

  if (!isVisible) {
    return (
      <MobileAppModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        defaultPlatform={currentOS === 'ios' ? 'ios' : 'android'}
      />
    );
  }

  const apkUrl = getApkDownloadUrl();

  return (
    <>
      <aside className="wp-mobile-banner" role="complementary" aria-label="Mobile app download banner">
        <div className="wp-mobile-banner-content">
          <span className="wp-mobile-banner-icon" aria-hidden="true">
            {currentOS === 'ios' ? '🍎' : '🤖'}
          </span>
          <div className="wp-mobile-banner-text">
            <span className="wp-mobile-banner-title">
              {currentOS === 'ios'
                ? 'Waypoint for iPhone & iPad'
                : 'Waypoint for Android'}
            </span>
            <span className="wp-mobile-banner-subtitle">
              {currentOS === 'ios'
                ? 'Add to Home Screen for fullscreen native app'
                : 'Download APK for direct offline installation'}
            </span>
          </div>
        </div>

        <div className="wp-mobile-banner-actions">
          {currentOS === 'android' ? (
            <a
              href={apkUrl}
              download="waypoint.apk"
              className="wp-mobile-banner-btn"
              id="banner-download-apk-btn"
            >
              <span>📥</span>
              <span>Download APK</span>
            </a>
          ) : (
            <button
              type="button"
              className="wp-mobile-banner-btn"
              onClick={() => setIsModalOpen(true)}
              id="banner-install-ios-btn"
            >
              <span>➕</span>
              <span>Install App</span>
            </button>
          )}

          <button
            type="button"
            className="wp-mobile-banner-btn wp-mobile-banner-btn-secondary"
            onClick={() => setIsModalOpen(true)}
            aria-label="View instructions"
            id="banner-info-btn"
          >
            Guide
          </button>

          <button
            type="button"
            className="wp-mobile-banner-close"
            onClick={handleDismiss}
            aria-label="Dismiss banner"
            id="banner-close-btn"
          >
            ×
          </button>
        </div>
      </aside>

      <MobileAppModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        defaultPlatform={currentOS === 'ios' ? 'ios' : 'android'}
      />
    </>
  );
}

