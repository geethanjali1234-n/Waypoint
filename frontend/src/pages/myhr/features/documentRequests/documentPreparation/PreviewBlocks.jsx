/**
 * Renders the plain-text/list preview blocks HR's prepare pages snapshot
 * into the workflow-state overlay at send time (see documentWorkflowState.js
 * and DOCUMENT_TYPE_CONFIGS' preview() builders). Shared by the employee
 * sign page and HR's signed-document review panel so both show the exact
 * same "what HR sent" content instead of re-implementing it.
 */
export default function PreviewBlocks({ blocks }) {
  if (!blocks || !blocks.length) return <p className="offer-letter__preview-signature-placeholder">No preview is available for this document.</p>;
  return <>{blocks.map((block, index) => block.type === 'list'
    ? <ul key={index}>{block.items.map((item, itemIndex) => <li key={itemIndex}>{item}</li>)}</ul>
    : <p key={index}>{block.text}</p>)}</>;
}
