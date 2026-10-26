// Isolates the handwritten ink of a photographed/scanned signature from its
// paper/background using only the Canvas 2D API already used elsewhere in
// this feature (SignatureField's Draw tab) - no new dependency, and nothing
// is sent to an external service. Shared by both signature owners in My HR:
// the Organization Signature (Organization Settings, COMPANY_ADMIN-managed)
// and the Employee Signature (Review & Sign, employee-managed) - the two
// stay separately owned/authorized, but the pixel processing they need is
// identical, so it lives here once instead of twice.
//
// Ink is separated from paper by COLOR DISTANCE, not brightness alone, and
// that distance is measured against a LOCAL, per-pixel background estimate,
// not one single color for the whole photo. A real paper photo's own tone
// is rarely uniform - lighting falloff, a soft shadow, paper texture - and
// a single global "the background is this one RGB value" reference can't
// represent that: it either has to be lenient enough to tolerate the
// photo's own natural variation (at which point genuine ink close in
// brightness to the paper, like blue ink on beige, stops registering as
// different enough) or strict enough to catch that ink (at which point the
// photo's own lighting variation starts registering as "ink" too, and the
// detected region balloons toward the whole frame - confirmed against a
// real rejected upload, where a global reference passed the contrast and
// ink-fraction checks fine but still produced a region covering the entire
// photo). The fix is the standard one for uneven illumination: estimate the
// background LOCALLY, per pixel, as a large-radius box blur of the photo
// itself (via a per-channel integral image, so it's still O(1) per pixel
// regardless of radius). The blur radius is deliberately much wider than a
// signature stroke, so a thin stroke is averaged away to near-nothing in
// the blurred version (the blur still reflects mostly surrounding paper at
// that point) while a lighting gradient, which varies over a much larger
// area, survives and is correctly treated as background at every point
// along it.
//
// A pixel's own darkness contributes to its distance from that local
// reference, but so does its hue and saturation - which is what a pure
// grayscale/brightness threshold (tried and found wanting: it separates
// black ink from white paper fine, but a blue or other colored ink against
// an off-white/beige/gray paper often isn't dramatically darker, even
// though it's obviously, chromatically different to a human eye) misses
// entirely.
//
// Otsu's method - the standard technique for splitting pixels into two
// unknown-proportion classes by finding whichever cutoff best separates
// them - is then run on that per-pixel local distance (not on raw
// brightness, and not against one fixed reference) to find the ink/paper
// boundary, so this makes no assumption about how much of the photo the
// signature covers either.
//
// Connected-component analysis picks out the largest coherent ink region as
// the ANCHOR that locates the signature (scattered noise and edge shadows
// are not it). A second refinement pass (refineSignatureMask) then rebuilds
// the mask around that anchor: paper re-estimated without ink contaminating
// it, a one-sided ink score with a paper-noise ceiling so the paper becomes
// fully transparent (no halo), hysteresis + small-fragment removal for
// noise, and every nearby fragment (i-dots, lifted strokes, initials)
// grouped back in so the signature is complete. Only as a LAST-RESORT sanity
// check - not the primary detection mechanism - is a result still rejected
// if a photo has too little color contrast to trust (MIN_DISTANCE_CONTRAST),
// the "ink" class is most of the frame (MAX_INK_FRACTION), or the final
// selected region still covers nearly the whole frame
// (MAX_BBOX_AREA_FRACTION).
//
// Known limitation: local-mean background estimation assumes the ink itself
// is spatially thin relative to the blur radius - true for an ordinary pen
// stroke. A large SOLID fill wider than roughly twice the radius would have
// its own deep interior's "local background" estimated from more of itself
// than of the surrounding paper, understating its own contrast right at
// that interior point. This does not affect ordinary handwriting strokes.

