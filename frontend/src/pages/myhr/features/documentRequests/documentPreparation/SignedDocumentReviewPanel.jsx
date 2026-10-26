import { useState } from 'react';
import { Button, Modal, StatusTag } from '../../../../../components';
import { formatDateOnly } from '../../../../../utils/dateFormat';
import PreviewBlocks from './PreviewBlocks';

export default function SignedDocumentReviewPanel({ request, workflowType, workflowState, onComplete, onViewOriginal }) {
  const [previewOpen, setPreviewOpen] = useState(false);

  // Offer Letter's prepare page uploads a real generated PDF via the
  // backend fulfill() call - prefer that real document over the frontend
  // -only previewBlocks snapshot whenever one exists.
  const viewOriginal = onViewOriginal || (() => setPreviewOpen(true));

  return <div className="document-requests__fulfill document-requests__returned">
    <article>
      <div><h3>{workflowType.label} - Signature Response Submitted</h3>
        <p>{request.requestedByName} submitted a signed document{request.signatureSubmittedAt ? ` on ${formatDateOnly(request.signatureSubmittedAt)}` : ''}. Review the complete document before completing the request.</p>
        <StatusTag status="Signature Response Submitted" tone="info" />
      </div>
      <div>
        <Button type="button" variant="secondary" onClick={viewOriginal}>View Signed Document</Button>
        <Button type="button" onClick={onComplete}>Complete Process</Button>
      </div>
    </article>
    <Modal isOpen={previewOpen} title={`${workflowType.label} preview`} onClose={() => setPreviewOpen(false)}
      footer={<Button type="button" variant="secondary" onClick={() => setPreviewOpen(false)}>Close</Button>}>
      <div className="offer-letter__preview" aria-label={`${workflowType.label} preview`}>
        <PreviewBlocks blocks={workflowState.previewBlocks} />
      </div>
    </Modal>
  </div>;
}
