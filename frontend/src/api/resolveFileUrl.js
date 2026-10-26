import { getApiBaseUrl } from './apiClient';

// Uploaded files (profile pictures, resumes) are served from the backend's
// root ("/uploads/**"), not under the "/api" prefix apiClient uses - so the
// API base URL's origin has to be reconstructed to build a usable <img src>.
export default function resolveFileUrl(relativeUrl) {
  if (!relativeUrl) return undefined;
  if (/^https?:\/\//i.test(relativeUrl)) return relativeUrl;

  const baseUrl = getApiBaseUrl();
  const origin = baseUrl.replace(/\/api\/?$/, '');
  return `${origin}${relativeUrl}`;
}