const MAX_DIMENSION = 1200; // keeps processing fast and the resulting PNG small regardless of source photo resolution
const LOCAL_BACKGROUND_RADIUS_FRACTION = 0.02; // local-background blur radius, as a fraction of the image's shorter side
const LOCAL_BACKGROUND_MIN_RADIUS = 12; // px - floor so tiny images still get a meaningful local estimate
const LOCAL_BACKGROUND_MAX_RADIUS = 80; // px - cap so very large images don't pay for an unnecessarily huge blur window
const MAX_COLOR_DISTANCE = Math.sqrt(3 * 255 * 255); // the largest possible Euclidean RGB distance, for histogram sizing
const MIN_DISTANCE_CONTRAST = 25; // color-distance points between the ink and paper classes below which the photo is too flat/uniform to trust
const MAX_INK_FRACTION = 0.75; // reject if the "ink" class is most of the frame - not a normal signature-on-paper photo (a tight close-up crop can legitimately have the signature fill much of the frame, so this stays generous)
const ALPHA_BBOX_THRESHOLD = 40; // minimum resulting alpha (0-255) to count as part of the signature when cropping
const MAX_BBOX_AREA_FRACTION = 0.85; // final sanity check only - reject a selected region covering more of the frame than this
const MIN_COMPONENT_PIXELS = 20; // ignore a connected ink region smaller than this - noise/compression specks, not a signature
const CROP_PADDING = 12; // px of transparent margin kept around the detected signature
// Refinement pass (see refineSignatureMask):
const PAPER_EXCLUSION_RADIUS = 3; // px - pixels this close to first-pass ink are left out of the paper-color estimate, so ink never darkens its own "paper" reference
const CHROMA_WEIGHT = 0.6; // how much a hue/saturation difference from the paper counts toward "ink", relative to plain darkness
const NOISE_SIGMA = 3.5; // paper-texture noise ceiling = mean + this many standard deviations of the paper's own ink score
const MIN_NOISE_FLOOR = 12; // ink-score points - never treat anything fainter than this as ink, even on perfectly flat paper
const MIN_ALPHA_RAMP = 12; // ink-score points - minimum width of the soft (anti-aliased) edge between transparent and opaque
const ALPHA_RAMP_FRACTION = 0.4; // the opaque threshold sits this far from the noise ceiling toward the stroke core's typical score
const MIN_FRAGMENT_PIXELS = 6; // morphological area-opening: a separate fragment smaller than this is a speck, not an i-dot or stroke
const GROUP_GAP_FACTOR = 1.0; // a separate fragment joins the signature if it lies within this many signature-heights of it
const MIN_GROUP_GAP = 24; // px - floor for that gap, so a short signature still picks up its own i-dots

// Diagnostics from the most recent applySignatureAlphaMask call (safe,
// non-sensitive numbers only - image dimensions, an estimated background
// color, and threshold comparisons - never image content). Exposed so a
// caller can show *why* detection failed directly in its own UI, since
// asking a person to separately open the browser console is easy to miss.
let lastDiagnostics = null;
export function getLastSignatureDetectionDiagnostics() { return lastDiagnostics; }

// Bumped whenever the detection algorithm itself changes, and always
// included in the displayed diagnostic string, so a report that comes back
// can be checked against this number - if it doesn't match the version
// actually in the source at the time, the browser/dev server is serving a
// stale build rather than the current code, which no amount of further
// algorithm changes would fix.
const DETECTOR_VERSION = 'v4-refined-mask';

/** Short, human-readable summary of the last detection attempt's diagnostics, for surfacing directly in an on-screen error message. */
export function formatSignatureDetectionDiagnostics(diagnostics) {
  if (!diagnostics) return '';
  const bg = diagnostics.estimatedBackgroundColor;
  const bgText = bg ? `rgb(${bg.r},${bg.g},${bg.b})` : 'unknown';
  const base = `detector ${DETECTOR_VERSION}, image ${diagnostics.imageSize}, local bg radius ${diagnostics.localBackgroundRadius}px, avg estimated background ${bgText}, contrast ${diagnostics.colorDistanceContrast} (need ${diagnostics.minRequired}), ink ${Math.round((diagnostics.inkFraction || 0) * 100)}% of frame (max ${Math.round((diagnostics.maxAllowed || 0) * 100)}%)`;
  if (diagnostics.reason === 'bbox-too-large') return `${base}, detected region ${Math.round((diagnostics.bboxAreaFraction || 0) * 100)}% of frame (max ${Math.round((diagnostics.maxAllowed || 0) * 100)}%) [reason: ${diagnostics.reason}]`;
  return `${base} [reason: ${diagnostics.reason}]`;
}

function loadImageFromFile(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => { URL.revokeObjectURL(url); resolve(image); };
    image.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Image could not be loaded.')); };
    image.src = url;
  });
}

/** Per-channel summed-area table (integral image), for O(1) box-average lookups of any radius. */
function buildIntegralImage(channel, width, height) {
  const stride = width + 1;
  const integral = new Float64Array(stride * (height + 1));
  for (let y = 0; y < height; y += 1) {
    let rowSum = 0;
    for (let x = 0; x < width; x += 1) {
      rowSum += channel[y * width + x];
      integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + rowSum;
    }
  }
  return integral;
}

function boxAverage(integral, width, height, x, y, radius) {
  const x0 = Math.max(0, x - radius); const y0 = Math.max(0, y - radius);
  const x1 = Math.min(width - 1, x + radius); const y1 = Math.min(height - 1, y + radius);
  const stride = width + 1;
  const sum = integral[(y1 + 1) * stride + (x1 + 1)] - integral[y0 * stride + (x1 + 1)]
    - integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0];
  return sum / ((x1 - x0 + 1) * (y1 - y0 + 1));
}

