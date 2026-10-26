import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import FileUpload from '../../components/form/FileUpload';

describe('FileUpload', () => {
  const originalCreateObjectURL = URL.createObjectURL;
  const originalRevokeObjectURL = URL.revokeObjectURL;

  afterEach(() => {
    URL.createObjectURL = originalCreateObjectURL;
    URL.revokeObjectURL = originalRevokeObjectURL;
    vi.restoreAllMocks();
  });

  it('keeps a long downloadable filename separate from the fixed file actions and downloads it on double-click', () => {
    const fileName = 'very-long-required-document-name-that-must-stay-inside-the-upload-card.pdf';
    const file = new File(['document'], fileName, { type: 'application/pdf' });
    const createObjectURL = vi.fn(() => 'blob:required-document');
    URL.createObjectURL = createObjectURL;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    render(<FileUpload name="required-document" fileName={fileName} downloadFile={file} onReplace={vi.fn()} onRemove={vi.fn()} />);

    const filename = screen.getByRole('button', { name: `Download ${fileName}` });
    expect(filename).toHaveClass('wp-file-upload__filename--downloadable');
    expect(filename).toHaveAttribute('title', `${fileName} — double-click to download`);
    expect(screen.getByText('Replace')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove file' })).toBeInTheDocument();

    fireEvent.doubleClick(filename);
    expect(createObjectURL).toHaveBeenCalledWith(file);
    expect(click).toHaveBeenCalled();
  });
});
