import { describe, expect, it } from 'vitest';
import { applySignatureAlphaMask, estimateStrokeWidth, naturalSignatureSize } from '../../../pages/myhr/features/signatureImageProcessing';

const WIDTH = 100;
const HEIGHT = 60;

function makePlainImage(width, height, fill) {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < data.length; i += 4) {
    data[i] = fill[0]; data[i + 1] = fill[1]; data[i + 2] = fill[2]; data[i + 3] = 255;
  }
  return data;
}

function makeImage(fill) {
  return makePlainImage(WIDTH, HEIGHT, fill);
}

// Deterministic pseudo-random noise, standing in for real photo grain/JPEG
// artifacts so a test can check the algorithm still works on a background
// that isn't perfectly flat pixel-to-pixel.
function seededRandom(seed) {
  let value = seed;
  return () => { value = (value * 9301 + 49297) % 233280; return value / 233280; };
}

function paintBlock(data, { x0, y0, x1, y1 }, color, width = WIDTH) {
  for (let y = y0; y < y1; y += 1) {
    for (let x = x0; x < x1; x += 1) {
      const i = (y * width + x) * 4;
      data[i] = color[0]; data[i + 1] = color[1]; data[i + 2] = color[2];
    }
  }
}

// The local-background estimate (see signatureImageProcessing.js's file
// header) is a blur, not an exact per-pixel reference, so the detected
// region legitimately extends a bit beyond the painted ink rectangle - a
// "halo" roughly the width of the blur's own radius. HALO_TOLERANCE is a
// generous bound on that, comfortably covering the radius these small test
// canvases actually use (the implementation's own 12px floor).
const HALO_TOLERANCE = 20;

function expectBboxNear(bbox, expected) {
  expect(bbox.minX).toBeLessThanOrEqual(expected.x0);
  expect(bbox.minX).toBeGreaterThanOrEqual(expected.x0 - HALO_TOLERANCE);
  expect(bbox.minY).toBeLessThanOrEqual(expected.y0);
  expect(bbox.minY).toBeGreaterThanOrEqual(expected.y0 - HALO_TOLERANCE);
  expect(bbox.maxX).toBeGreaterThanOrEqual(expected.x1 - 1);
  expect(bbox.maxX).toBeLessThanOrEqual(expected.x1 - 1 + HALO_TOLERANCE);
  expect(bbox.maxY).toBeGreaterThanOrEqual(expected.y1 - 1);
  expect(bbox.maxY).toBeLessThanOrEqual(expected.y1 - 1 + HALO_TOLERANCE);
}

