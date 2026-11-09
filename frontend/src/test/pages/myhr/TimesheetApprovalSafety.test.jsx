import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { TimesheetDetails } from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import { isRecordCleanAndAuditReady } from '../../../pages/myhr/features/timesheets/timesheetPeriodRules';
import { approveTimesheetBatch } from '../../../pages/myhr/features/timesheets/timesheetApprovalBatch';

const record = { id:'sheet', status:'SUBMITTED', employeeName:'Test Employee', periodStartDate:'2026-08-31', periodEndDate:'2026-08-31',
  totalHours:8, entries:[{workDate:'2026-08-31',hoursWorked:8}], supportingDocuments:[{id:'doc', fileName:'hours.pdf',identityValidationStatus:'UNVERIFIED'}] };

it.each(['UNVERIFIED','MISMATCH',undefined])('excludes %s documents from express approval', status => {
  expect(isRecordCleanAndAuditReady({...record,supportingDocuments:[{id:'doc',identityValidationStatus:status}]})).toBe(false);
});
it('excludes missing attachments and permits standard-hour name-matched attachments', () => {
  expect(isRecordCleanAndAuditReady({...record,supportingDocuments:[]})).toBe(false);
  expect(isRecordCleanAndAuditReady({...record,supportingDocuments:[{id:'doc',identityValidationStatus:'MATCHED'}]})).toBe(true);
});
it('counts only confirmed approvals and continues after failures', async () => {
  const review = vi.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false).mockRejectedValueOnce(new Error('Unavailable')).mockResolvedValueOnce(undefined);
  const progress = vi.fn();
  expect(await approveTimesheetBatch([record,record,record,record],review,progress)).toEqual({approved:1,failed:3});
  expect(progress).toHaveBeenLastCalledWith({done:4,total:4});
});
it('offers correction from approved details only when an owner correction handler is supplied',()=>{
  const correction=vi.fn();
  const {rerender}=render(<TimesheetDetails record={{...record,status:'APPROVED'}} onClose={vi.fn()} onRequestCorrection={correction} />);
  fireEvent.click(screen.getByRole('button',{name:'Request correction'}));
  expect(correction).toHaveBeenCalledWith(expect.objectContaining({id:'sheet',status:'APPROVED'}));
  rerender(<TimesheetDetails record={{...record,status:'APPROVED'}} onClose={vi.fn()} />);
  expect(screen.queryByRole('button',{name:'Request correction'})).not.toBeInTheDocument();
});
it('requires a manual review checkbox and note for an unverified attachment', () => {
  const review=vi.fn();
  render(<TimesheetDetails record={record} canReview onReview={review} onClose={vi.fn()} onReject={vi.fn()} />);
  const approve=screen.getByRole('button',{name:'Approve'});
  expect(approve).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed the supporting document/}));
  expect(approve).toBeDisabled();
  fireEvent.change(screen.getByRole('textbox',{name:/Review note/}),{target:{value:'Checked name, dates and hours with manager approval'}});
  expect(approve).toBeEnabled();
  fireEvent.click(approve);
  expect(review).toHaveBeenCalledWith('APPROVED','Checked name, dates and hours with manager approval',true);
});
it.each([{docs:[]},{docs:[{id:'doc',identityValidationStatus:'MISMATCH'}]}])('blocks approval of missing or mismatched attachments while allowing rejection', ({docs}) => {
  render(<TimesheetDetails record={{...record,supportingDocuments:docs}} canReview onReview={vi.fn()} onClose={vi.fn()} onReject={vi.fn()} />);
  expect(screen.getByRole('button',{name:'Approve'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Reject'})).toBeEnabled();
});
