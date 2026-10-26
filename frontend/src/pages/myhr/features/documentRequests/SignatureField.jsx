import { useEffect, useRef, useState } from 'react';
import { Button, FileUpload, TextInput } from '../../../../components';

// Fonts loaded via the Google Fonts @import in documentRequests.css.
const SIGNATURE_FONTS = [
  { label: 'Dancing Script', family: 'Dancing Script' },
  { label: 'Great Vibes', family: 'Great Vibes' },
  { label: 'Pacifico', family: 'Pacifico' },
  { label: 'Caveat', family: 'Caveat' },
];

// A signature may be drawn anywhere on the capture canvas. Persisting the
// whole canvas also persists its empty margins, which makes the visible ink
// look offset when it is stamped into a fixed document signature area.
// Crop every generated image to its visible pixels so the document anchor,
// rather than the position where somebody drew on the pad, controls placement.
const cropCanvasToSignature = (canvas) => {
  try {
    const context = canvas.getContext('2d');
    const { data, width, height } = context.getImageData(0, 0, canvas.width, canvas.height);
    let minX = width; let minY = height; let maxX = -1; let maxY = -1;
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        if (data[((y * width) + x) * 4 + 3] < 16) continue;
        minX = Math.min(minX, x); minY = Math.min(minY, y);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
      }
    }
    if (maxX < minX || maxY < minY) return '';
    const padding = 12;
    const sourceX = Math.max(0, minX - padding);
    const sourceY = Math.max(0, minY - padding);
    const sourceWidth = Math.min(width, maxX + 1 + padding) - sourceX;
    const sourceHeight = Math.min(height, maxY + 1 + padding) - sourceY;
    const cropped = document.createElement('canvas');
    cropped.width = sourceWidth; cropped.height = sourceHeight;
    cropped.getContext('2d').drawImage(canvas, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, sourceWidth, sourceHeight);
    return cropped.toDataURL('image/png');
  } catch {
    // Canvas support is unavailable in a few non-browser environments; keep
    // capture functional there while browsers always use the cropped image.
    return canvas.toDataURL('image/png');
  }
};

// Rasterizes a typed name in the given signature font so it can be stored the
// same way as a drawn signature (a plain PNG data URL).
const renderTypedSignatureDataUrl = async (text, font) => {
  if (!text.trim()) return '';
  try { await document.fonts.load(`64px "${font}"`); } catch { /* fall back to the browser default */ }
  const canvas = document.createElement('canvas');
  canvas.width = 800; canvas.height = 180;
  const context = canvas.getContext('2d');
  context.fillStyle = '#173f33';
  context.font = `64px "${font}", cursive`;
  context.textBaseline = 'middle';
  context.fillText(text.trim(), 24, canvas.height / 2);
  return cropCanvasToSignature(canvas);
};

/**
 * Draw-or-type signature capture, shared by every document-preparation
 * workflow (Offer Letter and the generic document types) so they all offer
 * the same two signature methods. Uncontrolled: the signer's in-progress
 * name/font choice lives here, and only the resulting image is reported to
 * the parent form via onChange.
 */
