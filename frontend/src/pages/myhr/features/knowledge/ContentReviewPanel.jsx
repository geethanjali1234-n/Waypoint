import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, ConfirmationDialog, ValidationMessage } from '../../../../components';
import myHrKnowledgeApi from '../../../../api/myHrKnowledgeApi';

/** The server refuses larger previews; anything larger is never treated as reviewable here either. */
const MAX_PREVIEW_TEXT_CHARS = 100_000;
const SHA256_HEX = /^[0-9a-f]{64}$/;

const CATEGORY_LABELS = {
  HIDDEN_TEXT: 'Hidden Word text',
  SUSPICIOUS_FORMATTING: 'Text formatted so it may be invisible',
  INVISIBLE_UNICODE: 'Invisible characters',
  HIDDEN_COMMENT: 'Hidden comments',
  INSTRUCTION_LIKE: 'Instruction-like wording',
  OCR_REVIEW: 'Pages without text',
};

const DECISIONS = {
  INCLUDE_FLAGGED_TEXT: {
    title: 'Include flagged text?',
    confirmLabel: 'Include',
    message: 'The extracted text shown, including every flagged passage, will be added to the knowledge base the '
      + 'assistant answers from once it is ingested and activated. Excluded hidden content stays out.',
  },
  REPLACE_DOCUMENT: {
    title: 'Reject this version and replace it?',
    confirmLabel: 'Reject and replace',
    message: 'This pending version will be rejected permanently and never indexed. You can then approve a '
      + 'corrected file with Replace file.',
  },
  DO_NOT_APPROVE: {
    title: 'Reject this version?',
    confirmLabel: 'Reject',
    message: 'This pending version will be rejected permanently and never indexed. The current active version, if '
      + 'any, keeps answering questions.',
  },
};

/** Server messages are fixed, safe strings; anything else is replaced by the fallback. */
function safeMessage(error, fallback) {
  const message = error?.response?.data?.message;
  return typeof message === 'string' && message.trim() ? message : fallback;
}

/** A preview is used only if it is complete, well formed and for the pending version the list shows. */
function verifiedPreview(data, pendingSourceDocumentId) {
  if (!data || typeof data !== 'object') return null;
  const { sourceDocumentId, findingsFingerprint, indexableTextFingerprint, indexableText, passages } = data;
  if (typeof sourceDocumentId !== 'string' || !pendingSourceDocumentId || sourceDocumentId !== pendingSourceDocumentId
      || !SHA256_HEX.test(findingsFingerprint || '') || !SHA256_HEX.test(indexableTextFingerprint || '')
      || typeof indexableText !== 'string' || !Array.isArray(passages) || data.contentReviewRequired !== true) {
    return null;
  }
  return data;
}

/** Instruction-like passages whose offsets point at exactly their reported text, in order and non-overlapping. */
function highlightRanges(text, passages) {
  const ranges = passages
    .filter((passage) => passage && passage.category === 'INSTRUCTION_LIKE' && !passage.excludedFromIndex
      && Number.isInteger(passage.startOffset) && Number.isInteger(passage.endOffset)
      && passage.startOffset >= 0 && passage.endOffset > passage.startOffset && passage.endOffset <= text.length
      && text.slice(passage.startOffset, passage.endOffset) === passage.text)
    .sort((a, b) => a.startOffset - b.startOffset);
  const accepted = [];
  for (const range of ranges) {
    if (!accepted.length || range.startOffset >= accepted[accepted.length - 1].endOffset) accepted.push(range);
  }
  return accepted;
}

/** Plain text only: React escapes every segment, and flagged ranges are wrapped in <mark>. */
function ExtractedText({ text, passages }) {
  const segments = [];
  let position = 0;
  highlightRanges(text, passages).forEach((range, index) => {
    if (range.startOffset > position) segments.push(text.slice(position, range.startOffset));
    segments.push(<mark key={`flag-${index}`} className="knowledge-review__flag" title="Instruction-like wording">
      {text.slice(range.startOffset, range.endOffset)}</mark>);
    position = range.endOffset;
  });
  if (position < text.length) segments.push(text.slice(position));
  return <pre className="knowledge-review__text" aria-label="Extracted text that would be indexed" tabIndex={0}>{segments}</pre>;
}