/**
 * The photo's own LOCAL background color at every pixel: a large-radius box
 * blur of each color channel (via an integral image, so it's O(1) per pixel
 * regardless of radius), wide enough to average away a signature stroke's
 * own width but still narrow enough to track real, gradual lighting
 * variation across the paper. See the file header for why a single global
 * background estimate isn't enough.
 */
function estimateLocalBackground(data, width, height) {
  const total = width * height;
  const r = new Float64Array(total); const g = new Float64Array(total); const b = new Float64Array(total);
  for (let p = 0; p < total; p += 1) { const i = p * 4; r[p] = data[i]; g[p] = data[i + 1]; b[p] = data[i + 2]; }
  const radius = Math.max(
    LOCAL_BACKGROUND_MIN_RADIUS,
    Math.min(LOCAL_BACKGROUND_MAX_RADIUS, Math.round(Math.min(width, height) * LOCAL_BACKGROUND_RADIUS_FRACTION)),
  );
  const integralR = buildIntegralImage(r, width, height);
  const integralG = buildIntegralImage(g, width, height);
  const integralB = buildIntegralImage(b, width, height);
  const localR = new Float64Array(total); const localG = new Float64Array(total); const localB = new Float64Array(total);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const p = y * width + x;
      localR[p] = boxAverage(integralR, width, height, x, y, radius);
      localG[p] = boxAverage(integralG, width, height, x, y, radius);
      localB[p] = boxAverage(integralB, width, height, x, y, radius);
    }
  }
  return { r: localR, g: localG, b: localB, radius };
}

/**
 * Labels every 8-connected group of mask pixels (an iterative flood fill,
 * not recursive - real photos are large enough that a recursive version
 * would overflow the call stack), returning a per-pixel label array plus
 * each component's bounding box and pixel count. When `strong` is given,
 * each component also counts how many of its pixels are set in it - used
 * for hysteresis thresholding (a faint pixel only counts as ink if it's
 * connected to at least one unmistakably-ink pixel).
 */
function labelConnectedComponents(mask, width, height, strong = null) {
  const total = width * height;
  const labels = new Int32Array(total).fill(-1);
  const queueX = new Int32Array(total);
  const queueY = new Int32Array(total);
  const components = [];

  for (let startY = 0; startY < height; startY += 1) {
    for (let startX = 0; startX < width; startX += 1) {
      const startIndex = startY * width + startX;
      if (labels[startIndex] !== -1 || !mask[startIndex]) continue;

      const label = components.length;
      let head = 0; let tail = 0;
      queueX[tail] = startX; queueY[tail] = startY; tail += 1;
      labels[startIndex] = label;
      let minX = startX; let maxX = startX; let minY = startY; let maxY = startY; let size = 0; let strongCount = 0;

      while (head < tail) {
        const x = queueX[head]; const y = queueY[head]; head += 1;
        size += 1;
        if (strong && strong[y * width + x]) strongCount += 1;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;

        for (let dy = -1; dy <= 1; dy += 1) {
          for (let dx = -1; dx <= 1; dx += 1) {
            if (dx === 0 && dy === 0) continue;
            const nx = x + dx; const ny = y + dy;
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
            const nIndex = ny * width + nx;
            if (labels[nIndex] !== -1 || !mask[nIndex]) continue;
            labels[nIndex] = label;
            queueX[tail] = nx; queueY[tail] = ny; tail += 1;
          }
        }
      }

      components.push({ minX, minY, maxX, maxY, size, strongCount, label });
    }
  }
  return { labels, components };
}

/**
 * The largest connected ink region of the first-pass mask - or null if no
 * ink pixel exists at all. Used only as the ANCHOR that says "the signature
 * is here"; a thin shadow or vignette near the photo's edges, a stray mark,
 * or sensor noise forms its own separate, usually much smaller region and
 * is never chosen as the anchor. The final output is NOT limited to this
 * one component (see refineSignatureMask) - an i-dot, a separately-lifted
 * stroke, or a set of initials is its own component too, and must survive.
 */
function largestConnectedInkComponent(isInk, width, height) {
  const { labels, components } = labelConnectedComponents(isInk, width, height);
  let best = null;
  components.forEach((component) => { if (!best || component.size > best.size) best = component; });
  if (!best) return null;
  return { ...best, labels };
}

function colorDistance(data, i, p, localBackground) {
  const dr = data[i] - localBackground.r[p];
  const dg = data[i + 1] - localBackground.g[p];
  const db = data[i + 2] - localBackground.b[p];
  return Math.sqrt(dr * dr + dg * dg + db * db);
}

