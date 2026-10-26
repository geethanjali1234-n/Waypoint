import { useState, useEffect } from 'react';
import { checkForAppUpdate, triggerAppUpdate } from '../../utils/appUpdater';
import { APP_CURRENT_VERSION } from '../../constants/appVersion';
import './AppUpdateNotification.css';

/**
 * AppUpdateNotification - Automatically detects when a newer APK build is available
 * and provides a 1-tap in-place update without requiring the user to uninstall.
 */
export default function AppUpdateNotification() {
  const [updateInfo, setUpdateInfo] = useState(null);
  const [showModal, setShowModal] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [toastMessage, setToastMessage] = useState(null);

  const check = async (manual = false) => {
    const res = await checkForAppUpdate();
    if (res.hasUpdate) {
      const remoteCode = String(res.remoteVersion?.versionCode);
      const isAlreadyDismissed = sessionStorage.getItem('waypoint_dismissed_update_code') === remoteCode;

      if (manual) {
        setUpdateInfo(res.remoteVersion);
        setShowModal(true);
      } else if (!isAlreadyDismissed) {
        setUpdateInfo(res.remoteVersion);
        setDismissed(false);

        // Auto-dismiss floating banner after 10s so it doesn't linger on screen
        setTimeout(() => {
          setDismissed(true);
        }, 10000);
      }
    } else if (manual) {
      setToastMessage(`Waypoint is up to date (${APP_CURRENT_VERSION.versionName}) ✅`);
      setTimeout(() => setToastMessage(null), 3500);
    }
  };

  useEffect(() => {
    // Check quietly 2 seconds after app mount
    const timer = setTimeout(() => {
      check(false);
    }, 2000);

    // Listen for manual trigger from anywhere in the app (e.g. login screen or settings)
    const handleManualCheck = () => {
      check(true);
    };
    window.addEventListener('waypoint:check-update', handleManualCheck);

    return () => {
      clearTimeout(timer);
      window.removeEventListener('waypoint:check-update', handleManualCheck);
    };
  }, []);

  const handleDismissBanner = () => {
    if (updateInfo?.versionCode) {
      sessionStorage.setItem('waypoint_dismissed_update_code', String(updateInfo.versionCode));
    }
    setDismissed(true);
  };

  const handleUpdateClick = () => {
    if (updateInfo?.downloadUrl) {
      triggerAppUpdate(updateInfo.downloadUrl);
      setShowModal(false);
      setToastMessage('Downloading update... Tap notification to install.');
      setTimeout(() => setToastMessage(null), 6000);
    }
  };

  return (
    <>
      {/* Toast message for feedback */}
      {toastMessage && (
        <div className="wp-update-toast" role="status">
          {toastMessage}
        </div>
      )}

      {/* Floating Banner */}
      {updateInfo && !dismissed && !showModal && (
        <div className="wp-update-banner" role="alert">
          <div className="wp-update-banner__left">
            <span className="wp-update-banner__icon">🚀</span>
            <div className="wp-update-banner__info">
              <span className="wp-update-banner__title">
                Update Ready <span className="wp-update-banner__tag">{updateInfo.versionName}</span>
              </span>
              <span className="wp-update-banner__desc">
                No uninstall required · Tap to update
              </span>
            </div>
          </div>
          <div className="wp-update-banner__actions">
            <button
              type="button"
              className="wp-update-banner__btn-update"
              onClick={() => setShowModal(true)}
            >
              Update
            </button>
            <button
              type="button"
              className="wp-update-banner__btn-close"
              onClick={handleDismissBanner}
              aria-label="Dismiss update notification"
            >
              ×
            </button>
          </div>
        </div>
      )}

      {/* Detail Modal */}
      {showModal && updateInfo && (
        <div className="wp-update-modal-overlay" onClick={() => { handleDismissBanner(); setShowModal(false); }}>
          <div className="wp-update-modal" onClick={(e) => e.stopPropagation()}>
            <div className="wp-update-modal__header">
              <span style={{ fontSize: '1.5rem' }}>🚀</span>
              <div>
                <h3>Waypoint Update</h3>
                <span className="wp-update-modal__badge">v{updateInfo.versionName} Available</span>
              </div>
            </div>

            <div className="wp-update-modal__notice">
              <span>⚡</span>
              <div>
                <strong>Seamless in-place update:</strong> No need to uninstall! Your login session and local data will be preserved.
              </div>
            </div>

            {updateInfo.releaseNotes && updateInfo.releaseNotes.length > 0 && (
              <>
                <div className="wp-update-modal__notes-title">What&apos;s New</div>
                <ul className="wp-update-modal__notes-list">
                  {updateInfo.releaseNotes.map((note, idx) => (
                    <li key={idx}>{note}</li>
                  ))}
                </ul>
              </>
            )}

            <div className="wp-update-modal__actions">
              <button
                type="button"
                className="wp-update-modal__btn-secondary"
                onClick={() => { handleDismissBanner(); setShowModal(false); }}
              >
                Later
              </button>
              <button
                type="button"
                className="wp-update-modal__btn-primary"
                onClick={handleUpdateClick}
              >
                Update Now
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
