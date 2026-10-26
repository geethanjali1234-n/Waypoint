import apiClient from './apiClient';
import compressFileForUpload, { compressFilesForUpload } from '../utils/compressFileForUpload';

// The backend takes I-983 signatures as a list of { page, x, y, width, height }
// placements; a single placement is wrapped into that list.
const toPlacements = (signature) => (Array.isArray(signature) ? signature : [signature]);
const appendSignatures = (body, signature) => {
  if (signature) body.append('signatures', new Blob([JSON.stringify(toPlacements(signature))], { type: 'application/json' }));
};

const myHrDocumentRequestApi = {
  list: (params = {}) => apiClient.get('/myhr/document-requests', { params }),
  get: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}`),
  requestableEmployees: () => apiClient.get('/myhr/document-requests/requestable-employees'),
  create: (payload) => apiClient.post('/myhr/document-requests', payload),
  createSelfService: async (payload, files = []) => {
    const body = new FormData();
    body.append('request', new Blob([JSON.stringify(payload)], { type: 'application/json' }));
    (await compressFilesForUpload(files)).forEach((file) => body.append('files', file));
    return apiClient.post('/myhr/document-requests/employee', body);
  },
  fulfill: async (requestId, file, email = null) => {
    const body = new FormData();
    body.append('file', await compressFileForUpload(file));
    if (email) body.append('email', new Blob([JSON.stringify(email)], { type: 'application/json' }));
    return apiClient.post(`/myhr/document-requests/${requestId}/fulfill`, body);
  },
  getFulfillmentEmailDraft: (requestId, { recipientEmail } = {}) => apiClient.get(
    `/myhr/document-requests/${requestId}/fulfillment-email-draft`, { params: { recipientEmail } },
  ),
  resendFulfillmentEmail: (requestId, email = null) => apiClient.post(
    `/myhr/document-requests/${requestId}/fulfillment-email`, email,
  ),
  submitSignedResponse: async (requestId, { signature, signedDocument }) => {
    const body = new FormData();
    if (signature) body.append('signature', await compressFileForUpload(signature));
    if (signedDocument) body.append('signedDocument', await compressFileForUpload(signedDocument));
    return apiClient.post(`/myhr/document-requests/${requestId}/signed-response`, body);
  },
  reject: (requestId, reason) => apiClient.post(`/myhr/document-requests/${requestId}/reject`, { reason }),
  getDocument: (requestId, documentId, download = false) => apiClient.get(
    `/myhr/document-requests/${requestId}/documents/${documentId}`,
    { params: { download }, responseType: 'blob' },
  ),
  getOfferLetter: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}/offer-letter`),
  saveOfferLetter: (requestId, payload) => apiClient.put(`/myhr/document-requests/${requestId}/offer-letter`, payload),
  generateOfferLetter: (requestId, payload) => apiClient.post(`/myhr/document-requests/${requestId}/offer-letter/generate`, payload),
  sendOfferLetter: (requestId) => apiClient.post(`/myhr/document-requests/${requestId}/offer-letter/send`),
  getOfferLetterEmailDraft: (requestId, { designation, employeeName } = {}) => apiClient.get(
    `/myhr/document-requests/${requestId}/offer-letter/email-draft`,
    { params: { designation, employeeName } },
  ),
  // Offer Letter Admin approval. HR submits the generated PDF + facts; only a Company Admin can approve or reject;
  // HR can deliver/send only once it is approved (all enforced by the server).
  submitOfferLetterForApproval: async (requestId, details, file) => {
    const body = new FormData();
    body.append('details', new Blob([JSON.stringify(details)], { type: 'application/json' }));
    body.append('file', await compressFileForUpload(file));
    return apiClient.post(`/myhr/document-requests/${requestId}/offer-letter/approval/submit`, body);
  },
  getOfferLetterApproval: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}/offer-letter/approval`),
  deliverOfferLetter: (requestId) => apiClient.post(`/myhr/document-requests/${requestId}/offer-letter/approval/deliver`),
  // The same approval for every other document HR delivers (the offer-letter calls above reach the same endpoints).
  submitDocumentForApproval: async (requestId, details, file) => {
    const body = new FormData();
    body.append('details', new Blob([JSON.stringify(details)], { type: 'application/json' }));
    body.append('file', await compressFileForUpload(file));
    return apiClient.post(`/myhr/document-requests/${requestId}/approval/submit`, body);
  },
  getDocumentApproval: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}/approval`),
  // Delivers the exact Admin-approved document and its HR-reviewed email.
  deliverApprovedDocument: (requestId, email = null) => apiClient.post(
    `/myhr/document-requests/${requestId}/approval/deliver`, email,
  ),
  listOfferLetterApprovals: () => apiClient.get('/myhr/offer-letter-approvals'),
  approveOfferLetter: (requestId, comment = '') => apiClient.post(`/myhr/offer-letter-approvals/${requestId}/approve`,
    comment.trim() ? { comment: comment.trim() } : null),
  rejectOfferLetter: (requestId, reason) => apiClient.post(`/myhr/offer-letter-approvals/${requestId}/reject`, { reason }),
  emailOfferLetter: (requestId, { recipientEmail, subject, message, employeeName }) => apiClient.post(
    `/myhr/document-requests/${requestId}/offer-letter/email`,
    { recipientEmail, subject, message, employeeName },
  ),

  // Signed I-983 workflow (Immigration & Visa Support).
  // signature (optional): where the employee placed their Profile signature - { page, x, y, width, height }.
  submitI983: async (payload, file, signature = null) => {
    const body = new FormData();
    body.append('request', new Blob([JSON.stringify(payload)], { type: 'application/json' }));
    body.append('file', await compressFileForUpload(file));
    appendSignatures(body, signature);
    return apiClient.post('/myhr/document-requests/i983', body);
  },
  getI983: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}/i983`),
  resubmitI983: async (requestId, file, signature = null) => {
    const body = new FormData();
    if (file) body.append('file', await compressFileForUpload(file));
    appendSignatures(body, signature);
    return apiClient.post(`/myhr/document-requests/${requestId}/i983/resubmit`, body);
  },
  getI983Document: (requestId, documentId, download = false) => apiClient.get(
    `/myhr/document-requests/${requestId}/i983/documents/${documentId}`,
    { params: { download }, responseType: 'blob' },
  ),
  replaceI983: async (requestId, file) => {
    const body = new FormData();
    body.append('file', await compressFileForUpload(file));
    return apiClient.post(`/myhr/document-requests/${requestId}/i983/replace`, body);
  },
  getI983Signature: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}/i983/signature`),
  getI983SignatureImage: (requestId) => apiClient.get(`/myhr/document-requests/${requestId}/i983/signature/image`, { responseType: 'blob' }),
  signI983: (requestId, { documentId, placements }) => apiClient.post(
    `/myhr/document-requests/${requestId}/i983/sign`,
    { documentId, placements: toPlacements(placements) },
  ),
  approveI983: (requestId) => apiClient.post(`/myhr/document-requests/${requestId}/i983/approve`),
  returnI983: (requestId, reason) => apiClient.post(`/myhr/document-requests/${requestId}/i983/return`, { reason }),
  rejectI983: (requestId, reason) => apiClient.post(`/myhr/document-requests/${requestId}/i983/reject`, { reason }),
};

export default myHrDocumentRequestApi;
