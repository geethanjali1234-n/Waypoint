import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import I983DocumentPreview from '../../../pages/myhr/features/documentRequests/i983/I983DocumentPreview';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';

vi.mock('../../../api/myHrDocumentRequestApi');
beforeEach(() => { vi.resetAllMocks(); URL.createObjectURL = vi.fn(() => 'blob:preview'); URL.revokeObjectURL = vi.fn(); });
it('loads the authorized document on demand and releases its preview URL on unmount', async () => {
  myHrDocumentRequestApi.getI983Document.mockResolvedValue({ data: new Blob(['%PDF'], { type: 'application/pdf' }) });
  const user = userEvent.setup();
  const { unmount } = render(<I983DocumentPreview requestId="request" documentId="document" />);
  expect(myHrDocumentRequestApi.getI983Document).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Load document preview' }));
  expect(await screen.findByTitle('I-983 document preview')).toHaveAttribute('src', 'blob:preview');
  expect(myHrDocumentRequestApi.getI983Document).toHaveBeenCalledWith('request', 'document', false);
  unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview');
});
it('keeps a failed preview retryable', async () => {
  myHrDocumentRequestApi.getI983Document.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: new Blob(['%PDF']) });
  const user = userEvent.setup();
  render(<I983DocumentPreview requestId="request" documentId="document" />);
  await user.click(screen.getByRole('button', { name: 'Load document preview' }));
  await user.click(await screen.findByRole('button', { name: 'Retry preview' }));
  await waitFor(() => expect(screen.getByTitle('I-983 document preview')).toHaveAttribute('src', 'blob:preview'));
});
