import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, Card, ConfirmationDialog, StatusBadge, ValidationMessage } from '../../../../components';
import myHrGoogleConnectionApi from '../../../../api/myHrGoogleConnectionApi';
import { getMyHrSession } from '../../../../auth/myHrSession';
import { isNativeApp } from '../../../../utils/nativeApp';
import { CALLBACK_PARAMS, describeCallbackResult, describeTestResult, isConsumerDomain } from './googleConnectionMessages';

const STATUS_BADGES = {
  CONNECTED: { state: 'active', label: 'Connected' },
  REAUTH_REQUIRED: { state: 'rejected', label: 'Reconnection required' },
};
const NOT_CONNECTED_BADGE = { state: 'inactive', label: 'Not connected' };

const redirectBrowser = (url) => window.location.assign(url);

const NATIVE_APP_MESSAGE = 'Google does not allow sign-in inside the WayPoint app. To connect a Google account, sign in to WayPoint '
  + 'in a web browser such as Chrome or Safari, open Organization Settings, and connect it there.';

/**
 * Organization Settings -> Email & Sender: connect the organization's own Google account so its email can be sent
 * through it. Emails go through the Gmail API when the connection is usable and otherwise through the standard sender;
 * the test email never falls back.
 *
 * - Only a Company Admin sees it (the route is already admin-only; the backend enforces it again).
 * - Hidden entirely while the platform has the feature switched off (the status call says so).
 * - "Connect" asks the backend for the Google authorization URL and sends the browser there. React never builds it.
 * - When Google sends the admin back, the backend redirects here with ?google=connected or ?google=error&reason=CODE.
 *   The result is shown once, the query parameters are removed (replace, so Back does not replay it), and the status is
 *   (re)loaded from the backend, which is the source of truth.
 */
