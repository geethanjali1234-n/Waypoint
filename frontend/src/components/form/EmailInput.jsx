import TextInput from './TextInput';

/**
 * EmailInput - TextInput preconfigured for email addresses.
 *
 * @param {Object} props - Same props as {@link TextInput}, `type` is fixed to "email".
 *
 * @example
 * <EmailInput label="Email" name="email" value={email} onChange={handleChange} required />
 */
export default function EmailInput({ placeholder = 'Enter your email', ...props }) {
  return <TextInput {...props} type="email" placeholder={placeholder} />;
}
