# Phase 8 review

Phase 8 completes the mobile and dark-theme QA pass. No later implementation phase is pending.

## Changes

- The My HR assistant has a reserved slot beside the four mobile navigation items. Its 52px launcher sits inside the opaque navigation dock, rather than over cards. On desktop, the frame reserves a right gutter for the launcher. Main content retains bottom padding and safe-area spacing.
- Workspace header padding respects the left and right safe-area insets independently. The chat window remains above mobile navigation and fits a simulated 390 x 500 viewport.
- Shared fields use token colors for surfaces, ink, borders, focus and placeholders in both themes. The old component-level neon focus shadow and white-only light override are removed.
- Shared mobile fields, dashboard/search controls, operational selects, command search and chat input have a 16px font to avoid iOS focus zoom; document preview typography/coordinates are retained.
- Marketing brand/account/footer links, employer event links, profile activity links and the timesheet breadcrumb have at least 44px hit areas. Payroll's header checkbox has a real 44px label target; selection behavior is unchanged.
- Danger buttons now use the shared danger palette and a readable hover state instead of the browser's gray fallback. The existing confirmation flow is retained.
- Marketing ticker and equalizer animations stop under prefers-reduced-motion. Canvas packets also remain still under that preference, while node selection stays available. Canvas lane labels use readable muted ink.

## Validation

- `npm run lint`: passed without warnings or errors.
- `npm run build`: passed; no dependencies added.
- Final `npm run test`: 948 passed, 0 failed.
- Browser matrix: 27 screens x 2 themes x desktop/390px = 108 views. No page-level overflow or runtime exceptions; no detected visible text contrast failures or visible controls below 44px in the final pass.
- Interaction checks: 32 additional views covering the assistant dock, employee/organization drawers, Quick Jump, notifications, account menu, chat and settings save bar. All tested overlays fit the viewport; the launcher does not visibly cover cards, tables or navigation links. Occluded content behind an opaque dock/dialog is excluded from the overlap calculation.
- Chat was checked at 390 x 500 as a shorter viewport check. The reduced-motion preference was emulated: ticker animation stopped and canvas pixels remained unchanged between frames.
- Source/test whitespace check: passed.
- No test files were changed in Phase 8. Existing selection, dialog, navigation and chatbot tests were retained; browser checks verify the visual changes rather than implementation-mirroring unit tests.

These browser checks use isolated API fixtures and an isolated browser profile. They do not verify live backend mutations or replace team flow verification. Android/iOS native builds, physical devices, real on-screen keyboards and nonzero hardware safe-area insets were not exercised. The CSS retains safe-area env() offsets; the short viewport check is an emulation, not a physical keyboard test.

## Routes to review

Open at http://localhost:5173 with the corresponding existing account, toggle both themes, and use a 390px viewport.

| Area | Route |
| --- | --- |
| Employee week and assistant dock | `/myhr/employee/dashboard` |
| HR dashboard | `/myhr/hr/dashboard` |
| Admin dashboard and header menus | `/myhr/admin/dashboard` |
| Employee roster and detail sheet | `/myhr/admin/employees` |
| Profile-change master/detail | `/myhr/admin/profile-change-requests` |
| Timesheet review | `/myhr/admin/timesheets` |
| Employee hour entry | `/myhr/employee/timesheets?mode=enter` |
| Documents and workflow preview | `/myhr/admin/document-requests` |
| Document approvals | `/myhr/admin/offer-letter-approvals` |
| Payroll selection and table | `/myhr/admin/timesheets?view=payroll` |
| Reports | `/myhr/admin/reports` |
| Audit timeline | `/myhr/admin/audit` |
| Settings save bar | `/myhr/admin/organization` |
| Profile | `/myhr/admin/profile` |
| Platform organization drawer | `/waypoint-admin/organizations` |
| Marketing and motion preference | `/` |
| Careers | `/careers` |
| Events | `/events` |
| Job seeker activity/profile links | `/job-seeker/profile` |
| Employer summary | `/employer/dashboard` |
| Employer jobs | `/employer/jobs` |
| Employer event links | `/employer/events` |

Open existing records to inspect document preparation/signing, applicants, registrants, and related detail paths. Existing request/job/event IDs and permissions are required.

## Files changed in Phase 8

