# Deploying Waypoint on Render for Free Team Testing

Waypoint runs as two Render services defined in [`render.yaml`](../render.yaml):

| Service | What it is | URL |
|---|---|---|
| `waypoint-backend` | Spring Boot API built from `backend/Dockerfile` | Render assigns this after the first deploy. |
| `waypoint-frontend` | React static site built from `frontend/` | Render assigns this after the first deploy. |

MongoDB Atlas remains the database and Cloudflare R2 remains the document store. Neither is created, migrated, or changed by this deployment.

## Before creating a release branch

The current working tree contains unrelated mobile, APK, MyHR profile, and documentation changes. Do not create the release branch in this dirty checkout.

1. Keep this checkout unchanged and create a separate clean Git worktree at the current committed `HEAD`.
2. In the clean worktree, verify that `release/production` does not already exist locally or on `origin`.
3. Create `release/production` there, apply only `render.yaml` and this runbook, and stage only those two files.
4. Inspect `git diff --cached` for URLs, credentials, APKs, Android files, MyHR changes, or untracked docs. If any appear, unstage them before committing.
5. Commit the deployment-only change. Do not push until it has been reviewed.

## Render dashboard setup

1. Rotate any credentials previously exposed in repository history. Generate a new JWT secret, MongoDB user/password, R2 API token, and mail-provider app password. Never add them to Git.
2. In MongoDB Atlas, allow Render network access as required by your Atlas policy. Do not alter database records for this team-test deployment.
3. In Render, sign in with the GitHub account that can access this repository, then choose **New → Blueprint**.
4. Select the repository and the reviewed `release/production` branch. Render reads `render.yaml` and creates the free backend web service and frontend static site.
5. Enter the backend secrets in Render Environment:

   | Variable | Required for this Blueprint |
   |---|---|
   | `JWT_SECRET` | Yes |
   | `MONGODB_URI`, `MONGODB_DATABASE` | Yes |
   | `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`, `R2_ENDPOINT` | Yes; R2 is enabled |
   | `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM_ADDRESS` | Yes; mail is enabled |
   | `OPENWEATHER_API_KEY`, `CLAUDE_API_KEY` | Optional |

   Keep `MAIL_FROM_NAME` optional. Do not put any secret in `render.yaml`, frontend source, or GitHub.

6. Create the Blueprint and let both services deploy once. From each service overview page, copy the actual Render-assigned `onrender.com` URL.
7. In the **backend** service's Environment tab, set:
   - `APP_FRONTEND_URL` to the actual frontend URL.
   - `CORS_ALLOWED_ORIGINS` to the same actual frontend URL.
8. In the **frontend** static site's Environment tab, set `VITE_API_BASE_URL` to the actual backend URL followed by `/api`. Set `VITE_GEOAPIFY_API_KEY` only if address autocomplete is needed.
9. Save the backend environment and deploy it. Save the frontend environment and manually redeploy it, because Vite embeds `VITE_API_BASE_URL` at build time.
10. Verify `<backend-onrender-url>/health` returns HTTP 200. Then open the frontend URL and test sign-in, a protected API request, direct navigation to a nested route, email delivery, and R2 document upload/download.

## Mobile APK after web testing

The web launch does not require a new APK. If an APK is later needed, build it with the actual backend API URL and actual frontend URL only after the web deployment has passed verification. Keep Android live-reload/LAN configuration out of the release commit.

## Notes

- The free backend can sleep after inactivity; the first request after sleep can be slow.
- `PAYROLL_READINESS_AUTOMATIC_REMINDERS_ENABLED` remains `false` for this shared-database team test.
- `VITE_UPDATE_BASE_URL` and `VITE_MOBILE_API_BASE_URL` are not required for the browser-hosted team-test site.

## Organizations sending email from their own Google account (optional, off by default)

Each organization's Company Admin can connect its own authorized Gmail or Google Workspace account so organization notifications are sent through that sender. The feature is **off** unless `GOOGLE_EMAIL_ENABLED=true`. In production platform mode, a missing, revoked, or invalid organization connection causes a safe email-delivery failure while the in-app notification and business operation remain; it does not fall back to SMTP or `waypointmyhr@gmail.com`. These variables are **not** in `render.yaml`; add them by hand under **waypoint-backend -> Environment** only when ready. Full setup and test steps: `docs/GOOGLE_OAUTH_PILOT_CHECKLIST.md`.

| Variable | What it is |
|---|---|
| `GOOGLE_EMAIL_ENABLED` | `true` to switch the feature on. Leave unset or `false` otherwise. |
| `GOOGLE_PLATFORM_SENDER_ENABLED` | `true` to enable global platform transactional mail through Gmail API. |
| `GOOGLE_PLATFORM_SENDER_ADDRESS` | Must be exactly `waypointmyhr@gmail.com` when enabled. |
| `GOOGLE_OAUTH_CLIENT_ID` | OAuth client ID (secret store) |
| `GOOGLE_OAUTH_CLIENT_SECRET` | OAuth client secret (secret store only; never commit or paste it anywhere) |
| `GOOGLE_OAUTH_REDIRECT_URI` | `<backend URL>/api/oauth/google/callback`, for example `https://waypoint-backend.onrender.com/api/oauth/google/callback`. It must be **exactly** the redirect URI listed on the Google OAuth client. The application refuses to start if the path is not exactly `/api/oauth/google/callback`, or if the address is `http://` on a public host. |
| `GOOGLE_TOKEN_ENCRYPTION_KEY` | Base64 of 32 random bytes, generated for this environment, backed up in the secret store. Losing it makes stored connections unreadable (organizations would reconnect). |
| `GOOGLE_TOKEN_ENCRYPTION_KEY_ID` | Optional label for the key (default `v1`). |

Also required when the feature is on:

- `APP_FRONTEND_URL` must be the public **https** address of the frontend (the address admins sign in at, exactly: `https://x` and `https://www.x` are different to the browser, and the sign-in token is stored per address). The application refuses to start otherwise, so admins are never sent back to a local address after connecting.
- `MAIL_ENABLED` must be `true` for organization emails to be tried through Google.
- The callback is a plain browser redirect to the **backend**, so the backend must be reachable from the admin's browser at the address used in `GOOGLE_OAUTH_REDIRECT_URI`. On the free plan the first request after sleep takes about 30-60 seconds.
- The MongoDB user must be allowed to create indexes (the connection indexes are created at startup when the feature is on).

Google Cloud: enable Gmail API; create an External OAuth Web application client; configure public home, privacy, and terms URLs; request only `openid`, `email`, and `https://www.googleapis.com/auth/gmail.send`; and add this exact production redirect URI: `https://api.waypoint-hr.com/api/oauth/google/callback`. `gmail.send` is sensitive, so complete Google verification before broad external use.

Organization authorization: a Company Admin opens **Organization Settings -> Email & Sender**, selects **Connect Google Account**, authorizes the organization's sender account, and uses the test-email action. A registered Organization Email alone is not authority to send; non-Google providers need a Gmail/Workspace sender authorized to send as that address. A WayPoint Admin authorizes the separate `waypointmyhr@gmail.com` platform account for global OTPs, password resets, registrations, and invitations only.

Rollback: set `GOOGLE_PLATFORM_SENDER_ENABLED=false` to disable platform transactional Gmail, or `GOOGLE_EMAIL_ENABLED=false` to disable organization Gmail routing, then restart/redeploy. Preserve `GOOGLE_TOKEN_ENCRYPTION_KEY`, `GOOGLE_TOKEN_ENCRYPTION_KEY_ID`, and stored connection records so encrypted authorizations remain recoverable on re-enable.
