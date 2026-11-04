/**
 * Messages for the result the backend puts in the redirect back to Organization Settings:
 *   ?google=connected
 *   ?google=error&reason=CODE[&accountDomain=example.com]
 * The codes are the names of the backend's GoogleConnectResult enum. Unknown codes fall back to a generic message.
 */
const GENERIC_FAILURE = 'We could not connect your Google account. Please try again, and contact WayPoint support if it keeps happening.';
const START_AGAIN = 'Choose Connect Google Account to start again.';

export function describeCallbackResult(params, organizationDomain) {
  const outcome = params.get('google');
  if (outcome === 'connected') {
    return { variant: 'success', message: 'Google account connected.' };
  }
  if (outcome !== 'error') return null;

  const reason = params.get('reason') || '';
  const accountDomain = params.get('accountDomain');
  const domain = organizationDomain ? `@${organizationDomain}` : 'your Organization Email';

  switch (reason) {
    case 'ACCESS_DENIED':
      return { variant: 'error', message: `Google authorization was cancelled or denied, so nothing was connected. ${START_AGAIN}` };
    case 'AUTHORIZATION_FAILED':
      return { variant: 'error', message: `Google could not complete the authorization. ${START_AGAIN}` };
    case 'INVALID_STATE':
    case 'STATE_ALREADY_USED':
      return { variant: 'error', message: `This connection attempt is not valid or was already used. ${START_AGAIN}` };
    case 'STATE_EXPIRED':
      return { variant: 'error', message: `This connection attempt expired. ${START_AGAIN}` };
    case 'NOT_AUTHORIZED':
      return { variant: 'error', message: 'Only an active Company Admin can connect a Google account. Sign in as a Company Admin and try again.' };
    case 'FEATURE_DISABLED':
      return { variant: 'error', message: 'Connecting a Google account is not available right now.' };
    case 'ORGANIZATION_EMAIL_MISSING':
      return { variant: 'error', message: 'Add a valid Organization Email to your organization settings, then connect again.' };
    case 'MISSING_SCOPE':
      return { variant: 'error', message: "Google did not give WayPoint permission to send email, so nothing was connected. Connect again and, on Google's permission screen, make sure the option to send email on your behalf is ticked before you continue." };
    case 'NO_REFRESH_TOKEN':
      return { variant: 'error', message: `Google did not grant ongoing access, so nothing was connected. ${START_AGAIN}` };
    case 'TOO_MANY_ATTEMPTS':
      return { variant: 'error', message: 'Too many attempts in a short time. Please wait a minute and try again.' };
    case 'DOMAIN_MISMATCH':
      return {
        variant: 'error',
        message: `The Google account you chose${accountDomain ? ` (@${accountDomain})` : ''} is not on your organization's email domain (${domain}), so nothing was connected. `
          + `WayPoint only sends from a Google account on the same domain as your Organization Email. Choose an account on ${domain}.`,
      };
    case 'ACCOUNT_EMAIL_MISMATCH':
      return {
        variant: 'error',
        message: 'Your Organization Email is a personal Google address, so WayPoint can only send through that exact Google account. The account you chose is a different one, so nothing was connected. Choose the Google account that matches your Organization Email.',
      };
    case 'ACCOUNT_IN_USE':
      return {
        variant: 'error',
        message: 'That Google account is already connected to another organization. A Google account can be connected to only one organization, so choose a different account.',
      };
    default:
      return { variant: 'error', message: GENERIC_FAILURE };
  }
}

/** Consumer mail domains: the connected account must be exactly the Organization Email (mirrors the backend rule). */
export const isConsumerDomain = (domain) => ['gmail.com', 'googlemail.com'].includes(String(domain || '').toLowerCase());

export const CALLBACK_PARAMS = ['google', 'reason', 'accountDomain'];

const TEST_NOT_FALLBACK = 'WayPoint did not use the standard sender for this test.';
const TEST_FAILURES = {
  RECIPIENT_UNAVAILABLE: 'The Organization Email is missing or invalid, so no test email was sent. Check it in Organization Settings.',
  NOT_CONNECTED: 'No Google account is connected.',
  NOT_ELIGIBLE: 'The connected Google account cannot be used right now.',
  REAUTH_REQUIRED: "Google no longer accepts WayPoint's access. Reconnect your Google account.",
  CONNECTION_REAUTH_REQUIRED: "Google no longer accepts WayPoint's access. Reconnect your Google account.",
  DOMAIN_MISMATCH: "The connected Google account is no longer on your Organization Email's domain.",
  ACCOUNT_EMAIL_MISMATCH: 'The connected Google account is no longer the same address as your Organization Email. Reconnect the matching Google account.',
  FEATURE_DISABLED: 'Sending through Google is not available right now.',
  TOKEN_UNREADABLE: 'The stored Google authorization could not be used. Reconnect your Google account.',
  NO_RESPONSE: 'Google did not respond, so it is not known whether the email was sent. Check your inbox and the Gmail Sent folder before trying again.',
};

/**
 * Message for the "Send test email" result ({ sent, code, recipient, sender } from the backend). A failure is always
 * shown as a failure: the test never falls back to the standard sender, because that would hide the problem. The
 * recipient is always the organization's Organization Email.
 */
export function describeTestResult(result) {
  if (result?.sent) {
    const from = result.sender ? ` from ${result.sender}` : '';
    const message = `Test email sent to ${result.recipient} (your Organization Email)${from}. Check the Organization Email inbox.`;
    const same = result.sender && String(result.recipient || '').trim().toLowerCase() === String(result.sender).trim().toLowerCase();
    return { variant: 'success', message, note: same ? 'This is the same address as the connected Google account that sent it.' : null };
  }
  const code = result?.code || '';
  const reason = TEST_FAILURES[code]
    || 'Google could not send the test email. Please try again, and contact WayPoint support if it keeps happening.';
  return { variant: 'error', message: `The test email was not sent. ${reason} ${TEST_NOT_FALLBACK}`, code };
}
