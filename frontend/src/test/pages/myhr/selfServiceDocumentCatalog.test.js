import { describe, expect, it } from 'vitest';
import {
  MANDATORY_ONBOARDING_DOCUMENTS,
  SELF_SERVICE_CATEGORIES,
  SELF_SERVICE_DOCUMENT_TYPES,
  SELF_SERVICE_TYPE_CONFIG,
  getSelfServiceCategoryForType,
} from '../../../pages/myhr/features/documentRequests/selfServiceDocumentCatalog';

// Locks in the approved document-by-document requirement matrix as an
// executable spec, so a future edit that silently invents a required
// document or flips a signature flag is caught here.
const EXPECTED = {
  OFFER_LETTER: { requiresEmployeeSignature: true, requiresSignedReturn: true },
  EMPLOYMENT_VERIFICATION: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  EMPLOYMENT_LETTER: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  EXPERIENCE_LETTER: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  SALARY_VERIFICATION: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  LCA: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  PERM: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  I140: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  // The employee uploads the I-983; the organization signs it (no employee signature).
  I983: { requiresEmployeeSignature: false, requiresSignedReturn: false },
  OTHER: { requiresEmployeeSignature: false, requiresSignedReturn: false, signatureConfigurable: true },
};

// Every type except Offer Letter has no named required/optional documents
// and doesn't mandate an attachment - see file-level comment in
// selfServiceDocumentCatalog.js. Offer Letter's six mandatory onboarding
// documents are prerequisites of the Offer Letter request itself.
const TYPES_WITHOUT_NAMED_DOCUMENTS = Object.keys(SELF_SERVICE_TYPE_CONFIG).filter((id) => !['OFFER_LETTER', 'I983'].includes(id));

describe('selfServiceDocumentCatalog', () => {
  it('lists all 10 self-service document types - there is no separate Pre-Employment / Onboarding Documents type', () => {
    expect(SELF_SERVICE_DOCUMENT_TYPES).toHaveLength(10);
    expect(Object.keys(SELF_SERVICE_TYPE_CONFIG)).toHaveLength(10);
    expect(SELF_SERVICE_TYPE_CONFIG.PRE_EMPLOYMENT_ONBOARDING).toBeUndefined();
  });

  it.each(Object.entries(EXPECTED))('%s matches the approved sign/return matrix', (typeId, expected) => {
    const config = SELF_SERVICE_TYPE_CONFIG[typeId];
    expect(config.requiresEmployeeSignature).toBe(expected.requiresEmployeeSignature);
    expect(config.requiresSignedReturn).toBe(expected.requiresSignedReturn);
    if (expected.signatureConfigurable) expect(config.signatureConfigurable).toBe(true);
  });

  it('has no invented required/optional documents for any type except Offer Letter', () => {
    for (const typeId of TYPES_WITHOUT_NAMED_DOCUMENTS) {
      const config = SELF_SERVICE_TYPE_CONFIG[typeId];
      expect(config.requiredDocuments).toEqual([]);
      expect(config.optionalDocuments).toEqual([]);
      expect(config.requiresAttachment).toBe(false);
    }
  });

  it('Offer Letter requires exactly the six approved onboarding documents as prerequisites, in order', () => {
    const config = SELF_SERVICE_TYPE_CONFIG.OFFER_LETTER;
    expect(config.requiredDocuments).toEqual([
      'Resume', 'Work Authorization Copy', 'Passport Copy (Complete Pages)', 'I-94', "Driver's License (DL) Copy", 'SSN Copy',
    ]);
    expect(config.optionalDocuments).toEqual([]);
    expect(config.requiresAttachment).toBe(true);
    expect(config.mandatoryDocumentUploads).toEqual(MANDATORY_ONBOARDING_DOCUMENTS);
    expect(config.mandatoryDocumentUploads.map((doc) => doc.label)).toEqual(config.requiredDocuments);
    // HR still prepares and sends the Offer Letter itself - only the
    // supporting documents are new, the rest of the lifecycle is unchanged.
    expect(config.hrPreparationRequired).toBe(true);
    expect(config.templateAvailable).toBe(true);
  });

  it('collects only employee info and reason as required information for every type', () => {
    for (const [typeId, config] of Object.entries(SELF_SERVICE_TYPE_CONFIG)) {
      expect(config.requiredInformation).toContain('Employee information (auto-filled)');
      expect(config.requiredInformation).toContain('Reason for request');
      if (typeId !== 'OTHER') expect(config.requiredInformation).toHaveLength(2);
    }
  });

  it('Signed I-983 requires exactly one employee-uploaded PDF and no HR preparation', () => {
    const config = SELF_SERVICE_TYPE_CONFIG.I983;
    expect(config.requiredDocuments).toEqual(['Completed Form I-983 (PDF)']);
    expect(config.requiresAttachment).toBe(true);
    expect(config.employeeDocumentUpload).toMatchObject({ id: 'I983', accept: 'application/pdf,.pdf' });
    expect(config.hrPreparationRequired).toBe(false);
  });

  it('marks HR preparation and a prototype template as available for every HR-prepared type except upload-only ones', () => {
    const uploadOnly = ['LCA', 'PERM', 'I140'];
    for (const [typeId, config] of Object.entries(SELF_SERVICE_TYPE_CONFIG).filter(([, item]) => item.documentType !== 'I983')) {
      expect(config.hrPreparationRequired).toBe(!uploadOnly.includes(typeId));
      expect(config.templateAvailable).toBe(!uploadOnly.includes(typeId));
    }
  });

  it('caps attachments at the real backend limit', () => {
    for (const config of Object.values(SELF_SERVICE_TYPE_CONFIG)) {
      expect(config.maxAttachments).toBe(10);
    }
  });

  it('groups every type into exactly one category', () => {
    const grouped = SELF_SERVICE_CATEGORIES.flatMap((category) => category.typeIds);
    expect(new Set(grouped).size).toBe(grouped.length);
    expect(grouped.sort()).toEqual(SELF_SERVICE_DOCUMENT_TYPES.map(([id]) => id).sort());
  });

  it('Pre-Employment / Onboarding & Offer contains only Offer Letter', () => {
    const category = SELF_SERVICE_CATEGORIES.find((item) => item.id === 'PRE_EMPLOYMENT_ONBOARDING_OFFER');
    expect(category.label).toBe('Pre-Employment / Onboarding & Offer');
    expect(category.typeIds).toEqual(['OFFER_LETTER']);
  });

  it('getSelfServiceCategoryForType resolves Offer Letter to the merged category and returns null for an unknown type', () => {
    expect(getSelfServiceCategoryForType('OFFER_LETTER').id).toBe('PRE_EMPLOYMENT_ONBOARDING_OFFER');
    expect(getSelfServiceCategoryForType('PRE_EMPLOYMENT_ONBOARDING')).toBeNull();
    expect(getSelfServiceCategoryForType('NOT_REAL')).toBeNull();
  });
});
