# Phase 7 review

Phase 7 refreshes Platform Admin, the public marketing page, Careers, Events, Job seeker, and Employer. Phase 8 has not started.

## Result

- Organizations uses the shared DataTable, with organization name and code, status dot and label, pagination footer, and a focus-managed DetailPanel. Search and pagination still use the same requests. Missing organization status is shown as unavailable.
- The platform dashboard uses PageHeader and points to the existing Organizations navigation instead of claiming that no modules are enabled.
- Employer replaces duplicate getting-started navigation and the uniform stat grid with NeedsYouList action rows. Counts appear once and come from the existing dashboard response. The application count is explicitly the total received, not a fabricated pending count. Recent activity remains available.
- Employer applicants and event registrants use real shared tables with name, secondary identifier, labeled statuses, and contained scrolling. Existing applicant-detail navigation and resume/status actions are retained.
- Employer jobs and events retain their filters, pagination, create/edit/close/reopen behavior. Tables have sticky headers and contained horizontal scrolling. Statuses use shared badges. Event titles are real links; the job editor backdrop is a labeled button.
- Careers and Events retain search, location, filters, selection, application, registration, cancellation, and calendar actions. Selection is exposed with aria-pressed. Cards use warm tokens, serif titles, and hairline borders. The careers search form stacks at narrow widths.
- Job seeker removes duplicate dashboard navigation tiles while preserving the primary careers action and guidance. Application/registration statuses use shared badges. Profile has a real page heading and accessible completion progress; unavailable completion and totals are omitted.
- Marketing retains its sections, simulations, theme/audio controls, and interactions. Gradients, glow shadows, blurred surfaces, and comparison emoji icons are replaced with warm tokens and the shared stroke icons. Profile, event-form, and contact surfaces use the same palette and fonts.

## Validation

- Lint: passed with no errors or warnings.
- Production build: passed; no dependencies added. The compressed main JS and CSS bundles are slightly smaller than before this phase.
- Full tests: 948 passed, 0 failed.
- Focused regression run: 20 tests passed, covering dashboards, organization search/pagination/details, employer event filters/links, and employer list pagination.
- Browser checks: 12 screens at 1440px and 390px (24 views) using isolated API fixtures. All screens loaded with no runtime exceptions or page-level horizontal overflow. Rechecked after the typography/search/profile fixes. These checks did not send real backend mutations.
- Whitespace check: passed for the changed source and test files.

## Routes to review

Open at http://localhost:5173 with the corresponding existing role/account.

| Area | Route |
| --- | --- |
| Public marketing | `/` (also `/experience`) |
| Platform dashboard | `/waypoint-admin/dashboard` |
| Organizations and detail drawer | `/waypoint-admin/organizations` |
| Careers | `/careers` |
| Events | `/events` |
| Job seeker dashboard | `/job-seeker/dashboard` |
| Applications | `/job-seeker/applications` |
| Registered events | `/job-seeker/registrations` |
| Profile | `/job-seeker/profile` |
| Edit profile | `/job-seeker/profile/edit` |
| Contact | `/job-seeker/contact-us` |
| Employer dashboard | `/employer/dashboard` |
| Job postings | `/employer/jobs` |
| New posting | `/employer/jobs/new` |
| Employer events | `/employer/events` |
| New event | `/employer/events/new` |
| Employer profile | `/employer/profile` |

From an existing job/event, open its detail, application/registration form, applicant list/detail, or registrants list to review those paths with a real record ID. No example IDs have been added to production data or routes.

## Tests updated and why

- `src/pages/EmployerDashboardPage.test.jsx`: verifies the existing summary counts inside action rows, preserves the new-posting action, covers job-review navigation, and expects missing counts to be omitted.
- `src/test/pages/EmployerDashboardPage.test.jsx`: checks the same outlet-context count inside its action row instead of the intentionally removed stat card.
- `src/pages/EmployerEventsPage.test.jsx`: supplies the router needed by real event links, retains the existing location/status filter checks, and verifies the registrants destination.
- `src/test/pages/waypointAdmin/WaypointAdminOrganizationsPage.test.jsx`: follows shared table button/pagination labels, preserves search/detail/error checks, verifies unavailable status, and adds drawer Escape/focus-restoration coverage.
- `src/test/pages/waypointAdmin/WaypointAdminDashboardPage.test.jsx`: follows the accurate Organizations guidance while retaining workspace isolation and sign-out checks.
- `src/test/pages/job-seeker/ProfilePage.test.jsx`: verifies accessible completion progress and adds missing-total/completion coverage while retaining profile and activity checks.

## Data-dependent omissions

No pending-only application count or hiring-stage progress is invented: the dashboard supplies total applications, so the UI labels and uses that total. Missing employer counts, profile totals/completion, organization status, and event registration counts are omitted or explicitly unavailable rather than guessed. Existing public marketing copy and demonstration simulations are retained.

## Files changed