/** Findings from the server-side inspection counts, split by whether the content is indexed. */
function findingLines(review) {
  if (!review) return { excluded: [], indexed: [], other: [] };
  const otherInvisible = Math.max(0, review.invisibleUnicodeCharacters - review.unicodeTagCharactersRemoved);
  const line = (count, singular, plural) => (count > 0 ? `${count} ${count === 1 ? singular : plural}` : null);
  return {
    excluded: [
      line(review.hiddenDocxRunsExcluded, 'hidden Word passage', 'hidden Word passages'),
      line(review.hiddenCommentBlocks, 'hidden comment', 'hidden comments'),
      line(review.unicodeTagCharactersRemoved, 'invisible tag character', 'invisible tag characters'),
    ].filter(Boolean),
    indexed: [
      line(review.suspiciousTextFragments, 'text fragment formatted so it may be invisible (for example white or very small text)',
        'text fragments formatted so they may be invisible (for example white or very small text)'),
      line(review.instructionLikeContentMatches, 'instruction-like phrase', 'instruction-like phrases'),
      line(otherInvisible, 'other invisible formatting character', 'other invisible formatting characters'),
    ].filter(Boolean),
    other: [line(review.pagesRequiringOcrReview, 'page without text', 'pages without text')].filter(Boolean),
  };
}

/**
 * Review of a pending version flagged by content inspection. The preview is loaded fresh each time
 * the panel opens; its source id and fingerprints, never values from the list, bind the decision.
 * After a failed decision the preview is discarded, so a stale preview can never be submitted.
 */
