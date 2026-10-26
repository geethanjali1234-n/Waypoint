/**
 * FormLabel appends a trailing " *" to required-field labels, so plain
 * getByLabelText('Email') fails (it's an exact match against "Email *").
 * byLabel anchors at the start of the label text so it matches with or
 * without the asterisk, while still distinguishing prefixed labels like
 * "Password" vs "Confirm password".
 */
export function byLabel(text) {
  return new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(\\s*\\*)?$`);
}
