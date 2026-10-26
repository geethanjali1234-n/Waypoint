import { describe, expect, it } from 'vitest';
import compressFileForUpload, { compressFilesForUpload } from '../../utils/compressFileForUpload';

function fileOfSize(bytes, { type = 'image/png', name = 'photo.png' } = {}) {
  return new File([new Blob([new ArrayBuffer(bytes)])], name, { type });
}

describe('compressFileForUpload', () => {
  it('leaves non-image, non-PDF files (Word, CSV, etc.) completely untouched', async () => {
    const docx = fileOfSize(500 * 1024, {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      name: 'offer.docx',
    });

    const result = await compressFileForUpload(docx);

    expect(result).toBe(docx);
  });

  it('leaves an already-small image untouched', async () => {
    const small = fileOfSize(10 * 1024, { type: 'image/jpeg', name: 'small.jpg' });

    const result = await compressFileForUpload(small);

    expect(result).toBe(small);
  });

  it('leaves an already-small PDF untouched', async () => {
    const small = fileOfSize(10 * 1024, { type: 'application/pdf', name: 'small.pdf' });

    const result = await compressFileForUpload(small);

    expect(result).toBe(small);
  });

  it('never throws and falls back to the original image if compression is unavailable or fails', async () => {
    const large = fileOfSize(300 * 1024, { type: 'image/png', name: 'scan.png' });

    const result = await compressFileForUpload(large);

    // jsdom has no real canvas/createImageBitmap support, so this exercises the
    // same safety net a real browser would hit on a decode/encode failure.
    expect(result).toBe(large);
  });

  it('never throws and falls back to the original PDF if page rendering is unavailable or fails', async () => {
    const large = fileOfSize(5 * 1024 * 1024, { type: 'application/pdf', name: 'Resume_5MB_TEST.pdf' });

    const result = await compressFileForUpload(large);

    // jsdom can't actually decode a PDF, so this exercises the same safety net
    // a real browser would hit if a page failed to render.
    expect(result).toBe(large);
  });

  it('returns the original value for null/undefined input instead of throwing', async () => {
    await expect(compressFileForUpload(null)).resolves.toBeNull();
    await expect(compressFileForUpload(undefined)).resolves.toBeUndefined();
  });
});

describe('compressFilesForUpload', () => {
  it('compresses every file in a list independently and preserves order', async () => {
    const docx = fileOfSize(500 * 1024, {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      name: 'a.docx',
    });
    const small = fileOfSize(1024, { type: 'image/jpeg', name: 'b.jpg' });

    const result = await compressFilesForUpload([docx, small]);

    expect(result).toEqual([docx, small]);
  });

  it('returns an empty array for null/undefined input', async () => {
    expect(await compressFilesForUpload(null)).toEqual([]);
    expect(await compressFilesForUpload(undefined)).toEqual([]);
  });
});
