# Route-line phase 6

Phase 6 refreshes document requests and preparation, I-983 progress, document approvals, payroll readiness, reports, audit, organization settings and profile. Existing endpoints, role guards, routes and signature placement tooling are retained. No Git state changes or commits were made.

## Browser review

Use the existing authorized account for each role. Base URL: `http://localhost:5173`.

- `/myhr/admin/document-requests`
- `/myhr/hr/document-requests`
- `/myhr/employee/document-requests`
- `/myhr/admin/offer-letter-approvals`
- `/myhr/admin/timesheets?view=payroll`
- `/myhr/hr/timesheets?view=payroll`
- `/myhr/admin/reports`
- `/myhr/hr/reports`
- `/myhr/admin/audit`
- `/myhr/admin/organization`
- `/myhr/admin/profile`
- `/myhr/hr/profile`
- `/myhr/employee/profile`

Open an existing document request from its list to inspect its workflow. Use its existing Prepare action for offer letters or other supported generated documents. Open an uploaded I-983 to inspect its signature and approval route. Select an existing approval to inspect its preview. Payroll View and report Review open shared detail panels. Edit Settings reveals the sticky save bar.

## Files changed in this phase

Paths below are relative to `frontend/`.

- `src/components/global/DataTable.jsx` — allow omission of the footer when endpoint totals are unknown.
- `src/pages/myhr/components/WorkspaceDataTable.jsx` — adapt existing cells/actions to the shared table; retain original API rows.
- `src/pages/myhr/components/DocumentRoute.jsx` — render recorded workflow stages with the shared vertical route.
- `src/pages/myhr/features/MyHrFeaturePage.jsx` — load scoped phase styles.
- `src/pages/myhr/features/phaseSix.css` — operational surfaces, route/preview layouts, timeline, save bar and responsive controls.
- `src/pages/myhr/features/admin/AuditHistoryPage.jsx` — default ordered timeline, category pills, table and detail panel.
- `src/pages/myhr/features/admin/OrganizationSettingsPage.jsx` — sectioned settings with sticky save controls.
- `src/pages/myhr/features/profile/MyHrProfilePage.jsx` — person identity and account status badge.
- `src/pages/myhr/features/payrollReadiness/PayrollReadinessPage.jsx` — single filter/count source, shared table and detail panel, recorded coverage only.
- `src/pages/myhr/features/reports/ReportsPage.jsx` — report/filter pills, shared tables, badges and review panel.
- `src/pages/myhr/features/documentRequests/DocumentRequestsPage.jsx` — filter pills, shared table, recorded pagination totals and document route.
- `src/pages/myhr/features/documentRequests/OfferLetterApprovalsPage.jsx` — counted filters, status badges and workflow beside preview.
- `src/pages/myhr/features/documentRequests/OfferLetterPreparePage.jsx` — route beside existing preparation/preview tooling.
- `src/pages/myhr/features/documentRequests/documentPreparation/DocumentPreparationPage.jsx` — route beside existing generic preparation flow.
- `src/pages/myhr/features/documentRequests/i983/I983ReviewPanel.jsx` — route reflects actual signature, correction and approval states.
- `src/pages/myhr/features/documentRequests/SignatureField.jsx` — remove unused drawing helper/state binding; retain capture behavior.
- `src/pages/myhr/features/documentRequests/selfServiceDocumentCatalog.js` — include the existing Employment Letter type in its category, fixing catalog completeness.
- `src/test/pages/myhr/PhaseSixPresentation.test.jsx` — original-row integrity, unknown-total omission and real workflow state tests.
- `src/test/pages/myhr/AuditHistoryPage.test.jsx` — category pills and ordered timeline; retained filter and before/after checks.
- `src/test/pages/myhr/MyHrProfilePage.test.jsx` — person/login identity fallback and repeated visible ID.
- `src/test/pages/myhr/PayrollReadinessPage.test.jsx` — counted pills, actual rendered row range, shared detail panel and empty state.
- `src/test/pages/myhr/ReportsPage.test.jsx` — accessible pill name after emoji removal; retained API filter assertion.
- `src/test/pages/myhr/OfferLetterApprovals.test.jsx` — counted filters and existing direct approve/reject workflow; retained no-delivery and validation checks.
- `src/test/pages/myhr/DocumentRequestsPage.test.jsx` — existing workspace controls, upload prerequisite and shared pagination labels.
- `src/test/pages/myhr/DocumentPreparationPage.test.jsx` — supply required signature fixtures, represent authorized self-signing admin, preserve approval/delivery assertions.
- `docs/route-line-phase6.md` — phase review record.

## Data omissions

Document filter counts are omitted when the endpoint provides no aggregate counts. Unknown list totals omit the total footer. Missing payroll summary counts and coverage percentages omit badges/progress rather than inventing zero. Unknown document workflows omit the route. Completed/approved/filed stops rely on recorded workflow or document facts.

## Validation

Final results are recorded below after the full suite and production build finish. Browser and Capacitor device review remain for the user and phase 8.