describe('applySignatureAlphaMask', () => {
  it('ignores a thin artifact ring touching all four edges (vignette/shadow/border) and still isolates the real signature - regression test for a real reported failure', () => {
    // Exactly reproduces a real rejection this heuristic produced against an
    // actual uploaded photo: color contrast and ink-fraction both passed,
    // but the OLD global bounding box (every ink-flagged pixel anywhere in
    // the photo) covered 100% of the frame because a thin dark border/
    // vignette/shadow strip touched all four edges, even though genuine ink
    // was a reasonable ~24% of the frame. A single connected component
    // (the actual signature) is a completely separate region from that
    // border, so selecting only the largest one must exclude it.
    const width = 200; const height = 150;
    const data = new Uint8ClampedArray(width * height * 4);
    const paper = [163, 154, 149];
    for (let i = 0; i < data.length; i += 4) {
      data[i] = paper[0]; data[i + 1] = paper[1]; data[i + 2] = paper[2]; data[i + 3] = 255;
    }
    // A 2px-wide dark ring around the entire photo, touching every edge.
    const ring = [40, 40, 40];
    for (let x = 0; x < width; x += 1) {
      for (let t = 0; t < 2; t += 1) {
        [t, height - 1 - t].forEach((y) => { const i = (y * width + x) * 4; data[i] = ring[0]; data[i + 1] = ring[1]; data[i + 2] = ring[2]; });
      }
    }
    for (let y = 0; y < height; y += 1) {
      for (let t = 0; t < 2; t += 1) {
        [t, width - 1 - t].forEach((x) => { const i = (y * width + x) * 4; data[i] = ring[0]; data[i + 1] = ring[1]; data[i + 2] = ring[2]; });
      }
    }
    // The genuine signature: a solid, fully-connected central block.
    const inkRect = { x0: 60, y0: 45, x1: 140, y1: 105 };
    for (let y = inkRect.y0; y < inkRect.y1; y += 1) {
      for (let x = inkRect.x0; x < inkRect.x1; x += 1) {
        const i = (y * width + x) * 4;
        data[i] = 30; data[i + 1] = 40; data[i + 2] = 130;
      }
    }

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).not.toBeNull();
    expectBboxNear(bbox, inkRect);
    // Whatever halo the local-background blur leaves around the true
    // signature, it must stay far short of the ring at the very edges.
    const bboxAreaFraction = (bbox.maxX - bbox.minX + 1) * (bbox.maxY - bbox.minY + 1) / (width * height);
    expect(bboxAreaFraction).toBeLessThan(0.5);

    // The border ring must not survive into the output at all.
    const ringPixel = (0 * width + 0) * 4;
    expect(data[ringPixel + 3]).toBe(0);
  });


  it('excludes an unconnected stray mark far away from the signature', () => {
    const width = 300; const height = 150;
    const data = makePlainImage(width, height, [248, 248, 245]);
    // An L-shaped signature on the left, and a disconnected fleck far over
    // on the right - well beyond any plausible gap inside a signature.
    paintBlock(data, { x0: 10, y0: 10, x1: 14, y1: 50 }, [15, 15, 20], width);
    paintBlock(data, { x0: 10, y0: 46, x1: 60, y1: 50 }, [15, 15, 20], width);
    paintBlock(data, { x0: 270, y0: 120, x1: 275, y1: 125 }, [10, 10, 10], width);

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).not.toBeNull();
    expect(bbox).toEqual({ minX: 10, minY: 10, maxX: 59, maxY: 49 });
    const strayPixel = (122 * width + 272) * 4;
    expect(data[strayPixel + 3]).toBe(0);
    const strokePixel = (48 * width + 30) * 4;
    expect(data[strokePixel + 3]).toBe(255);
  });

  it('keeps separate parts of the signature - an i-dot and a disconnected initial - and bounds the COMPLETE signature, not just its largest stroke', () => {
    const width = 300; const height = 150;
    const data = makePlainImage(width, height, [248, 248, 245]);
    // Main stroke (the largest component).
    paintBlock(data, { x0: 60, y0: 70, x1: 200, y1: 74 }, [20, 30, 120], width);
    paintBlock(data, { x0: 60, y0: 40, x1: 64, y1: 74 }, [20, 30, 120], width);
    // A 3x3 i-dot above it, not touching it.
    paintBlock(data, { x0: 120, y0: 55, x1: 123, y1: 58 }, [20, 30, 120], width);
    // A separate initial to the left, with a clear gap.
    paintBlock(data, { x0: 30, y0: 45, x1: 34, y1: 74 }, [20, 30, 120], width);

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).toEqual({ minX: 30, minY: 40, maxX: 199, maxY: 73 });
    expect(data[(56 * width + 121) * 4 + 3]).toBe(255); // i-dot kept
    expect(data[(60 * width + 32) * 4 + 3]).toBe(255); // initial kept
  });

  it('leaves no paper halo around the ink on textured mid-gray paper - regression test for the reported 1200x900 rgb(163,154,149) upload', () => {
    // Matches the reported photo's diagnostics: 1200x900, average paper
    // rgb(163,154,149), with grain and a gentle lighting gradient. The
    // previous output kept a light gray rim of paper around every stroke,
    // because the paper right beside a stroke differed from a blurred
    // reference that the stroke itself had darkened.
    const width = 1200; const height = 900;
    const data = new Uint8ClampedArray(width * height * 4);
    const random = seededRandom(7);
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const light = (x / width - 0.5) * 16; // lighting falloff across the photo
        const i = (y * width + x) * 4;
        const grain = (random() - 0.5) * 12;
        data[i] = 163 + light + grain; data[i + 1] = 154 + light + grain + (random() - 0.5) * 4; data[i + 2] = 149 + light + grain; data[i + 3] = 255;
      }
    }
    const ink = [28, 36, 110];
    const inkPixels = new Uint8Array(width * height);
    const dab = (cx, cy, r) => {
      for (let y = cy - r; y <= cy + r; y += 1) {
        for (let x = cx - r; x <= cx + r; x += 1) {
          if ((x - cx) ** 2 + (y - cy) ** 2 > r * r) continue;
          const i = (y * width + x) * 4;
          data[i] = ink[0]; data[i + 1] = ink[1]; data[i + 2] = ink[2];
          inkPixels[y * width + x] = 1;
        }
      }
    };
    // Cursive-like main stroke, a separate initial, and an i-dot.
    for (let x = 400; x <= 820; x += 1) dab(x, Math.round(450 + 40 * Math.sin((x - 400) / 25)), 2);
    for (let y = 380; y <= 500; y += 1) dab(Math.round(330 + (y - 380) * 0.3), y, 2);
    dab(600, 360, 4);

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).not.toBeNull();
    // Complete signature: the initial's left edge and the i-dot's top are both inside the box.
    expect(bbox.minX).toBeLessThanOrEqual(328);
    expect(bbox.minY).toBeLessThanOrEqual(356);
    expect(bbox.maxX).toBeGreaterThanOrEqual(822);
    expect(bbox.maxY).toBeGreaterThanOrEqual(492);
    // Tight to the ink - no big rectangle of paper.
    expect(bbox.minX).toBeGreaterThanOrEqual(320);
    expect(bbox.maxX).toBeLessThanOrEqual(830);

    // Every pixel more than 2px away from real ink must be FULLY transparent.
    let haloPixels = 0;
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const p = y * width + x;
        if (!data[p * 4 + 3]) continue;
        let nearInk = false;
        for (let dy = -2; dy <= 2 && !nearInk; dy += 1) {
          for (let dx = -2; dx <= 2 && !nearInk; dx += 1) {
            const nx = x + dx; const ny = y + dy;
            if (nx >= 0 && nx < width && ny >= 0 && ny < height && inkPixels[ny * width + nx]) nearInk = true;
          }
        }
        if (!nearInk) haloPixels += 1;
      }
    }
    expect(haloPixels).toBe(0);

    // Ink keeps its own (blue) color and full opacity.
    const core = (360 * width + 600) * 4;
    expect(data[core + 3]).toBe(255);
    expect([data[core], data[core + 1], data[core + 2]]).toEqual(ink);
  });

  it('makes light paper transparent and keeps dark ink opaque, cropped to its bounding box', () => {
    const data = makeImage([248, 248, 245]); // light paper background
    const inkRect = { x0: 30, y0: 20, x1: 70, y1: 40 };
    paintBlock(data, inkRect, [15, 15, 20]); // dark ink

    const bbox = applySignatureAlphaMask(data, WIDTH, HEIGHT);

    expect(bbox).not.toBeNull();
    expectBboxNear(bbox, inkRect);

    // A pixel inside the ink block is fully opaque; a background pixel far
    // from the ink (and its halo) is fully transparent.
    const inkPixel = (20 * WIDTH + 40) * 4;
    const backgroundPixel = (0 * WIDTH + 0) * 4;
    expect(data[inkPixel + 3]).toBe(255);
    expect(data[backgroundPixel + 3]).toBe(0);
  });

  it('returns null when no ink darker than the background is present', () => {
    const data = makeImage([250, 250, 250]);
    expect(applySignatureAlphaMask(data, WIDTH, HEIGHT)).toBeNull();
  });

  it('separates ink correctly against a non-white background', () => {
    const data = makeImage([90, 120, 200]); // medium-blue background (e.g. a colored form)
    const inkRect = { x0: 10, y0: 10, x1: 30, y1: 25 };
    paintBlock(data, inkRect, [5, 5, 5]); // near-black ink, clearly darker than the blue background

    const bbox = applySignatureAlphaMask(data, WIDTH, HEIGHT);

    expect(bbox).not.toBeNull();
    expect(bbox.minX).toBeLessThanOrEqual(inkRect.x0);
    expect(bbox.minX).toBeGreaterThanOrEqual(inkRect.x0 - HALO_TOLERANCE);
    expect(bbox.minY).toBeLessThanOrEqual(inkRect.y0);
    expect(bbox.minY).toBeGreaterThanOrEqual(inkRect.y0 - HALO_TOLERANCE);
  });

  it('detects blue handwritten ink on beige/light-gray paper - a chromatic difference, not just a brightness one', () => {
    // Regression test for the reported failure: blue ballpoint ink on
    // off-white/beige paper isn't dramatically darker than the paper (the
    // luminance formula heavily under-weights the blue channel), even
    // though it's obviously, chromatically different to a human eye. A
    // brightness-only threshold can miss this; color distance from the
    // photo's own background color does not.
    //
    // The rect is stroke-width thin (6px), not a large filled block - a
    // pen stroke, or a filled shape wider than roughly twice the local-
    // background blur radius, would have its own deep interior's "local
    // background" estimated from itself rather than the surrounding paper
    // (a real, documented limitation of local-mean estimation - see the
    // production file's header), which a thin stroke never triggers.
    const data = makeImage([225, 215, 195]); // light beige/gray paper
    const inkRect = { x0: 25, y0: 25, x1: 75, y1: 31 };
    paintBlock(data, inkRect, [30, 40, 130]); // dark blue ballpoint ink

    const bbox = applySignatureAlphaMask(data, WIDTH, HEIGHT);

    expect(bbox).not.toBeNull();
    expectBboxNear(bbox, inkRect);

    const inkPixel = (28 * WIDTH + 50) * 4;
    const paperPixel = (0 * WIDTH + 0) * 4;
    expect(data[inkPixel + 3]).toBeGreaterThan(200);
    expect(data[paperPixel + 3]).toBe(0);
  });

  it('handles a photo with uneven lighting (a shadow/vignette darkening toward the corners) without the shadow being mistaken for signature ink', () => {
    // Regression test for the real reported failure: a fixed absolute
    // brightness threshold correctly separates ink from paper in a
    // perfectly flat synthetic test image, but a real phone photo of paper
    // is rarely perfectly flat - shadow/vignette darkens the edges relative
    // to the center. A threshold that isn't relative to the photo's own
    // contrast misclassifies that shadow as "ink" too, and the extracted
    // "signature" balloons out to cover most of the frame - which looks,
    // for all practical purposes, like the original photo was used as-is.
    const width = 120; const height = 80;
    const data = new Uint8ClampedArray(width * height * 4);
    const cx = width / 2; const cy = height / 2;
    const maxDist = Math.sqrt(cx * cx + cy * cy);
    const LIGHT = 250; // well-lit paper at the center of the photo
    const SHADOW = 190; // the same paper, darkened toward the corners - still clearly paper, not ink
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const dist = Math.sqrt((x - cx) ** 2 + (y - cy) ** 2) / maxDist;
        const value = Math.round(LIGHT - dist * (LIGHT - SHADOW));
        const i = (y * width + x) * 4;
        data[i] = value; data[i + 1] = value; data[i + 2] = value; data[i + 3] = 255;
      }
    }
    const inkRect = { x0: 50, y0: 35, x1: 70, y1: 45 }; // a compact signature near the (well-lit) center
    for (let y = inkRect.y0; y < inkRect.y1; y += 1) {
      for (let x = inkRect.x0; x < inkRect.x1; x += 1) {
        const i = (y * width + x) * 4;
        data[i] = 20; data[i + 1] = 20; data[i + 2] = 25;
      }
    }

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).not.toBeNull();
    expectBboxNear(bbox, inkRect);

    // The extracted region must stay small - a real signature, not most of
    // the frame - and a shadowed corner pixel (paper, not ink) must stay
    // (close to) fully transparent.
    const bboxArea = (bbox.maxX - bbox.minX + 1) * (bbox.maxY - bbox.minY + 1);
    expect(bboxArea / (width * height)).toBeLessThan(0.5);
    const cornerPixel = (2 * width + 2) * 4;
    expect(data[cornerPixel + 3]).toBeLessThan(40); // below the module's own ALPHA_BBOX_THRESHOLD
  });

  it('detects a thin, noisy, hand-drawn blue signature on beige paper - simulating a real phone photo rather than a clean synthetic block', () => {
    const width = 100; const height = 60;
    const data = new Uint8ClampedArray(width * height * 4);
    const random = seededRandom(42);
    const paper = [225, 215, 195];
    for (let i = 0; i < data.length; i += 4) {
      const noise = (random() - 0.5) * 14; // +/- 7 per-channel, standing in for sensor/JPEG noise
      data[i] = paper[0] + noise; data[i + 1] = paper[1] + noise; data[i + 2] = paper[2] + noise; data[i + 3] = 255;
    }
    const ink = [30, 40, 130];
    const strokeHalfWidth = 1;
    const setInk = (x, y) => {
      for (let dy = -strokeHalfWidth; dy <= strokeHalfWidth; dy += 1) {
        const py = y + dy;
        if (px_outOfBounds(x, py, width, height)) continue;
        const i = (py * width + x) * 4;
        data[i] = ink[0]; data[i + 1] = ink[1]; data[i + 2] = ink[2];
      }
    };
    function px_outOfBounds(x, y, w, h) { return x < 0 || x >= w || y < 0 || y >= h; }
    // A wavy "signature"-like stroke, not a solid block - most of its own
    // bounding box is still paper, exactly like real handwriting.
    for (let x = 20; x < 80; x += 1) setInk(x, Math.round(30 + 8 * Math.sin((x - 20) / 10)));
    for (let x = 15; x < 30; x += 1) setInk(x, Math.round(15 + (x - 15) * 0.8));

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).not.toBeNull();
    const bboxArea = (bbox.maxX - bbox.minX + 1) * (bbox.maxY - bbox.minY + 1);
    expect(bboxArea / (width * height)).toBeLessThan(0.5);
  });

  it('finds a small signature even when it covers only a tiny fraction of a large, otherwise-blank page', () => {
    // Regression guard for a percentile-based approach tried and discarded
    // before Otsu's method: fixing "how dark must the darkest N% of pixels
    // be" assumes roughly how much of the photo the ink covers. A signature
    // photographed as part of a whole page (mostly blank) covers far less
    // of the frame than a close-up crop of just the signature does, and a
    // fixed percentile tuned for one breaks the other.
    const width = 600; const height = 400;
    const data = makePlainImage(width, height, [245, 245, 245]);
    const inkRect = { x0: 300, y0: 200, x1: 320, y1: 212 }; // 240 px out of 240,000 (0.1%)
    for (let y = inkRect.y0; y < inkRect.y1; y += 1) {
      for (let x = inkRect.x0; x < inkRect.x1; x += 1) {
        const i = (y * width + x) * 4;
        data[i] = 20; data[i + 1] = 20; data[i + 2] = 25;
      }
    }

    const bbox = applySignatureAlphaMask(data, width, height);

    expect(bbox).not.toBeNull();
    expectBboxNear(bbox, inkRect);
  });

  it('does not mistake a second, unrelated dark region for background and still bounds ink correctly within the processed area', () => {
    // Simulates what a caller passes after cropping to a manually selected
    // region (extractSignatureFromImage's `region` option): only the
    // signature's own block is present in `data` here, exactly as if a
    // second dark region elsewhere in the original photo had been excluded
    // by the selection before this pure step ever ran.
    const data = makeImage([250, 250, 250]);
    const inkRect = { x0: 40, y0: 15, x1: 60, y1: 30 };
    paintBlock(data, inkRect, [10, 10, 10]);

    const bbox = applySignatureAlphaMask(data, WIDTH, HEIGHT);

    expect(bbox).not.toBeNull();
    expect(bbox.minX).toBeLessThanOrEqual(inkRect.x0);
    expect(bbox.minX).toBeGreaterThanOrEqual(inkRect.x0 - HALO_TOLERANCE);
    expect(bbox.maxX).toBeGreaterThanOrEqual(inkRect.x1 - 1);
    expect(bbox.maxX).toBeLessThanOrEqual(inkRect.x1 - 1 + HALO_TOLERANCE);
  });
});

