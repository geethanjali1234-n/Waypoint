# Google OAuth / Gmail API — pilot checklist

**Status: implemented and unit/integration tested with mocked Google. Not yet verified against real Google.**
`GOOGLE_EMAIL_ENABLED` is **off by default** and must stay off in every environment until the checks below pass in a
pilot environment. Nothing in this document contains a secret; configuration is named by variable only. Never paste a
client secret, token, encryption key or the contents of any secrets file into a ticket, chat or this document.

How it works, in one paragraph: each organization's Company Admin connects its own Google account (OAuth, scopes
`gmail.send`, `openid`, `email`). WayPoint stores only an encrypted refresh token, per organization. Organization emails
(document requests, reminders, timesheets, offer letters, credentials, other notifications) are sent through that
organization's authorized Gmail account. In production platform mode, a missing, revoked, or invalid organization
connection causes a safe email-delivery failure while the in-app notification and business operation remain; it does not
fall back to SMTP or `waypointmyhr@gmail.com`. Platform transactional emails use the separately authorized
`waypointmyhr@gmail.com` sender when enabled.

---

## 0. Preparation status (read this first)

### Already done in the code (no Google Cloud access needed)

| Item | What was done | Where |
|---|---|---|
| Installed-app guard | Inside the Capacitor app (Android/iOS WebView) the **Connect / Reconnect Google Account** button is not offered, nothing can start Google sign-in from a WebView, and the page explains that the account must be connected from a web browser (Chrome, Safari). Test email and Disconnect still work in the app. The guard also refuses the click if the app is detected late. | `utils/nativeApp.js`, `GoogleEmailConnectionCard.jsx` |
| Redirect URI check at startup | With `GOOGLE_EMAIL_ENABLED=true` the backend refuses to start unless `GOOGLE_OAUTH_REDIRECT_URI` is an http(s) address whose path is **exactly** `/api/oauth/google/callback` (case-sensitive, no trailing slash, no prefix), with no credentials, query, fragment or wildcard; `https` is required except for `localhost`/loopback; a raw IP address or single-label host is refused. The message names the rule and the expected shape and never prints the value. | `GoogleOAuthProperties` |
| Return address check at startup | With the feature on, `APP_FRONTEND_URL` must be a valid web address, and **a public redirect URI requires a public https `APP_FRONTEND_URL`** (otherwise the missing-variable default `http://localhost:5173` would send every admin back to a local address). | `GoogleOAuthProperties`, `GoogleConfig` |
| Startup log line | One INFO line with the redirect **host** and callback path (no secrets, no path or query) so a wrong environment is obvious. | `GoogleConfig` |
| Public legal pages | `/privacy` (Privacy Policy) and `/terms` (Terms of Service), reachable without signing in, linked from the public home page footer (`/privacy-policy` and `/terms-of-service` redirect). The Google section states: authorization is used to send organization emails; WayPoint does not read Gmail; only `gmail.send` is requested; refresh tokens are stored encrypted and access tokens are not stored; messages appear in the connected Sent folder; who can connect and how to disconnect; the Limited Use statement. | `pages/legal/*` |
| Sign-in hint bug | A `+` in the Organization Email (for example `hr+team@...`) is now encoded in the sign-in hint (Google reads a raw `+` as a space). | `GoogleOAuthClient` |
| Render documentation | The Google variables, the exact redirect URI shape and the `APP_FRONTEND_URL` rule are documented in `docs/DEPLOY_RENDER.md`. `render.yaml` was deliberately **not** changed (new secret entries there would make Render prompt for values on the next blueprint sync). | `docs/DEPLOY_RENDER.md` |

### Outstanding: legal (must be finished before the Google verification submission)

- [ ] **Counsel reviews both pages.** They are drafts. Every bracketed `[Legal review: ...]` marker on the pages is a gap
  for counsel, not placeholder text to ship. The Privacy Policy's Google section contains commitments only the company can
  make (Limited Use statement; "not used for advertising and not sold"); counsel must confirm each before publication.
- [ ] Fill in `pages/legal/legalConfig.js`: `operatorName`, `contactEmail`, `effectiveDate`. Until all three are set, both
  pages show a visible "Draft for legal review" notice; it disappears by itself once they are filled. **Then remove every
  remaining `LegalTodo` marker** by writing the missing wording (searching the two page files for `LegalTodo` finds them all).
- [ ] Decide and write: retention periods, deletion/request process, named service providers, logging/analytics, governing
  law, warranty/liability terms. None of these have been invented.