export default function GoogleEmailConnectionCard({ organizationEmail, redirect = redirectBrowser }) {
  const isCompanyAdmin = getMyHrSession()?.role === 'COMPANY_ADMIN';
  const [searchParams, setSearchParams] = useSearchParams();
  // Read once: the parameters are removed from the address right after, but the message must stay on screen.
  const [callbackParams] = useState(() => new URLSearchParams(searchParams));
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(isCompanyAdmin);
  const [loadError, setLoadError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [connecting, setConnecting] = useState(false);
  const [confirmingDisconnect, setConfirmingDisconnect] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const nativeApp = isNativeApp();
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState(null);
  const cleanedUrl = useRef(false);

  // Remove the callback parameters from the address, keeping the page and any other parameters.
  useEffect(() => {
    if (cleanedUrl.current || !CALLBACK_PARAMS.some((name) => searchParams.has(name))) return;
    cleanedUrl.current = true;
    const next = new URLSearchParams(searchParams);
    CALLBACK_PARAMS.forEach((name) => next.delete(name));
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    if (!isCompanyAdmin) return undefined;
    let active = true;
    setLoading(true); setLoadError('');
    myHrGoogleConnectionApi.getStatus()
      .then((response) => { if (active) setStatus(response?.data?.data || null); })
      .catch((failure) => {
        if (active && failure?.response?.status !== 401) {
          setLoadError(failure?.response?.data?.message || 'Unable to load the Google email connection.');
        }
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [isCompanyAdmin, reloadKey]);

  if (!isCompanyAdmin) return null;
  if (!loading && !loadError && status && status.featureEnabled === false) return null;

  const connected = status?.status === 'CONNECTED';
  const reauthRequired = status?.status === 'REAUTH_REQUIRED';
  const hasConnection = connected || reauthRequired;
  const badge = STATUS_BADGES[status?.status] || NOT_CONNECTED_BADGE;
  const organizationDomain = status?.organizationEmailDomain || '';
  const callbackResult = describeCallbackResult(callbackParams, organizationDomain);

  const connect = async () => {
    // Defence in depth: the button is not offered in the app, but nothing may start Google sign-in from a WebView.
    if (isNativeApp()) {
      setActionError(NATIVE_APP_MESSAGE);
      return;
    }
    setConnecting(true); setActionError(''); setNotice('');
    try {
      const response = await myHrGoogleConnectionApi.startConnect();
      const url = response?.data?.data?.authorizationUrl;
      if (!url) throw new Error('missing authorization url');
      redirect(url);
    } catch (failure) {
      if (failure?.response?.status !== 401) {
        setActionError(failure?.response?.data?.message || 'Unable to start the Google connection. Please try again.');
      }
      setConnecting(false);
    }
  };

  const sendTest = async () => {
    setTesting(true); setTestResult(null); setActionError(''); setNotice('');
    try {
      const response = await myHrGoogleConnectionApi.sendTest();
      const result = describeTestResult(response?.data?.data);
      setTestResult(result);
      // A failure may mean the connection changed (for example access was revoked), so show the real status.
      if (result.variant === 'error') setReloadKey((key) => key + 1);
    } catch (failure) {
      if (failure?.response?.status !== 401) {
        setTestResult({ variant: 'error', message: failure?.response?.data?.message || 'The test email could not be requested. Please try again.' });
      }
    } finally { setTesting(false); }
  };

  const disconnect = async () => {
    setDisconnecting(true); setActionError(''); setNotice('');
    try {
      await myHrGoogleConnectionApi.disconnect();
      setConfirmingDisconnect(false);
      setTestResult(null);
      setNotice('Google account disconnected.');
      setReloadKey((key) => key + 1);
    } catch (failure) {
      setConfirmingDisconnect(false);
      if (failure?.response?.status !== 401) {
        setActionError(failure?.response?.data?.message || 'Unable to disconnect the Google account. Please try again.');
      }
    } finally { setDisconnecting(false); }
  };

  return (
    <Card title="Email & Sender">
      {loading ? <p className="myhr-feature__notice" role="status">Loading email settings…</p>
        : loadError ? <div className="organization-settings__google">
          <ValidationMessage message={loadError} variant="error" />
          <div><Button type="button" variant="secondary" onClick={() => setReloadKey((key) => key + 1)}>Try again</Button></div>
        </div>
        : <div className="organization-settings__google">
          {callbackResult && (callbackResult.variant === 'success'
            ? <p className="myhr-feature__notice" role="status">{callbackResult.message}</p>
            : <ValidationMessage message={callbackResult.message} variant="error" />)}
          <p>Send your organization's notification emails through your own Google account.</p>
          <dl className="organization-settings__details">
            <div><dt>Organization Email</dt><dd>{organizationEmail || 'Not provided'}</dd></div>
            <div><dt>Google connection</dt><dd><StatusBadge status={badge.state} label={badge.label} /></dd></div>
            {hasConnection && <div><dt>Connected Google account</dt><dd>{status.googleAccountEmail || 'Unavailable'}</dd></div>}
          </dl>

          {reauthRequired && <ValidationMessage variant="error"
            message="Google no longer accepts WayPoint's access to this account (for example, access was removed in your Google account settings). Reconnect to continue." />}

          {!connected && <div className="organization-settings__google-notice" role="note">
            <strong>Before you connect</strong>
            <ul>
              <li>Emails WayPoint sends through the connected Google account will appear in that account's Gmail Sent folder.</li>
              <li>WayPoint uses the account only to send notification emails, such as document requests and timesheet updates. It asks Google only for permission to send email, not to read your mailbox.</li>
              <li>Google's permission screen lists each permission with its own tick box. Keep the one that lets WayPoint send email on your behalf ticked, or Google will not grant it and nothing will be connected.</li>
              <li>Use a dedicated mailbox if you do not want these messages mixed with personal mail.</li>
              {isConsumerDomain(organizationDomain)
                ? <li>Your Organization Email is a personal Google address, so the Google account you connect must be exactly {organizationEmail || 'your Organization Email'}.</li>
                : <li>The Google account must be on the same domain as your Organization Email{organizationDomain ? ` (@${organizationDomain})` : ''}.</li>}
            </ul>
          </div>}

          {!connected && nativeApp && <p className="organization-settings__google-native" data-testid="google-native-notice">{NATIVE_APP_MESSAGE}</p>}
          {actionError && <ValidationMessage message={actionError} variant="error" />}
          {notice && <p className="myhr-feature__notice" role="status">{notice}</p>}
          {testResult && (testResult.variant === 'success'
            ? <><p className="myhr-feature__notice" role="status">{testResult.message}</p>{testResult.note && <p className="myhr-feature__notice" data-testid="google-test-note">{testResult.note}</p>}</>
            : <ValidationMessage message={testResult.message} variant="error" />)}

          {connected && <p className="organization-settings__google-hint" data-testid="google-test-hint">Send test email sends one message from the connected Google account to your Organization Email.</p>}
          <div className="organization-settings__google-actions">
            {!connected && !nativeApp && <Button type="button" variant="primary" loading={connecting} onClick={connect}>
              {reauthRequired ? 'Reconnect Google Account' : 'Connect Google Account'}
            </Button>}
            {connected && <Button type="button" variant="secondary" loading={testing} disabled={disconnecting} onClick={sendTest}>
              Send test email
            </Button>}
            {hasConnection && <Button type="button" variant="secondary" disabled={disconnecting || testing} onClick={() => setConfirmingDisconnect(true)}>
              Disconnect
            </Button>}
          </div>
        </div>}
      <ConfirmationDialog
        isOpen={confirmingDisconnect}
        title="Disconnect Google account?"
        message={`WayPoint will stop using ${status?.googleAccountEmail || 'this Google account'} and its access will be revoked. Emails will continue to be sent from the standard WayPoint sender. You can reconnect at any time.`}
        confirmLabel={disconnecting ? 'Disconnecting…' : 'Disconnect'}
        onConfirm={disconnect}
        onCancel={() => { if (!disconnecting) setConfirmingDisconnect(false); }}
      />
    </Card>
  );
}
