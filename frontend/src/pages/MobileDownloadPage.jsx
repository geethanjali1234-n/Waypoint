import { useState } from 'react';
import { Link } from 'react-router-dom';
import { getMobileOS, getApkDownloadUrl } from '../utils/deviceDetection';
import MobileAppModal from '../components/MobileAppModal';
import './MobileDownloadPage.css';

/**
 * MobileDownloadPage - Dedicated landing page for downloading the Waypoint mobile apps.
 * Supports Android (.apk direct download) and iOS (Progressive Web App installation).
 */
export default function MobileDownloadPage() {
  const currentOS = getMobileOS();
  const [modalOpen, setModalOpen] = useState(false);
  const [modalPlatform, setModalPlatform] = useState('android');
  const apkUrl = getApkDownloadUrl();
  const currentOrigin = typeof window !== 'undefined' ? window.location.origin : '';

  const openModal = (platform) => {
    setModalPlatform(platform);
    setModalOpen(true);
  };

  const detectedLabel =
    currentOS === 'android'
      ? 'Android device detected'
      : currentOS === 'ios'
      ? 'iOS device detected (iPhone / iPad)'
      : 'Desktop browser detected';

  return (
    <div className="wp-download-page">
      <div className="wp-download-container">
        <header className="wp-download-hero">
          <img
            src="/waypoint-logo.svg"
            alt="Waypoint Logo"
            className="wp-download-logo"
          />
          <h1>Download Waypoint Mobile</h1>
          <p>
            Access your career profile, timesheets, job applications, and documents anywhere, anytime with our optimized mobile applications.
          </p>
          <div className="wp-download-detected-pill">
            <span>✨</span>
            <span>{detectedLabel}</span>
          </div>
        </header>

        <main className="wp-download-grid">
          {/* ANDROID CARD */}
          <div
            className={`wp-download-card ${
              currentOS === 'android' ? 'highlighted' : ''
            }`}
          >
            {currentOS === 'android' && (
              <span className="wp-download-card-badge">Your Device</span>
            )}
            <div className="wp-download-card-icon" aria-hidden="true">🤖</div>
            <h2>Waypoint for Android</h2>
            <p className="wp-download-card-desc">
              Download the standalone Android APK directly to your phone. Fast, secure, and ready for offline use.
            </p>
            <a
              href={apkUrl}
              download="waypoint.apk"
              className="wp-download-card-btn primary"
              id="download-page-android-btn"
            >
              <span>📥</span>
              <span>Download Android APK</span>
            </a>
            <button
              type="button"
              className="wp-download-card-btn secondary"
              style={{ marginTop: '0.75rem' }}
              onClick={() => openModal('android')}
              id="download-page-android-guide-btn"
            >
              <span>📋</span>
              <span>Installation Instructions</span>
            </button>
            <div className="wp-download-meta">
              Direct APK • Android 8.0+ • Compatible with all Android phones
            </div>
          </div>

          {/* IOS CARD */}
          <div
            className={`wp-download-card ${
              currentOS === 'ios' ? 'highlighted' : ''
            }`}
          >
            {currentOS === 'ios' && (
              <span className="wp-download-card-badge">Your Device</span>
            )}
            <div className="wp-download-card-icon" aria-hidden="true">🍎</div>
            <h2>Waypoint for iOS</h2>
            <p className="wp-download-card-desc">
              Add Waypoint directly to your iPhone or iPad home screen for a full-screen, native experience without needing the App Store.
            </p>
            <button
              type="button"
              className="wp-download-card-btn primary"
              onClick={() => openModal('ios')}
              id="download-page-ios-btn"
            >
              <span>➕</span>
              <span>Install on iPhone / iPad</span>
            </button>
            <button
              type="button"
              className="wp-download-card-btn secondary"
              style={{ marginTop: '0.75rem' }}
              onClick={() => openModal('ios')}
              id="download-page-ios-guide-btn"
            >
              <span>📖</span>
              <span>View Step-by-Step Guide</span>
            </button>
            <div className="wp-download-meta">
              iOS 14.0+ • Safari Native Web App • Full Screen
            </div>
          </div>

          {/* DESKTOP / QR CARD */}
          <div className="wp-download-card">
            <div className="wp-download-card-icon" aria-hidden="true">📱</div>
            <h2>Scan & Open on Phone</h2>
            <p className="wp-download-card-desc">
              Browsing on your laptop or PC? Scan the QR code to open Waypoint on your phone and install with one tap.
            </p>
            <button
              type="button"
              className="wp-download-card-btn secondary"
              onClick={() => openModal('qr')}
              id="download-page-qr-btn"
            >
              <span>📷</span>
              <span>Show Mobile QR Code</span>
            </button>
            <div className="wp-download-meta">
              Host: {currentOrigin}
            </div>
          </div>
        </main>

        <footer className="wp-download-footer-nav">
          <Link to="/login" className="wp-download-back-link" id="download-back-login-link">
            <span>←</span>
            <span>Back to Waypoint Login</span>
          </Link>
        </footer>
      </div>

      <MobileAppModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        defaultPlatform={modalPlatform}
      />
    </div>
  );
}