- [ ] The same URLs go into the Google consent screen: `https://<frontend>/privacy` and `https://<frontend>/terms`.

### Outstanding: manual Google Cloud tasks (the app cannot do these)

- [ ] Sections 1 to 3 below: project, Gmail API, consent screen (Branding, Audience, Data Access), the three scopes, Web
  application client, authorized redirect URI(s). Do **not** put credentials anywhere except the deployment's secret store.
- [ ] Section 4: production verification (domain ownership in Search Console, scope justification, demo video). The example
  Render addresses (`*.onrender.com`) are subdomains of a domain you do not own; Google requires ownership of the authorized
  domain, so plan to use a **custom domain you own** for the frontend before applying (confirm with Google).
- [ ] Section 5: each pilot organization's Workspace admin allows the WayPoint client if third-party apps are restricted.
- [ ] Section 6: set the variables by hand in Render (they are not in `render.yaml`); generate the encryption key yourself,
  privately.

### Known limitation to accept for the pilot

- The installed app cannot start the Google connection. An admin must connect from a web browser; afterwards the app works
  normally. (Opening the system browser from inside the app would need the Capacitor Browser plugin, which is not installed,
  and a native rebuild.)
- Static-site note: the home page, privacy and terms pages are rendered by JavaScript. Reviewers use real browsers, so this
  should be fine, but if Google reports it cannot see the privacy link on the home page, pre-rendering is the fix.

---

## 1. Google Cloud project (done once, owned by the WayPoint platform)

- [ ] A Google Cloud project owned by the WayPoint company (not an individual's personal project).
- [ ] **Gmail API** enabled on that project.
- [ ] At least two project Owners/Editors (so the project and its verification are not a single person's).
- [ ] Organizations never need their own Google Cloud project; confirm nothing in onboarding asks for one.

## 2. OAuth consent screen and scopes

- [ ] User type **External** (organizations are in different Google domains).
- [ ] App name, support email, developer contact, logo.
- [ ] Authorized domain(s) added, matching the public application domain.
- [ ] Application home page, **privacy policy** and terms URLs are public and on the application's domain.
- [ ] Scopes configured, and **only** these:

  | Scope | Why | Classification |
  |---|---|---|
  | `https://www.googleapis.com/auth/gmail.send` | Send the organization's email | **Sensitive** (listed under "Sensitive scopes", "Send email on your behalf", in Google's Gmail scopes page) |
  | `openid`, `email` | Learn which Google account was connected (enforces one account per organization and the domain rule) | Non-sensitive |

- [ ] Publishing status understood: in **Testing**, Google issues refresh tokens that **expire after 7 days**, so a pilot in
  Testing mode will see connections go to "Reconnection required" every week. Add each pilot Google account as a test user.

## 3. OAuth client and redirect URI

- [ ] Client type **Web application**.
- [ ] **Authorized redirect URI** added for each environment and matching `GOOGLE_OAUTH_REDIRECT_URI` **exactly**
  (scheme, host, port, path, no trailing slash differences). The path is `/api/oauth/google/callback` on the **backend**
  public address, not the frontend. The backend now refuses to start if the path is anything else, so a typo is caught at
  startup rather than at the first connection. Examples: `https://<backend-host>/api/oauth/google/callback`;
  local `http://localhost:8081/api/oauth/google/callback`. Google's rules: https required except localhost, no raw IP
  addresses (loopback excepted), no wildcards, no fragment, exact match. Changes can take minutes to hours to apply.
- [ ] Authorized JavaScript origins: leave empty (the flow is entirely server-side).
- [ ] After the callback the browser is returned to `APP_FRONTEND_URL/myhr/admin/organization`; confirm `APP_FRONTEND_URL`
  is correct for the environment and is **exactly the address admins sign in at** (the sign-in token is stored per browser
  address, so `localhost` and `127.0.0.1`, or `x.com` and `www.x.com`, are different: an admin returning to a different one
  would appear signed out). The backend checks that it is a public https address when the redirect URI is public.
- [ ] The client secret is stored only in the deployment's secret store (never in git, chat or logs).

## 4. Production OAuth verification (required before more than a pilot)

Because `gmail.send` is a sensitive scope, Google requires app verification before the consent screen can be published
for general use. From Google's sensitive-scope verification guide, plan for:

- [ ] Ownership of the authorized domain verified in **Google Search Console** (by a project Owner/Editor).
- [ ] A publicly accessible home page (not behind login).
- [ ] A privacy policy hosted on the same domain as the home page and linked from the consent screen.
- [ ] A written justification for each sensitive scope, including why a narrower scope would not work.
- [ ] A **demo video** (unlisted on YouTube) showing the consent flow, the app name on the consent screen, the OAuth client ID
  in the address bar, and the feature that uses the scope (connecting, then an email sent from the connected account).
- [ ] Requesting the least access needed (done: send only; WayPoint cannot read the mailbox).
- [ ] Allow time: Google states the process **can take up to 10 days**.
- [ ] Until verified, expect the "unverified app" warning for users.

## 5. Google Workspace administrator restrictions (per organization, cannot be configured by WayPoint)

- [ ] For each pilot organization on Google Workspace, ask its Workspace admin whether third-party app access is restricted
  (Admin console, Security, API controls). If it is, the admin must **allow the WayPoint OAuth client** before a user of
  that organization can connect.
- [ ] If connecting fails only for one organization, check this first.
- [ ] The Google account must be on the **same domain** as the organization's Organization Email (the connection is refused
  otherwise). For a `gmail.com`/`googlemail.com` Organization Email the account must be **exactly** the Organization Email.
  Confirm the pilot accounts satisfy this.
