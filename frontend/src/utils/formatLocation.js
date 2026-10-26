// Job.location is stored as a single free-text string (e.g. "Austin, TX 78701"),
// not separate city/zip fields. This strips a trailing ZIP code for display
// only - the stored value itself is untouched.
const TRAILING_ZIP_PATTERN = /,?\s*\d{5}(-\d{4})?\s*$/;

export default function formatLocationWithoutZip(location) {
  if (!location) return location;
  return location.replace(TRAILING_ZIP_PATTERN, '').trim();
}
