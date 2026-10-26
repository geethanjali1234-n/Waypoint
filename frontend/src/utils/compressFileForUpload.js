// Raster images are re-encoded directly. PDFs are re-rendered page-by-page and
// rebuilt at a lower resolution - the only way to meaningfully shrink a PDF
// (most commonly a phone-scanned passport/I-94/work-authorization copy) without
// a server-side library that can rewrite the embedded objects in place. Word,
// Excel, CSV, and text files are left untouched - they're already compressed
// internally and a generic recompression would risk corrupting them.
const COMPRESSIBLE_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

// Below this, re-encoding risks costing more than it saves (header/overhead),
// so small files are left alone.
const MIN_SIZE_TO_BOTHER = 150 * 1024;

// Generous enough that OCR'd timesheet photos and signed documents stay
// legible, while still shrinking the huge (3000-4000px) images phone cameras
// produce.
const MAX_IMAGE_DIMENSION = 2000;

// Only JPEG/WebP support a quality knob; PNG stays lossless (its transparency
// matters for signatures/logos) and is shrunk by downscaling alone.
const JPEG_WEBP_QUALITY = 0.85;

// ~150 DPI (PDF pages are defined in 72-dpi points) - legible for scanned IDs
// and documents, while far smaller than the 300-600 DPI most scanning apps
// default to, which is what makes a "5MB passport copy" in the first place.
const PDF_RENDER_SCALE = 150 / 72;
const PDF_PAGE_QUALITY = 0.8;

async function compressImage(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  const ctx = canvas.getContext('2d');
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();

  const quality = file.type === 'image/png' ? undefined : JPEG_WEBP_QUALITY;
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, file.type, quality));
  if (!blob) return file;

  // Plain Blobs (e.g. a canvas-captured signature) have no name - keep them a
  // Blob rather than stringifying `undefined` into a "file" name.
  if (typeof file.name !== 'string') return blob;
  return new File([blob], file.name, { type: file.type, lastModified: Date.now() });
}

let pdfToolsPromise = null;
function loadPdfTools() {
  if (!pdfToolsPromise) {
    pdfToolsPromise = Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
      import('jspdf'),
    ]).then(([pdfjs, worker, { jsPDF }]) => {
      pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
      return { pdfjs, jsPDF };
    }).catch((failure) => {
      pdfToolsPromise = null;
      throw failure;
    });
  }
  return pdfToolsPromise;
}

async function compressPdf(file) {
  const { pdfjs, jsPDF } = await loadPdfTools();
  const sourceBytes = await file.arrayBuffer();
  const pdf = await pdfjs.getDocument({ data: sourceBytes }).promise;

  let doc = null;
  for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber++) {
    const page = await pdf.getPage(pageNumber);
    const viewport = page.getViewport({ scale: PDF_RENDER_SCALE });
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(viewport.width);
    canvas.height = Math.round(viewport.height);
    const ctx = canvas.getContext('2d');
    await page.render({ canvas, canvasContext: ctx, viewport }).promise;

    // Convert the rendered pixel size back to PDF points (the viewport was
    // scaled up by PDF_RENDER_SCALE from the page's native point dimensions).
    const widthPt = viewport.width / PDF_RENDER_SCALE;
    const heightPt = viewport.height / PDF_RENDER_SCALE;
    const orientation = widthPt > heightPt ? 'landscape' : 'portrait';
    const imageData = canvas.toDataURL('image/jpeg', PDF_PAGE_QUALITY);

    if (!doc) {
      doc = new jsPDF({ orientation, unit: 'pt', format: [widthPt, heightPt], compress: true });
    } else {
      doc.addPage([widthPt, heightPt], orientation);
    }
    doc.addImage(imageData, 'JPEG', 0, 0, widthPt, heightPt);
  }
  if (!doc) return file;

  const blob = doc.output('blob');
  if (blob.size >= file.size) return file;
  return new File([blob], file.name, { type: 'application/pdf', lastModified: Date.now() });
}

/**
 * Shrinks an oversized file before upload to cut storage/bandwidth use and
 * clear server upload-size limits. Returns the original file unchanged for
 * types it can't safely compress, already-small files, or if compression
 * doesn't actually help or fails for any reason - never blocks or risks
 * corrupting an upload.
 *
 * @param {File} file
 * @returns {Promise<File>}
 */
export default async function compressFileForUpload(file) {
  if (!file || !(file.type === 'application/pdf' || COMPRESSIBLE_IMAGE_TYPES.has(file.type))
      || file.size <= MIN_SIZE_TO_BOTHER) {
    return file;
  }

  try {
    return file.type === 'application/pdf' ? await compressPdf(file) : await compressImage(file);
  } catch {
    return file;
  }
}

/** Compresses every file in a list, in parallel; passes uncompressible types through unchanged. */
export async function compressFilesForUpload(files) {
  return Promise.all(Array.from(files || []).map(compressFileForUpload));
}
