# Route-line Phase 3 review

The four active workspaces now share `WorkspaceFrame`: a sticky logo/header,
Quick Jump (Ctrl K or Command K), theme toggle, caller-owned account menus,
desktop sidebar, content area and mobile navigation. Existing data requests,
notification destinations, outlet contexts, logout handlers and role guards
are retained. No API module, authentication/session module, route definition,
backend file or credentials file was edited. No Git state was changed.

## Browser review

Open these routes at http://localhost:5173 with the corresponding existing role:

- `/myhr/employee/dashboard`
- `/myhr/hr/dashboard`
- `/myhr/admin/dashboard`
- `/myhr/admin/employees`
- `/myhr/hr/timesheets`
- `/employer/dashboard`
- `/employer/jobs`
- `/job-seeker/dashboard`
- `/careers`
- `/waypoint-admin/dashboard`
- `/waypoint-admin/organizations`

Review the header name and login ID, accessible unread notification count,
theme toggle, sidebar collapse, active links and Ctrl K navigation. At 390px,
the sidebar is replaced by bottom navigation and Quick Jump remains available
in the header. My HR uses Home / Time / Docs / Me. Other workspaces show only
their existing destinations. Safe-area spacing is included; the broader mobile
and dark-theme QA remains Phase 8.

The duplicate My HR dashboard Quick Actions section was removed. Reports,
document requests and organization settings remain in the sidebar and existing
Quick Jump registry. The dashboard's data requests and operational content were
retained for Phase 4.

## Files changed in Phase 3

New shared frame/navigation files:

- `src/components/layout/WorkspaceFrame.jsx`
- `src/components/layout/WorkspaceFrame.css`
- `src/components/navigation/WorkspaceSidebar.jsx`
- `src/components/navigation/WorkspaceSidebar.css`
- `src/components/navigation/QuickJump.jsx`
- `src/components/navigation/workspaceNavigation.js`

Shared exports, icons and account controls:

- `src/components/layout/index.js`
- `src/components/navigation/index.js`
- `src/components/global/StrokeIcon.jsx`
- `src/components/global/ThemeToggle.jsx`
- `src/components/global/ThemeToggle.css`
- `src/components/navigation/CommandPalette.jsx`
- `src/components/navigation/CommandPalette.css`
- `src/components/navigation/MobileBottomNav.jsx`
- `src/components/navigation/MobileBottomNav.css`
- `src/components/navigation/NotificationMenu.jsx`
- `src/components/navigation/NotificationMenu.css`
- `src/components/navigation/ProfileMenu.jsx`
- `src/components/navigation/ProfileMenu.css`

Workspaces and identity presentation:

- `src/pages/myhr/MyHrShell.jsx`
- `src/pages/myhr/MyHrShell.css`
- `src/pages/myhr/components/MyHrNavigation.jsx`
- `src/pages/myhr/components/myHrNavigation.css`
- `src/pages/myhr/components/WorkspaceDashboard.jsx`
- `src/pages/myhr/MyHrRoleLandingPage.jsx`
- `src/pages/EmployerLayout.jsx`
- `src/pages/EmployerLayout.css`
- `src/pages/JobSeekerLayout.jsx`
- `src/pages/JobSeekerLayout.css`
- `src/pages/waypointAdmin/WaypointAdminShell.jsx`
- `src/pages/waypointAdmin/WaypointAdminShell.css`
- `src/hooks/useJobSeekerIdentity.js`

Tests and this record:

- `src/test/components/WorkspaceFrame.test.jsx` (new)
- `src/test/pages/EmployerLayout.test.jsx` (new)
- `src/test/components/CommandPalette.test.jsx`
- `src/test/components/MobileBottomNav.test.jsx`
- `src/test/components/NotificationMenu.test.jsx`
- `src/test/pages/myhr/MyHrShell.test.jsx`
- `src/test/pages/myhr/MyHrRoleLandingPage.test.jsx`
- `docs/route-line-phase3.md`

Verification logs: `phase3-focused.log`, `phase3-lint.log`, `phase3-test.log`,
`phase3-build.log`. The production build also regenerated `dist/` assets.

## Test intent

The account-menu tests now expect the admin's person name/login ID rather than
the organization name. They still verify every account action and destination.
The mobile tests cover the requested four tabs, active navigation, role
resolution and workspace-specific destinations. The old dashboard shortcut
test now verifies Reports is reached through the sidebar with no duplicate
Quick Actions region. Existing authorization and operational assertions were
retained.

New coverage verifies full unread counts, unknown-count omission, scoped Quick
Jump without unrelated employee API searches, keyboard focus trapping and
restoration, theme switching, sidebar preferences, employer outlet data and
the existing 401/403 behavior. The employer's existing refresh on navigation
was preserved rather than changing its request lifecycle.

## Validation and limits

- Focused frame/navigation/identity tests: **70 passed across 12 files**.
- Production build: **passed**. No dependency was added. The built CSS and main
  JavaScript remain within the previous phase's bundle sizes.
- Full test suite: **877 passed / 31 failed**, across **103 files**. The same
  31 failures appeared before Phase 3, in the same seven files: application and
  event date expectations, dashboard/router expectations, document preparation,
  requests/approvals, and document-category completeness.
- Full lint: **two existing errors** in `src/api/apiClient.js:11` and
  `src/pages/myhr/features/documentRequests/SignatureField.jsx:125`, plus the
  existing hook-dependency warning in `OfferLetterPreparePage.jsx:656`.
  No Phase 3 file has a lint error. These inherited failures were not suppressed
  or repaired by changing protected API or signature code.

Platform notification UI is omitted because the existing platform shell has no
notification endpoint/data source. Employer dashboard data supplies a company
name and email, so those remain the fallback identity; no person name or login
ID was invented. Job-seeker login ID/email is shown only if its existing profile
response supplies it. My HR uses the existing profile name and session login ID.

Employer/job-seeker/platform Quick Jump uses their existing destinations and
does not call My HR employee search. The My HR registry and its permission
resolution remain unchanged. Roles without governance routes receive none.
The legacy `MyHrEmployeeLayout` was not migrated: active `/employee/*` routes
already redirect into the My HR shell. No route definitions were changed.

Phase 4 has not started.
