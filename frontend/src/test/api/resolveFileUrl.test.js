import { describe, it, expect } from 'vitest';
import resolveFileUrl from '../../api/resolveFileUrl';

describe('resolveFileUrl', () => {
  it('returns undefined for a falsy relative url', () => {
    expect(resolveFileUrl(undefined)).toBeUndefined();
    expect(resolveFileUrl('')).toBeUndefined();
    expect(resolveFileUrl(null)).toBeUndefined();
  });

  it('prefixes the relative url with the API origin (strips the /api suffix)', () => {
    const apiOrigin = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/api\/?$/, '');

    expect(resolveFileUrl('/uploads/resumes/abc.pdf')).toBe(
      `${apiOrigin}/uploads/resumes/abc.pdf`
    );
  });
});
