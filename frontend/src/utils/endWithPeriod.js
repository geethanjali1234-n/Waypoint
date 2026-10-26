/**
 * Appends a sentence-ending period unless the text already ends with one, so
 * names like 'Tardus Inc.' don't render as 'Tardus Inc..'.
 */
export default function endWithPeriod(text) {
  const value = String(text ?? '').trimEnd();
  return value.endsWith('.') ? value : `${value}.`;
}
