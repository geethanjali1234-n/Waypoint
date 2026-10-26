import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, afterEach } from 'vitest';
import RouteLine from '../../components/global/RouteLine';
import FilterPills from '../../components/global/FilterPills';
import StatusBadge from '../../components/global/StatusBadge';
import NeedsYouList from '../../components/global/NeedsYouList';
import DataTable from '../../components/global/DataTable';
import PageHeader from '../../components/layout/PageHeader';
import DetailPanel from '../../components/layout/DetailPanel';
import FloatingAssistant from '../../components/chatbot/FloatingAssistant';

afterEach(cleanup);
describe('Route-line primitives', () => {
  it('announces the current stop and explicit state labels in both orientations', () => {
    const steps = [{ label: 'Requested', state: 'done' }, { label: 'Review', state: 'current' }, { label: 'Filed', state: 'upcoming' }];
    const { rerender } = render(<RouteLine steps={steps} />);
    expect(screen.getByRole('list')).toBeInTheDocument();
    expect(screen.getByText('Review').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.getByText('Upcoming')).toBeInTheDocument();
    rerender(<RouteLine steps={steps} orientation="vertical" />);
    expect(screen.getByText('Review').closest('li')).toHaveAttribute('aria-current', 'step');
  });
  it('keeps filters controlled and uses only supplied counts', () => {
    const change = vi.fn();
    render(<FilterPills options={[{ value: 'all', label: 'All', count: 7 }, { value: 'pending', label: 'Pending' }, { value: 'locked', label: 'Locked', disabled: true }]} value="all" onChange={change} />);
    expect(screen.getByRole('button', { name: /All/ })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Pending' }));
    expect(change).toHaveBeenCalledWith('pending');
    expect(screen.getByRole('button', { name: 'Pending' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Locked' })).toBeDisabled();
    expect(screen.getByText('7')).toBeInTheDocument();
  });
  it.each(['active', 'pending', 'approved', 'rejected', 'inactive', 'draft'])('labels %s without relying on color', (status) => {
    render(<StatusBadge status={status} />);
    expect(screen.getByText(new RegExp(status, 'i'))).toBeInTheDocument();
  });
  it('omits zero-count actions and links to the supplied filtered destination', () => {
    render(<MemoryRouter><NeedsYouList items={[{ id: 'empty', title: 'Nothing pending', count: 0 }, { id: 'review', title: 'Awaiting review', count: 2, to: '/myhr/admin/timesheets?status=pending' }]} /></MemoryRouter>);
    expect(screen.queryByText('Nothing pending')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/myhr/admin/timesheets?status=pending');
  });
  it('preserves legacy page header props and supports the new summary', () => {
    const { rerender } = render(<PageHeader title="Records" subtitle="Legacy subtitle" action={<button>Refresh</button>} />);
    expect(screen.getByText('Legacy subtitle')).toBeInTheDocument();
    rerender(<PageHeader eyebrow="People" title="Records" summary="Current summary" primaryAction={<button>Add</button>} />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Records');
    expect(screen.getByText('People')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });
  it('provides an accessible assistant trigger', () => {
    const click = vi.fn();
    render(<FloatingAssistant onClick={click} controls="chat" />);
    fireEvent.click(screen.getByRole('button', { name: 'Open Waypoint Assistant' }));
    expect(click).toHaveBeenCalledOnce();
    expect(screen.getByRole('button')).toHaveAttribute('aria-controls', 'chat');
  });
  it('announces whether the assistant is expanded', () => {
    const { rerender } = render(<FloatingAssistant controls="chat" />);
    expect(screen.getByRole('button')).toHaveAttribute('aria-expanded', 'false');
    rerender(<FloatingAssistant controls="chat" expanded />);
    expect(screen.getByRole('button')).toHaveAttribute('aria-expanded', 'true');
  });
  it('orders actions by priority without creating counts', () => {
    render(<NeedsYouList items={[{ id: 'later', title: 'Later', priority: 3 }, { id: 'first', title: 'First', priority: 1 }]} />);
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('First');
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });
});

describe('Controlled DataTable', () => {
  const columns = [{ key: 'name', header: 'Name', sortable: true }, { key: 'status', header: 'Status', type: 'status' }];
  const rows = [{ id: 'a', name: 'Test person A', status: 'pending' }, { id: 'b', name: 'Test person B', status: 'approved' }];
  it('selects this page without discarding selections on other pages', () => {
    const change = vi.fn();
    render(<DataTable columns={columns} rows={rows} selectedRowIds={['off-page', 'a']} onSelectionChange={change} />);
    expect(screen.getByRole('checkbox', { name: 'Select this page' })).toBePartiallyChecked();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select this page' }));
    expect(change).toHaveBeenCalledWith(['off-page', 'a', 'b']);
  });
  it('deselects only selectable rows on the current page', () => {
    const change = vi.fn();
    render(<DataTable columns={columns} rows={rows} selectedRowIds={['off-page', 'a', 'b']} onSelectionChange={change} />);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select this page' }));
    expect(change).toHaveBeenCalledWith(['off-page']);
  });
  it('requests sorting without reordering supplied rows', () => {
    const sort = vi.fn();
    render(<DataTable columns={columns} rows={rows} sort={{ key: 'name', direction: 'asc' }} onSort={sort} />);
    expect(screen.getByRole('columnheader', { name: 'Name' })).toHaveAttribute('aria-sort', 'ascending');
    fireEvent.click(screen.getByRole('button', { name: 'Name' }));
    expect(sort).toHaveBeenCalledWith({ key: 'name', direction: 'desc' });
  });
  it('uses supplied totals and sends bounded pagination requests', () => {
    const page = vi.fn();
    render(<DataTable columns={columns} rows={rows} pagination={{ page: 2, pageSize: 2, total: 5, onPageChange: page }} />);
    expect(screen.getByText('Showing 3–4 of 5')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(page).toHaveBeenCalledWith(3);
  });
  it('opens records through a real button and distinguishes loading from empty', () => {
    const open = vi.fn();
    const { rerender } = render(<DataTable columns={columns} rows={rows} onOpenRow={open} />);
    fireEvent.click(screen.getByRole('button', { name: 'View Test person A' }));
    expect(open).toHaveBeenCalledWith(rows[0]);
    rerender(<DataTable columns={columns} loading />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading records');
    rerender(<DataTable columns={columns} />);
    expect(screen.getByText('No records to show.')).toBeInTheDocument();
  });
  it('respects row selection restrictions and pagination boundaries', () => {
    const change = vi.fn();
    render(<DataTable columns={columns} rows={rows} onSelectionChange={change} isRowSelectable={(row) => row.id === 'a'} pagination={{ page: 1, pageSize: 2, total: 2, onPageChange: vi.fn() }} />);
    expect(screen.getByRole('checkbox', { name: 'Select Test person B' })).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select this page' }));
    expect(change).toHaveBeenCalledWith(['a']);
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });
  it('shows a supplied person name and secondary employee ID', () => {
    render(<DataTable columns={[{ key: 'person', header: 'Person', type: 'person' }]} rows={[{ id: 'test', person: { name: 'Fixture person', id: 'TEST-001' } }]} />);
    expect(screen.getByText('Fixture person')).toBeInTheDocument();
    expect(screen.getByText('TEST-001')).toBeInTheDocument();
  });
});

describe('DetailPanel', () => {
  it('uses the full-screen presentation when requested', () => {
    render(<DetailPanel open fullScreen onClose={vi.fn()} title="Details" />);
    expect(screen.getByRole('dialog', { name: 'Details' })).toHaveClass('wp-detail-panel--full-screen');
  });
  it('returns keyboard focus inside when the panel itself receives focus', () => {
    render(<DetailPanel open onClose={vi.fn()} title="Details"><input aria-label="Note" /></DetailPanel>);
    const dialog = screen.getByRole('dialog', { name: 'Details' });
    dialog.focus();
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(screen.getByRole('textbox', { name: 'Note' })).toHaveFocus();
    dialog.focus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(screen.getByRole('button', { name: 'Close panel' })).toHaveFocus();
  });
  it('closes from the backdrop without overriding an existing inert attribute', () => {
    const background = document.createElement('div');
    background.setAttribute('inert', '');
    document.body.append(background);
    const close = vi.fn();
    const { unmount } = render(<DetailPanel open onClose={close} title="Details" />);
    fireEvent.click(screen.getByRole('button', { name: 'Close details' }));
    expect(close).toHaveBeenCalledOnce();
    unmount();
    expect(background).toHaveAttribute('inert');
    background.remove();
  });
  it('traps focus, closes on Escape, restores focus and body scrolling', () => {
    const close = vi.fn();
    const trigger = document.createElement('button');
    document.body.append(trigger);
    trigger.focus();
    const { rerender } = render(<DetailPanel open onClose={close} title="Details"><input aria-label="Note" /></DetailPanel>);
    expect(screen.getByRole('dialog', { name: 'Details' })).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('button', { name: 'Close panel' })).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'Tab', shiftKey: true });
    expect(screen.getByRole('textbox', { name: 'Note' })).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'Tab' });
    expect(screen.getByRole('button', { name: 'Close panel' })).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(close).toHaveBeenCalledOnce();
    rerender(<DetailPanel open={false} onClose={close} title="Details" />);
    expect(trigger).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
    expect(trigger).not.toHaveAttribute('inert');
    trigger.remove();
  });
});

describe('DataTable row opening', () => {
  it('opens a row from its non-interactive cells and keyboard, but not its selection checkbox', () => {
    const open = vi.fn();
    const select = vi.fn();
    render(<DataTable label="Employees" columns={[{ key: 'name', header: 'Name' }]} rows={[{ id: 'employee-1', name: 'Alex Doe' }]} selectedRowIds={[]} onSelectionChange={select} onOpenRow={open} rowOpenable />);

    const row = screen.getByLabelText('Open Alex Doe');
    fireEvent.click(screen.getByText('Alex Doe'));
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ id: 'employee-1' }));
    fireEvent.keyDown(row, { key: 'Enter' });
    expect(open).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Alex Doe' }));
    expect(open).toHaveBeenCalledTimes(2);
    expect(select).toHaveBeenCalledWith(['employee-1']);
  });
});
