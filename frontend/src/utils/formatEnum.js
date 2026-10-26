export default function formatEnum(value) {
  const values = Array.isArray(value) ? value : [value];
  const formattedValues = values
    .filter((item) => typeof item === 'string' && item.trim())
    .map((item) => item.toLowerCase()
      .replaceAll('_', ' ')
      .replace(/\b\w/g, (letter) => letter.toUpperCase()));

  return formattedValues.length > 0 ? formattedValues.join(', ') : 'Not specified';
}
