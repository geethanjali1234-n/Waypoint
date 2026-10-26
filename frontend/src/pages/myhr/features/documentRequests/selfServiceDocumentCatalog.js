/**
 * Employee-request-facing catalog for the self-service "ask HR to prepare a
 * document" workflow (Employee -> HR direction). Deliberately separate from
 * documentWorkflowTypes.js (which drives HR's prepare/send pages) even
 * though both key off the same type id, so this file can stay focused on
 * what the employee sees while requesting: which category a type belongs
 * to, what happens next, and exactly what information/documents are needed
 * to process the request - without overloading the HR-prep registry with
 * request-form concerns.
 *
 * This is the single source of truth for the flat self-service type list;
 * EmployeeDocumentRequestForm.jsx re-exports SELF_SERVICE_DOCUMENT_TYPES
 * from here for backward compatibility.
 *
 * Every SELF_SERVICE_TYPE_CONFIG entry is intentionally data, not per-page
 * logic, so EmployeeDocumentRequestForm.jsx can render one generic
 * "Requirements" section for any type. requiredDocuments/optionalDocuments
 * are [] for every type except OFFER_LETTER, whose six mandatory onboarding
 * documents (MANDATORY_ONBOARDING_DOCUMENTS) are the one real, named
 * requirement established for this workflow - the employee requests an
 * Offer Letter and attaches them as prerequisites, they are not a document
 * request of their own. For every other type, nothing in the existing
 * WayPoint implementation (or product decisions so far) names a specific
 * supporting document - that's a real gap, not an oversight, and it is NOT
 * invented here. Where a real product requirement plausibly exists but
 * isn't established anywhere in this codebase (the 4 immigration types),
 * that's called out in a comment instead of guessed at. requiresAttachment
 * stays false for every type except OFFER_LETTER.
 */
export const SELF_SERVICE_DOCUMENT_TYPES = [
  ['OFFER_LETTER', 'Offer Letter'],
  ['EMPLOYMENT_VERIFICATION', 'Employment Verification'],
  ['EMPLOYMENT_LETTER', 'Employment Letter'],
  ['EXPERIENCE_LETTER', 'Experience and Relieving Letter'],
  ['SALARY_VERIFICATION', 'Salary Verification Letter'],
  ['LCA', 'LCA'],
  ['PERM', 'PERM'],
  ['I140', 'I-140'],
  ['I983', 'Signed I-983'],
  ['OTHER', 'Other Employment Document'],
];

// "Pre-Employment / Onboarding & Offer" holds only Offer Letter - there is
// no separate employee-facing "Pre-Employment / Onboarding Documents" type.
// The six onboarding documents (MANDATORY_ONBOARDING_DOCUMENTS below) are
// prerequisites/attachments of the Offer Letter request itself, not a
// document the employee requests on their own. Do not reintroduce a second
// type or category for them.
export const SELF_SERVICE_CATEGORIES = [
  { id: 'PRE_EMPLOYMENT_ONBOARDING_OFFER', label: 'Pre-Employment / Onboarding & Offer', typeIds: ['OFFER_LETTER'] },
  { id: 'EMPLOYMENT_LETTERS', label: 'Employment Verification & Letters', typeIds: ['EMPLOYMENT_VERIFICATION', 'EMPLOYMENT_LETTER', 'SALARY_VERIFICATION'] },
  { id: 'SEPARATION', label: 'Separation Documents', typeIds: ['EXPERIENCE_LETTER'] },
  { id: 'IMMIGRATION', label: 'Immigration & Visa Support', typeIds: ['LCA', 'PERM', 'I140', 'I983'] },
  { id: 'OTHER', label: 'Other', typeIds: ['OTHER'] },
];

// The employee's own pre-employment/onboarding identity & work-authorization
// documents - required prerequisites the employee must attach to their
// Offer Letter request so HR can process it (see OFFER_LETTER's
// mandatoryDocumentUploads below). Mirrors, by name, the Admin/HR
// "Pre-Employment / Onboarding" category already used in
// RequestDocumentsForm.jsx's DOCUMENT_CATEGORIES for the opposite
// (HR -> Employee) direction - the two stay separate data structures, this
// is naming parity only.
export const MANDATORY_ONBOARDING_DOCUMENTS = [
  { id: 'RESUME', label: 'Resume' },
  { id: 'WORK_AUTHORIZATION_COPY', label: 'Work Authorization Copy' },
  { id: 'PASSPORT_COPY', label: 'Passport Copy (Complete Pages)' },
  { id: 'I94', label: 'I-94' },
  { id: 'DRIVERS_LICENSE_COPY', label: "Driver's License (DL) Copy" },
  { id: 'SSN_COPY', label: 'SSN Copy' },
];

// The only "required information" collected from the employee at request
// time today, for every type - see documentTypeConfigs.js for the richer
// per-type fields HR fills in later, during preparation, which are not
// collected from the employee at request time in the current
// implementation.
const BASELINE_REQUIRED_INFORMATION = ['Employee information (auto-filled)', 'Reason for request'];

