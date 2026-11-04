import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { EntryWorkspace, ReeditConfirmationModal } from '../../../pages/myhr/features/timesheets/TimesheetsPage';

import { requiresTimesheetChangeReason } from '../../../pages/myhr/features/timesheets/timesheetChangeReason';

const entries = [{ workDate: '2026-11-02', hoursWorked: 8, dayOfWeek: 'MONDAY', entryType: 'WORK', description: '' }];
const filed = { status: 'SUBMITTED', entries };

it('requires a reason for overlapping filed hours or an explicit correction', () => {
  expect(requiresTimesheetChangeReason(filed, entries)).toBe(true);
  expect(requiresTimesheetChangeReason({ ...filed, status: 'APPROVED' }, entries)).toBe(true);
  expect(requiresTimesheetChangeReason({ ...filed, status: 'REJECTED' }, entries)).toBe(true);
  expect(requiresTimesheetChangeReason(null, [], true)).toBe(true);
});

it('does not require a correction reason for drafts, new dates, or unfiled hours', () => {
  expect(requiresTimesheetChangeReason(null, entries)).toBe(false);
  expect(requiresTimesheetChangeReason({ ...filed, status: 'DRAFT' }, entries)).toBe(false);
  expect(requiresTimesheetChangeReason(filed, [{ ...entries[0], workDate: '2026-11-03' }])).toBe(false);
  expect(requiresTimesheetChangeReason({ ...filed, entries: [{ ...entries[0], hoursWorked: 0 }] }, entries)).toBe(false);
});

function Review({ needsReason = true, onSubmit = vi.fn(), initialReason = '', approvedCorrection = false }) {
  const [reason, setReason] = useState(initialReason);
  return <EntryWorkspace step="review" today="2026-11-04" rangeStart="2026-11-02" rangeEnd="2026-11-02"
    rangeDays={['2026-11-02']} rangeHours={{ '2026-11-02': 8 }} rangeDescriptions={{}} rangeEntryTypes={{}}
    rangeEntries={entries} reviewTotal={8} supportingDocuments={[]} managerDisplayName="Reporting manager"
    requiresChangeReason={needsReason} reeditSubmitted={false} approvedCorrection={approvedCorrection} changeReason={reason} setChangeReason={setReason}
    onSubmit={() => onSubmit(reason)} />;
}

it('lets a user enter a correction reason on Review without entering re-edit mode', async () => {
  const onSubmit = vi.fn();
  render(<Review onSubmit={onSubmit} />);
  const field = screen.getByRole('textbox', { name: 'Reason for change' });
  expect(field).toBeRequired();
  expect(field).toHaveAccessibleDescription(/previously submitted hours/);
  await userEvent.setup().type(field, 'Corrected Monday hours after manager review');
  await userEvent.setup().click(screen.getByRole('button', { name: 'Submit timesheet' }));
  expect(onSubmit).toHaveBeenCalledWith('Corrected Monday hours after manager review');
});

it('preserves the reason entered earlier when showing Review', () => {
  render(<Review initialReason="Updated shift" />);
  expect(screen.getByRole('textbox', { name: 'Reason for change' })).toHaveValue('Updated shift');
});

it('does not show a mandatory correction field for a new submission', () => {
  render(<Review needsReason={false} />);
  expect(screen.queryByRole('textbox', { name: 'Reason for change' })).not.toBeInTheDocument();
});

it('keeps approved records read-only until correction is requested and confirmed', async () => {
  const confirm = vi.fn();
  const user = userEvent.setup();
  render(<ReeditConfirmationModal record={{ ...filed, id: 'approved-1', status: 'APPROVED', periodStartDate: '2026-11-02', periodEndDate: '2026-11-08', totalHours: 8 }} onConfirm={confirm} onClose={vi.fn()} />);
  expect(screen.getByRole('dialog', { name: /Already approved/ })).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Confirm re-edit' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Request correction' }));
  expect(confirm).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Confirm correction' }));
  expect(confirm).toHaveBeenCalledTimes(1);
});

it('offers HR review instead of Save draft when correcting approved hours', () => {
  render(<Review approvedCorrection />);
  expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Submit correction for review' })).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'Reason for change' })).toBeRequired();
});

it('locks approved dates even when the hours screen is opened directly', async () => {
  const request = vi.fn();
  const approved = { ...filed, id: 'approved-1', status: 'APPROVED' };
  render(<EntryWorkspace step="hours" rangeDays={['2026-11-02']} rangeHours={{}} rangeEntries={entries}
    approvedRecords={[approved]} onRequestCorrection={request} />);
  expect(screen.getByRole('heading', { name: 'Already approved' })).toBeInTheDocument();
  expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Submit timesheet' })).not.toBeInTheDocument();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Request correction' }));
  expect(request).toHaveBeenCalledWith(approved);
});