export default function SignatureField({ onChange, onUploadImage, defaultTypedName = '', lockTypedName = false }) {
  const safeDefaultTypedName = typeof defaultTypedName === 'string' ? defaultTypedName : '';
  const [method, setMethod] = useState('draw');
  const [typedName, setTypedName] = useState(safeDefaultTypedName);
  const [typedFont, setTypedFont] = useState(SIGNATURE_FONTS[0].family);
  const [hasDrawing, setHasDrawing] = useState(false);
  const [isEditingTypedName, setIsEditingTypedName] = useState(false);
  const canvasRef = useRef(null);
  const drawingRef = useRef(false);
  const pointRef = useRef(null);

  useEffect(() => {
    setTypedName(safeDefaultTypedName);
  }, [safeDefaultTypedName]);

  const signaturePoint = (event) => {
    const canvas = canvasRef.current;
    const bounds = canvas.getBoundingClientRect();
    return { x: (event.clientX - bounds.left) * (canvas.width / bounds.width), y: (event.clientY - bounds.top) * (canvas.height / bounds.height) };
  };
  const startSignature = (event) => {
    const canvas = canvasRef.current;
    drawingRef.current = true;
    setHasDrawing(true);
    pointRef.current = signaturePoint(event);
    canvas.setPointerCapture(event.pointerId);
  };
  const drawSignature = (event) => {
    if (!drawingRef.current) return;
    const canvas = canvasRef.current;
    const point = signaturePoint(event);
    const context = canvas.getContext('2d');
    context.beginPath(); context.moveTo(pointRef.current.x, pointRef.current.y); context.lineTo(point.x, point.y);
    context.strokeStyle = '#173f33'; context.lineWidth = 2.5; context.lineCap = 'round'; context.stroke();
    pointRef.current = point;
  };
  // Ends only the current stroke. A signature takes several strokes, so the
  // drawing is reported once, when the signer clicks Continue.
  const finishSignature = () => {
    drawingRef.current = false;
  };
  const continueDraw = () => {
    if (hasDrawing && canvasRef.current) onChange(cropCanvasToSignature(canvasRef.current));
  };
  const clearSignature = () => {
    const canvas = canvasRef.current;
    canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
    setHasDrawing(false);
    onChange('');
  };

  const handleTypedNameChange = (event) => {
    const value = event.target.value;
    setTypedName(value);
  };
  const handleTypedFontChange = (font) => {
    setTypedFont(font);
  };
  const continueType = async () => {
    const url = await renderTypedSignatureDataUrl(typedName, typedFont);
    if (url) onChange(url);
  };
  const selectMethod = (nextMethod) => {
    setMethod(nextMethod);
    onChange('');
  };
  const typedNameLocked = lockTypedName && !isEditingTypedName;

  return (
    <>
      <div className="offer-letter__signature-tabs" role="tablist" aria-label="Signature method">
        {[
          { id: 'draw', label: 'Draw' },
          { id: 'type', label: 'Type' },
          { id: 'upload', label: 'Upload image' },
        ].map((tab) => (
          <button key={tab.id} type="button" role="tab" aria-selected={method === tab.id}
            className={`offer-letter__signature-tab${method === tab.id ? ' active' : ''}`}
            onClick={() => selectMethod(tab.id)}>
            {tab.label}
          </button>
        ))}
      </div>
      <div className="offer-letter__signature">
        {method === 'draw' && (
          <div className="offer-letter__signature-option">
            <div className="offer-letter__signature-head"><strong>Draw Signature</strong><Button type="button" variant="secondary" onClick={clearSignature}>Clear</Button></div>
            <canvas ref={canvasRef} className="offer-letter__signature-pad" width="800" height="180" aria-label="Signature pad"
              onPointerDown={startSignature} onPointerMove={drawSignature} onPointerUp={finishSignature} onPointerLeave={finishSignature} />
            <small>Use your mouse, trackpad, or touch screen to sign, then click Continue.</small>
            <Button type="button" onClick={continueDraw} disabled={!hasDrawing}>Continue</Button>
          </div>
        )}

        {method === 'type' && (
          <div className="offer-letter__signature-option">
            <div className="offer-letter__signature-head"><strong>Type Signature</strong></div>
            <TextInput name="typedSignatureName" label="Full Name" value={typedName}
              onChange={handleTypedNameChange} placeholder="e.g. Jordan Alvarez" disabled={typedNameLocked}
              hint={typedNameLocked ? 'Your Profile name is used for this signature.' : undefined} />
            {typedNameLocked && <Button type="button" variant="secondary" onClick={() => setIsEditingTypedName(true)}>Edit Name</Button>}
            <div className="offer-letter__signature-fonts">
              {SIGNATURE_FONTS.map((font) => (
                <button key={font.family} type="button"
                  className={`offer-letter__signature-font-option${typedFont === font.family ? ' active' : ''}`}
                  style={{ fontFamily: `"${font.family}", cursive` }}
                  onClick={() => handleTypedFontChange(font.family)}>
                  {typedName.trim() || font.label}
                </button>
              ))}
            </div>
            <small>{typedNameLocked ? 'Choose the signature style you like, or edit the name for this signature.' : 'Type your name, then pick the signature style you like.'}</small>
            <Button type="button" onClick={continueType} disabled={!typedName.trim()}>Continue</Button>
          </div>
        )}

        {method === 'upload' && (
          <div className="offer-letter__signature-option">
            <div className="offer-letter__signature-head"><strong>Upload Signature Image</strong></div>
            <FileUpload name="signatureImage" label="Signature image" accept="image/*"
              helperText="Upload an image of your signature (PNG, JPG, or similar)." onUpload={onUploadImage} onReplace={onUploadImage} />
          </div>
        )}
      </div>
    </>
  );
}
