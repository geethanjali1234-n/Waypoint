import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import GoogleEmailConnectionCard from '../../../pages/myhr/features/admin/GoogleEmailConnectionCard';
import OrganizationSettingsPage from '../../../pages/myhr/features/admin/OrganizationSettingsPage';
import { describeCallbackResult, describeTestResult, isConsumerDomain } from '../../../pages/myhr/features/admin/googleConnectionMessages';
import { isNativeApp } from '../../../utils/nativeApp';
import myHrGoogleConnectionApi from '../../../api/myHrGoogleConnectionApi';
import myHrOrganizationSettingsApi from '../../../api/myHrOrganizationSettingsApi';
import myHrNotificationApi from '../../../api/myHrNotificationApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';

vi.mock('../../../api/myHrGoogleConnectionApi');
vi.mock('../../../api/myHrOrganizationSettingsApi');
vi.mock('../../../api/myHrNotificationApi');
vi.mock('../../../api/myHrEmployeeApi');

const status = (overrides = {}) => ({ data: { data: {
  featureEnabled: true, status: 'NOT_CONNECTED', googleAccountEmail: null, organizationEmailDomain: 'itap.example',
  connectedAt: null, lastSendAt: null, lastErrorCode: null, ...overrides,
} } });
const connectedStatus = () => status({ status: 'CONNECTED', googleAccountEmail: 'admin@itap.example', connectedAt: '2026-10-30T10:00:00' });

function setSession(role) {
  localStorage.clear();
  if (role) localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'u-1', role }));
}

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location">{`${location.pathname}${location.search}`}</div>;
}

function renderCard(path = '/myhr/admin/organization', props = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <GoogleEmailConnectionCard organizationEmail="hr@itap.example" {...props} />
      <LocationDisplay />
    </MemoryRouter>,
  );
}