- [ ] Workspace sending limits apply to the connected mailbox; confirm the pilot organization's expected volume is within
  Google's current sending limits (see Google's "Gmail sending limits in Google Workspace").

## 6. WayPoint configuration (names only)

- [ ] `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT_URI`.
- [ ] `GOOGLE_TOKEN_ENCRYPTION_KEY`: Base64 of exactly 32 random bytes, generated for this environment, kept in the secret
  store, **never reused across environments**, and backed up (losing it makes stored connections unreadable; organizations
  would have to reconnect). `GOOGLE_TOKEN_ENCRYPTION_KEY_ID` identifies it for future rotation.
- [ ] `GOOGLE_EMAIL_ENABLED=true` **only** in the pilot environment. Startup fails with a clear message (naming only the
  variable) if any required value is missing or malformed (redirect URI shape, encryption key, `APP_FRONTEND_URL`).
- [ ] After start, the log contains one line `Google email connection is enabled [redirectHost=..., callbackPath=...]`;
  check the host is your backend.
- [ ] `APP_FRONTEND_URL` set to the public https frontend address (see section 3). In Render these variables are added by
  hand; see `docs/DEPLOY_RENDER.md`.
- [ ] Email delivery itself is on (`MAIL_ENABLED`). `GOOGLE_PLATFORM_SENDER_ENABLED=true` and
  `GOOGLE_PLATFORM_SENDER_ADDRESS=waypointmyhr@gmail.com` enable only global platform transactional mail; they are never
  an organization-notification fallback.
- [ ] On first start with the flag on, the connection indexes are created (one connection per organization; one organization
  per Google account; single-use, expiring OAuth state). Confirm the collections `organization_google_connections` and
  `google_oauth_states` exist.

---

## 7. Pilot test script

Use two pilot organizations ("Org A", "Org B") with different domains and different Google accounts, plus one organization
with no Google account ("Org C"). For each step record who ran it, when, and the result.

### 7.0 Before connecting anything
- [ ] Open `https://<frontend>/privacy` and `https://<frontend>/terms` in a private window (not signed in): both load; the
  home page footer links to both; the draft notice is gone and no `[Legal review: ...]` marker remains.
- [ ] Open `https://<backend>/api/oauth/google/callback` in a browser with no parameters: it redirects to the frontend with
  `reason=INVALID_STATE` (this proves the callback is publicly reachable and the frontend address is right).
- [ ] Start the backend with a deliberately wrong `GOOGLE_OAUTH_REDIRECT_URI` (wrong path, or `http://` on a public host) in a
  throwaway environment and confirm it refuses to start with a clear message that does not print the value.

### 7.1 Organization account connection
- [ ] Settings page shows **Email & Sender** only to a Company Admin; HR and employees do not see it.
- [ ] In the installed app (Android/iOS), as a Company Admin: the page explains that the account must be connected from a web
  browser and offers no Connect button; Disconnect and Send test email still work for an existing connection.
- [ ] The Sent-folder notice is visible before connecting.
- [ ] Org A admin: Connect Google Account, choose the Org A account, grant the permission; return to the page showing
  **Connected** and the account address.
