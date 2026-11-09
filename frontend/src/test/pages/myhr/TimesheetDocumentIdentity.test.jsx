import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { SupportingDocumentList } from '../../../pages/myhr/features/timesheets/TimesheetsPage';

it('marks legacy attachments as unverified instead of implying a match', () => {
  render(<SupportingDocumentList documents={[{ id: 'doc', fileName: 'hours.pdf' }]} />);
  expect(screen.getByText('Employee name not verified — HR review needed')).toBeInTheDocument();
});

it('shows the server identity result and preserves document removal', () => {
  const onRemove = vi.fn();
  const document = { id: 'doc', fileName: 'hours.pdf', identityValidationStatus: 'MATCHED' };
  render(<SupportingDocumentList documents={[document]} onRemove={onRemove} />);
  expect(screen.getByText('Employee name matches')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Remove hours.pdf' }));
  expect(onRemove).toHaveBeenCalledWith(document);
});

it('shows a mismatch in a previously attached document to reviewers', () => {
  render(<SupportingDocumentList documents={[{ id: 'doc', fileName: 'hours.pdf', identityValidationStatus: 'MISMATCH' }]} />);
  expect(screen.getByText('Employee name does not match')).toBeInTheDocument();
});
