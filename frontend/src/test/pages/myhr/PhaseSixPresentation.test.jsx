import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DocumentRoute from '../../../pages/myhr/components/DocumentRoute';
import WorkspaceDataTable from '../../../pages/myhr/components/WorkspaceDataTable';
import { setWorkflowState } from '../../../pages/myhr/features/documentRequests/documentPreparation/documentWorkflowState';

describe('Phase six presentation adapters', () => {
  beforeEach(() => localStorage.clear());
  it('preserves the original API row for actions without manufacturing an ID', async () => {
    const row = { employeeName: 'Avery Chen', employeeId: 'WP42' };
    const action = vi.fn();
    render(<WorkspaceDataTable columns={[{ key: 'employee', header: 'Person' }, { key: 'employeeId', header: 'ID' }, { key: 'action', header: 'Action' }]} rows={[row]}
      renderCell={(column, original) => column.key === 'action' ? <button onClick={() => action(original)}>Review</button> : original.employeeName} />);
    expect(screen.queryByRole('columnheader', { name: 'ID' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('table')).getByText('WP42')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Review' }));
    expect(action.mock.calls[0][0]).toBe(row);
    expect(row).not.toHaveProperty('id');
  });

  it('omits the total footer when the endpoint supplies no total', () => {
    render(<WorkspaceDataTable showFooter={false} columns={[{ key: 'name', header: 'Name' }]} rows={[{ name: 'Avery' }]} />);
    expect(screen.queryByText(/Showing/)).not.toBeInTheDocument();
  });

  it('marks the actual next approval action as current and delivery as upcoming', () => {
    render(<DocumentRoute approvalStatus="PENDING_APPROVAL" />);
    const stops = screen.getAllByRole('listitem');
    expect(stops[1]).toHaveAttribute('aria-current', 'step');
    expect(stops[1]).toHaveTextContent('Review and approve');
    expect(stops[2]).toHaveTextContent('Upcoming');
    expect(screen.queryByText('Filed')).not.toBeInTheDocument();
  });

  it('does not invent approval when a directly sent document is recorded', () => {
    render(<DocumentRoute sent />);
    expect(screen.getByText('Sent to employee')).toBeInTheDocument();
    expect(screen.queryByText('Approved')).not.toBeInTheDocument();
    expect(screen.getAllByRole('listitem').every((stop) => !stop.hasAttribute('aria-current'))).toBe(true);
  });

  it('omits an unknown workflow instead of inventing stops', () => {
    const { container } = render(<DocumentRoute request={{ status: 'UNKNOWN' }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('keeps HR preparation on the approval route instead of suggesting direct delivery', () => {
    render(<DocumentRoute preparationStep={2} needsApproval />);
    const current = screen.getAllByRole('listitem').find((stop) => stop.getAttribute('aria-current') === 'step');
    expect(current).toHaveTextContent('Preview and submit for approval');
    expect(screen.queryByText('Preview and send')).not.toBeInTheDocument();
  });

  it('shows a signed return as awaiting review rather than filed', () => {
    setWorkflowState('phase-six-return', { stage: 'SIGNED_RETURNED', requiresEmployeeSignature: true });
    render(<DocumentRoute request={{ id: 'phase-six-return', documentType: 'EXPERIENCE_LETTER', status: 'FULFILLED', signedResponseDocumentId: 'signed-response' }} />);
    const current = screen.getAllByRole('listitem').find((stop) => stop.getAttribute('aria-current') === 'step');
    expect(current).toHaveTextContent('Review signed return');
    expect(screen.getByText('Filed').closest('li')).toHaveTextContent('Upcoming');
  });
});
