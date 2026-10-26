// pdf.js is only needed on the signing page, so it (and its worker) is
// loaded on first use instead of being bundled into every My HR page.
let pdfJsPromise = null;

export function loadPdfJs() {
  if (!pdfJsPromise) {
    pdfJsPromise = Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
    ]).then(([pdfjs, worker]) => {
      pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
      return pdfjs;
    }).catch((failure) => {
      pdfJsPromise = null;
      throw failure;
    });
  }
  return pdfJsPromise;
}

export async function openPdf(data) {
  const pdfjs = await loadPdfJs();
  return pdfjs.getDocument({ data }).promise;
}

/**
 * Starts rendering one page to fit cssWidth pixels, sharp on high-DPI
 * screens. Returns the displayed size (so overlays can be positioned on top
 * of it) and the render task, which the caller awaits and cancels when the
 * page changes - pdf.js refuses two renders on the same canvas at once.
 */
export async function startPdfPageRender(pdf, pageNumber, canvas, cssWidth) {
  const page = await pdf.getPage(pageNumber);
  const base = page.getViewport({ scale: 1 });
  const scale = cssWidth / base.width;
  const ratio = window.devicePixelRatio || 1;
  const viewport = page.getViewport({ scale: scale * ratio });
  canvas.width = Math.floor(viewport.width);
  canvas.height = Math.floor(viewport.height);
  const cssHeight = base.height * scale;
  canvas.style.width = `${cssWidth}px`;
  canvas.style.height = `${cssHeight}px`;
  const task = page.render({ canvas, canvasContext: canvas.getContext('2d'), viewport });
  return { size: { width: cssWidth, height: cssHeight }, task };
}

/** Natural size of a signature image, so its box keeps the signature's proportions. */
export function loadImageSize(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve({ width: image.naturalWidth || 1, height: image.naturalHeight || 1 });
    image.onerror = reject;
    image.src = url;
  });
}
