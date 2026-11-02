import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Blob as FileBlob } from 'node:buffer';
import documentApi from '../../../api/documentApi';
import { DocumentViewerModal } from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import DocumentTablePreview from '../../../pages/myhr/features/timesheets/components/DocumentTablePreview';
vi.mock('../../../api/documentApi', () => ({ default: { download: vi.fn() } }));
afterEach(() => vi.unstubAllGlobals());

describe('HTML spreadsheet preview', () => {
  it('detects an HTML workbook returned as an .xls download through the existing document API', async () => {
    vi.stubGlobal('Blob', FileBlob);
    documentApi.download.mockResolvedValue({ data: new FileBlob(['<!doctype html><html><body><table><tr><th>Hours</th></tr><tr><td>32.5</td></tr></table></body></html>'], { type: 'application/vnd.ms-excel' }) });
    render(<DocumentViewerModal doc={{ id: 'test-document', fileName: 'Payroll_Readiness.xls' }} onClose={vi.fn()} onError={vi.fn()} />);
    expect(await screen.findByRole('cell', { name: '32.5' })).toBeInTheDocument();
    expect(documentApi.download).toHaveBeenCalledWith('test-document');
    expect(screen.getByRole('button', { name: 'Download original' })).toBeEnabled();
  });
  it('renders exported spreadsheet content as readable cells instead of source code', () => {
    render(<DocumentTablePreview html={'<!doctype html><html><head><style>table {border-collapse:collapse}</style></head><body><h1>Payroll readiness</h1><table><tr><th>Employee</th><th>Hours</th></tr><tr><td>Jordan</td><td>40</td></tr></table></body></html>'} />);
    expect(screen.getByRole('columnheader', { name: 'Employee' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Jordan' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '40' })).toBeInTheDocument();
    expect(screen.queryByText(/doctype|border-collapse/)).not.toBeInTheDocument();
  });

  it('never inserts scripts, event handlers or remote resources from uploaded markup', () => {
    const { container } = render(<DocumentTablePreview html={'<table><tr><td><img src="https://untrusted.test/pixel" onerror="alert(1)">Safe label<script>alert(1)</script></td></tr></table><iframe src="https://untrusted.test"></iframe>'} />);
    expect(within(screen.getByRole('table')).getByText('Safe label')).toBeInTheDocument();
    expect(container.querySelector('script,iframe,img,[onerror]')).toBeNull();
  });

  it('explains when a document has no tabular preview', () => {
    render(<DocumentTablePreview html="<html><body>Unsupported document layout</body></html>" />);
    expect(screen.getByRole('status')).toHaveTextContent('Download the original');
  });
});
