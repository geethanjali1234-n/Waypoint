import resolveFileUrl from '../../../../../api/resolveFileUrl';

/** jsPDF's addImage needs an explicit format matching the image's own encoding. */
function imageFormat(dataUrl) {
  const subtype = /^data:image\/([a-zA-Z0-9+.-]+);/.exec(dataUrl)?.[1]?.toLowerCase();
  if (subtype === 'jpeg' || subtype === 'jpg') return 'JPEG';
  if (subtype === 'webp') return 'WEBP';
  return 'PNG';
}

/**
 * Fetches the Organization Settings logo as a data URL plus its natural size,
 * since jsPDF cannot load a bare HTTP URL (same approach as the offer letter).
 */
export function fetchLetterheadLogo(logoUrl) {
  return fetch(resolveFileUrl(logoUrl))
    .then((response) => response.blob())
    .then((blob) => new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    }))
    .then((dataUrl) => new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve({ dataUrl, width: image.naturalWidth, height: image.naturalHeight, format: imageFormat(dataUrl) });
      image.onerror = reject;
      image.src = dataUrl;
    }));
}