describe('GoogleEmailConnectionCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setSession('COMPANY_ADMIN');
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status());
  });
  afterEach(() => localStorage.clear());

  // ---------------------------------------------------------------- not connected

  it('shows the not-connected state with the Organization Email, the Sent-folder notice and a Connect button', async () => {
    renderCard();

    expect(await screen.findByRole('heading', { name: 'Email & Sender' })).toBeInTheDocument();
    expect(screen.getByText('hr@itap.example')).toBeInTheDocument();
    expect(screen.getByText('Not connected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect Google Account' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Disconnect' })).not.toBeInTheDocument();
    const notice = screen.getByRole('note');
    expect(within(notice).getByText(/will appear in that account's Gmail Sent folder/)).toBeInTheDocument();
    expect(within(notice).getByText(/only to send notification emails/)).toBeInTheDocument();
    expect(within(notice).getByText(/Keep the one that lets WayPoint send email on your behalf ticked/)).toBeInTheDocument();
    expect(within(notice).getByText(/same domain as your Organization Email \(@itap\.example\)/)).toBeInTheDocument();
  });

  it('tells a Gmail organization to connect exactly its Organization Email, not just any account on the domain', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status({ status: 'NOT_CONNECTED', organizationEmailDomain: 'gmail.com' }));
    renderCard('/myhr/admin/organization', { organizationEmail: 'tardus.org.example@gmail.com' });

    const notice = await screen.findByRole('note');
    expect(within(notice).getByText(/must be exactly tardus\.org\.example@gmail\.com/)).toBeInTheDocument();
    expect(within(notice).queryByText(/same domain as your Organization Email/)).not.toBeInTheDocument();
  });

  it('shows a loading state until the status arrives', async () => {
    let resolve;
    myHrGoogleConnectionApi.getStatus.mockReturnValue(new Promise((done) => { resolve = done; }));
    renderCard();

    expect(screen.getByRole('status')).toHaveTextContent('Loading email settings');
    expect(screen.queryByRole('button', { name: 'Connect Google Account' })).not.toBeInTheDocument();
    resolve(status());
    expect(await screen.findByRole('button', { name: 'Connect Google Account' })).toBeInTheDocument();
  });

  it('shows an error with a retry when the status cannot be loaded', async () => {
    myHrGoogleConnectionApi.getStatus
      .mockRejectedValueOnce({ response: { status: 500, data: { message: 'Server unavailable' } } })
      .mockResolvedValueOnce(status());
    renderCard();

    expect(await screen.findByText('Server unavailable')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('button', { name: 'Connect Google Account' })).toBeInTheDocument();
    expect(myHrGoogleConnectionApi.getStatus).toHaveBeenCalledTimes(2);
  });

  it('is hidden while the platform has the feature switched off', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status({ featureEnabled: false }));
    renderCard();

    await waitFor(() => expect(myHrGoogleConnectionApi.getStatus).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Email & Sender' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Google Account/ })).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------- permissions

  it.each([['HR'], ['EMPLOYEE'], ['WAYPOINT_ADMIN'], [null]])('is not shown, and loads nothing, for %s', async (role) => {
    setSession(role);
    const { container } = renderCard();

    expect(container.querySelector('.wp-card')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Email & Sender' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Google Account|Disconnect/ })).not.toBeInTheDocument();
    expect(myHrGoogleConnectionApi.getStatus).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------- connect

  it('asks the backend for the authorization URL and sends the browser exactly there', async () => {
    const redirect = vi.fn();
    myHrGoogleConnectionApi.startConnect.mockResolvedValue({ data: { data: { authorizationUrl: 'https://accounts.google.test/auth?state=abc' } } });
    renderCard('/myhr/admin/organization', { redirect });

    await userEvent.click(await screen.findByRole('button', { name: 'Connect Google Account' }));

    await waitFor(() => expect(redirect).toHaveBeenCalledWith('https://accounts.google.test/auth?state=abc'));
    expect(myHrGoogleConnectionApi.startConnect).toHaveBeenCalledTimes(1);
    expect(myHrGoogleConnectionApi.startConnect).toHaveBeenCalledWith(); // no organization id or anything else is sent
  });

  it('shows an error and allows another try when the connection cannot be started', async () => {
    const redirect = vi.fn();
    myHrGoogleConnectionApi.startConnect.mockRejectedValue({ response: { status: 400, data: { message: 'Google email connection is not enabled' } } });
    renderCard('/myhr/admin/organization', { redirect });

    await userEvent.click(await screen.findByRole('button', { name: 'Connect Google Account' }));

    expect(await screen.findByText('Google email connection is not enabled')).toBeInTheDocument();
    expect(redirect).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Connect Google Account' })).toBeEnabled();
  });

  it('treats a response without an authorization URL as a failure', async () => {
    const redirect = vi.fn();
    myHrGoogleConnectionApi.startConnect.mockResolvedValue({ data: { data: {} } });
    renderCard('/myhr/admin/organization', { redirect });

    await userEvent.click(await screen.findByRole('button', { name: 'Connect Google Account' }));

    expect(await screen.findByText('Unable to start the Google connection. Please try again.')).toBeInTheDocument();
    expect(redirect).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------- callback results

  it('shows success after the callback, refreshes the status from the backend and cleans the address', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    renderCard('/myhr/admin/organization?google=connected');

    expect(await screen.findByText('Google account connected.')).toBeInTheDocument();
    expect(await screen.findByText('admin@itap.example')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
    expect(myHrGoogleConnectionApi.getStatus).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/myhr\/admin\/organization$/));
    // The message stays visible after the parameters are gone.
    expect(screen.getByText('Google account connected.')).toBeInTheDocument();
  });

  it('removes only the callback parameters and keeps the page and any other parameters', async () => {
    renderCard('/myhr/admin/organization?tab=email&google=error&reason=ACCESS_DENIED&accountDomain=gmail.com');

    await screen.findByText(/cancelled or denied/);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/myhr/admin/organization?tab=email'));
    expect(screen.getByTestId('location').textContent).not.toMatch(/google|reason|accountDomain/);
  });

  it('shows no result message after a page refresh following the callback', async () => {
    const first = renderCard('/myhr/admin/organization?google=connected');
    await screen.findByText('Google account connected.');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/myhr\/admin\/organization$/));
    first.unmount();

    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    renderCard('/myhr/admin/organization'); // what the browser loads after a refresh of the cleaned address

    expect(await screen.findByText('admin@itap.example')).toBeInTheDocument();
    expect(screen.queryByText('Google account connected.')).not.toBeInTheDocument();
  });

  it('explains that authorization was denied and offers to try again', async () => {
    renderCard('/myhr/admin/organization?google=error&reason=ACCESS_DENIED');

    expect(await screen.findByText(/cancelled or denied, so nothing was connected/)).toBeInTheDocument();
    expect(screen.getByText('Not connected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect Google Account' })).toBeInTheDocument();
  });

  it.each([
    ['INVALID_STATE', /not valid or was already used/],
    ['STATE_ALREADY_USED', /not valid or was already used/],
    ['STATE_EXPIRED', /attempt expired/],
  ])('explains an invalid or expired attempt (%s)', async (reason, pattern) => {
    renderCard(`/myhr/admin/organization?google=error&reason=${reason}`);

    expect(await screen.findByText(pattern)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('explains a domain mismatch with both domains and connects nothing', async () => {
    renderCard('/myhr/admin/organization?google=error&reason=DOMAIN_MISMATCH&accountDomain=gmail.com');

    const message = await screen.findByText(/not on your organization's email domain/);
    expect(message).toHaveTextContent('@gmail.com');
    expect(message).toHaveTextContent('@itap.example');
    expect(message).toHaveTextContent('nothing was connected');
    expect(screen.getByText('Not connected')).toBeInTheDocument();
  });

  it('explains that a Google account can belong to only one organization', async () => {
    renderCard('/myhr/admin/organization?google=error&reason=ACCOUNT_IN_USE');

    expect(await screen.findByText(/already connected to another organization/)).toBeInTheDocument();
    expect(screen.getByText(/only one organization/)).toBeInTheDocument();
  });

  it.each(['CONNECT_FAILED', 'TOKEN_EXCHANGE_FAILED', 'INVALID_IDENTITY', 'SOMETHING_NEW', ''])('shows a general message for %s', async (reason) => {
    renderCard(`/myhr/admin/organization?google=error&reason=${reason}`);

    expect(await screen.findByText(/We could not connect your Google account/)).toBeInTheDocument();
  });

  it('never prints a query value other than the known domain into the page', async () => {
    const { container } = renderCard('/myhr/admin/organization?google=error&reason=%3Cscript%3Ealert(1)%3C/script%3E&code=secret-code&state=secret-state');

    await screen.findByText(/We could not connect your Google account/);
    const cardText = container.querySelector('.wp-card').textContent;
    expect(cardText).not.toContain('<script>');
    expect(cardText).not.toContain('secret-code');
    expect(cardText).not.toContain('secret-state');
  });

  // ---------------------------------------------------------------- connected / reconnect

  it('shows the connected account with a Disconnect button and no Connect button or Sent-folder notice', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    renderCard();

    expect(await screen.findByText('admin@itap.example')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /Connect Google Account/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });

  it('asks for a reconnection when Google no longer accepts the stored access', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status({ status: 'REAUTH_REQUIRED', googleAccountEmail: 'admin@itap.example' }));
    renderCard();

    expect(await screen.findByText('Reconnection required')).toBeInTheDocument();
    expect(screen.getByText(/no longer accepts WayPoint's access/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reconnect Google Account' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument();
  });

  // ---------------------------------------------------------------- disconnect

  it('asks for confirmation before disconnecting and does nothing when cancelled', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }));
    const dialog = screen.getByRole('dialog', { name: 'Disconnect Google account?' });
    expect(within(dialog).getByText(/will stop using admin@itap\.example/)).toBeInTheDocument();
    expect(myHrGoogleConnectionApi.disconnect).not.toHaveBeenCalled();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(myHrGoogleConnectionApi.disconnect).not.toHaveBeenCalled();
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });

  it('disconnects after confirmation, then refreshes the status from the backend', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValueOnce(connectedStatus()).mockResolvedValueOnce(status({ status: 'DISCONNECTED' }));
    myHrGoogleConnectionApi.disconnect.mockResolvedValue(status({ status: 'DISCONNECTED' }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Disconnect' }));

    expect(await screen.findByText('Google account disconnected.')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Connect Google Account' })).toBeInTheDocument();
    expect(screen.getByText('Not connected')).toBeInTheDocument();
    expect(screen.queryByText('admin@itap.example')).not.toBeInTheDocument();
    expect(myHrGoogleConnectionApi.disconnect).toHaveBeenCalledTimes(1);
    expect(myHrGoogleConnectionApi.getStatus).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps the connection shown and reports the problem when disconnecting fails', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    myHrGoogleConnectionApi.disconnect.mockRejectedValue({ response: { status: 500, data: { message: 'Could not disconnect right now' } } });
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Disconnect' }));

    expect(await screen.findByText('Could not disconnect right now')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------- no secrets

  it('never renders anything beyond the status fields the backend returns', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status({
      status: 'CONNECTED', googleAccountEmail: 'admin@itap.example', refreshToken: 'must-not-appear', clientSecret: 'must-not-appear',
    }));
    renderCard();

    await screen.findByText('admin@itap.example');
    expect(document.body.textContent).not.toContain('must-not-appear');
  });
});

describe('Consumer domains', () => {
  it('recognizes gmail.com and googlemail.com only, in any letter case', () => {
    expect(isConsumerDomain('gmail.com')).toBe(true);
    expect(isConsumerDomain('GoogleMail.com')).toBe(true);
    expect(isConsumerDomain('itap.example')).toBe(false);
    expect(isConsumerDomain(undefined)).toBe(false);
  });

  it('explains an account-email mismatch without naming any address', () => {
    const result = describeCallbackResult(new URLSearchParams({ google: 'error', reason: 'ACCOUNT_EMAIL_MISMATCH' }), 'gmail.com');
    expect(result.variant).toBe('error');
    expect(result.message).toMatch(/different one/);
    expect(result.message).toMatch(/Organization Email/);
  });
});

describe('Callback messages', () => {
  const backendReasons = [
    'ACCESS_DENIED', 'AUTHORIZATION_FAILED', 'INVALID_STATE', 'STATE_EXPIRED', 'STATE_ALREADY_USED', 'NOT_AUTHORIZED',
    'FEATURE_DISABLED', 'ORGANIZATION_EMAIL_MISSING', 'MISSING_SCOPE', 'NO_REFRESH_TOKEN', 'TOKEN_EXCHANGE_FAILED',
    'INVALID_IDENTITY', 'DOMAIN_MISMATCH', 'ACCOUNT_EMAIL_MISMATCH', 'ACCOUNT_IN_USE', 'TOO_MANY_ATTEMPTS', 'CONNECT_FAILED',
  ];

  it('has a message for every failure the backend can report', () => {
    for (const reason of backendReasons) {
      const result = describeCallbackResult(new URLSearchParams({ google: 'error', reason }), 'itap.example');
      expect(result?.variant, reason).toBe('error');
      expect(result.message.length, reason).toBeGreaterThan(20);
    }
  });

  it('tells the admin exactly what to do when the send permission was not granted', () => {
    const result = describeCallbackResult(new URLSearchParams({ google: 'error', reason: 'MISSING_SCOPE' }), 'itap.example');

    expect(result.variant).toBe('error');
    expect(result.message).toMatch(/nothing was connected/);
    expect(result.message).toMatch(/ticked/);
  });

  it('never reports a missing permission for a successful connection', () => {
    const result = describeCallbackResult(new URLSearchParams({ google: 'connected' }), 'itap.example');

    expect(result.variant).toBe('success');
    expect(result.message).not.toMatch(/permission/i);
  });

  it('returns nothing when there is no callback result', () => {
    expect(describeCallbackResult(new URLSearchParams(''), 'itap.example')).toBeNull();
    expect(describeCallbackResult(new URLSearchParams({ google: 'something' }), 'itap.example')).toBeNull();
  });
});

describe('OrganizationSettingsPage with the Email & Sender section', () => {
  const settings = { data: { data: {
    organizationId: 'org-1', organizationCode: 'IT', organizationEmail: 'hr@itap.example', organizationName: 'ITAP',
    payrollPeriod: 'BIWEEKLY',
  } } };

  beforeEach(() => {
    vi.clearAllMocks();
    myHrOrganizationSettingsApi.get.mockResolvedValue(settings);
    myHrOrganizationSettingsApi.getAuthorizedSignature.mockResolvedValue({ data: { data: { active: false, signatureOwnerIsViewer: true } } });
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status());
    // The workspace shell polls these; keep it off the network so nothing can touch the stored session.
    myHrNotificationApi.listNotifications.mockResolvedValue({ data: { data: [] } });
    myHrNotificationApi.getUnreadCount.mockResolvedValue({ data: { data: 0 } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: {} } });
  });
  afterEach(() => localStorage.clear());

  it('shows the section to a Company Admin, with the existing settings intact', async () => {
    setSession('COMPANY_ADMIN');
    render(<MemoryRouter><OrganizationSettingsPage /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Email & Sender' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Organization Information' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Authorized Signature' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Settings' })).toBeInTheDocument();
  });

  it('does not show the section, or call the Google API, for any other user', async () => {
    setSession('HR');
    render(<MemoryRouter><OrganizationSettingsPage /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Organization Information' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Email & Sender' })).not.toBeInTheDocument();
    expect(myHrGoogleConnectionApi.getStatus).not.toHaveBeenCalled();
  });

  it('keeps the settings page when the callback parameters are cleaned', async () => {
    setSession('COMPANY_ADMIN');
    render(<MemoryRouter initialEntries={['/myhr/admin/organization?google=connected']}><OrganizationSettingsPage /><LocationDisplay /></MemoryRouter>);

    expect(await screen.findByText('Google account connected.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Organization Information' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/myhr\/admin\/organization$/));
  });
});

