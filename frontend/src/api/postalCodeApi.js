import axios from 'axios';

const POSTAL_API_URL = 'https://api.zippopotam.us';

const CITY_ALIASES = {
  'HI|Kailua-Kona': 'Kailua Kona',
  'MO|St. Louis': 'Saint Louis',
  'NC|Winston-Salem': 'Winston Salem',
  'UT|St. George': 'Saint George',
};

// These cities use ZIPs whose postal service preferred name differs from the
// municipality, so a city-name lookup does not return them.
const CITY_POSTAL_CODE_FALLBACKS = {
  'KS|Overland Park': ['66204', '66207', '66209', '66210', '66211', '66212', '66213', '66214', '66221', '66223', '66224', '66225', '66251', '66282', '66283'],
  'MN|Bloomington': ['55420', '55425', '55431', '55435', '55437', '55438', '55439'],
  'UT|West Valley City': ['84118', '84119', '84120', '84128'],
};

const postalCodeApi = {
  getPostalCodes: async (stateAbbreviation, city) => {
    const key = `${stateAbbreviation}|${city}`;
    const fallback = CITY_POSTAL_CODE_FALLBACKS[key];
    if (fallback) return fallback;

    const postalCity = CITY_ALIASES[key] || city;
    const response = await axios.get(
      `${POSTAL_API_URL}/US/${encodeURIComponent(stateAbbreviation)}/${encodeURIComponent(postalCity)}`,
    );
    return [...new Set(
      (response.data?.places || [])
        .map((place) => place['post code'])
        .filter(Boolean),
    )].sort();
  },
};

export default postalCodeApi;
