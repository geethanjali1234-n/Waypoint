// Mirrors the server's checks so HR sees problems before uploading; the server re-checks everything.
export const DEFAULT_POLICY_EXTENSIONS = ['pdf', 'docx', 'xlsx', 'txt', 'md'];
const DEFAULT_MAX_BYTES = 5 * 1024 * 1024;

const extensionOf = (name = '') => {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
};

export const policyFileAccept = (settings) =>
  (settings?.acceptedFileExtensions || DEFAULT_POLICY_EXTENSIONS).map((extension) => `.${extension}`).join(',');

export function validatePolicyFile(file, settings) {
  if (!file) return 'Choose a policy file.';
  if (!(settings?.acceptedFileExtensions || DEFAULT_POLICY_EXTENSIONS).includes(extensionOf(file.name))) {
    return 'Policy files must be PDF, DOCX, XLSX, TXT or Markdown.';
  }
  const maxBytes = settings?.maxFileSizeBytes || DEFAULT_MAX_BYTES;
  if (file.size === 0) return 'The selected file is empty.';
  if (file.size > maxBytes) return `Policy files cannot exceed ${Math.round(maxBytes / (1024 * 1024))} MB.`;
  return '';
}