describe('GoogleEmailConnectionCard test email', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setSession('COMPANY_ADMIN');
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
  });
  afterEach(() => localStorage.clear());

  it('offers the test only while a Google account is connected', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status());
    const first = renderCard();
    await screen.findByRole('button', { name: 'Connect Google Account' });
    expect(screen.queryByRole('button', { name: 'Send test email' })).not.toBeInTheDocument();
    first.unmount();

    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status({ status: 'REAUTH_REQUIRED', googleAccountEmail: 'admin@itap.example' }));
    const second = renderCard();
    await screen.findByText('Reconnection required');
    expect(screen.queryByRole('button', { name: 'Send test email' })).not.toBeInTheDocument();
    second.unmount();

    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    renderCard();
    expect(await screen.findByRole('button', { name: 'Send test email' })).toBeEnabled();
  });

  it('sends the test without any input and shows who it was sent to and from', async () => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: { sent: true, code: 'SENT', recipient: 'jane@itap.example', sender: 'admin@itap.example' } } });
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText('Test email sent to jane@itap.example (your Organization Email) from admin@itap.example. Check the Organization Email inbox.')).toBeInTheDocument();
    expect(myHrGoogleConnectionApi.sendTest).toHaveBeenCalledTimes(1);
    expect(myHrGoogleConnectionApi.sendTest).toHaveBeenCalledWith(); // no recipient or other input is ever sent
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(myHrGoogleConnectionApi.getStatus).toHaveBeenCalledTimes(1);
  });

  it('shows a clear failure, says no fallback was used, and refreshes the real status', async () => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: { sent: false, code: 'REAUTH_REQUIRED', recipient: 'jane@itap.example', sender: null } } });
    myHrGoogleConnectionApi.getStatus.mockResolvedValueOnce(connectedStatus())
      .mockResolvedValueOnce(status({ status: 'REAUTH_REQUIRED', googleAccountEmail: 'admin@itap.example' }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    const alert = await screen.findByText(/The test email was not sent/);
    expect(alert).toHaveTextContent("Google no longer accepts WayPoint's access");
    expect(alert).toHaveTextContent('did not use the standard sender');
    expect(screen.queryByText(/Test email sent/)).not.toBeInTheDocument();
    expect(await screen.findByText('Reconnection required')).toBeInTheDocument();
    expect(myHrGoogleConnectionApi.getStatus).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['NO_RESPONSE', /not known whether the email was sent/],
    ['RECIPIENT_UNAVAILABLE', /Organization Email is missing or invalid/],
    ['DOMAIN_MISMATCH', /no longer on your Organization Email's domain/],
    ['GMAIL_HTTP_403', /Google could not send the test email/],
  ])('explains the %s result', async (code, pattern) => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: { sent: false, code } } });
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText(pattern)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('The test email was not sent.');
  });

  it('shows the backend message when the request itself is refused, such as by the rate limit', async () => {
    myHrGoogleConnectionApi.sendTest.mockRejectedValue({ response: { status: 400, data: { message: 'Too many test emails. Please wait a few minutes and try again.' } } });
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText('Too many test emails. Please wait a few minutes and try again.')).toBeInTheDocument();
  });

  it('disables the actions while a test is in progress so it cannot be sent twice', async () => {
    let resolve;
    myHrGoogleConnectionApi.sendTest.mockReturnValue(new Promise((done) => { resolve = done; }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(screen.getByRole('button', { name: 'Send test email' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));
    expect(myHrGoogleConnectionApi.sendTest).toHaveBeenCalledTimes(1);
    resolve({ data: { data: { sent: true, code: 'SENT', recipient: 'jane@itap.example', sender: 'admin@itap.example' } } });
    expect(await screen.findByText(/Test email sent to jane@itap.example/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send test email' })).toBeEnabled();
  });

  it('clears an earlier test result when the account is disconnected', async () => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: { sent: true, code: 'SENT', recipient: 'jane@itap.example', sender: 'admin@itap.example' } } });
    myHrGoogleConnectionApi.getStatus.mockResolvedValueOnce(connectedStatus()).mockResolvedValueOnce(status({ status: 'DISCONNECTED' }));
    myHrGoogleConnectionApi.disconnect.mockResolvedValue(status({ status: 'DISCONNECTED' }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));
    await screen.findByText(/Test email sent to/);

    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Disconnect' }));

    expect(await screen.findByText('Google account disconnected.')).toBeInTheDocument();
    expect(screen.queryByText(/Test email sent to/)).not.toBeInTheDocument();
  });

  it('explains when too many callback attempts were made', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status());
    renderCard('/myhr/admin/organization?google=error&reason=TOO_MANY_ATTEMPTS');

    expect(await screen.findByText(/Too many attempts in a short time/)).toBeInTheDocument();
  });
});

