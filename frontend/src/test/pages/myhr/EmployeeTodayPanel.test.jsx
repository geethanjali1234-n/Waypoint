import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import EmployeeTodayPanel from '../../../pages/myhr/components/EmployeeTodayPanel';
import timesheetApi from '../../../api/timesheetApi';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';

vi.mock('../../../api/timesheetApi');
vi.mock('../../../api/myHrProfileChangeApi');
vi.mock('../../../api/myHrDocumentRequestApi');

const key = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
function LocationState() { return <output data-testid="entry-state">{JSON.stringify(useLocation().state)}</output>; }
describe('Employee daily overview', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-10-12T12:00:00'));
    vi.resetAllMocks();
    myHrProfileChangeApi.listMine.mockResolvedValue({ data: { data: [{ fieldName: 'ADDRESS', status: 'PENDING' }] } });
    myHrDocumentRequestApi.list.mockResolvedValue({ data: { data: { items: [{ id: 'doc-request', requestTitle: 'Training plan', documentType: 'I983', originalDocumentId: 'original', status: 'SUBMITTED' }] } } });
  });
  afterEach(() => vi.useRealTimers());
  it('highlights only today and preserves the standard-week handoff without saving hours', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    render(<MemoryRouter><EmployeeTodayPanel basePath="/myhr/employee" /><LocationState /></MemoryRouter>);
    await screen.findByText(/0 of 5 days in/);
    const week = screen.getByRole('list', { name: 'Monday to Friday progress' });
    expect(within(week).getByText('Mon, Oct 12').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(week.querySelectorAll('[aria-current="step"]')).toHaveLength(1);
    fireEvent.click(screen.getByRole('link', { name: 'Log standard week' }));
    expect(screen.getByTestId('entry-state')).toHaveTextContent('"timesheetEntryMethod":"standard"');
  });
  it('does not invent a weekday today marker on weekends', async () => {
    vi.setSystemTime(new Date('2026-10-17T12:00:00'));
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    render(<MemoryRouter><EmployeeTodayPanel basePath="/myhr/employee" /></MemoryRouter>);
    await screen.findByText(/0 of 5 days in/);
    const week = screen.getByRole('list', { name: 'Monday to Friday progress' });
    expect(week.querySelector('[aria-current="step"]')).toBeNull();
    expect(within(week).queryByText('Not logged')).not.toBeInTheDocument();
  });
  it('counts unique weekdays from real entries and links to the employee’s own requests', async () => {
    const monday = new Date();
    monday.setDate(monday.getDate() - (monday.getDay() + 6) % 7);
    const tuesday = new Date(monday); tuesday.setDate(monday.getDate() + 1);
    timesheetApi.list.mockResolvedValue({ data: { data: [
      { updatedAt: '2026-10-10', entries: [{ workDate: key(monday), hoursWorked: 0 }, { workDate: key(monday), hoursWorked: 8 }, { workDate: key(tuesday), hoursWorked: 4 }] },
      { updatedAt: '2026-10-09', entries: [{ workDate: key(monday), hoursWorked: 8 }] },
    ] } });
    render(<MemoryRouter><EmployeeTodayPanel name="Lilly" basePath="/myhr/employee" /></MemoryRouter>);
    expect(await screen.findByText(/2 of 5 days in/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View signing progress' })).toHaveAttribute('href', '/myhr/employee/document-requests/doc-request');
    expect(screen.getByRole('heading', { name: 'Address update' })).toBeInTheDocument();
    const week = screen.getByRole('region', { name: 'Your timesheet this week' });
    expect(within(week).getAllByText('Hours recorded')).toHaveLength(2);
    expect(myHrDocumentRequestApi.list).toHaveBeenCalledWith({ workspace: 'mine', page: 0, size: 5 });
  });
  it('shows unavailable states instead of fabricated progress when requests fail', async () => {
    timesheetApi.list.mockRejectedValue(new Error('offline'));
    myHrProfileChangeApi.listMine.mockRejectedValue(new Error('offline'));
    myHrDocumentRequestApi.list.mockRejectedValue(new Error('offline'));
    render(<MemoryRouter><EmployeeTodayPanel basePath="/myhr/employee" /></MemoryRouter>);
    expect(await screen.findAllByText(/Unable to load. Try Refresh./)).toHaveLength(3);
    expect(screen.queryByText(/0 of 5 days/)).not.toBeInTheDocument();
  });
  it('prompts for an employee signature only when the document is ready to sign', async () => {
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    myHrDocumentRequestApi.list.mockResolvedValue({ data: { data: { items: [{ id: 'sign-due', requestTitle: 'Training plan', documentType: 'I983', status: 'FULFILLED', fulfilledDocumentId: 'draft' }] } } });
    myHrProfileChangeApi.listMine.mockResolvedValue({ data: { data: [{ fieldName: 'PERSONAL_EMAIL', status: 'APPROVED', processedAt: '2026-10-01T12:00:00' }] } });
    render(<MemoryRouter><EmployeeTodayPanel name="Lilly" basePath="/myhr/employee" /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: 'Review and sign' })).toHaveAttribute('href', '/myhr/employee/document-requests/sign-due');
    const progress = screen.getByRole('list', { name: 'Document progress' });
    expect(within(progress).getByText('Review and sign').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(screen.getByText('Thu, Oct 1')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Email update' }).closest('section')).toHaveClass('employee-today__card--past');
    expect(screen.getByText(/0 of 5 days in/)).toBeInTheDocument();
    const week = screen.getByRole('region', { name: 'Your timesheet this week' });
    expect(within(week).getAllByText('-')).toHaveLength(4);
    expect(within(week).getAllByText('Not logged')).toHaveLength(1);
    expect(screen.getByText(/Your week/)).toHaveTextContent('Mon, Oct 12');
  });

});
