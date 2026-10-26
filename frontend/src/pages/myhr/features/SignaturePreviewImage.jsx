import { useEffect, useState } from 'react';
import { measureSignatureNaturalSize } from './signatureImageProcessing';

// Shows an extracted signature at its natural handwritten size (see
// measureSignatureNaturalSize) - the same size the Offer Letter preview and
// PDF use - instead of a fixed box. Until measured, the caller's CSS safety
// limits apply. Shared by Organization Settings and the My HR Profile.
export default function SignaturePreviewImage({ src, alt, className }) {
  const [measured, setMeasured] = useState(null);
  useEffect(() => {
    if (!src) return undefined;
    let active = true;
    measureSignatureNaturalSize(src)
      .then((size) => { if (active) setMeasured({ src, width: size.width }); })
      .catch(() => {});
    return () => { active = false; };
  }, [src]);
  const width = measured?.src === src ? measured.width : undefined;
  return <img className={className} src={src} alt={alt} style={width ? { width } : undefined} />;
}
