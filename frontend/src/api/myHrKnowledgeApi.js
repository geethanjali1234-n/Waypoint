import apiClient from './apiClient';

const BASE = '/myhr/knowledge';
const documentPath = (id, action) => `${BASE}/documents/${encodeURIComponent(id)}/${action}`;

/**
 * The content-review decisions this client may send. EXCLUDE_FLAGGED_TEXT is deliberately absent:
 * the server cannot exclude individual passages yet, so the decision is never offered or sent.
 */
export const CONTENT_REVIEW_DECISIONS = Object.freeze(['INCLUDE_FLAGGED_TEXT', 'REPLACE_DOCUMENT', 'DO_NOT_APPROVE']);
const SHA256_HEX = /^[0-9a-f]{64}$/;

/**
 * HR and Company Admin knowledge base management. The backend derives the organization and role
 * from the authenticated session; no organization id is ever sent. Policy files are uploaded
 * unmodified (never compressed), because approval pins their exact content hash.
 */
const myHrKnowledgeApi = {
  getSettings: () => apiClient.get(`${BASE}/settings`),
  listDocuments: () => apiClient.get(`${BASE}/documents`),
  addPolicy: ({ file, title, accessLevel }) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('title', title);
    formData.append('accessLevel', accessLevel);
    return apiClient.post(`${BASE}/documents`, formData);
  },
  replacePolicy: (id, { file, accessLevel }) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('accessLevel', accessLevel);
    return apiClient.post(documentPath(id, 'replacement'), formData);
  },
  // Re-approves a source from this document's server-provided version history. It never reuses chunks.
  restoreHistoricalVersion: (id, expectedHistoricalSourceDocumentId) =>
    apiClient.post(documentPath(id, 'restore-version'), { expectedHistoricalSourceDocumentId }),
  acceptIncompleteText: (id, expectedVersionId) =>
    apiClient.post(documentPath(id, 'accept-incomplete-text'), { expectedVersionId }),
  /** The exact pending text that would be indexed, with its findings and fingerprints. Contains policy text. */
  previewPendingContent: (id) => apiClient.get(documentPath(id, 'pending/extracted-text')),
  /**
   * Records a decision bound to the preview the reviewer saw. Refuses, without a request, any
   * unsupported decision or a missing or malformed source id or fingerprint.
   */
  reviewContent: (id, { expectedSourceDocumentId, findingsFingerprint, indexableTextFingerprint, decision }) => {
    if (!CONTENT_REVIEW_DECISIONS.includes(decision)
        || typeof expectedSourceDocumentId !== 'string' || !expectedSourceDocumentId
        || !SHA256_HEX.test(findingsFingerprint || '') || !SHA256_HEX.test(indexableTextFingerprint || '')) {
      return Promise.reject(new Error('The content review could not be sent: reopen the preview and try again.'));
    }
    return apiClient.post(documentPath(id, 'content-review'),
      { expectedSourceDocumentId, findingsFingerprint, indexableTextFingerprint, decision });
  },
  ingest: (id) => apiClient.post(documentPath(id, 'ingest')),
  activate: (id, expectedVersionId) => apiClient.post(documentPath(id, 'activate'), { expectedVersionId }),
  revoke: (id) => apiClient.post(documentPath(id, 'revoke')),
};

export default myHrKnowledgeApi;
