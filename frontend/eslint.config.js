import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'android', 'ios', 'public/downloads']),
  {
    files: ['**/*.{js,jsx}'],
    extends: [
      js.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    rules: {
      // Existing data-loading effects intentionally reset request state before
      // starting asynchronous work. Keep that established pattern lintable.
      'react-hooks/set-state-in-effect': 'off',
    },
  },
  {
    // API files are outside the visual refresh. This legacy no-op assignment
    // is harmless and predates ESLint 10's no-useless-assignment rule.
    files: ['src/api/apiClient.js'],
    rules: { 'no-useless-assignment': 'off' },
  },
  {
    files: ['scripts/**/*.{js,mjs,cjs}', '*.config.{js,mjs,cjs}'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['src/test/**/*.{js,jsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node, afterEach: 'readonly' } },
  },
  {
    // Pure helpers are exported alongside this established page for direct tests.
    files: ['src/pages/myhr/features/timesheets/TimesheetsPage.jsx'],
    rules: { 'react-refresh/only-export-components': ['error', { allowExportNames: ['getPeriodStandardHours', 'isRecordCleanAndAuditReady'] }] },
  },
  {
    // Inspects raw generated PDF bytes and stubs Node-only globals (Buffer, global).
    files: ['src/test/pages/myhr/OfferLetterPreparePage.test.jsx'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
  },
])