/**
 * Otsu's method: finds the cutoff (over the given histogram, indexed 0..N)
 * that maximizes the separation between the values below it and above it,
 * and returns that cutoff along with each side's own mean value and pixel
 * count.
 */
function otsuSplit(hist, total) {
  let sumAll = 0;
  for (let level = 0; level < hist.length; level += 1) sumAll += level * hist[level];

  let sumBelow = 0; let countBelow = 0; let bestVariance = -1; let bestThreshold = 0;
  for (let t = 0; t < hist.length; t += 1) {
    countBelow += hist[t];
    sumBelow += t * hist[t];
    const countAbove = total - countBelow;
    if (countBelow === 0 || countAbove === 0) continue;
    const meanBelow = sumBelow / countBelow;
    const meanAbove = (sumAll - sumBelow) / countAbove;
    const weightBelow = countBelow / total;
    const weightAbove = countAbove / total;
    const variance = weightBelow * weightAbove * (meanBelow - meanAbove) ** 2;
    if (variance > bestVariance) { bestVariance = variance; bestThreshold = t; }
  }

  let sumBelowFinal = 0; let countBelowFinal = 0;
  for (let level = 0; level <= bestThreshold; level += 1) { sumBelowFinal += level * hist[level]; countBelowFinal += hist[level]; }
  const countAboveFinal = total - countBelowFinal;
  return {
    threshold: bestThreshold,
    meanBelow: countBelowFinal ? sumBelowFinal / countBelowFinal : bestThreshold,
    meanAbove: countAboveFinal ? (sumAll - sumBelowFinal) / countAboveFinal : bestThreshold,
    countBelow: countBelowFinal,
  };
}

function luminance(r, g, b) { return 0.299 * r + 0.587 * g + 0.114 * b; }

/**
 * How strongly a pixel reads as INK against the paper color at that spot:
 * how much darker it is, plus how different its hue/saturation is (its
 * chroma - the color left over once brightness is removed), so blue or
 * other colored ink counts even where it isn't much darker than the paper.
 * Unlike the first pass's plain color distance, this is ONE-SIDED: a pixel
 * lighter than the paper (glare, paper grain, or the bright rim a blurred
 * reference produces right beside a stroke) is penalized rather than
 * counted as "different" - which is what used to leave a light,
 * paper-colored halo around the handwriting.
 */
function inkScore(r, g, b, pr, pg, pb) {
  const lum = luminance(r, g, b);
  const paperLum = luminance(pr, pg, pb);
  const darkness = paperLum - lum;
  const cr = (r - lum) - (pr - paperLum);
  const cg = (g - lum) - (pg - paperLum);
  const cb = (b - lum) - (pb - paperLum);
  const chroma = Math.sqrt(cr * cr + cg * cg + cb * cb);
  return Math.max(0, darkness + CHROMA_WEIGHT * chroma);
}

function bboxGap(a, b) {
  const gapX = Math.max(0, Math.max(a.minX, b.minX) - Math.min(a.maxX, b.maxX));
  const gapY = Math.max(0, Math.max(a.minY, b.minY) - Math.min(a.maxY, b.maxY));
  return Math.max(gapX, gapY);
}

/**
 * Second pass, run once the first pass has located the signature: rebuilds
 * the alpha channel from a cleaner foreground mask so the output is ONLY
 * the handwritten ink, fully transparent everywhere else, and complete.
 *
 * 1. Paper color is re-estimated locally from paper pixels only (first-pass
 *    ink, dilated slightly, is excluded), so a stroke no longer darkens its
 *    own reference and the paper right beside it stops looking "different."
 * 2. Each pixel gets a one-sided ink score (inkScore), and the paper's own
 *    score distribution sets a noise ceiling: anything at or below it -
 *    paper texture, grain, lighting - becomes fully transparent (alpha 0),
 *    not faintly translucent.
 * 3. Hysteresis: faint pixels above that ceiling are kept only when
 *    connected to a clearly-inked pixel, which keeps thin/light stroke
 *    edges attached to their strokes without letting texture back in.
 * 4. Area-opening (morphology that removes small connected fragments rather
 *    than eroding shapes, so thin strokes and dots survive intact) drops
 *    isolated specks.
 * 5. EVERY remaining fragment near the signature - i-dots, separately
 *    lifted strokes, initials - is grouped with it, growing outward from
 *    the anchor, so the bounding box covers the complete signature rather
 *    than only its single largest stroke.
 * 6. Soft edge pixels keep their alpha but have the paper tint pulled out
 *    of their color (blended toward the signature's own ink color), so the
 *    anti-aliased edge doesn't show as a light rim; fully-inked pixels keep
 *    their original color untouched.
 */