- [src/components/domain/HeroSection.css](src/components/domain/HeroSection.css)
- [src/pages/EmployerApplicantsPage.css](src/pages/EmployerApplicantsPage.css)
- [src/pages/EmployerApplicantsPage.jsx](src/pages/EmployerApplicantsPage.jsx)
- [src/pages/EmployerDashboardPage.css](src/pages/EmployerDashboardPage.css)
- [src/pages/EmployerDashboardPage.jsx](src/pages/EmployerDashboardPage.jsx)
- [src/pages/EmployerDashboardPage.test.jsx](src/pages/EmployerDashboardPage.test.jsx)
- [src/pages/EmployerEventFormPage.css](src/pages/EmployerEventFormPage.css)
- [src/pages/EmployerEventRegistrantsPage.css](src/pages/EmployerEventRegistrantsPage.css)
- [src/pages/EmployerEventRegistrantsPage.jsx](src/pages/EmployerEventRegistrantsPage.jsx)
- [src/pages/EmployerEventsPage.css](src/pages/EmployerEventsPage.css)
- [src/pages/EmployerEventsPage.jsx](src/pages/EmployerEventsPage.jsx)
- [src/pages/EmployerEventsPage.test.jsx](src/pages/EmployerEventsPage.test.jsx)
- [src/pages/EmployerProfilePage.css](src/pages/EmployerProfilePage.css)
- [src/pages/JobPostingPage.css](src/pages/JobPostingPage.css)
- [src/pages/JobPostingPage.jsx](src/pages/JobPostingPage.jsx)
- [src/pages/JobSeekerApplicationsPage.jsx](src/pages/JobSeekerApplicationsPage.jsx)
- [src/pages/JobSeekerContactUsPage.css](src/pages/JobSeekerContactUsPage.css)
- [src/pages/JobSeekerDashboardPage.css](src/pages/JobSeekerDashboardPage.css)
- [src/pages/JobSeekerDashboardPage.jsx](src/pages/JobSeekerDashboardPage.jsx)
- [src/pages/JobSeekerRegisteredEventsPage.jsx](src/pages/JobSeekerRegisteredEventsPage.jsx)
- [src/pages/careers/CareersPage.css](src/pages/careers/CareersPage.css)
- [src/pages/careers/CareersPage.jsx](src/pages/careers/CareersPage.jsx)
- [src/pages/careers/JobApplyPage.css](src/pages/careers/JobApplyPage.css)
- [src/pages/careers/components/SalaryFilterDropdown.css](src/pages/careers/components/SalaryFilterDropdown.css)
- [src/pages/events/EventRegisterPage.css](src/pages/events/EventRegisterPage.css)
- [src/pages/events/EventsPage.css](src/pages/events/EventsPage.css)
- [src/pages/events/EventsPage.jsx](src/pages/events/EventsPage.jsx)
- [src/pages/events/components/EventListItem.css](src/pages/events/components/EventListItem.css)
- [src/pages/events/components/EventListItem.jsx](src/pages/events/components/EventListItem.jsx)
- [src/pages/experience/ExperiencePage.css](src/pages/experience/ExperiencePage.css)
- [src/pages/experience/ExperiencePage.jsx](src/pages/experience/ExperiencePage.jsx)
- [src/pages/job-seeker/EditProfilePage.css](src/pages/job-seeker/EditProfilePage.css)
- [src/pages/job-seeker/ProfilePage.css](src/pages/job-seeker/ProfilePage.css)
- [src/pages/job-seeker/ProfilePage.jsx](src/pages/job-seeker/ProfilePage.jsx)
- [src/pages/waypointAdmin/WaypointAdminDashboardPage.css](src/pages/waypointAdmin/WaypointAdminDashboardPage.css)
- [src/pages/waypointAdmin/WaypointAdminDashboardPage.jsx](src/pages/waypointAdmin/WaypointAdminDashboardPage.jsx)
- [src/pages/waypointAdmin/WaypointAdminOrganizationsPage.css](src/pages/waypointAdmin/WaypointAdminOrganizationsPage.css)
- [src/pages/waypointAdmin/WaypointAdminOrganizationsPage.jsx](src/pages/waypointAdmin/WaypointAdminOrganizationsPage.jsx)
- [src/test/pages/EmployerDashboardPage.test.jsx](src/test/pages/EmployerDashboardPage.test.jsx)
- [src/test/pages/job-seeker/ProfilePage.test.jsx](src/test/pages/job-seeker/ProfilePage.test.jsx)
- [src/test/pages/waypointAdmin/WaypointAdminDashboardPage.test.jsx](src/test/pages/waypointAdmin/WaypointAdminDashboardPage.test.jsx)
- [src/test/pages/waypointAdmin/WaypointAdminOrganizationsPage.test.jsx](src/test/pages/waypointAdmin/WaypointAdminOrganizationsPage.test.jsx)
- [PHASE7_REVIEW.md](PHASE7_REVIEW.md)

API modules, auth/session logic, route definitions, role guards, backend code, and dev-credentials files were not modified. No commits, pushes, or other Git state changes were made.
