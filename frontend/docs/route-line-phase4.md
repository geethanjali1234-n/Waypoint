# Route-line Phase 4 review

The resumed Phase 4 implements the active My HR employee, HR and company-admin
dashboards. Phase 5 has not been started in this continuation. Earlier working
tree edits to other screens have been retained.

## Browser review

Open these routes with the corresponding existing account:

- http://localhost:5173/myhr/employee/dashboard
- http://localhost:5173/myhr/hr/dashboard
- http://localhost:5173/myhr/admin/dashboard

Employee: check the dated Monday-Friday route, today's marker, real logged-day
count, Log hours, and Log standard week. The standard-week action hands off to
the existing entry flow; it does not save or submit a timesheet. Check document
next actions, signature requirements and profile decision dates when available.

HR/admin: check Needs you today, live counts, the selected payroll month,
readiness, filtered queue destinations, people search and pagination. HR does
not load the admin-only approval endpoint, and its own timesheet is excluded
from the review count. Failed loads display an unavailable state rather than
zero. Sidebar destinations remain available without duplicate action tiles.

## Phase 4 implementation files

Paths below are relative to frontend. This list includes the dashboard work
already present when this continuation started:

- src/pages/myhr/MyHrRoleLandingPage.jsx
- src/pages/myhr/components/WorkspaceDashboard.jsx
- src/pages/myhr/components/workspaceDashboard.css
- src/pages/myhr/components/EmployeeTodayPanel.jsx
- src/pages/myhr/components/employeeTodayPanel.css
- src/pages/myhr/components/dashboardDocumentProgress.js
- src/pages/myhr/features/timesheets/TimesheetsPage.jsx (standard-week handoff;
  other pre-existing edits in this file are not part of this continuation)
- src/test/pages/myhr/MyHrRoleLandingPage.test.jsx
- src/test/pages/myhr/EmployeeTodayPanel.test.jsx
- src/test/pages/myhr/dashboardDocumentProgress.test.js
- src/test/pages/myhr/OfferLetterApprovals.test.jsx (dashboard assertion only)
- src/test/routes/AppRouter.test.jsx (dashboard greeting/navigation assertions)
- docs/route-line-phase4.md

This continuation changed WorkspaceDashboard.jsx, MyHrRoleLandingPage.test.jsx,
OfferLetterApprovals.test.jsx and this review record. No dependency, API module,
auth/session module, route definition, role guard, backend or credentials file
was changed. No Git mutation command was run.

## Test intent and validation

Dashboard tests retain role permissions, real counts, navigation destinations,
failure states, refresh, people search and pagination. Employee tests cover
unique recorded weekdays, weekend behavior, the standard-week handoff and real
document/signature progress. Document progress tests prevent declaring a file
complete or requiring a signature without supporting workflow facts.

The approval dashboard test now checks the live count and filtered action link
in Needs you today rather than the removed tile. Approval-screen tests have
not been weakened or rewritten. The added payroll test verifies that even 100%
readiness does not claim a completed payroll run.

- Dashboard/document-progress/router tests: 55 passed across four files.
- Targeted lint for dashboard code and affected tests: passed.
- Production build: passed. No dependency was added.
- Full test suite: 899 passed, 25 failed across 104 files (98 passing files).
  The remaining failures are in JobSeekerApplicationsPage.test.jsx,
  JobSeekerRegisteredEventsPage.test.jsx, DocumentPreparationPage.test.jsx,
  DocumentRequestsPage.test.jsx, OfferLetterApprovals.test.jsx (review screens)
  and selfServiceDocumentCatalog.test.js. These areas already had failures
  recorded in Phase 3. Dashboard and router tests pass. The initial full run
  had 26 failures; updating the intentionally changed dashboard assertion
  removes that failure in the final run. Remaining tests were not suppressed.
- Full lint was run: two existing errors remain in src/api/apiClient.js:11
  and src/pages/myhr/features/documentRequests/SignatureField.jsx:125, with
  the existing OfferLetterPreparePage.jsx hook warning. These were recorded
  in Phase 3. The API file is explicitly outside the authorized edit scope;
  signature tooling was preserved.

Validation logs: phase4-dashboard-verified.log, phase4-dashboard-final.log,
phase4-test-final.log, phase4-lint-final.log and phase4-build-final.log.
Build validation regenerated ignored dist assets.

## Data limits

My HR's loaded responses provide no open-job totals or applicant-review counts;
these are omitted. The document queue uses actual company-admin approval counts,
not an invented count of employee signatures. HR does not receive that queue.

Payroll period stops appear only when the endpoint supplies period dates. Run
appears as a future stop only with an actual cutoff date and explicitly states
that run status is unavailable; readiness never proves a run was completed.
If the cutoff is absent, Run is omitted. Missing readiness fields omit numeric
progress and follow-up counts. Active-employee totals require account statuses.

Employee document progress uses existing request fields and workflow state.
Unknown workflows omit route stops. Decision dates and requested dates appear
only when supplied and valid. No numbers or decision timestamps are fabricated.
Platform and employer dashboard redesigns remain Phase 7.