function refineSignatureMask(data, width, height, isInk, anchor, radius) {
  const total = width * height;

  // 1. Local paper color from paper-only pixels.
  const inkIntegral = buildIntegralImage(isInk, width, height);
  const paperMask = new Float64Array(total);
  const maskedR = new Float64Array(total); const maskedG = new Float64Array(total); const maskedB = new Float64Array(total);
  let paperCount = 0; let paperSumR = 0; let paperSumG = 0; let paperSumB = 0;
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      if (boxAverage(inkIntegral, width, height, x, y, PAPER_EXCLUSION_RADIUS) > 0) continue;
      const p = y * width + x; const i = p * 4;
      paperMask[p] = 1; maskedR[p] = data[i]; maskedG[p] = data[i + 1]; maskedB[p] = data[i + 2];
      paperCount += 1; paperSumR += data[i]; paperSumG += data[i + 1]; paperSumB += data[i + 2];
    }
  }
  if (!paperCount) return null;
  const globalPaper = { r: paperSumR / paperCount, g: paperSumG / paperCount, b: paperSumB / paperCount };
  const countIntegral = buildIntegralImage(paperMask, width, height);
  const integralR = buildIntegralImage(maskedR, width, height);
  const integralG = buildIntegralImage(maskedG, width, height);
  const integralB = buildIntegralImage(maskedB, width, height);

  // 2. Ink score, and the paper's own noise level.
  const scores = new Float64Array(total);
  let noiseSum = 0; let noiseSumSq = 0;
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const p = y * width + x; const i = p * 4;
      // Inside a thick stroke the window can be all ink; widen it once, then fall back to the photo's overall paper color.
      let windowRadius = radius;
      let count = boxAverage(countIntegral, width, height, x, y, windowRadius);
      if (count === 0) { windowRadius = radius * 3; count = boxAverage(countIntegral, width, height, x, y, windowRadius); }
      const pr = count ? boxAverage(integralR, width, height, x, y, windowRadius) / count : globalPaper.r;
      const pg = count ? boxAverage(integralG, width, height, x, y, windowRadius) / count : globalPaper.g;
      const pb = count ? boxAverage(integralB, width, height, x, y, windowRadius) / count : globalPaper.b;
      const score = inkScore(data[i], data[i + 1], data[i + 2], pr, pg, pb);
      scores[p] = score;
      if (paperMask[p]) { noiseSum += score; noiseSumSq += score * score; }
    }
  }
  const noiseMean = noiseSum / paperCount;
  const noiseStd = Math.sqrt(Math.max(0, noiseSumSq / paperCount - noiseMean * noiseMean));
  const low = Math.max(MIN_NOISE_FLOOR, noiseMean + NOISE_SIGMA * noiseStd);

  const anchorScores = [];
  for (let p = 0; p < total; p += 1) if (anchor.labels[p] === anchor.label) anchorScores.push(scores[p]);
  anchorScores.sort((a, b) => a - b);
  const strokeCoreScore = anchorScores[Math.floor(anchorScores.length * 0.75)] || low;
  const high = low + Math.max(MIN_ALPHA_RAMP, ALPHA_RAMP_FRACTION * (strokeCoreScore - low));

  // 3. Hysteresis: weak pixels survive only in a component with a strong pixel.
  const weak = new Uint8Array(total); const strong = new Uint8Array(total);
  for (let p = 0; p < total; p += 1) {
    if (scores[p] > low) weak[p] = 1;
    if (scores[p] >= high) strong[p] = 1;
  }
  const { labels, components } = labelConnectedComponents(weak, width, height, strong);

  // The refined component that best overlaps the first-pass anchor is where the signature starts.
  const overlap = new Uint32Array(components.length);
  for (let p = 0; p < total; p += 1) if (anchor.labels[p] === anchor.label && labels[p] >= 0) overlap[labels[p]] += 1;
  let seed = null;
  components.forEach((component) => {
    if (!component.strongCount) return;
    const better = !seed || overlap[component.label] > overlap[seed.label]
      || (overlap[component.label] === overlap[seed.label] && component.size > seed.size);
    if (better) seed = component;
  });
  if (!seed) return null;

  // 4 + 5. Drop specks, then grow the signature outward to every nearby fragment.
  const maxArea = MAX_BBOX_AREA_FRACTION * total;
  const areaOf = (box) => (box.maxX - box.minX + 1) * (box.maxY - box.minY + 1);
  const candidates = components.filter((component) => component !== seed
    && component.strongCount > 0 && component.size >= MIN_FRAGMENT_PIXELS && areaOf(component) <= maxArea);
  const selected = new Uint8Array(components.length);
  selected[seed.label] = 1;
  const cluster = { minX: seed.minX, minY: seed.minY, maxX: seed.maxX, maxY: seed.maxY };
  const maxGap = Math.max(MIN_GROUP_GAP, Math.round((seed.maxY - seed.minY + 1) * GROUP_GAP_FACTOR));
  let grew = true;
  while (grew) {
    grew = false;
    for (const component of candidates) {
      if (selected[component.label] || bboxGap(cluster, component) > maxGap) continue;
      const union = {
        minX: Math.min(cluster.minX, component.minX), minY: Math.min(cluster.minY, component.minY),
        maxX: Math.max(cluster.maxX, component.maxX), maxY: Math.max(cluster.maxY, component.maxY),
      };
      if (areaOf(union) > maxArea) continue;
      selected[component.label] = 1;
      Object.assign(cluster, union);
      grew = true;
    }
  }

  // 6. Final alpha + edge color decontamination.
  let inkCount = 0; let inkSumR = 0; let inkSumG = 0; let inkSumB = 0;
  for (let p = 0; p < total; p += 1) {
    if (labels[p] < 0 || !selected[labels[p]] || !strong[p]) continue;
    const i = p * 4;
    inkCount += 1; inkSumR += data[i]; inkSumG += data[i + 1]; inkSumB += data[i + 2];
  }
  const inkR = inkSumR / inkCount; const inkG = inkSumG / inkCount; const inkB = inkSumB / inkCount;
  let minX = width; let minY = height; let maxX = -1; let maxY = -1; let fragments = 0;
  selected.forEach((flag) => { fragments += flag; });
  for (let p = 0; p < total; p += 1) {
    const i = p * 4;
    if (labels[p] < 0 || !selected[labels[p]]) { data[i + 3] = 0; continue; }
    const alpha = Math.max(1, Math.min(255, Math.round(((scores[p] - low) / (high - low)) * 255)));
    data[i + 3] = alpha;
    if (alpha < 255) {
      const a = alpha / 255;
      data[i] = Math.round(inkR * (1 - a) + data[i] * a);
      data[i + 1] = Math.round(inkG * (1 - a) + data[i + 1] * a);
      data[i + 2] = Math.round(inkB * (1 - a) + data[i + 2] * a);
    }
    const x = p % width; const y = (p - x) / width;
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  return {
    bbox: { minX, minY, maxX, maxY },
    stats: { noiseCeiling: Math.round(low), opaqueThreshold: Math.round(high), fragments },
  };
}