- [ ] Org B admin: same with Org B's account.
- [ ] Negative: choose an account on a different domain, expect "not on your organization's email domain", nothing connected.
- [ ] Gmail organizations (Organization Email on `gmail.com`/`googlemail.com`): connecting any other Gmail account is refused
  ("personal Google address ... exact Google account"), nothing connected; connecting the Organization Email itself works.
  A company-domain organization may still connect any mailbox on its domain. An already-stored Gmail connection whose account
  differs from the Organization Email stops being used for sending (mail falls back to SMTP; the test email fails with
  `ACCOUNT_EMAIL_MISMATCH`) until the matching account is reconnected.
- [ ] Negative: cancel on Google's screen, expect the denied message, nothing connected.
- [ ] Negative: Org B tries to connect Org A's Google account, expect "already connected to another organization".
- [ ] Negative: reload the callback URL (replay), expect an invalid/used message, no change.

### 7.2 Test email

**Who receives it.** The test email is sent **from** the organization's connected Google account **to** that same
organization's **Organization Email**, with Reply-To set to the Organization Email (an `Organization.replyToEmail`
override is ignored for this test). It is never sent to the admin's login email (`User.email`) or to the Admin Email
setting, and no recipient can be supplied in the request. The caller must be an authenticated Company Admin and can only
test their own organization. There is no SMTP fallback: if Google cannot send, the test fails with a reason. The Organization
Email inbox must therefore be readable by the pilot team (for a Gmail organization it is the connected account itself, so the
message appears in its Inbox and Sent folder).

Before testing, confirm the addresses in Organization Settings:

| Organization | Organization Email (recipient and Reply-To) | Connected Google account (sender) |
|---|---|---|
| Org A (for example Tardus) | _its Organization Email_ | _its connected Google account_ |
| Org B (for example ITAP) | _its Organization Email_ | _its connected Google account_ |

- [ ] Org A admin: **Send test email**. It arrives in Org A's **Organization Email** inbox, from the Org A Google account, with
  Reply-To = Org A's Organization Email. It also appears in the connected mailbox's Sent folder.
- [ ] It does **not** go to the admin's login email or the Admin Email, even when those differ (Tardus).
- [ ] There is no way to enter a recipient (confirm in the UI and by inspecting the request: no body).
- [ ] Sending more than 5 in 10 minutes is refused with a clear message.
- [ ] With the account disconnected, the test fails clearly and does **not** arrive by SMTP.

### 7.3 Document request and reminder email
- [ ] An Org A employee requests a document from their manager: the manager's "New employee document request" email comes
  **from the Org A Google account** (check headers: From and, if available, the Gmail "mailed-by"/signed-by details).
- [ ] An HR user requests a document from an employee: same, to the employee.
- [ ] Fulfil a request that emails the finished document (small PDF): arrives from the Org account with the attachment.
- [ ] With `DOCUMENT_REQUEST_REMINDERS_ENABLED=true` in the pilot only: a reminder and an overdue alert arrive from the Org
  account, once each.

### 7.4 Timesheet notifications
- [ ] Submit a timesheet: the reporting manager is notified (in-app and email) from the Org account.
- [ ] Approve one and reject one with a reason: the employee gets both the in-app notification and the email, from the Org
  account, with the reason in the rejection.

### 7.5 Missing organization authorization and platform mail
- [ ] Org C (no Google account): trigger a notification. Confirm the in-app notification exists and email fails safely; it
  must not be sent from another organization, `waypointmyhr@gmail.com`, or SMTP.
- [ ] Disconnect Org A, then trigger an organization notification: confirm the in-app notification remains and no SMTP or
  platform-sender fallback occurs.
- [ ] Platform emails (registration OTP, organization registration credentials, password reset) use the separately
  authorized `waypointmyhr@gmail.com` sender when `GOOGLE_PLATFORM_SENDER_ENABLED=true`.

### 7.6 Cross-tenant isolation
- [ ] Trigger emails in Org A and Org B within the same minute: each arrives from its own account; none from the other's.
- [ ] Org B's admin cannot see Org A's connection, disconnect it, or send a test through it.
- [ ] Org A's notification never goes to Org B's users (existing check) and Org A's admins are the only ones notified of
  Org A's connection problems.
- [ ] Inspect the stored connection document for each org: the token field is ciphertext, there is no access token field,
  and no token or secret appears in application logs for the whole test run (search the logs).

