import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import TimesheetsPage from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import timesheetApi from '../../../api/timesheetApi';
import documentApi from '../../../api/documentApi';

vi.mock('../../../pages/myhr/MyHrShell', () => ({ default: ({ children }) => <main>{children}</main> }));
vi.mock('../../../api/timesheetApi', () => ({ default: {
  list: vi.fn(), update: vi.fn(), create: vi.fn(),
  getSettings: vi.fn().mockResolvedValue({ data: { data: { payrollPeriod: 'MONTHLY' } } }),
} }));
vi.mock('../../../api/documentApi', () => ({ default: { upload: vi.fn(), parseTimesheet: vi.fn() } }));
vi.mock('../../../api/myHrEmployeeApi', () => ({ default: {
  getMine: vi.fn().mockResolvedValue({ data: { data: {} } }),
} }));
vi.mock('../../../api/myHrReportApi', () => ({ default: { searchEmployees: vi.fn().mockResolvedValue({ data: { data: [] } }) } }));
let storedDraft;
beforeEach(() => {
  vi.clearAllMocks();
  storedDraft = { id: 'draft-1', employeeUserId: 'employee-1', status: 'DRAFT', periodStartDate: '2026-08-31', periodEndDate: '2026-08-31',
    totalHours: 4, entries: [{ workDate: '2026-08-31', hoursWorked: 4, entryType: 'WORK', description: 'In progress' }],
    supportingDocuments: [{ id: 'doc-1', fileName: 'hours.pdf', identityValidationStatus: 'MATCHED' }] };
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: 'employee-1', role: 'EMPLOYEE' }));
  timesheetApi.list.mockImplementation(async () => ({ data: { data: [storedDraft] } }));
  timesheetApi.update.mockImplementation(async (id, payload) => {
    storedDraft = { ...storedDraft, ...payload, id, totalHours: payload.entries.reduce((sum, e) => sum + e.hoursWorked, 0) };
    return { data: { data: storedDraft } };
  });
  documentApi.upload.mockResolvedValue({ data: { data: { id: 'uploaded-doc', fileName: 'support.txt' } } });
});
afterEach(() => { localStorage.clear(); vi.useRealTimers(); });
function showPage() { return render(<MemoryRouter initialEntries={['/myhr/employee/timesheets']}><TimesheetsPage role="EMPLOYEE" /></MemoryRouter>); }
async function resume() {
  showPage();
  fireEvent.click(await screen.findByRole('button', { name: /Resume draft/ }));
  return screen.findByRole('spinbutton', { name: 'Hours' });
}

it('allows manual hours entry before attaching a document', async () => {
  showPage();
  fireEvent.click(await screen.findByRole('button', { name: /Start entry/ }));
  const enter = screen.getByRole('button', { name: 'Enter manually' });
  expect(enter).toBeEnabled();
  fireEvent.click(enter);
  const continueButton=screen.getByRole('button',{name:'Continue to hours'});
  expect(continueButton).toBeEnabled();
  fireEvent.click(continueButton);
  expect((await screen.findAllByRole('spinbutton',{name:'Hours'})).some(input=>!input.disabled)).toBe(true);
  expect(documentApi.upload).not.toHaveBeenCalled();
});