const NO_SIGNATURE_INSTRUCTIONS = 'HR will prepare and send this document. No signature is required from you.';
const UPLOAD_ONLY_INSTRUCTIONS = 'Your HR or company admin will upload this document to your request. You can view and download it once it is available. No signature is required from you.';
const OTHER_INSTRUCTIONS = 'Describe the document you need below. HR will determine whether a signature is required and let you know.';

// Shared defaults for every type: no supporting documents are established
// anywhere in the codebase for any self-service type, so none are invented
// here (see file-level comment). Individual entries only override the
// fields that differ.
const base = (overrides) => ({
  requiredInformation: BASELINE_REQUIRED_INFORMATION,
  requiredDocuments: [],
  optionalDocuments: [],
  requiresAttachment: false,
  maxAttachments: 10, // matches the real backend limit (DocumentRequestService: at most 10 attachments)
  hrPreparationRequired: true,
  templateAvailable: true,
  ...overrides,
});

export const SELF_SERVICE_TYPE_CONFIG = {
  OFFER_LETTER: base({
    documentType: 'OFFER_LETTER', category: 'PRE_EMPLOYMENT_ONBOARDING_OFFER', description: 'Offer Letter',
    requiredDocuments: MANDATORY_ONBOARDING_DOCUMENTS.map((doc) => doc.label),
    requiresAttachment: true,
    mandatoryDocumentUploads: MANDATORY_ONBOARDING_DOCUMENTS,
    requiresEmployeeSignature: true, requiresSignedReturn: true,
  }),
  EMPLOYMENT_VERIFICATION: base({
    documentType: 'EMPLOYMENT_VERIFICATION', category: 'EMPLOYMENT_LETTERS', description: 'Employment Verification',
    employeeInstructions: NO_SIGNATURE_INSTRUCTIONS,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  EMPLOYMENT_LETTER: base({
    documentType: 'EMPLOYMENT_LETTER', category: 'EMPLOYMENT_LETTERS', description: 'Employment Letter',
    employeeInstructions: NO_SIGNATURE_INSTRUCTIONS,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  EXPERIENCE_LETTER: base({
    documentType: 'EXPERIENCE_LETTER', category: 'SEPARATION', description: 'Experience and Relieving Letter',
    employeeInstructions: NO_SIGNATURE_INSTRUCTIONS,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  SALARY_VERIFICATION: base({
    documentType: 'SALARY_VERIFICATION', category: 'EMPLOYMENT_LETTERS', description: 'Salary Verification Letter',
    employeeInstructions: NO_SIGNATURE_INSTRUCTIONS,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  // Immigration-related types: a real deployment may eventually need a
  // specific supporting document (e.g. a visa/EAD copy) here, but nothing
  // in this codebase establishes one today - left at [] rather than guessed.
  // LCA / PERM / I-140 are upload-only: HR or Admin uploads the existing
  // document, nothing is prepared from a template.
  LCA: base({
    documentType: 'LCA', category: 'IMMIGRATION', description: 'LCA',
    employeeInstructions: UPLOAD_ONLY_INSTRUCTIONS,
    hrPreparationRequired: false, templateAvailable: false,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  PERM: base({
    documentType: 'PERM', category: 'IMMIGRATION', description: 'PERM',
    employeeInstructions: UPLOAD_ONLY_INSTRUCTIONS,
    hrPreparationRequired: false, templateAvailable: false,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  I140: base({
    documentType: 'I140', category: 'IMMIGRATION', description: 'I-140',
    employeeInstructions: UPLOAD_ONLY_INSTRUCTIONS,
    hrPreparationRequired: false, templateAvailable: false,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  // Signed I-983: the reverse of the other types - the employee uploads
  // their own completed I-983 and HR/Admin applies the organization's
  // Authorized Signature (see I983ReviewPanel / I983SigningPage). Nothing
  // is prepared by HR and the employee signs nothing in WayPoint.
  I983: base({
    documentType: 'I983', category: 'IMMIGRATION', description: 'Signed I-983',
    employeeInstructions: 'Upload your completed Form I-983 as a PDF. HR/Admin will review it and apply the organization\'s authorized signature. Once it is approved you can view and download the signed I-983 here.',
    requiredDocuments: ['Completed Form I-983 (PDF)'],
    requiresAttachment: true,
    employeeDocumentUpload: { id: 'I983', label: 'I-983 Document', accept: 'application/pdf,.pdf' },
    hrPreparationRequired: false, templateAvailable: false,
    requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
  // Not fixed at the catalog level - HR decides sign/return per request on
  // the prepare page (see DocumentPreparationPage's OTHER-only checkboxes).
  OTHER: base({
    documentType: 'OTHER', category: 'OTHER', description: 'Other Employment Document',
    employeeInstructions: OTHER_INSTRUCTIONS,
    requiredInformation: [...BASELINE_REQUIRED_INFORMATION, "Employee's own description of the document needed"],
    signatureConfigurable: true, requiresEmployeeSignature: false, requiresSignedReturn: false,
  }),
};

export function getSelfServiceCategoryForType(typeId) {
  return SELF_SERVICE_CATEGORIES.find((category) => category.typeIds.includes(typeId)) || null;
}