### 7.7 Disconnect and reconnect
- [ ] Disconnect asks for confirmation; afterwards the status is **Not connected**, the stored token is gone, WayPoint's
  access no longer appears under the Google account's third-party access, and emails use SMTP.
- [ ] Reconnect the same account, then connect a **different** account for the same organization (the old grant is revoked,
  one connection remains). Reconnect works repeatedly (Google keeps at most 100 refresh tokens per account per client; the
  oldest is invalidated silently beyond that).

### 7.8 Revoked refresh-token handling
- [ ] In the Google account's security settings, remove WayPoint's access (or change the account password: Google
  invalidates refresh tokens holding Gmail scopes when the password changes). Then trigger an email.
- [ ] Expect: that email still arrives (by SMTP); the connection shows **Reconnection required**; the organization's Company
  Admin(s) receive **one** "Reconnect your Google email account" notification (and email, by SMTP); no other organization's
  admin is notified; further emails do not repeat the notification.
- [ ] Reconnect restores Google sending; the status returns to **Connected**.
- [ ] Also observe: refresh tokens expire after 7 days in Testing status, and after six months without use.

---

## 8. Known limits and risks to accept or resolve before wider use

- **Message size limit is unverified.** Google's current official pages do not state a maximum message size for
  `messages.send` or a limit on its JSON `raw` field. The only documented figure is the upload guide's "5 MB or less" for
  the simple upload type. WayPoint caps messages at 3.5 MB of MIME (about 4.7 MB once base64-encoded, under 5 MiB) and
  sends larger ones by SMTP. A 35 MB figure appears in secondary sources only and is **not** relied on. Offer letters or
  I-983 documents above the cap will go by SMTP from the platform sender, not from the organization's account.
- **Sent-folder privacy.** Every message appears in the connected mailbox's Sent folder, including notifications about
  employees. The UI says so before connecting; recommend a dedicated mailbox.
- **Sender is the connected account**, which can differ from the Organization Email (Reply-To keeps replies going to the
  Organization Email). Only accounts on the same domain are accepted; for Gmail/googlemail Organization Emails only that exact
  address.
- **Rate limits are per application instance** (in memory) and key on the forwarded client address. They are abuse
  protection, not an authorization control; the 256-bit single-use state is the real protection of the callback. A caller can
  choose the first forwarded address, so the callback also has one shared ceiling (300 per minute for all clients) that rotating
  addresses cannot dodge; only IP-shaped forwarded values are used as keys.
- **No delivery/bounce tracking.** A message accepted by Gmail can still bounce; WayPoint does not see bounces yet.
- **Ambiguous sends are not retried.** If Google gives no answer, WayPoint does not resend (it might duplicate); the
  failure is logged and, for notifications, the in-app notification still exists.
- **Real Google behavior is untested.** Everything above is covered by mocked tests only; this checklist is how that gap
  is closed.

## 9. Rollback

Set `GOOGLE_EMAIL_ENABLED=false` to disable organization Gmail routing, or `GOOGLE_PLATFORM_SENDER_ENABLED=false` to disable
the dedicated platform sender, then restart. Preserve `GOOGLE_TOKEN_ENCRYPTION_KEY` and `GOOGLE_TOKEN_ENCRYPTION_KEY_ID`;
stored encrypted connections remain available when re-enabled, or can be removed by each organization admin with Disconnect.

## 10. Sign-off

| Area | Verified by | Date | Notes |
|---|---|---|---|
| Google Cloud project, consent screen, scopes (1–3) | | | |
| Verification submitted / approved (4) | | | |
| Workspace restrictions checked per pilot org (5) | | | |
| Configuration and flag (6) | | | |
| Connection, test email (7.1–7.2) | | | |
| Document request, reminder, timesheet (7.3–7.4) | | | |
| SMTP fallback and platform emails (7.5) | | | |
| Cross-tenant isolation (7.6) | | | |
| Disconnect, reconnect, revocation (7.7–7.8) | | | |

## Sources (Google documentation, read while preparing this checklist)

- Sensitive scope verification: https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification
- OAuth 2.0 refresh token expiration: https://developers.google.com/identity/protocols/oauth2
- Gmail API scopes (`gmail.send` is "Sensitive"): https://developers.google.com/workspace/gmail/api/auth/scopes
- Gmail API uploads ("5 MB or less" for simple upload): https://developers.google.com/workspace/gmail/api/guides/uploads
- Gmail API usage limits: https://developers.google.com/workspace/gmail/api/reference/quota