/**
 * Pure pixel step, exported separately so it can be unit-tested without a
 * real canvas: rewrites `data`'s alpha channel in place so ink pixels
 * become opaque and paper/background pixels become transparent, with a
 * smooth ramp between the two classes' own mean color-distance (not the
 * boundary itself, which can coincide with one class's mean in a perfectly
 * flat/synthetic image and produce a zero-width ramp). Returns the bounding
 * box of the detected ink, or null when no reliable signature could be
 * separated out.
 */
export function applySignatureAlphaMask(data, width, height) {
  const localBackground = estimateLocalBackground(data, width, height);

  const distHistSize = Math.ceil(MAX_COLOR_DISTANCE) + 1;
  const distHist = new Uint32Array(distHistSize);
  const total = width * height;
  const distances = new Float64Array(total);
  let backgroundSumR = 0; let backgroundSumG = 0; let backgroundSumB = 0;
  for (let p = 0; p < total; p += 1) {
    const i = p * 4;
    const distance = colorDistance(data, i, p, localBackground);
    distances[p] = distance;
    distHist[Math.max(0, Math.min(distHistSize - 1, Math.round(distance)))] += 1;
    backgroundSumR += localBackground.r[p]; backgroundSumG += localBackground.g[p]; backgroundSumB += localBackground.b[p];
  }

  const { meanBelow, meanAbove, countBelow } = otsuSplit(distHist, total);
  const contrast = meanAbove - meanBelow;
  const inkFraction = (total - countBelow) / total;
  const diagnostics = {
    imageSize: `${width}x${height}`,
    // The photo's LOCAL background varies per pixel (see file header) - this
    // is the average of that local estimate across the whole photo, shown
    // only as a representative approximation for diagnostics, not a value
    // actually used anywhere in detection itself.
    estimatedBackgroundColor: { r: Math.round(backgroundSumR / total), g: Math.round(backgroundSumG / total), b: Math.round(backgroundSumB / total) },
    localBackgroundRadius: localBackground.radius,
    colorDistanceContrast: Math.round(contrast), minRequired: MIN_DISTANCE_CONTRAST,
    inkFraction: Number(inkFraction.toFixed(4)), maxAllowed: MAX_INK_FRACTION,
  };
  if (contrast < MIN_DISTANCE_CONTRAST) {
    lastDiagnostics = { ok: false, reason: 'low-contrast', ...diagnostics };
    console.warn('[signature-detect] rejected: color contrast too low (background and ink look too similar)', diagnostics);
    return null;
  }
  if (inkFraction > MAX_INK_FRACTION) {
    lastDiagnostics = { ok: false, reason: 'ink-fraction-too-high', ...diagnostics };
    console.warn('[signature-detect] rejected: too much of the photo was classified as ink', diagnostics);
    return null;
  }

  const isInk = new Uint8Array(total);
  for (let p = 0; p < total; p += 1) {
    const i = p * 4;
    const alpha = Math.max(0, Math.min(255, Math.round(((distances[p] - meanBelow) / contrast) * 255)));
    data[i + 3] = alpha;
    if (alpha >= ALPHA_BBOX_THRESHOLD) isInk[p] = 1;
  }

  const component = largestConnectedInkComponent(isInk, width, height);
  if (!component || component.size < MIN_COMPONENT_PIXELS) {
    lastDiagnostics = { ok: false, reason: 'no-pixels-above-alpha-threshold', ...diagnostics, alphaBboxThreshold: ALPHA_BBOX_THRESHOLD, largestComponentSize: component?.size || 0 };
    console.warn('[signature-detect] rejected: no connected ink region large enough to be a signature', lastDiagnostics);
    return null;
  }
  const { minX, minY, maxX, maxY } = component;

  const bboxArea = (maxX - minX + 1) * (maxY - minY + 1);
  const bboxAreaFraction = bboxArea / (width * height);
  if (bboxAreaFraction > MAX_BBOX_AREA_FRACTION) {
    lastDiagnostics = { ok: false, reason: 'bbox-too-large', ...diagnostics, bboxAreaFraction: Number(bboxAreaFraction.toFixed(4)), maxAllowed: MAX_BBOX_AREA_FRACTION };
    console.warn('[signature-detect] rejected: detected region covers too much of the frame', lastDiagnostics);
    return null;
  }

  // First pass located the signature; the refinement pass rebuilds the
  // alpha channel so only the complete handwritten ink survives.
  const refined = refineSignatureMask(data, width, height, isInk, component, localBackground.radius);
  if (!refined) {
    lastDiagnostics = { ok: false, reason: 'refinement-empty', ...diagnostics };
    console.warn('[signature-detect] rejected: no ink survived the refined foreground mask', lastDiagnostics);
    return null;
  }
  const finalBbox = refined.bbox;
  const finalAreaFraction = ((finalBbox.maxX - finalBbox.minX + 1) * (finalBbox.maxY - finalBbox.minY + 1)) / total;

  lastDiagnostics = { ok: true, ...diagnostics, ...refined.stats, bbox: finalBbox, bboxAreaFraction: Number(finalAreaFraction.toFixed(4)), componentSize: component.size };
  console.info('[signature-detect] accepted', lastDiagnostics);
  return finalBbox;
}