describe('Test email messages', () => {
  it('describes success and every failure as the right kind of message', () => {
    expect(describeTestResult({ sent: true, recipient: 'a@x.example', sender: 's@x.example' }))
      .toEqual({ variant: 'success', message: 'Test email sent to a@x.example (your Organization Email) from s@x.example. Check the Organization Email inbox.', note: null });
    for (const code of ['NOT_CONNECTED', 'REAUTH_REQUIRED', 'NO_RESPONSE', 'UNKNOWN_CODE', undefined]) {
      const result = describeTestResult({ sent: false, code });
      expect(result.variant).toBe('error');
      expect(result.message).toContain('The test email was not sent.');
      expect(result.message).toContain('did not use the standard sender');
    }
    expect(describeTestResult(null).variant).toBe('error');
  });

  it('never mentions the Admin Email, even if the backend still sent one', () => {
    const result = describeTestResult({ sent: true, recipient: 'tardus.org.example@gmail.com', sender: 'tardus.org.example@gmail.com', adminEmailSetting: 'tardus.admin.example@gmail.com' });

    expect(result.variant).toBe('success');
    expect(result.message).toContain('Test email sent to tardus.org.example@gmail.com (your Organization Email)');
    expect(result.message).not.toContain('tardus.admin.example');
    expect(result.note).toBe('This is the same address as the connected Google account that sent it.');
    expect(result.note).not.toMatch(/Admin Email/);
  });

  it('adds no note when the recipient and sender differ', () => {
    expect(describeTestResult({ sent: true, recipient: 'hr@itap.example', sender: 'ops@itap.example' }).note).toBeNull();
  });

  it('never adds a note to a failure', () => {
    expect(describeTestResult({ sent: false, code: 'NOT_CONNECTED', adminEmailSetting: 'x@y.example' }).note).toBeUndefined();
  });
});