// A transparent extracted-signature image: a wavy stroke of the given pen
// width, drawn at `pxPerUnit` pixels per unit of "paper" - so the same
// handwriting can be rendered as if photographed from nearer or farther.
function makeExtractedSignature(pxPerUnit, strokeUnits) {
  const width = Math.round(50 * pxPerUnit); const height = Math.round(18 * pxPerUnit);
  const data = new Uint8ClampedArray(width * height * 4);
  const radius = (strokeUnits * pxPerUnit) / 2;
  for (let t = 0; t <= 1; t += 0.0005) {
    const cx = (2 + 46 * t) * pxPerUnit; const cy = (9 + 5 * Math.sin(t * 12)) * pxPerUnit;
    for (let y = Math.floor(cy - radius - 1); y <= cy + radius + 1; y += 1) {
      for (let x = Math.floor(cx - radius - 1); x <= cx + radius + 1; x += 1) {
        if (x < 0 || y < 0 || x >= width || y >= height) continue;
        const coverage = Math.max(0, Math.min(1, radius + 0.5 - Math.hypot(x - cx, y - cy)));
        const i = (y * width + x) * 4;
        data[i] = 30; data[i + 1] = 38; data[i + 2] = 105; data[i + 3] = Math.max(data[i + 3], Math.round(coverage * 255));
      }
    }
  }
  return { data, width, height };
}

