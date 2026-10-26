import { describe, expect, it } from 'vitest';
import { STATE_OPTIONS, normalizeLocationSearch } from './jobPostingOptions';

describe('normalizeLocationSearch', () => {
  it('maps every state abbreviation to its full state name', () => {
    STATE_OPTIONS.forEach(({ abbreviation, value }) => {
      expect(normalizeLocationSearch(abbreviation)).toBe(value.toLowerCase());
      expect(normalizeLocationSearch(value)).toBe(value.toLowerCase());
    });
  });

  it('normalizes state aliases inside a city and state location', () => {
    expect(normalizeLocationSearch('Austin, TX')).toBe('austin texas');
    expect(normalizeLocationSearch('Austin, Texas')).toBe('austin texas');
    expect(normalizeLocationSearch('Washington, DC')).toBe('washington district of columbia');
  });
});