// --- Natural display size --------------------------------------------------
// A photo's pixels have no physical size: the same 45mm signature is ~260px
// wide in a 1200px photo of a page and ~870px wide in a 4000px phone photo,
// so neither the extracted PNG's pixel size nor any fixed box says how big
// the handwriting really was. The one physical ruler inside the image is the
// pen stroke itself - a ballpoint line is ~0.5mm wide on paper whatever
// the camera - so measuring the stroke width in the extracted image gives
// its scale, and from that the size the handwriting actually had.
const NATURAL_PEN_STROKE_MM = 0.5; // a standard ballpoint line; errs toward a slightly smaller, never a larger, signature
const CSS_PX_PER_MM = 96 / 25.4;
const NATURAL_STROKE_CSS_PX = NATURAL_PEN_STROKE_MM * CSS_PX_PER_MM; // ~1.9 CSS px
/**
 * Typical pen-stroke width (px) of an extracted signature, from its
 * anti-aliased alpha channel: for a stroke much longer than it is wide,
 * area = width x length and outline = 2 x length, so width = 2 x area /
 * outline. Both are measured sub-pixel accurately - area as the summed
 * alpha, outline as the summed alpha-gradient magnitude (which adds up to
 * the edge length whatever the stroke's direction) - rather than by
 * counting whole pixels, which misreads thin strokes by up to a pixel.
 * Null if there is no ink.
 */
export function estimateStrokeWidth(data, width, height) {
  const alpha = (x, y) => (x < 0 || y < 0 || x >= width || y >= height ? 0 : data[(y * width + x) * 4 + 3] / 255);
  let area = 0; let outline = 0;
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      area += alpha(x, y);
      const gx = (alpha(x + 1, y) - alpha(x - 1, y)) / 2;
      const gy = (alpha(x, y + 1) - alpha(x, y - 1)) / 2;
      outline += Math.sqrt(gx * gx + gy * gy);
    }
  }
  if (!area || !outline) return null;
  return (2 * area) / outline;
}

