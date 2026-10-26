const GEOAPIFY_AUTOCOMPLETE_URL = 'https://api.geoapify.com/v1/geocode/autocomplete';
const MINIMUM_SEARCH_LENGTH = 3;
const RESULT_LIMIT = 5;

function streetAddress(properties) {
  if (properties.address_line1) return properties.address_line1;
  return [properties.housenumber, properties.street].filter(Boolean).join(' ');
}

function normalizeSuggestion(feature, index) {
  const properties = feature?.properties || {};
  const normalizedStreet = streetAddress(properties);
  const city = properties.city || properties.town || properties.village || properties.municipality || '';
  const state = properties.state_code || properties.state || '';
  const zipCode = properties.postcode || '';

  return {
    id: properties.place_id || feature?.id || `address-suggestion-${index}`,
    displayAddress: properties.formatted || [normalizedStreet, city, state, zipCode].filter(Boolean).join(', '),
    streetAddress: normalizedStreet,
    city,
    state,
    zipCode,
  };
}

export async function searchAddressSuggestions(searchText, { signal } = {}) {
  const query = String(searchText || '').trim();
  if (query.length < MINIMUM_SEARCH_LENGTH) return [];

  const apiKey = import.meta.env.VITE_GEOAPIFY_API_KEY;
  if (!apiKey?.trim()) return [];

  const params = new URLSearchParams({
    text: query,
    filter: 'countrycode:us',
    limit: String(RESULT_LIMIT),
    apiKey,
  });

  let response;
  try {
    response = await fetch(`${GEOAPIFY_AUTOCOMPLETE_URL}?${params}`, { signal });
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    throw new Error('Address suggestions unavailable', { cause: error });
  }

  if (!response.ok) throw new Error('Address suggestions unavailable');

  try {
    const data = await response.json();
    return Array.isArray(data?.features)
      ? data.features.map(normalizeSuggestion).filter((item) => item.displayAddress)
      : [];
  } catch {
    throw new Error('Address suggestions unavailable');
  }
}

export { MINIMUM_SEARCH_LENGTH, RESULT_LIMIT };
