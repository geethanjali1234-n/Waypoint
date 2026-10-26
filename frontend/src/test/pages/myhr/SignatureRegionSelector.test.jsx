import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SignatureRegionSelector from '../../../pages/myhr/features/SignatureRegionSelector';

describe('SignatureRegionSelector', () => {
  it('shows the uploaded photo under React StrictMode, where a blob: URL revoked on unmount used to leave it broken', async () => {
    const photo = new File(['photo-bytes'], 'signature.png', { type: 'image/png' });
    render(<StrictMode><SignatureRegionSelector file={photo} onSelect={vi.fn()} onCancel={vi.fn()} /></StrictMode>);

    const image = await screen.findByAltText('Uploaded signature source');
    expect(image.getAttribute('src')).toMatch(/^data:image\/png;base64,/);
    expect(screen.getByRole('button', { name: 'Use Selection' })).toBeDisabled();
  });
});
