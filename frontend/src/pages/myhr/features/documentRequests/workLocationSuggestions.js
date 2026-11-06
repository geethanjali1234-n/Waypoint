import { STATE_DATA } from '../../../jobPostingOptions';

// Work Location suggestions for the offer letter form, built from the
// existing US city list used by job postings and events (no API, no key).
const MAX_SUGGESTIONS = 8;

const WORK_LOCATIONS = STATE_DATA.flatMap(({ value: stateName, abbreviation, cities }) => (
  cities.map((city) => ({
    label: `${city}, ${abbreviation}`,
    city: city.toLowerCase(),
    stateName: stateName.toLowerCase(),
    abbreviation: abbreviation.toLowerCase(),
  }))
));

function stateMatches(location, statePart) {
  return location.abbreviation.startsWith(statePart) || location.stateName.startsWith(statePart);
}

// Lower rank = better match. Returns null when the location doesn't match.
function matchRank(location, query) {
  const [cityPart, statePart = ''] = query.split(',').map((part) => part.trim());

  // "Dallas, T" - city and state typed together.
  if (query.includes(',')) {
    return location.city.startsWith(cityPart) && (!statePart || stateMatches(location, statePart)) ? 0 : null;
  }
  if (location.city.startsWith(query)) return 0;
  if (location.city.split(/[\s.]+/).some((word) => word.startsWith(query))) return 1;
  if (location.abbreviation === query || location.stateName.startsWith(query)) return 2;
  return null;
}

/** Returns up to 8 "City, ST" suggestions matching what HR has typed. */
export function getWorkLocationSuggestions(text) {
  const query = String(text || '').trim().toLowerCase();
  if (!query) return [];
  // Hide the list once HR's value is already an exact suggestion.
  if (WORK_LOCATIONS.some((location) => location.label.toLowerCase() === query)) return [];

  return WORK_LOCATIONS
    .map((location) => ({ label: location.label, rank: matchRank(location, query) }))
    .filter((match) => match.rank !== null)
    .sort((a, b) => a.rank - b.rank || a.label.localeCompare(b.label))
    .slice(0, MAX_SUGGESTIONS)
    .map((match) => match.label);
}
