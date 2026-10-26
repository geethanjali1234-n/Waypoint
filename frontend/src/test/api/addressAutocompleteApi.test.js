import { afterEach, describe, expect, it, vi } from 'vitest';
import { searchAddressSuggestions } from '../../api/addressAutocompleteApi';

describe('addressAutocompleteApi', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('does not request suggestions for fewer than three characters', async () => {
    const fetchMock = vi.fn();
    vi.stubEnv('VITE_GEOAPIFY_API_KEY', 'test-key');
    vi.stubGlobal('fetch', fetchMock);

    await expect(searchAddressSuggestions('12')).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does not request suggestions when the API key is missing', async () => {
    const fetchMock = vi.fn();
    vi.stubEnv('VITE_GEOAPIFY_API_KEY', '');
    vi.stubGlobal('fetch', fetchMock);

    await expect(searchAddressSuggestions('500 Congress')).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('restricts results to the US and normalizes Geoapify properties', async () => {
    vi.stubEnv('VITE_GEOAPIFY_API_KEY', 'test-key');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ features: [{ properties: {
        place_id: 'place-1', formatted: '500 Congress Ave, Austin, TX 78701, United States',
        address_line1: '500 Congress Ave', city: 'Austin', state_code: 'TX', postcode: '78701',
      } }] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(searchAddressSuggestions('500 Congress')).resolves.toEqual([{
      id: 'place-1',
      displayAddress: '500 Congress Ave, Austin, TX 78701, United States',
      streetAddress: '500 Congress Ave', city: 'Austin', state: 'TX', zipCode: '78701',
    }]);
    const requestedUrl = new URL(fetchMock.mock.calls[0][0]);
    expect(requestedUrl.searchParams.get('filter')).toBe('countrycode:us');
    expect(requestedUrl.searchParams.get('limit')).toBe('5');
  });

  it('handles incomplete results without crashing', async () => {
    vi.stubEnv('VITE_GEOAPIFY_API_KEY', 'test-key');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ features: [{ properties: { place_id: 'partial', formatted: 'Austin, Texas' } }, null] }),
    }));

    const results = await searchAddressSuggestions('Austin');
    expect(results).toEqual([{ id: 'partial', displayAddress: 'Austin, Texas', streetAddress: '', city: '', state: '', zipCode: '' }]);
  });

  it('reports failures with a controlled message', async () => {
    vi.stubEnv('VITE_GEOAPIFY_API_KEY', 'test-key');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('third-party details')));

    await expect(searchAddressSuggestions('500 Congress')).rejects.toThrow('Address suggestions unavailable');
  });
});
