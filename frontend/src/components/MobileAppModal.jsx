import { useState, useEffect } from 'react';
import { getMobileOS, getApkDownloadUrl, isStandaloneApp } from '../utils/deviceDetection';
import './MobileAppModal.css';

/**
 * MobileAppModal - Download and install modal for Android (.apk / PWA) & iOS (PWA / Add to Home Screen).
 * Dynamically resolves download URL to the active host or live domain.
 */
export default function MobileAppModal({ isOpen, onClose, defaultPlatform }) {
  const currentOS = getMobileOS();
  const [activeTab, setActiveTab] = useState(
    defaultPlatform || (currentOS === 'ios' ? 'ios' : 'android')
  );
  const [pwaPrompt, setPwaPrompt] = useState(null);
  const [isInstalled, setIsInstalled] = useState(false);

  useEffect(() => {
    setIsInstalled(isStandaloneApp());

    const handleBeforeInstall = (e) => {
      e.preventDefault();
      setPwaPrompt(e);
    };

    window.addEventListener('beforeinstallprompt', handleBeforeInstall);
    return () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstall);
    };
  }, []);

  useEffect(() => {
    if (defaultPlatform) {
      setActiveTab(defaultPlatform);
    } else if (currentOS === 'ios') {
      setActiveTab('ios');
    } else {
      setActiveTab('android');
    }
  }, [defaultPlatform, currentOS, isOpen]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const apkUrl = getApkDownloadUrl();
  const currentOrigin = typeof window !== 'undefined' ? window.location.origin : '';
  const currentUrl = typeof window !== 'undefined' ? window.location.href : '';

  const handleInstallPwa = async () => {
    if (pwaPrompt) {
      pwaPrompt.prompt();
      const choice = await pwaPrompt.userChoice;
      if (choice.outcome === 'accepted') {
        setIsInstalled(true);
      }
      setPwaPrompt(null);
    }
  };

  // Simple QR code encoded URL for easy scanning
  const qrCodeApiUrl = `https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(
    currentUrl || currentOrigin
  )}&margin=4`;

  return (
    <div
      className="wp-mobile-modal-overlay"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="mobile-modal-title"
    >
      <div className="wp-mobile-modal-container">
        <div className="wp-mobile-modal-header">
          <div className="wp-mobile-modal-header-text">
            <h3 id="mobile-modal-title">
              <span>📱</span> Get Waypoint Mobile App
            </h3>
            <p>Experience fast and seamless career management on your mobile device</p>
          </div>
          <button
            type="button"
            className="wp-mobile-modal-close-btn"
            onClick={onClose}
            aria-label="Close download modal"
            id="mobile-modal-close-btn"
          >
            ×
          </button>
        </div>

        {/* Tab Selection */}
        <div className="wp-mobile-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'android'}
            className={`wp-mobile-tab-btn ${activeTab === 'android' ? 'active' : ''}`}
            onClick={() => setActiveTab('android')}
            id="tab-btn-android"
          >
            <span>🤖</span> Android
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'ios'}
            className={`wp-mobile-tab-btn ${activeTab === 'ios' ? 'active' : ''}`}
            onClick={() => setActiveTab('ios')}
            id="tab-btn-ios"
          >
            <span>🍎</span> iOS (iPhone/iPad)
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'qr'}
            className={`wp-mobile-tab-btn ${activeTab === 'qr' ? 'active' : ''}`}
            onClick={() => setActiveTab('qr')}
            id="tab-btn-qr"
          >
            <span>📷</span> Scan QR
          </button>
        </div>

        <div className="wp-mobile-modal-body">
          {/* ANDROID TAB */}
          {activeTab === 'android' && (
            <>
              <div className="wp-mobile-action-card">
                <div className="wp-mobile-action-card-header">
                  <div>
                    <strong>Waypoint Android APK</strong>
                    <div style={{ fontSize: '0.8rem', color: '#64748b' }}>
                      Direct installer (.apk) • Android 8.0+
                    </div>
                  </div>
                  <span className="wp-mobile-badge">Recommended</span>
                </div>

                <a
                  href={apkUrl}
                  download="waypoint.apk"
                  className="wp-download-primary-btn"
                  id="btn-download-apk"
                >
                  <span aria-hidden="true">📥</span>
                  <span>Download Android App (.apk)</span>
                </a>

                {pwaPrompt && !isInstalled && (
                  <button
                    type="button"
                    onClick={handleInstallPwa}
                    className="wp-download-primary-btn"
                    style={{ background: '#059669', marginTop: '0.25rem' }}
                    id="btn-pwa-install-android"
                  >
                    <span>⚡</span>
                    <span>1-Tap Instant Web App Install</span>
                  </button>
                )}
              </div>

              <div>
                <h4 style={{ margin: '0 0 0.75rem 0', fontSize: '0.95rem', color: '#0f172a' }}>
                  How to install on Android:
                </h4>
                <ol className="wp-mobile-steps-list">
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">1</span>
                    <div>
                      Tap <span className="wp-mobile-step-highlight">Download Android App</span> above.
                      If your browser asks to confirm the download, tap <strong>Download anyway</strong>.
                    </div>
                  </li>
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">2</span>
                    <div>
                      Once downloaded, tap the download notification or open the file from your{' '}
                      <strong>Downloads</strong> folder.
                    </div>
                  </li>
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">3</span>
                    <div>
                      If prompted <em>"For security, your phone is not allowed to install unknown apps"</em>,
                      tap <strong>Settings</strong> and toggle on <strong>Allow from this source</strong>.
                    </div>
                  </li>
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">4</span>
                    <div>
                      Tap <span className="wp-mobile-step-highlight">Install</span>. Waypoint will be added to your home screen!
                    </div>
                  </li>
                </ol>
              </div>
            </>
          )}

          {/* iOS TAB */}
          {activeTab === 'ios' && (
            <>
              <div className="wp-mobile-action-card">
                <div className="wp-mobile-action-card-header">
                  <div>
                    <strong>Waypoint for iPhone & iPad</strong>
                    <div style={{ fontSize: '0.8rem', color: '#64748b' }}>
                      iOS Fullscreen Native Web App
                    </div>
                  </div>
                  <span className="wp-mobile-badge secondary">No App Store Required</span>
                </div>

                <p style={{ margin: 0, fontSize: '0.875rem', color: '#334155' }}>
                  Apple supports installing Waypoint directly from Safari as a standalone app with full offline capabilities, instant launch, and home screen icon.
                </p>
              </div>

              <div>
                <h4 style={{ margin: '0 0 0.75rem 0', fontSize: '0.95rem', color: '#0f172a' }}>
                  3 Simple Steps to Install on iOS Safari:
                </h4>
                <ol className="wp-mobile-steps-list">
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">1</span>
                    <div>
                      Open this website in <strong>Safari</strong> and tap the{' '}
                      <span className="wp-ios-share-icon">Share ⎋</span> button at the bottom of the screen.
                    </div>
                  </li>
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">2</span>
                    <div>
                      Scroll down the options list and tap{' '}
                      <span className="wp-mobile-step-highlight">"Add to Home Screen" ➕</span>.
                    </div>
                  </li>
                  <li className="wp-mobile-step-item">
                    <span className="wp-mobile-step-num">3</span>
                    <div>
                      Tap <span className="wp-mobile-step-highlight">"Add"</span> in the top right corner.
                      Waypoint is now installed as an app on your iOS home screen!
                    </div>
                  </li>
                </ol>
              </div>
            </>
          )}

          {/* QR CODE SCAN TAB */}
          {activeTab === 'qr' && (
            <div className="wp-qr-section">
              <div className="wp-qr-svg-wrapper">
                <img
                  src={qrCodeApiUrl}
                  alt="Scan QR code to open Waypoint on mobile"
                  width="180"
                  height="180"
                  style={{ display: 'block', borderRadius: '8px' }}
                />
              </div>
              <div>
                <strong style={{ fontSize: '0.95rem', color: '#0f172a' }}>
                  Scan with your phone camera
                </strong>
                <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.825rem', color: '#64748b' }}>
                  Point your iPhone or Android camera at the QR code above to instantly open Waypoint and download the app.
                </p>
              </div>
              <div className="wp-qr-url-hint">
                Current URL: <code>{currentOrigin}</code>
              </div>
            </div>
          )}
        </div>

        <div className="wp-mobile-modal-footer">
          Host: {currentOrigin} • Compatible with Android 8.0+ & iOS 14.0+
        </div>
      </div>
    </div>
  );
}

