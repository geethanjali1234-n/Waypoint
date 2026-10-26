import { useEffect, useRef, useState } from 'react';
import { Button } from '../../../components';
import './SignatureRegionSelector.css';

/**
 * Manual fallback for a signature photo automatic detection can't reliably
 * handle alone - a photo with more than one dark region (stray marks,
 * printed text, a second signature) where the system can't tell which one
 * is the real signature. The person drags a box around just the signature;
 * the selected rectangle (as a fraction of the image, independent of its
 * displayed size) is handed to the caller, which re-runs extraction
 * restricted to that area instead of guessing.
 */
export default function SignatureRegionSelector({ file, onSelect, onCancel }) {
  const [imageUrl, setImageUrl] = useState(null);
  const stageRef = useRef(null);
  const [dragStart, setDragStart] = useState(null);
  const [box, setBox] = useState(null);

  // Read as a data URL rather than a revocable blob: URL. React StrictMode
  // mounts, unmounts and remounts every component in development; a blob:
  // URL created once and revoked on unmount was dead by the remount, so the
  // photo never loaded and no area could be selected.
  useEffect(() => {
    let active = true;
    const reader = new FileReader();
    reader.onload = () => { if (active) setImageUrl(reader.result); };
    reader.readAsDataURL(file);
    return () => { active = false; };
  }, [file]);

  const localPoint = (event) => {
    const bounds = stageRef.current.getBoundingClientRect();
    return {
      x: Math.min(Math.max(event.clientX - bounds.left, 0), bounds.width),
      y: Math.min(Math.max(event.clientY - bounds.top, 0), bounds.height),
    };
  };

  const startDrag = (event) => {
    const point = localPoint(event);
    setDragStart(point);
    setBox({ x: point.x, y: point.y, w: 0, h: 0 });
    stageRef.current.setPointerCapture(event.pointerId);
  };
  const continueDrag = (event) => {
    if (!dragStart) return;
    const point = localPoint(event);
    setBox({
      x: Math.min(dragStart.x, point.x), y: Math.min(dragStart.y, point.y),
      w: Math.abs(point.x - dragStart.x), h: Math.abs(point.y - dragStart.y),
    });
  };
  const endDrag = () => setDragStart(null);

  const hasSelection = box && box.w >= 8 && box.h >= 8;
  const useSelection = () => {
    if (!hasSelection) return;
    const bounds = stageRef.current.getBoundingClientRect();
    onSelect({ x: box.x / bounds.width, y: box.y / bounds.height, width: box.w / bounds.width, height: box.h / bounds.height });
  };

  return (
    <div className="signature-region-selector">
      <p>Drag a box around just the handwritten signature.</p>
      <div ref={stageRef} className="signature-region-selector__stage"
        onPointerDown={startDrag} onPointerMove={continueDrag} onPointerUp={endDrag} onPointerLeave={endDrag}>
        {imageUrl && <img src={imageUrl} alt="Uploaded signature source" draggable="false" />}
        {box && <div className="signature-region-selector__box" style={{ left: box.x, top: box.y, width: box.w, height: box.h }} />}
      </div>
      <div className="signature-region-selector__actions">
        <Button type="button" variant="secondary" onClick={onCancel}>Cancel</Button>
        <Button type="button" disabled={!hasSelection} onClick={useSelection}>Use Selection</Button>
      </div>
    </div>
  );
}