it('saves current hours without requiring Review and resumes the saved values', async () => {
  const hours = await resume();
  expect(hours).toHaveValue(4);
  fireEvent.change(hours, { target: { value: '6.5' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
  await waitFor(() => expect(timesheetApi.update).toHaveBeenCalledWith('draft-1', expect.objectContaining({ status: 'DRAFT',
    supportingDocumentIds: ['doc-1'], entries: [expect.objectContaining({ hoursWorked: 6.5, description: 'In progress' })] })));
  fireEvent.click(await screen.findByRole('button', { name: /Resume draft/ }));
  expect(await screen.findByRole('spinbutton', { name: 'Hours' })).toHaveValue(6.5);
});

it('logs and resumes a daily draft without a document, then requires attachment to submit',async()=>{
  const user=userEvent.setup();
  storedDraft.supportingDocuments=[];
  const hours=await resume();
  fireEvent.change(hours,{target:{value:'6'}});
  await user.click(screen.getByRole('button',{name:'Save draft'}));
  await waitFor(()=>expect(timesheetApi.update).toHaveBeenCalledWith('draft-1',expect.objectContaining({status:'DRAFT',supportingDocumentIds:[],entries:[expect.objectContaining({hoursWorked:6})]})));
  await user.click(await screen.findByRole('button',{name:/Resume draft/}));
  expect(await screen.findByRole('spinbutton',{name:'Hours'})).toHaveValue(6);
  await user.click(screen.getByRole('button',{name:'Review timesheet'}));
  const submit=await screen.findByRole('button',{name:'Submit timesheet'});
  expect(submit).toBeDisabled();
  fireEvent.click(submit);
  expect(timesheetApi.update).toHaveBeenCalledTimes(1);
  fireEvent.change(document.getElementById('supportingDocument'),{target:{files:[new File(['Employee Name: John Doe'],'support.txt',{type:'text/plain'})]}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Submit timesheet'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button',{name:'Submit timesheet'}));
  await waitFor(()=>expect(timesheetApi.update).toHaveBeenLastCalledWith('draft-1',expect.objectContaining({status:'SUBMITTED',supportingDocumentIds:['uploaded-doc']})));
});

it('offers Save draft and leave on Back and keeps edits when cancelled', async () => {
  storedDraft.supportingDocuments=[];
  const hours = await resume();
  fireEvent.change(hours, { target: { value: '7' } });
  fireEvent.click(screen.getByRole('button', { name: 'Back to overview' }));
  const dialog = screen.getByRole('dialog', { name: 'Save your timesheet progress?' });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Keep editing' }));
  expect(screen.getByRole('spinbutton', { name: 'Hours' })).toHaveValue(7);
  fireEvent.click(screen.getByRole('button', { name: 'Back to overview' }));
  fireEvent.click(screen.getByRole('button', { name: 'Save draft and leave' }));
  await screen.findByRole('region', { name: 'Saved drafts' });
  expect(timesheetApi.update).toHaveBeenCalledWith('draft-1', expect.objectContaining({ status: 'DRAFT', entries: [expect.objectContaining({ hoursWorked: 7 })] }));
});

it('preserves edits and stays in the editor if saving fails', async () => {
  const hours = await resume();
  timesheetApi.update.mockRejectedValueOnce({ response: { data: { message: 'Connection lost' } } });
  fireEvent.change(hours, { target: { value: '5' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
  await screen.findByText('Connection lost');
  expect(screen.getByRole('spinbutton', { name: 'Hours' })).toHaveValue(5);
});

it('protects browser Back and continues to its destination after saving', async () => {
  storedDraft.supportingDocuments=[];
  const router = createMemoryRouter([
    { path: '/other', element: <h1>Other page</h1> },
    { path: '/myhr/employee/timesheets', element: <TimesheetsPage role="EMPLOYEE" /> },
  ], { initialEntries: ['/other', '/myhr/employee/timesheets'], initialIndex: 1 });
  render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByRole('button', { name: /Resume draft/ }));
  fireEvent.change(await screen.findByRole('spinbutton', { name: 'Hours' }), { target: { value: '7.5' } });
  await act(async () => { await router.navigate(-1); });
  expect(screen.getByRole('dialog', { name: 'Save your timesheet progress?' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Save draft and leave' }));
  await screen.findByRole('heading', { name: 'Other page' });
  expect(timesheetApi.update).toHaveBeenCalledWith('draft-1', expect.objectContaining({ entries: [expect.objectContaining({ hoursWorked: 7.5 })] }));
});

it('adds missing days across two weeks while keeping approved Tuesday and Wednesday locked', async () => {
  const approved = {...storedDraft,id:'approved-week',status:'APPROVED',periodEndDate:'2026-09-13',totalHours:16,
    entries:[{workDate:'2026-09-01',hoursWorked:7,entryType:'WORK',description:'Approved Tuesday'},
      {workDate:'2026-09-02',hoursWorked:9,entryType:'WORK',description:'Approved Wednesday'}]};
  timesheetApi.list.mockResolvedValue({data:{data:[approved]}});
  documentApi.parseTimesheet.mockResolvedValue({data:{data:{periodStartDate:'2026-08-31',periodEndDate:'2026-09-13',
    entries:[{workDate:'2026-08-31',hoursWorked:6,entryType:'WORK'}, {workDate:'2026-09-01',hoursWorked:99,entryType:'WORK'},
      {workDate:'2026-09-02',hoursWorked:99,entryType:'WORK'}, {workDate:'2026-09-07',hoursWorked:8,entryType:'WORK'}]}}});
  timesheetApi.create.mockImplementation(async payload=>({data:{data:{...payload,id:'new-days',employeeUserId:'employee-1',
    supportingDocuments:[{id:'uploaded-doc',fileName:'support.txt'}],totalHours:14}}}));
  showPage();
  await screen.findByText('No saved drafts yet.');
  fireEvent.click(screen.getByRole('button',{name:/Start entry/}));
  fireEvent.change(document.getElementById('timesheetImport'),{target:{files:[new File(['timesheet fixture'],'support.txt',{type:'text/plain'})]}});
  await screen.findAllByRole('spinbutton',{name:'Hours'});
  expect(screen.queryByRole('dialog',{name:/Already approved/})).not.toBeInTheDocument();
  expect(document.getElementById('hours-2026-09-01')).toBeDisabled();
  expect(document.getElementById('hours-2026-09-01')).toHaveValue(7);
  expect(document.getElementById('hours-2026-09-02')).toBeDisabled();
  expect(document.getElementById('hours-2026-09-02')).toHaveValue(9);
  expect(document.getElementById('hours-2026-08-31')).toBeEnabled();
  expect(screen.getAllByText('Approved · Locked')).toHaveLength(2);
  fireEvent.click(screen.getByRole('button',{name:'Review timesheet'}));
  expect(screen.queryByRole('textbox',{name:'Reason for change'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'Submit timesheet'}));
  await waitFor(()=>expect(timesheetApi.create).toHaveBeenCalledWith(expect.objectContaining({status:'SUBMITTED',
    periodStartDate:'2026-08-31',periodEndDate:'2026-09-13',entries:[expect.objectContaining({workDate:'2026-08-31',hoursWorked:6}),
      expect.objectContaining({workDate:'2026-09-07',hoursWorked:8})]})));
  expect(timesheetApi.update).not.toHaveBeenCalled();
  expect(approved.status).toBe('APPROVED');
  expect(approved.entries.map(entry=>entry.hoursWorked)).toEqual([7,9]);
});

it.each([false,true])('tests the Nov 3–16 screenshots with other submitted days: %s',async hasPending=>{
  vi.useFakeTimers({toFake:['Date']});
  vi.setSystemTime(new Date('2026-11-09T12:00:00-06:00'));
  const approved={...storedDraft,id:'approved-november',status:'APPROVED',periodStartDate:'2026-11-03',periodEndDate:'2026-11-16',totalHours:16,
    entries:[{workDate:'2026-11-03',hoursWorked:8,entryType:'WORK'},{workDate:'2026-11-04',hoursWorked:8,entryType:'WORK'}]};
  const editableEntries=['2026-11-05','2026-11-06','2026-11-09'].map(workDate=>({workDate,hoursWorked:8,entryType:'WORK'}));
  const pending={...approved,id:'pending-november',status:'SUBMITTED',totalHours:24,entries:editableEntries};
  timesheetApi.list.mockResolvedValue({data:{data:hasPending?[approved,pending]:[approved]}});
  documentApi.upload.mockResolvedValue({data:{data:{id:'uploaded-doc',fileName:'June_22_-_June_28_2026.pdf'}}});
  documentApi.parseTimesheet.mockResolvedValue({data:{data:{periodStartDate:'2026-11-03',periodEndDate:'2026-11-16',entries:editableEntries}}});
  timesheetApi.create.mockImplementation(async payload=>({data:{data:{...payload,id:'new-november',employeeUserId:'employee-1',totalHours:24}}}));
  showPage();
  await screen.findByText('No saved drafts yet.');
  fireEvent.click(screen.getByRole('button',{name:/Start entry/}));
  fireEvent.change(document.getElementById('timesheetImport'),{target:{files:[new File(['Synthetic screenshot fixture'],'June_22_-_June_28_2026.pdf',{type:'application/pdf'})]}});
  if(hasPending){
    fireEvent.click(await screen.findByRole('button',{name:'Confirm re-edit'}));
    expect(await screen.findByText('Re-editing submitted timesheet')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox',{name:'Reason for change'}),{target:{value:'Testing screenshot correction'}});
  }else{
    await screen.findAllByRole('spinbutton',{name:'Hours'});
    expect(screen.queryByText('Re-editing submitted timesheet')).not.toBeInTheDocument();
  }
  expect(screen.getAllByText('Approved · Locked')).toHaveLength(2);
  expect(document.getElementById('hours-2026-11-03')).toBeDisabled();
  expect(document.getElementById('hours-2026-11-04')).toBeDisabled();
  expect(document.getElementById('hours-2026-11-10')).toBeDisabled();
  expect(document.getElementById('hours-2026-11-02')).not.toBeInTheDocument();
  expect(within(screen.getByText('TOTAL HOURS').parentElement).getByText('24.0')).toBeInTheDocument();
  await userEvent.setup().click(screen.getByRole('button',{name:'Fill blank weekdays · 8h'}));
  expect(await screen.findByText(/^No eligible blank weekdays/)).toBeInTheDocument();
  expect(document.getElementById('hours-2026-11-03')).toHaveValue(8);
  expect(document.getElementById('hours-2026-11-04')).toHaveValue(8);
  fireEvent.click(screen.getByRole('button',{name:'Review timesheet'}));
  fireEvent.click(screen.getByRole('button',{name:'Submit timesheet'}));
  const payload=expect.objectContaining({entries:editableEntries.map(entry=>expect.objectContaining(entry)),status:'SUBMITTED'});
  if(hasPending){
    await waitFor(()=>expect(timesheetApi.update).toHaveBeenCalledWith('pending-november',payload));
    expect(timesheetApi.create).not.toHaveBeenCalled();
  }else{
    await waitFor(()=>expect(timesheetApi.create).toHaveBeenCalledWith(payload));
    expect(timesheetApi.update).not.toHaveBeenCalled();
  }
  expect(approved.status).toBe('APPROVED');
  expect(approved.entries.map(entry=>entry.hoursWorked)).toEqual([8,8]);
});
