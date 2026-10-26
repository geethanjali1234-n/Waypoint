# Route-line Phase 5 review

Employee Management, Profile Change Requests and Timesheets now use the shared
Route-line presentation. Earlier working-tree changes were retained. Phase 6
has not been started. No dependency, API module, auth/session module, route
definition, role guard, backend or credentials file was changed. No Git state
or history was changed.

## Browser review

Open these with the corresponding existing account at localhost:5173:

- http://localhost:5173/myhr/admin/employees
- http://localhost:5173/myhr/hr/employees
- http://localhost:5173/myhr/admin/profile-change-requests
- http://localhost:5173/myhr/hr/profile-change-requests?view=assigned
- http://localhost:5173/myhr/hr/profile-change-requests
- http://localhost:5173/myhr/employee/profile-change-requests
- http://localhost:5173/myhr/admin/timesheets
- http://localhost:5173/myhr/hr/timesheets?view=team
- http://localhost:5173/myhr/employee/timesheets

Employee Management: counts live in All / Active / Inactive / HR staff pills;
the existing Needs manager filter is retained when matching people exist.
Search is always visible beside filters, export and Table/Cards controls.
The table has avatar/name/ID, type, designation, manager and a status badge.
Selection persists across pages and card/table presentation. Selection does
not perform an account mutation. Opening a person keeps the roster in place
behind the shared desktop drawer/mobile sheet. Reporting history and the
current manager form a vertical route. Manager changes, effective-date rules,
account creation, address suggestions, copy ID, exports, status confirmation
and mandatory HR report reassignment retain their existing calls and behavior.

Profile changes: reviewer list and selected request are side by side on wider
screens, stacked on narrower screens. The selected row is highlighted. The
right pane compares On file and Requested with a bordered highlight on the new
value, then the optional note and existing decision actions/confirmation.
Late responses cannot replace a newer selected request. Requesters retain their
own submission and history workflows; their read-only view uses the same plain
comparison rather than the animated badge. HR's chooser and own inline form
remain available. Counts appear once in filter pills, without duplicate cards.
Notification links remain scoped to the already authorized lists.

Timesheets: Pending / My team / Approved / Rejected pills own the counts.
The week switcher is an optional filter; initial results retain all existing
matching dates. Daily strips show actual Mon-Fri entries for the displayed
week, with the period, total hours, attachment count and Review action.
The table scrolls inside its container, has a sticky header, a footer and
shared pagination. Approved/rejected rows cannot be selected for approval.
Approve N selected calls the existing review handler sequentially, reports
actual successes, and keeps failed decisions selected for retry. HR's own
timesheets remain excluded from its review queue. Existing individual review,
rejection comments, attachments, download/preview, export, sort, period filters,
employee views, express approval and payroll navigation remain available.

## Files changed in this phase

All paths are relative to frontend:

- src/components/global/FilterPills.jsx
- src/components/global/StrokeIcon.jsx
- src/hooks/useDialogFocus.js (new)
- src/pages/myhr/features/employeeManagement/EmployeeManagementPage.jsx
- src/pages/myhr/features/employeeManagement/employeeManagement.css
- src/pages/myhr/features/profileChanges/ProfileChangeRequestsPage.jsx
- src/pages/myhr/features/profileChanges/profileChanges.css
- src/pages/myhr/features/timesheets/TimesheetsPage.jsx
- src/pages/myhr/features/timesheets/timesheetReview.css (new)
- src/pages/myhr/features/timesheets/components/WeekHoursStrip.jsx (new)
- src/pages/myhr/features/timesheets/components/weekHoursStrip.css (new)
- src/test/pages/myhr/EmployeeManagementListAndDetail.test.jsx
- src/test/pages/myhr/ProfileChangeRequestsPage.test.jsx
- src/test/pages/myhr/TimesheetsPage.test.jsx
- src/test/pages/myhr/WeekHoursStrip.test.jsx (new)
- docs/route-line-phase5.md (new)

Temporary edit scripts were removed. Validation logs are phase5-focused-third.log,
phase5-header-check.log, phase5-test-verified.log, phase5-lint-final.log and
phase5-build-final.log. Build checks regenerate ignored dist assets.

## Test changes and data limits

Existing tests now expect separated person names/IDs, filter pills rather than
stat tiles, the inline reviewer region rather than a dialog, shared pagination,
and Review for team rows. Employee self-history still uses View details.
The page-header test retains the dashboard destination check through the shell
home link, whose accessible name may include the organization's actual name.
The underlying account, review, approval, rejection, scoping, payload, export,
validation and attachment assertions were retained.

New tests cover cross-page roster selection without mutations, drawer and nested
dialog keyboard behavior, stale profile responses, week filtering, partial bulk
approval/retry, and daily strip aggregation/empty days/schedule differences.

Whitespace was added between filter labels and counts so accessible names read
Pending 1 rather than Pending1. The same counts still come from the caller.
Stroke icons replace emoji in the edited screen controls. The two rewritten
screen styles use theme tokens; review/filter styling follows the same palette.
No heavy dependency was introduced.

No daily standard is invented. A daily bar is marked as different only if an
actual standardDailyHours value is supplied on its record. Existing responses
that omit this schedule metadata show recorded hours without an amber
difference classification. Missing attachment metadata displays a dash rather
than claiming a zero count. Missing entries remain dashes; missing dates omit
the strip. Reporting routes include only returned historical/current managers.
Full mobile and dark-theme QA remains Phase 8.

## Validation results

- Focused screen/primitives suite: 203 tests passed across eight files. All of
  these files also pass in the final full-suite run.
- Targeted lint for every Phase 5 JavaScript/JSX source and test: passed.
- Production build: passed. Main JavaScript is 1,959.87 kB versus 1,960.80 kB
  at the end of Phase 4; no dependency was added.
- Full suite: 908 passed, 25 failed across 105 files (99 passing files).
  The same 25 failures remain from Phase 4, in JobSeekerApplicationsPage,
  JobSeekerRegisteredEventsPage, DocumentPreparationPage, DocumentRequestsPage,
  OfferLetterApprovals review screens and selfServiceDocumentCatalog tests.
  No Phase 5 test fails. These unrelated tests were not weakened or suppressed.
- Full lint: the same two existing errors remain in src/api/apiClient.js:11
  (unused assignment) and SignatureField.jsx:125 (unused continueDraw), plus
  the OfferLetterPreparePage.jsx hook-dependency warning. The API file is
  explicitly protected and signature tooling was preserved.

Notification tests wait for actual selected-request content before checking the
requested API ID. The inline review region is always present, so its existence
alone no longer proves that asynchronous request details have loaded.

Phase 5 is ready for browser review. Phase 6 awaits the user's continue message.
