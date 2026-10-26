# Route-line Phase 2 review

Phase 2 supplies controlled, reusable primitives. Feature-page adoption remains
in the later phases. No API modules, authentication/session code, route paths,
role guards, backend code, or signature placement tooling were edited in this
resumed work. No credentials files were read. No Git state was changed.

## Browser review

- http://localhost:5173/experience?preview=route-line — existing development-only
  primitive gallery. Toggle the theme, select filters, open the detail panel,
  and test it at 390px. The gallery deliberately has no employee records or
  operational counts. Its route stages are labeled component examples.
- http://localhost:5173/myhr/employee/dashboard
- http://localhost:5173/myhr/hr/dashboard
- http://localhost:5173/myhr/admin/dashboard

The dashboard routes show the updated assistant where it is already mounted;
their dashboard redesign is scheduled for Phase 4. Existing login and role
permissions apply.

## Edits made while resuming Phase 2

- `src/components/global/DataTable.jsx` — safe default column collection.
- `src/components/global/DataTable.css` — bounded internal scrolling, sticky
  headers, separate borders, and narrow-screen column scrolling.
- `src/components/global/StrokeIcon.jsx` — matching trash and send stroke icons.
- `src/components/layout/DetailPanel.jsx` — wrap keyboard focus when the dialog
  container itself receives focus.
- `src/components/chatbot/WaypointChatbot.jsx` — consistent control icons,
  reduced-motion scrolling, Escape dismissal, focus restoration, and focus-timer
  cleanup. Existing messages, knowledge queries, and action destinations remain.
- `src/components/chatbot/WaypointChatbot.css` — replace neon/glass styles with
  tokens, hairline borders, 44px controls, and safe-area positioning.
- `src/test/components/RouteLinePrimitives.test.jsx` — additional coverage for
  dialog-container focus trapping and assistant expansion announcements.
- `src/test/components/WaypointChatbot.test.jsx` — additional Escape dismissal
  and focus-restoration coverage; existing behavior assertions remain.
- `docs/route-line-phase2.md` — this review record.

## Phase 2 implementation already present when this work resumed

The existing implementation was inspected and retained:

- `src/components/global/RouteLine.jsx`
- `src/components/global/RouteLine.css`
- `src/components/global/FilterPills.jsx`
- `src/components/global/FilterPills.css`
- `src/components/global/StatusBadge.jsx`
- `src/components/global/StatusBadge.css`
- `src/components/global/NeedsYouList.jsx`
- `src/components/global/NeedsYouList.css`
- `src/components/global/DataTable.jsx`
- `src/components/global/DataTable.css`
- `src/components/global/StrokeIcon.jsx`
- `src/components/global/index.js`
- `src/components/layout/PageHeader.jsx`
- `src/components/layout/PageHeader.css`
- `src/components/layout/DetailPanel.jsx`
- `src/components/layout/DetailPanel.css`
- `src/components/layout/index.js`
- `src/components/chatbot/FloatingAssistant.jsx`
- `src/components/chatbot/FloatingAssistant.css`
- `src/components/chatbot/WaypointChatbot.jsx`
- `src/components/index.js`
- `src/pages/experience/ExperiencePage.jsx`
- `src/pages/experience/components/RouteLinePreview.jsx`
- `src/pages/experience/components/RouteLinePreview.css`
- `src/test/components/RouteLinePrimitives.test.jsx`

These components retain caller-owned data, counts, selection, pagination,
sorting, and actions. No operational numbers were fabricated. RouteLine states
and badges include text labels. The drawer supports desktop and mobile sizing,
Escape dismissal, focus trapping/restoration, and background inertness.

## Validation

- Focused primitives, chatbot, and existing contrast tests: **72 passed**.
- `npm run build`: **passed**.
- `npm run lint`: **fails on existing errors outside the resumed edits**:
  `src/api/apiClient.js:11` (`no-useless-assignment`) and
  `src/pages/myhr/features/documentRequests/SignatureField.jsx:125`
  (`no-unused-vars`). There is also an existing dependency warning in
  `OfferLetterPreparePage.jsx:656`.
- Full-suite output is in `phase2-verification.log`. The initial full run had
  **861 passed / 31 failed** across **101 files**. Failures are in application
  and event date expectations, legacy dashboard/router expectations, document
  preparation/request/approval expectations, and document category completeness.
  The category test exposes `EMPLOYMENT_LETTER` missing from its category list.

The full checks are not green. The API file is explicitly prohibited, signature
tooling must remain unchanged, and repairing document workflows or redesigning
dashboards belongs outside Phase 2. Those failures were not suppressed, nor were
their tests weakened to claim a pass. Phase 3 has not started.