describe('naturalSignatureSize', () => {
  it('measures the pen-stroke width of an extracted signature', () => {
    const { data, width, height } = makeExtractedSignature(6, 0.5); // a 3px-wide stroke
    expect(estimateStrokeWidth(data, width, height)).toBeGreaterThanOrEqual(2.5);
    expect(estimateStrokeWidth(data, width, height)).toBeLessThanOrEqual(3.5);
  });

  it('gives the same natural size for the same handwriting whether the photo was taken close up or from farther away', () => {
    const far = makeExtractedSignature(6, 0.5);
    const near = makeExtractedSignature(10, 0.5);
    const farSize = naturalSignatureSize(far.data, far.width, far.height);
    const nearSize = naturalSignatureSize(near.data, near.width, near.height);
    // The raw crops differ by 10/6 in pixel size, but the displayed signature must not.
    expect(near.width / far.width).toBeCloseTo(10 / 6, 1);
    expect(nearSize.width / farSize.width).toBeGreaterThan(0.85);
    expect(nearSize.width / farSize.width).toBeLessThan(1.15);
    // ...and both come out smaller than their raw pixel size - the crop is never shown at photo resolution.
    expect(farSize.width).toBeLessThan(far.width);
    expect(nearSize.width).toBeLessThan(near.width);
  });

  it('never enlarges a signature beyond its own pixel size', () => {
    const { data, width, height } = makeExtractedSignature(3, 0.4); // a hairline stroke in a low-resolution photo
    const size = naturalSignatureSize(data, width, height);
    expect(size.scale).toBe(1);
    expect(size.width).toBe(width);
    expect(size.height).toBe(height);
  });
});
