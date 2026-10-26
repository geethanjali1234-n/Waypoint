# Route-line ? Phase 1 browser review

Only Phase 1 has been implemented. No git state/history commands were used.
No API files, authentication/session logic, role guards, routes, or backend files were changed.
Development-credential files were not read or modified.

## Preview

Open these on the existing Vite server (authenticate through the existing login for protected pages):

- http://localhost:5173/
- http://localhost:5173/login
- http://localhost:5173/myhr/employee/dashboard
- http://localhost:5173/myhr/admin/dashboard
- http://localhost:5173/myhr/admin/employees
- http://localhost:5173/myhr/admin/timesheets
- http://localhost:5173/careers
- http://localhost:5173/events

Use the existing theme toggle to inspect dark mode on the same routes.
This phase changes the visual foundation; page-specific layouts and new
RouteLine/DataTable/etc. primitives remain for their assigned later phases.

## Design changes

- Tokens now define the specified warm light palette and deep forest dark palette.
- Legacy aliases use the same tokens; enterprise and experiment styles no longer own competing palettes.
- Self-hosted Fraunces 500 and Instrument Sans 400?600, with fallbacks and both SIL OFL licenses.
- Display serif is restricted to page/card titles. UI text uses Instrument Sans.
- CSS shadows, text glows and backdrop blur are disabled centrally; canvas glow is removed.
- Gradient title text is replaced with readable text. Existing reduced-motion rules remain.
- Existing variant exports/provider remain compatible but cannot switch the production design or rewrite saved experiments.

## Validation

- Production build: passed; fonts are bundled locally.
- New contrast checks: all 18 passed (normal text >= 4.5:1 in both palettes).
- Variant compatibility checks: all 8 passed.
- Full test run: 840 passed, 31 failed, 871 total.
- Before Phase 1: 821 passed, 32 failed, 853 total. Every remaining failing test was already failing before this phase.
- Full lint: two pre-existing errors remain, plus one pre-existing warning.
  - `src/api/apiClient.js`: unused initial assignment (`no-useless-assignment`). API edits are prohibited.
  - `src/pages/myhr/features/documentRequests/SignatureField.jsx`: unused `continueDraw` helper. Signature tooling is left unchanged.
  - `OfferLetterPreparePage.jsx`: existing hook-dependency warning.
- Remaining test failures concern legacy route/dashboard assertions, public date formatting, document preparation/approval workflows, and document catalog grouping. They are baseline failures, not new phase regressions.
- No browser screenshot QA was available through the current tools; use the preview routes above.

## Every file changed in this phase

### Design foundation and compatibility

- `src/components/styles/tokens.css`
- `src/components/styles/fonts.css` (new)
- `src/components/styles/routeLineFoundation.css` (new)
- `src/components/styles/enterprise.css`
- `src/components/styles/uiVariants.css`
- `src/index.css`
- `src/main.jsx`
- `src/context/uiVariant.js`
- `src/context/UiVariantProvider.jsx`
- `src/components/dev/UiExperimenterDock.jsx`

### Self-hosted fonts

- `src/assets/fonts/fraunces-latin.woff2` (new)
- `src/assets/fonts/instrument-sans-latin.woff2` (new)
- `src/assets/fonts/fraunces-OFL.txt` (new)
- `src/assets/fonts/instrument-sans-OFL.txt` (new)
- `src/assets/fonts/README.md` (new)

### Safe validation cleanup

These are mechanical cleanup/environment fixes, not screen redesigns or workflow changes.

- `eslint.config.js` (Node/test globals; explicitly permitted existing pure helper exports)
- `src/components/chatbot/knowledgeBase.js` (unused parameters)
- `src/pages/experience/components/ChatbotShowcaseWidget.jsx` (collision-free message IDs)
- `src/pages/experience/components/DocumentScannerWidget.jsx` (unused import)
- `src/pages/experience/components/WaypointCanvas.jsx` (unused counter and canvas glow)
- `src/pages/myhr/components/ApplePayBloom.jsx` (unused React import)
- `src/utils/deviceDetection.js` (equivalent boolean condition)
- `src/test/pages/myhr/ExpressClearanceLogic.test.jsx` (unused imports)

### Tests and report

- `src/test/components/UiVariants.test.jsx` (assert fixed production design, retained navigation, and unchanged saved preference)
- `src/test/components/RouteLineTokens.test.js` (new contrast checks)
- `docs/route-line-phase-1.md` (this report)

Reply **continue** to authorize Phase 2. No later phase has been started.
