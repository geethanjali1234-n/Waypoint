/**
 * Canonical categories for the HR/Admin -> Employee "supply an existing
 * document" direction (RequestDocumentsForm.jsx). These values are enforced
 * server-side (DocumentRequestService.CATEGORIES) and are what every
 * document request in that direction stores in its `category` field, so
 * they are not renamed or restructured here - only centralized, so every
 * place that shows or filters by this category (the shared
 * DocumentRequestsPage list used by Employee, HR, and Admin alike) reads
 * from one place instead of keeping its own hardcoded copy that can drift.
 *
 * This is deliberately separate from selfServiceDocumentCatalog.js's
 * SELF_SERVICE_CATEGORIES, which groups the *document types* an employee can
 * ask HR to prepare (the opposite direction) - the two catalogs describe
 * different things and are not meant to be merged.
 */
export const DOCUMENT_CATEGORIES = {
  PRE_EMPLOYMENT_ONBOARDING: { label: 'Pre-Employment / Onboarding', documents: ["Government-issued photo ID (driver's license, passport, state ID)", 'Social Security card (or proof of SSN)', 'Form I-9 supporting documents (List A, B, or C)', 'Proof of work authorization (H-1B approval notice, EAD card, etc.)', 'Signed offer letter / employment agreement', 'Educational certificates / degrees (transcripts, diplomas)', 'Professional license / certification copies', 'Background check consent form', 'Drug test consent / results', 'Previous employment verification / reference letters'] },
  TAX_PAYROLL: { label: 'Tax & Payroll', documents: ['Form W-4 (federal tax withholding)', 'State tax withholding form', 'Direct deposit authorization form + voided check / bank details', 'Form W-9 (independent contractor)'] },
  BENEFITS_ENROLLMENT: { label: 'Benefits Enrollment', documents: ['Health insurance enrollment form', 'Dependent information (marriage certificate, birth certificates)', 'Beneficiary designation forms (401(k), life insurance)', 'COBRA election forms (incoming)', 'FSA / HSA enrollment forms'] },
  IMMIGRATION_RELATED: { label: 'Immigration-Related', documents: ['I-983', 'Visa / EAD renewal documents', 'Passport copies (for visa processing)', 'Form I-9 reverification documents'] },
  EXIT_OFFBOARDING: { label: 'Exit / Offboarding', documents: ['Signed separation agreement', 'Company property return acknowledgment (laptop, badge, etc.)', 'Exit interview form', 'COBRA election form (outgoing)'] },
};

export const DOCUMENT_CATEGORY_OPTIONS = Object.entries(DOCUMENT_CATEGORIES)
  .map(([value, item]) => ({ value, label: item.label }));

// The canonical display label for one of these 5 categories (with its
// hyphen/slash formatting intact, e.g. "Pre-Employment / Onboarding"), or
// null for a category this catalog doesn't cover (for example, self-service
// requests' backend-set "EMPLOYMENT" category) - callers fall back to
// generic label formatting (formatDisplayLabel) for those.
export function documentCategoryLabel(value) {
  return DOCUMENT_CATEGORIES[value]?.label || null;
}
