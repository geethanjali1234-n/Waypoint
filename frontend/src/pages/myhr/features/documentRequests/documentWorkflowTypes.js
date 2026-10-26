/**
 * Central registry of employee document types that follow the shared
 * request -> prepare -> template -> preview -> send -> (sign -> return)
 * workflow (the document-generation design doc). This is the single place
 * that maps a document request to a preparation route, so
 * DocumentRequestsPage and AppRouter don't hardcode document names. It also
 * carries the data-driven lifecycle flags (does HR prepare it, does the
 * employee need to sign and return it) that the sign/return workflow reads
 * - so no component hardcodes per-type behavior.
 *
 * Offer Letter keeps its own dedicated, already-tested page
 * (OfferLetterPreparePage) - it's listed here only so the request-detail
 * page can recognize it and link to it. Every other type is driven by the
 * generic DocumentPreparationPage, configured per type in
 * documentPreparation/documentTypeConfigs.js.
 */
export const DOCUMENT_WORKFLOW_TYPES = [
  {
    id: 'OFFER_LETTER',
    requestTitle: 'Offer Letter',
    label: 'Offer Letter',
    prepareLabel: 'Prepare Offer Letter',
    routeSegment: 'offer-letter',
    category: 'PRE_EMPLOYMENT_ONBOARDING_OFFER',
    hasDedicatedPage: true,
    requiresEmployeeSignature: true,
    requiresSignedReturn: true,
  },
  {
    id: 'EMPLOYMENT_VERIFICATION',
    requestTitle: 'Employment Verification',
    label: 'Employment Verification',
    prepareLabel: 'Prepare Employment Verification',
    routeSegment: 'employment-verification',
    category: 'EMPLOYMENT_LETTERS',
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  {
    // Experience and Relieving Letters are one merged document with one
    // template. Requests raised before the merge were titled "Experience
    // Letter" or "Relieving Letter" - legacyRequestTitles keeps those
    // routing to this same prepare page.
    id: 'EXPERIENCE_LETTER',
    requestTitle: 'Experience and Relieving Letter',
    legacyRequestTitles: ['Experience Letter', 'Relieving Letter'],
    label: 'Experience and Relieving Letter',
    prepareLabel: 'Prepare Experience and Relieving Letter',
    routeSegment: 'experience-relieving-letter',
    category: 'SEPARATION',
    // Issued by the company, not countersigned - the employee only views
    // and downloads the completed letter.
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  {
    id: 'SALARY_VERIFICATION',
    requestTitle: 'Salary Verification Letter',
    label: 'Salary Verification Letter',
    prepareLabel: 'Prepare Salary Verification',
    routeSegment: 'salary-verification',
    category: 'EMPLOYMENT_LETTERS',
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  // LCA, PERM and I-140 are filed documents HR/Admin already hold - they are
  // never generated from a template. The assigned HR or a Company Admin
  // uploads the existing file on the request detail page (the real fulfill()
  // call) and the employee only views/downloads it, so there's no prepare
  // route (uploadOnly keeps them out of GENERIC_DOCUMENT_WORKFLOW_TYPES).
  {
    id: 'LCA',
    requestTitle: 'LCA',
    label: 'LCA',
    prepareLabel: 'Upload LCA',
    category: 'IMMIGRATION',
    uploadOnly: true,
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  {
    id: 'PERM',
    requestTitle: 'PERM',
    label: 'PERM',
    prepareLabel: 'Upload PERM',
    category: 'IMMIGRATION',
    uploadOnly: true,
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  {
    id: 'I140',
    requestTitle: 'I-140',
    label: 'I-140',
    prepareLabel: 'Upload I-140',
    category: 'IMMIGRATION',
    uploadOnly: true,
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  {
    id: 'EMPLOYMENT_LETTER',
    requestTitle: 'Employment Letter',
    label: 'Employment Letter',
    prepareLabel: 'Prepare Employment Letter',
    routeSegment: 'employment-letter',
    category: 'EMPLOYMENT_LETTERS',
    // Same category as Employment Verification - no signature required.
    // Working default; revisit if product direction changes.
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
  {
    id: 'I983',
    requestTitle: 'Signed I-983',
    label: 'Signed I-983',
    prepareLabel: 'Prepare Signed I-983',
    routeSegment: 'signed-i983',
    category: 'IMMIGRATION',
    // Distinct from the separate HR -> Employee "upload your existing I-983"
    // request (RequestDocumentsForm.jsx's Immigration-Related category) -
    // this is the employee asking HR to prepare/provide one to sign.
    requiresEmployeeSignature: true,
    requiresSignedReturn: true,
  },
  {
    id: 'OTHER',
    requestTitle: 'Other Employment Document',
    label: 'Other Employment Document',
    prepareLabel: 'Prepare Document',
    routeSegment: 'other-employment-document',
    category: 'OTHER',
    // Not fixed per type - HR decides per request on the prepare page
    // (see DocumentPreparationPage's OTHER-only signature checkboxes).
    signatureConfigurable: true,
    requiresEmployeeSignature: false,
    requiresSignedReturn: false,
  },
];

// Every type except ones with their own dedicated page (only Offer Letter
// today) or that are upload-only (no preparation step) - these are the ones
// AppRouter wires up to the generic DocumentPreparationPage.
export const GENERIC_DOCUMENT_WORKFLOW_TYPES = DOCUMENT_WORKFLOW_TYPES.filter((type) => !type.hasDedicatedPage && !type.uploadOnly);

export function matchesRequestTitle(type, requestTitle) {
  return type.requestTitle === requestTitle || Boolean(type.legacyRequestTitles?.includes(requestTitle));
}

export function getDocumentWorkflowType(request) {
  if (!request) return null;
  // Offer letters have a persisted backend type. Their display title may be
  // customized, so the signing workflow must never depend on title wording.
  if (request.documentType === 'OFFER_LETTER') {
    return DOCUMENT_WORKFLOW_TYPES.find((item) => item.id === 'OFFER_LETTER') || null;
  }
  const type = DOCUMENT_WORKFLOW_TYPES.find((item) => matchesRequestTitle(item, request.requestTitle));
  // A display title can be edited or duplicated. Never treat a non-offer
  // request as an offer letter merely because it uses that display title.
  if (type?.id === 'OFFER_LETTER' && request.documentType !== 'OFFER_LETTER') return null;
  return type || null;
}