/**
 * The size (CSS px) an extracted signature should be DISPLAYED at so the
 * handwriting appears at its natural size: scaled so its strokes are as wide
 * as a real pen line. Never larger than the image's own pixel size (no
 * upscaling). Shared by the Offer Letter preview and its PDF so both show
 * the same signature at the same size.
 */
export function naturalSignatureSize(data, width, height) {
  const strokeWidth = estimateStrokeWidth(data, width, height);
  const scale = strokeWidth ? Math.min(1, NATURAL_STROKE_CSS_PX / strokeWidth) : 1;
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)), strokeWidth, scale };
}

/**
 * naturalSignatureSize for an image source (URL or data URL). A remote URL
 * (e.g. the stored signature on the API origin) is fetched to a local blob
 * first, since a canvas can't read a cross-origin image's pixels. Falls back
 * to the image's own pixel size - still never upscaled - when its pixels
 * can't be read (no canvas support, e.g. in jsdom).
 */
export async function measureSignatureNaturalSize(src) {
  if (/^(data|blob):/.test(src)) return measureLocalSignatureImage(src);
  const blobUrl = URL.createObjectURL(await (await fetch(src)).blob());
  try { return await measureLocalSignatureImage(blobUrl); } finally { URL.revokeObjectURL(blobUrl); }
}

function measureLocalSignatureImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      const width = image.naturalWidth || image.width;
      const height = image.naturalHeight || image.height;
      try {
        const canvas = document.createElement('canvas');
        canvas.width = width; canvas.height = height;
        const context = canvas.getContext('2d');
        context.drawImage(image, 0, 0);
        resolve(naturalSignatureSize(context.getImageData(0, 0, width, height).data, width, height));
      } catch {
        resolve({ width, height, strokeWidth: null, scale: 1 });
      }
    };
    image.onerror = () => reject(new Error('Signature image could not be loaded.'));
    image.src = src;
  });
}

/**
 * Accepts an uploaded signature photo/scan and returns a transparent-
 * background PNG data URL containing only the extracted ink, cropped to its
 * bounding box - or null when no signature-like ink could be detected, so
 * the caller can ask for a clearer image instead of silently embedding the
 * original, unprocessed photo.
 *
 * `region`, if given, is a fractional rectangle ({x, y, width, height}, each
 * 0-1 relative to the source image) that restricts processing to a manually
 * selected part of the photo - for a photo containing more than one dark
 * region (stray marks, printed text, a second signature) where automatic
 * detection can't reliably tell which one is the real signature, the caller
 * lets the person doing the upload draw a box around it first instead of
 * guessing.
 */
export async function extractSignatureFromImage(file, region = null) {
  const image = await loadImageFromFile(file);
  const sourceWidth = image.naturalWidth || image.width;
  const sourceHeight = image.naturalHeight || image.height;

  const sx = region ? Math.max(0, Math.round(region.x * sourceWidth)) : 0;
  const sy = region ? Math.max(0, Math.round(region.y * sourceHeight)) : 0;
  const sWidth = region ? Math.max(1, Math.round(region.width * sourceWidth)) : sourceWidth;
  const sHeight = region ? Math.max(1, Math.round(region.height * sourceHeight)) : sourceHeight;

  const scale = Math.min(1, MAX_DIMENSION / Math.max(sWidth, sHeight));
  const width = Math.max(1, Math.round(sWidth * scale));
  const height = Math.max(1, Math.round(sHeight * scale));

  const canvas = document.createElement('canvas');
  canvas.width = width; canvas.height = height;
  const context = canvas.getContext('2d');
  context.drawImage(image, sx, sy, sWidth, sHeight, 0, 0, width, height);
  const imageData = context.getImageData(0, 0, width, height);

  const bbox = applySignatureAlphaMask(imageData.data, width, height);
  if (!bbox) return null;
  context.putImageData(imageData, 0, 0);

  const cropX = Math.max(0, bbox.minX - CROP_PADDING);
  const cropY = Math.max(0, bbox.minY - CROP_PADDING);
  const cropWidth = Math.min(width, bbox.maxX + 1 + CROP_PADDING) - cropX;
  const cropHeight = Math.min(height, bbox.maxY + 1 + CROP_PADDING) - cropY;

  const cropped = document.createElement('canvas');
  cropped.width = cropWidth; cropped.height = cropHeight;
  cropped.getContext('2d').drawImage(canvas, cropX, cropY, cropWidth, cropHeight, 0, 0, cropWidth, cropHeight);
  return cropped.toDataURL('image/png');
}
