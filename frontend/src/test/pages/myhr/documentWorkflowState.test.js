import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearWorkflowState,
  getSignedDocumentBlob,
  getWorkflowState,
  setSignedDocumentBlob,
  setWorkflowState,
} from '../../../pages/myhr/features/documentRequests/documentPreparation/documentWorkflowState';

describe('documentWorkflowState (Phase 1 localStorage overlay)', () => {
  beforeEach(() => localStorage.clear());

  it('returns null for a request with no state yet', () => {
    expect(getWorkflowState('req-1')).toBeNull();
  });

  it('sets and merges state per requestId without clobbering other requests', () => {
    setWorkflowState('req-1', { stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true });
    setWorkflowState('req-2', { stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: false });
    setWorkflowState('req-1', { submittedAt: '2026-01-01T00:00:00.000Z' });

    expect(getWorkflowState('req-1')).toEqual({ stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: true, submittedAt: '2026-01-01T00:00:00.000Z' });
    expect(getWorkflowState('req-2')).toEqual({ stage: 'SENT_TO_EMPLOYEE', requiresEmployeeSignature: false });
  });

  it('clears state for one request only', () => {
    setWorkflowState('req-1', { stage: 'SENT_TO_EMPLOYEE' });
    setWorkflowState('req-2', { stage: 'SENT_TO_EMPLOYEE' });
    clearWorkflowState('req-1');
    expect(getWorkflowState('req-1')).toBeNull();
    expect(getWorkflowState('req-2')).not.toBeNull();
  });

  it('tolerates corrupted localStorage content instead of throwing', () => {
    localStorage.setItem('wp:myhr:documentWorkflowState:v1', 'not json');
    expect(() => getWorkflowState('req-1')).not.toThrow();
    expect(getWorkflowState('req-1')).toBeNull();
    expect(() => setWorkflowState('req-1', { stage: 'SENT_TO_EMPLOYEE' })).not.toThrow();
  });

  it('keeps the signed-document blob in memory, separate per request', () => {
    const file = new File(['x'], 'signed.pdf', { type: 'application/pdf' });
    expect(getSignedDocumentBlob('req-1')).toBeNull();
    setSignedDocumentBlob('req-1', file);
    expect(getSignedDocumentBlob('req-1')).toBe(file);
    expect(getSignedDocumentBlob('req-2')).toBeNull();
  });
});