describe('Test email note on the page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setSession('COMPANY_ADMIN');
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
  });

  it('states before sending that the test goes to the Organization Email', async () => {
    renderCard();
    expect(await screen.findByTestId('google-test-hint')).toHaveTextContent('to your Organization Email');
  });

  it('Tardus: says the test went to the Organization Email and shows no Admin Email comparison', async () => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: {
      sent: true, code: 'SENT', recipient: 'tardus.org.example@gmail.com', sender: 'tardus.org.example@gmail.com',
    } } });
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText(/Test email sent to tardus.org.example@gmail.com \(your Organization Email\)/)).toBeInTheDocument();
    expect(screen.queryByText(/Admin Email/)).not.toBeInTheDocument();
    expect(screen.queryByText(/tardus\.admin\.example/)).not.toBeInTheDocument();
    expect(myHrGoogleConnectionApi.sendTest).toHaveBeenCalledWith();
  });

  it('ITAP: says the test went to the Organization Email', async () => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: {
      sent: true, code: 'SENT', recipient: 'itap.org.example@gmail.com', sender: 'itap.org.example@gmail.com',
    } } });
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText(/Test email sent to itap.org.example@gmail.com \(your Organization Email\)/)).toBeInTheDocument();
    expect(screen.queryByText(/itap\.admin\.example/)).not.toBeInTheDocument();
  });
});