- [src/index.css](src/index.css)
- [src/pages/EmployerEventsPage.css](src/pages/EmployerEventsPage.css)
- [src/pages/job-seeker/ProfilePage.css](src/pages/job-seeker/ProfilePage.css)
- [src/pages/experience/ExperiencePage.css](src/pages/experience/ExperiencePage.css)
- [src/pages/experience/components/WaypointCanvas.jsx](src/pages/experience/components/WaypointCanvas.jsx)
- [src/pages/myhr/features/payrollReadiness/PayrollReadinessPage.jsx](src/pages/myhr/features/payrollReadiness/PayrollReadinessPage.jsx)
- [src/pages/myhr/features/timesheets/timesheetsRoute.css](src/pages/myhr/features/timesheets/timesheetsRoute.css)
- [src/components/chatbot/FloatingAssistant.css](src/components/chatbot/FloatingAssistant.css)
- [src/components/layout/WorkspaceFrame.css](src/components/layout/WorkspaceFrame.css)
- [src/components/form/fieldShared.css](src/components/form/fieldShared.css)
- [src/components/global/Button.css](src/components/global/Button.css)
- [PHASE8_REVIEW.md](PHASE8_REVIEW.md)
- [ROUTE_LINE_FINAL_REVIEW.md](ROUTE_LINE_FINAL_REVIEW.md)

No API, authentication/session, route-definition, role-guard, backend or dev-credentials files were edited. No commits or pushes were made.

## Follow-up: populated timesheet review dialog

A user screenshot exposed a missed case: the outer details grid still auto-created three columns while every nested field reserved a 160px label column. Values were squeezed into narrow strips. The dialog now explicitly uses one outer column, with label/value rows on desktop and stacked fields on mobile. Its overlay is above the assistant; close/footer targets remain usable.

Changed: `src/pages/myhr/features/timesheets/timesheetsRoute.css` only. No API, review, approval, rejection or attachment behavior changed. No test assertions were changed.

Verified with a populated HR submission, filename, timestamp and change reason at 390/1024/1280/1440px in both themes (eight browser views). No narrow-value columns, page overflow, footer clipping or assistant overlap. All 82 TimesheetsPage tests passed; lint and production build passed. This supplements the earlier Phase 8 screen/menu checks, which did not cover this populated dialog.

## Follow-up: timesheet submissions table

Restored readable action sizing from the earlier table presentation: Review has an 88px minimum width and stays on one line; the employee name/ID column stays visible during desktop horizontal scrolling. Scrolling remains inside the table on narrow screens.

Submitted On previously passed a full timestamp into a helper that appended local midnight to date-only strings, producing Invalid Date. The cell now distinguishes timestamps from calendar dates, preserves supplied times, and renders a dash for missing/invalid values without changing API data.

Files: `src/pages/myhr/features/timesheets/TimesheetsPage.jsx`, `src/pages/myhr/features/timesheets/timesheetsRoute.css`, and `src/test/pages/myhr/TimesheetsPage.test.jsx`. One regression test covers valid timestamps, date-only values, missing values and invalid values; existing assertions remain. All 83 timesheet tests passed. Lint and production build passed. Sixteen isolated browser views verified start/end scrolling at 390/1024/1280/1440px in both themes. No page overflow, invalid-date labels or split Review text. API calls, review decisions and permissions remain unchanged.

## Follow-up: reference-inspired timesheet presentation

Applied the supplied sign-in reference's visual language to the timesheets workspace: a larger serif page title, Time & pay eyebrow, cream review panel, mint counted filters, grouped workspace tabs and a single week/search/export control area. The table remains full-width with contained scrolling. Bulk approval controls appear when selection or a result message exists, rather than showing an empty Approve 0 selected bar on initial load.

Changed `src/pages/myhr/features/MyHrFeaturePage.jsx` (optional eyebrow pass-through), `src/pages/myhr/features/timesheets/TimesheetsPage.jsx` (presentation grouping and contextual bulk toolbar), and `src/pages/myhr/features/timesheets/timesheetsRoute.css` (scoped design styles). No APIs, permissions, workflow destinations or review handlers changed. Existing date/dialog fixes are retained.

Validation: all 83 TimesheetsPage tests passed; lint and production build passed. Twelve isolated browser views covered HR, Admin and Employee at desktop/390px in both themes, with no detected contrast, touch-target, overflow or runtime errors. Selecting a reviewable row still reveals Approve 1 selected. No test assertions were changed for this presentation follow-up.
