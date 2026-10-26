import { useState } from 'react';

const SAMPLES = [
  {
    id: 'ts_pdf',
    name: 'timesheet_biweekly_signed.pdf',
    type: 'PDF Document (PDF/A)',
    period: 'Sept 07 - Sept 20, 2026',
    hours: '40.0 hrs regular, 3.5 hrs overtime',
    hash: 'sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    storage: 'Cloudflare R2 (waypoint-myhr-documents)',
    status: 'AUDIT_APPROVED',
  },
  {
    id: 'receipt_img',
    name: 'travel_reimbursement_scan.png',
    type: 'Receipt (PNG OCR)',
    period: 'Sept 15, 2026',
    hours: 'N/A (Expense Attached to Timesheet #4829)',
    hash: 'sha256-a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e',
    storage: 'Cloudflare R2 (waypoint-myhr-documents)',
    status: 'RECEIPT_VERIFIED',
  },
];

export default function DocumentScannerWidget() {
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [isScanning, setIsScanning] = useState(false);
  const [scanComplete, setScanComplete] = useState(false);

  const sample = SAMPLES[selectedIdx];

  const handleScan = () => {
    if (isScanning) return;
    setIsScanning(true);
    setScanComplete(false);

    setTimeout(() => {
      setIsScanning(false);
      setScanComplete(true);
    }, 1800);
  };

  const handleSwitchSample = (idx) => {
    setSelectedIdx(idx);
    setIsScanning(false);
    setScanComplete(false);
  };

  return (
    <div className="wp-scanner-card">
      <div className="wp-scanner-header">
        <div className="wp-scanner-title-group">
          <span className="wp-scanner-badge">DUAL-ENGINE INGESTION (LLM + OCR)</span>
          <h3 className="wp-scanner-title">OCR & Document Intelligence Scanner</h3>
          <p className="wp-scanner-desc">
            Waypoint extracts structured dates and hours using your configured <strong>LLM API Key</strong> for deep semantic understanding, with automatic fallback to high-precision <strong>Optical OCR</strong> if the LLM is unconfigured or offline—ensuring zero operational downtime.
          </p>
        </div>

        <div className="wp-scanner-tabs">
          {SAMPLES.map((s, idx) => (
            <button
              key={s.id}
              type="button"
              className={`wp-scanner-tab ${idx === selectedIdx ? 'wp-scanner-tab--active' : ''}`}
              onClick={() => handleSwitchSample(idx)}
            >
              {s.name}
            </button>
          ))}
        </div>
      </div>

      <div className="wp-scanner-body">
        {/* Visual Document Stage with Laser */}
        <div className="wp-scanner-stage">
          <div className={`wp-scanner-doc ${isScanning ? 'wp-scanner-doc--scanning' : ''}`}>
            {/* Document Header lines */}
            <div className="wp-scanner-doc-header">
              <span className="wp-scanner-doc-logo">WAYPOINT HR VERIFIED</span>
              <span className="wp-scanner-doc-num">REF #{sample.id.toUpperCase()}</span>
            </div>

            <div className="wp-scanner-doc-lines">
              <div className="wp-scanner-line wp-scanner-line--long" />
              <div className="wp-scanner-line wp-scanner-line--med" />
              <div className="wp-scanner-line wp-scanner-line--short" />
            </div>

            {/* Table simulation */}
            <div className="wp-scanner-doc-table">
              <div className="wp-scanner-doc-row wp-scanner-doc-row--head">
                <span>DATE</span>
                <span>CODE</span>
                <span>HRS</span>
              </div>
              <div className="wp-scanner-doc-row">
                <span>09/14/26</span>
                <span>PROJ-ALPHA</span>
                <span>8.0</span>
              </div>
              <div className="wp-scanner-doc-row">
                <span>09/15/26</span>
                <span>PROJ-ALPHA</span>
                <span>8.0</span>
              </div>
              <div className="wp-scanner-doc-row">
                <span>09/16/26</span>
                <span>PROJ-BETA</span>
                <span>9.5</span>
              </div>
            </div>

            {/* Signature Area */}
            <div className="wp-scanner-doc-sign">
              <span>Authorized Signature:</span>
              <span className="wp-scanner-sig-script">J. Doe</span>
            </div>

            {/* Laser Line */}
            {isScanning && <div className="wp-scanner-laser" aria-hidden="true" />}
          </div>
        </div>

        {/* Telemetry Readout */}
        <div className="wp-scanner-telemetry">
          <div className="wp-scanner-hud">
            <span className="wp-scanner-hud-title">PARSER TELEMETRY HUD</span>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">FILE IDENTIFIER:</span>
              <span className="wp-scanner-hud-v">{sample.name}</span>
            </div>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">DOC FORMAT:</span>
              <span className="wp-scanner-hud-v">{sample.type}</span>
            </div>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">EXTRACTED PERIOD:</span>
              <span className="wp-scanner-hud-v">{sample.period}</span>
            </div>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">QUANTIFIED HOURS:</span>
              <span className="wp-scanner-hud-v wp-scanner-hud-v--cyan">{sample.hours}</span>
            </div>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">INTEGRITY DIGEST:</span>
              <span className="wp-scanner-hud-v wp-scanner-hud-v--mono">{sample.hash.slice(0, 24)}...</span>
            </div>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">AI PARSING ENGINE:</span>
              <span className="wp-scanner-hud-v wp-scanner-hud-v--cyan">Primary: LLM API Key • Fallback: Optical OCR</span>
            </div>
            <div className="wp-scanner-hud-field">
              <span className="wp-scanner-hud-k">STORAGE DEST:</span>
              <span className="wp-scanner-hud-v">{sample.storage}</span>
            </div>
          </div>

          <div className="wp-scanner-action-row">
            <button
              type="button"
              className="wp-scanner-trigger-btn"
              onClick={handleScan}
              disabled={isScanning}
            >
              {isScanning ? '⚡ Optical Laser Sweeping...' : '✦ Trigger Laser OCR Scan'}
            </button>

            {scanComplete && (
              <div className="wp-scanner-verified-pill">
                <span className="wp-scanner-verified-icon">✓</span>
                <span>Zero-loss OCR parsing verified. Document synced with Cloudflare R2 bucket. (LLM Engine active; OCR fallback ready).</span>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