describe('Google card never presents the Admin Email as the test recipient', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setSession('COMPANY_ADMIN');
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
  });

  it('shows only the Organization Email even if the response still carries an Admin Email value', async () => {
    myHrGoogleConnectionApi.sendTest.mockResolvedValue({ data: { data: {
      sent: true, code: 'SENT', recipient: 'tardus.org.example@gmail.com', sender: 'tardus.org.example@gmail.com',
      adminEmailSetting: 'tardus.admin.example@gmail.com',
    } } });
    renderCard('/myhr/admin/organization', { organizationEmail: 'tardus.org.example@gmail.com' });

    await userEvent.click(await screen.findByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText(/Test email sent to tardus.org.example@gmail.com \(your Organization Email\)/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('tardus.admin.example@gmail.com');
    expect(document.body.textContent).not.toMatch(/Admin Email|login email|sign in to WayPoint with/i);
    expect(screen.getByTestId('google-test-hint')).toHaveTextContent('to your Organization Email');
  });
});

describe('GoogleEmailConnectionCard inside the installed app', () => {
  const NATIVE_TEXT = /Google does not allow sign-in inside the WayPoint app/;

  beforeEach(() => {
    vi.clearAllMocks();
    setSession('COMPANY_ADMIN');
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status());
    window.Capacitor = { isNativePlatform: () => true };
  });
  afterEach(() => {
    delete window.Capacitor;
    localStorage.clear();
  });

  it('does not offer Connect Google Account, and explains how to connect from a web browser', async () => {
    renderCard();

    expect(await screen.findByTestId('google-native-notice')).toHaveTextContent(NATIVE_TEXT);
    expect(screen.getByTestId('google-native-notice')).toHaveTextContent('web browser such as Chrome or Safari');
    expect(screen.queryByRole('button', { name: /Connect Google Account|Reconnect Google Account/ })).not.toBeInTheDocument();
    expect(screen.getByText('Not connected')).toBeInTheDocument();
    expect(myHrGoogleConnectionApi.startConnect).not.toHaveBeenCalled();
  });

  it('does not offer Reconnect either when the connection needs reauthentication', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(status({ status: 'REAUTH_REQUIRED', googleAccountEmail: 'admin@itap.example' }));
    renderCard();

    expect(await screen.findByText('Reconnection required')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reconnect Google Account/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('google-native-notice')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument();
  });

  it('still lets the admin test and disconnect an existing connection, since neither involves Google sign-in', async () => {
    myHrGoogleConnectionApi.getStatus.mockResolvedValue(connectedStatus());
    renderCard();

    expect(await screen.findByRole('button', { name: 'Send test email' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeEnabled();
    expect(screen.queryByTestId('google-native-notice')).not.toBeInTheDocument();
  });

  it('refuses to start Google sign-in even if the app is detected only after the page rendered', async () => {
    delete window.Capacitor;
    const redirect = vi.fn();
    renderCard('/myhr/admin/organization', { redirect });
    const button = await screen.findByRole('button', { name: 'Connect Google Account' });
    window.Capacitor = { isNativePlatform: () => true }; // the native bridge becomes available late

    await userEvent.click(button);

    expect((await screen.findAllByText(NATIVE_TEXT)).length).toBeGreaterThan(0);
    expect(screen.getByRole('alert')).toHaveTextContent(NATIVE_TEXT);
    expect(myHrGoogleConnectionApi.startConnect).not.toHaveBeenCalled();
    expect(redirect).not.toHaveBeenCalled();
  });

  it('is unchanged in a normal web browser', async () => {
    delete window.Capacitor;
    renderCard();

    expect(await screen.findByRole('button', { name: 'Connect Google Account' })).toBeEnabled();
    expect(screen.queryByTestId('google-native-notice')).not.toBeInTheDocument();
  });
});

describe('isNativeApp', () => {
  afterEach(() => { delete window.Capacitor; });

  it('is true only when the Capacitor native bridge says so', () => {
    expect(isNativeApp()).toBe(false);
    window.Capacitor = { isNativePlatform: () => false };
    expect(isNativeApp()).toBe(false);
    window.Capacitor = {};
    expect(isNativeApp()).toBe(false);
    window.Capacitor = { isNativePlatform: () => true };
    expect(isNativeApp()).toBe(true);
  });
});
