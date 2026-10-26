import { useState, useEffect, useRef } from 'react';
import { renderAsync } from 'docx-preview';
import './DocumentViewerModal.css';

/**
 * DocumentViewerModal - Universal document viewer with interactive zoom in/out,
 * pan, pinch-to-zoom, fit-to-width, and download capabilities for mobile and desktop.
 */
export default function DocumentViewerModal({ isOpen, onClose, file, onDownload }) {
  const [zoom, setZoom] = useState(1);
  const [fitWidth, setFitWidth] = useState(false);
  const docxRef = useRef(null);
  const touchStartDistRef = useRef(null);

  // Reset zoom on document open
  useEffect(() => {
    if (isOpen) {
      setZoom(1);
      setFitWidth(false);
    }
  }, [isOpen, file?.url, file?.blob]);

  // Handle DOCX rendering if provided a blob
  useEffect(() => {
    if (!isOpen || !file?.blob || !docxRef.current) return;
    const isDocx = file.fileName?.toLowerCase().endsWith('.docx') ||
      file.contentType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    
    if (isDocx) {
      const target = docxRef.current;
      target.replaceChildren();
      renderAsync(file.blob, target, undefined, { inWrapper: true }).catch(() => {
        // Fallback or ignore
      });
    }
  }, [isOpen, file]);

  if (!isOpen || !file) return null;

  const handleZoomIn = () => {
    setZoom((z) => Math.min(3.0, Math.round((z + 0.25) * 100) / 100));
  };

  const handleZoomOut = () => {
    setZoom((z) => Math.max(0.5, Math.round((z - 0.25) * 100) / 100));
  };

  const handleResetZoom = () => {
    setZoom(1);
    setFitWidth(false);
  };

  const handleToggleFitWidth = () => {
    setFitWidth((prev) => !prev);
    if (!fitWidth) {
      setZoom(1.4);
    } else {
      setZoom(1);
    }
  };

  // Touch gesture support: pinch to zoom
  const handleTouchStart = (e) => {
    if (e.touches.length === 2) {
      const dist = Math.hypot(
        e.touches[0].clientX - e.touches[1].clientX,
        e.touches[0].clientY - e.touches[1].clientY
      );
      touchStartDistRef.current = dist;
    }
  };

  const handleTouchMove = (e) => {
    if (e.touches.length === 2 && touchStartDistRef.current) {
      const dist = Math.hypot(
        e.touches[0].clientX - e.touches[1].clientX,
        e.touches[0].clientY - e.touches[1].clientY
      );
      const diff = (dist - touchStartDistRef.current) / 200;
      setZoom((z) => Math.max(0.5, Math.min(3.0, z + diff)));
      touchStartDistRef.current = dist;
    }
  };

  const handleTouchEnd = () => {
    touchStartDistRef.current = null;
  };

  const isDocx = file.fileName?.toLowerCase().endsWith('.docx') ||
    file.contentType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
  const isImage = file.type === 'image' ||
    file.contentType?.startsWith('image/') ||
    /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(file.fileName || '');
  const isPdf = file.type === 'pdf' ||
    file.contentType === 'application/pdf' ||
    /\.pdf$/i.test(file.fileName || '');

  return (
    <div
      className="wp-doc-viewer-overlay"
      role="dialog"
      aria-modal="true"
      aria-label={file.fileName || 'Document viewer'}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
    >
      <header className="wp-doc-viewer-header">
        <div className="wp-doc-viewer-title-group">
          <span className="wp-doc-viewer-icon" aria-hidden="true">
            {isPdf ? '📄' : isDocx ? '📝' : isImage ? '🖼️' : '📎'}
          </span>
          <h2 className="wp-doc-viewer-title" title={file.fileName}>
            {file.fileName || 'Document Preview'}
          </h2>
        </div>

        <div className="wp-doc-viewer-controls">
          {/* Zoom controls */}
          <div className="wp-doc-zoom-toolbar" role="toolbar" aria-label="Document zoom controls">
            <button
              type="button"
              className="wp-doc-zoom-btn"
              onClick={handleZoomOut}
              disabled={zoom <= 0.5}
              aria-label="Zoom out"
              title="Zoom out (-)"
            >
              −
            </button>
            <button
              type="button"
              className="wp-doc-zoom-level"
              onClick={handleResetZoom}
              aria-label="Reset zoom level to 100%"
              title="Click to reset to 100%"
            >
              {Math.round(zoom * 100)}%
            </button>
            <button
              type="button"
              className="wp-doc-zoom-btn"
              onClick={handleZoomIn}
              disabled={zoom >= 3.0}
              aria-label="Zoom in"
              title="Zoom in (+)"
            >
              +
            </button>
          </div>

          <button
            type="button"
            className="wp-doc-action-btn"
            onClick={handleToggleFitWidth}
            aria-label="Toggle fit width"
            title="Toggle fit width"
          >
            <span>↔</span>
          </button>

          {onDownload && (
            <button
              type="button"
              className="wp-doc-action-btn"
              onClick={onDownload}
              aria-label="Download document"
              title="Download original document"
            >
              <span>📥</span>
            </button>
          )}

          <button
            type="button"
            className="wp-doc-close-btn"
            onClick={onClose}
            aria-label="Close document viewer"
            title="Close"
          >
            ×
          </button>
        </div>
      </header>

      <main className="wp-doc-viewer-body" onClick={(e) => {
        // Double tap or click background to reset
        if (e.target === e.currentTarget && zoom !== 1) {
          setZoom(1);
        }
      }}>
        <div
          className="wp-doc-viewer-viewport"
          style={{
            transform: `scale(${zoom})`,
            transformOrigin: 'top center',
            width: fitWidth ? '100%' : 'auto'
          }}
        >
          {isImage && (
            <img
              src={file.url}
              alt={file.fileName || 'Document preview'}
              className="wp-doc-viewer-img"
              onDoubleClick={() => setZoom((z) => (z > 1 ? 1 : 1.75))}
            />
          )}

          {isPdf && (
            <iframe
              src={file.url}
              title={file.fileName || 'PDF preview'}
              className="wp-doc-viewer-iframe"
            />
          )}

          {isDocx && (
            <div
              ref={docxRef}
              className="wp-doc-viewer-docx-container"
              aria-label="DOCX preview"
            />
          )}

          {!isImage && !isPdf && !isDocx && file.text && (
            <pre className="wp-doc-viewer-text">{file.text}</pre>
          )}
        </div>
      </main>
    </div>
  );
}
