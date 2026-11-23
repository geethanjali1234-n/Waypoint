import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from '../../api/apiClient';
import myHrKnowledgeApi, { CONTENT_REVIEW_DECISIONS } from '../../api/myHrKnowledgeApi';

vi.mock('../../api/apiClient');

const FINDINGS = 'a'.repeat(64);
const TEXT = 'b'.repeat(64);
const review = (overrides = {}) => ({
  expectedSourceDocumentId: 'stored-1', findingsFingerprint: FINDINGS, indexableTextFingerprint: TEXT,
  decision: 'INCLUDE_FLAGGED_TEXT', ...overrides,
});

describe('myHrKnowledgeApi content review', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.post.mockResolvedValue({ data: { success: true } });
  });

  it('loads the pending preview from the documented endpoint', () => {
    myHrKnowledgeApi.previewPendingContent('kd/1');
    expect(apiClient.get).toHaveBeenCalledWith('/myhr/knowledge/documents/kd%2F1/pending/extracted-text');
  });

  it('restores only a server-provided historical source id', async () => {
    await myHrKnowledgeApi.restoreHistoricalVersion('kd/1', 'stored-old');
    expect(apiClient.post).toHaveBeenCalledWith('/myhr/knowledge/documents/kd%2F1/restore-version', {
      expectedHistoricalSourceDocumentId: 'stored-old',
    });
  });

  it('sends exactly the bound decision fields and nothing else', async () => {
    await myHrKnowledgeApi.reviewContent('kd-1', { ...review(), excludedPassageIds: ['instruction-0'], extra: 'x' });
    expect(apiClient.post).toHaveBeenCalledWith('/myhr/knowledge/documents/kd-1/content-review', review());
  });

  it('offers only the supported decisions, never exclusion', () => {
    expect(CONTENT_REVIEW_DECISIONS).toEqual(['INCLUDE_FLAGGED_TEXT', 'REPLACE_DOCUMENT', 'DO_NOT_APPROVE']);
  });

  it.each([
    ['an exclusion decision', { decision: 'EXCLUDE_FLAGGED_TEXT' }],
    ['an unknown decision', { decision: 'APPROVE_EVERYTHING' }],
    ['a missing source id', { expectedSourceDocumentId: '' }],
    ['a missing findings fingerprint', { findingsFingerprint: undefined }],
    ['a malformed text fingerprint', { indexableTextFingerprint: 'B'.repeat(64) }],
  ])('refuses %s without sending a request', async (_, overrides) => {
    await expect(myHrKnowledgeApi.reviewContent('kd-1', review(overrides))).rejects.toThrow(/reopen the preview/);
    expect(apiClient.post).not.toHaveBeenCalled();
  });
});
