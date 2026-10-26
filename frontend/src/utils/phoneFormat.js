/**
 * '+19726936993' (PhoneInput's stored format) -> '+1 (972) 693-6993'.
 * Anything that isn't a 10-digit US number is returned as-is.
 */
export function formatUsPhone(value) {
  if (!value) return value;
  const match = /^\+?1?(\d{3})(\d{3})(\d{4})$/.exec(String(value).replace(/[^\d+]/g, ''));
  return match ? `+1 (${match[1]}) ${match[2]}-${match[3]}` : value;
}