export default function ContentReviewPanel({ document, busy, onDecision, onClose }) {
  const pending = document.pendingVersion;
  const pendingSourceDocumentId = pending?.sourceDocumentId;
  const [state, setState] = useState({ status: 'loading', preview: null, error: '' });
  const [acknowledged, setAcknowledged] = useState(false);
  const [confirming, setConfirming] = useState(null);
  const submitting = useRef(false);

  const load = useCallback(async () => {
    setState({ status: 'loading', preview: null, error: '' });
    setAcknowledged(false);
    try {
      const response = await myHrKnowledgeApi.previewPendingContent(document.id);
      const preview = verifiedPreview(response?.data?.data, pendingSourceDocumentId);
      if (!preview) {
        setState({ status: 'error', preview: null,
          error: 'The preview does not match the pending version. Reload the knowledge base and try again.' });
      } else if (preview.indexableText.length > MAX_PREVIEW_TEXT_CHARS) {
        setState({ status: 'tooLarge', preview: null, error: '' });
      } else {
        setState({ status: 'ready', preview, error: '' });
      }
    } catch (error) {
      if (error?.response?.status === 401) return;
      const message = safeMessage(error, 'The extracted text could not be loaded for review.');
      setState({ status: /too large/i.test(message) ? 'tooLarge' : 'error', preview: null, error: message });
    }
  }, [document.id, pendingSourceDocumentId]);

  useEffect(() => { load(); }, [load]);

  const submit = async (decision) => {
    setConfirming(null);
    const { preview } = state;
    if (!preview || submitting.current) return;
    submitting.current = true;
    try {
      const succeeded = await onDecision(decision, {
        expectedSourceDocumentId: preview.sourceDocumentId,
        findingsFingerprint: preview.findingsFingerprint,
        indexableTextFingerprint: preview.indexableTextFingerprint,
        decision,
      });
      if (!succeeded) {
        // The version may have changed; never reuse these fingerprints.
        setState({ status: 'stale', preview: null, error: '' });
      }
    } finally {
      submitting.current = false;
    }
  };

  const findings = findingLines(pending?.contentReview);
  const { preview } = state;
  const excludedPassages = preview ? preview.passages.filter((passage) => passage?.excludedFromIndex) : [];
  const flaggedPassages = preview ? preview.passages.filter((passage) => passage && !passage.excludedFromIndex) : [];
  const locked = busy || state.status !== 'ready';

  return <section className="knowledge-review" aria-label={`Content review for ${document.title}`}>
    <div className="knowledge-review__header">
      <h4>Review extracted content</h4>
      <Button variant="secondary" onClick={onClose} disabled={busy}>Close review</Button>
    </div>
    <p className="knowledge-review__source">
      <span className="knowledge-review__title">{document.title}</span>
      {pending?.fileName && <span> · {pending.fileName}</span>}
      {Number.isInteger(pending?.pageCount) && pending.pageCount > 0 && <span> · {pending.pageCount} pages</span>}
    </p>

    <div className="knowledge-review__findings">
      {findings.excluded.length > 0 && <div>
        <p className="knowledge-review__group-label">Removed — will not be indexed</p>
        <ul>{findings.excluded.map((text) => <li key={text}>{text}</li>)}</ul>
      </div>}
      {findings.indexed.length > 0 && <div>
        <p className="knowledge-review__group-label">Flagged — still in the text that would be indexed</p>
        <ul>{findings.indexed.map((text) => <li key={text}>{text}</li>)}</ul>
      </div>}
      {findings.other.length > 0 && <div>
        <p className="knowledge-review__group-label">Needs a separate review</p>
        <ul>{findings.other.map((text) => <li key={text}>{text}</li>)}</ul>
      </div>}
    </div>

    <p className="knowledge-base__hint">Read the extracted text below. It is exactly what would be indexed for the assistant.
      Approve it only if every flagged passage is legitimate company policy; otherwise reject this version.</p>

    {state.status === 'loading' && <p className="knowledge-base__hint" role="status">Loading extracted text…</p>}
    {state.status === 'error' && <div className="knowledge-review__notice">
      <ValidationMessage variant="error" message={state.error} />
      <Button variant="secondary" onClick={load} disabled={busy}>Try again</Button>
    </div>}
    {state.status === 'stale' && <div className="knowledge-review__notice">
      <ValidationMessage variant="error"
        message="The review was not recorded and this preview may be out of date. Load the preview again before deciding." />
      <Button variant="secondary" onClick={load} disabled={busy}>Load preview again</Button>
    </div>}
    {state.status === 'tooLarge' && <ValidationMessage variant="error"
      message={`This document's extracted text is too large to review securely here (more than ${MAX_PREVIEW_TEXT_CHARS.toLocaleString('en-US')} characters), so it cannot be approved. Use Replace file to approve a smaller document, such as one split into separate policies.`} />}

    {state.status === 'ready' && <>
      {preview.incompleteTextReviewRequired && <ValidationMessage variant="hint"
        message="This PDF also has pages without text. After this review, use Accept incomplete text before the document can be ingested." />}
      {excludedPassages.length > 0 && <div className="knowledge-review__passages">
        <p className="knowledge-review__group-label">Not indexed</p>
        <ul>{excludedPassages.map((passage) => <li key={passage.id}>
          {CATEGORY_LABELS[passage.category] || 'Excluded content'}
          {typeof passage.sourceLocation === 'string' && <span className="knowledge-review__location"> ({passage.sourceLocation})</span>}
          {' '}— excluded; its content is not shown and will not be indexed.
        </li>)}</ul>
      </div>}
      {flaggedPassages.length > 0 && <div className="knowledge-review__passages">
        <p className="knowledge-review__group-label">Flagged in the indexable text</p>
        <ul>{flaggedPassages.map((passage) => <li key={passage.id}>
          {CATEGORY_LABELS[passage.category] || 'Flagged content'}
          {passage.category === 'SUSPICIOUS_FORMATTING'
            ? ' — applies to the whole extracted text below.'
            : typeof passage.text === 'string' && <>: <q className="knowledge-review__quote">{passage.text}</q></>}
        </li>)}</ul>
      </div>}
      <ExtractedText text={preview.indexableText} passages={preview.passages} />
      <label className="knowledge-review__acknowledge">
        <input type="checkbox" checked={acknowledged} disabled={locked}
          onChange={(event) => setAcknowledged(event.target.checked)} />
        <span>I have read the extracted text and every flagged passage is legitimate company policy.</span>
      </label>
    </>}

    <div className="knowledge-base__actions" role="group" aria-label="Review decisions">
      <Button disabled={locked || !acknowledged} loading={busy}
        onClick={() => setConfirming('INCLUDE_FLAGGED_TEXT')}>Include flagged text</Button>
      <Button variant="secondary" disabled={locked} onClick={() => setConfirming('REPLACE_DOCUMENT')}>Reject and replace</Button>
      <Button variant="secondary" disabled={locked} onClick={() => setConfirming('DO_NOT_APPROVE')}>Do not approve</Button>
      <Button variant="secondary" disabled aria-describedby={`exclude-unavailable-${document.id}`}>
        Exclude flagged text (not available yet)</Button>
    </div>
    <p id={`exclude-unavailable-${document.id}`} className="knowledge-base__hint">
      Excluding only the flagged passages is not available yet, because individual passages cannot be removed precisely.
      To leave flagged content out, reject this version and replace the file.</p>

    <ConfirmationDialog isOpen={Boolean(confirming)} title={DECISIONS[confirming]?.title}
      message={DECISIONS[confirming]?.message} confirmLabel={DECISIONS[confirming]?.confirmLabel}
      onConfirm={() => submit(confirming)} onCancel={() => setConfirming(null)} />
  </section>;
}
