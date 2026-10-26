import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ProfileChangeRequestsPage from '../../../pages/myhr/features/profileChanges/ProfileChangeRequestsPage';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';

vi.mock('../../../api/myHrProfileChangeApi');
vi.mock('../../../api/myHrEmployeeApi');

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal()),
  useNavigate: () => navigate,
}));

function renderAdminPage() {
  return render(<MemoryRouter><ProfileChangeRequestsPage role="COMPANY_ADMIN" /></MemoryRouter>);
}

it('keeps the latest comparison when an earlier request detail arrives late', async () => {
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(mixedListResponse());
  let resolveOlder;
  myHrProfileChangeApi.getDetail.mockImplementation((id) => id === 'req-emp'
    ? new Promise((resolve) => { resolveOlder = resolve; })
    : Promise.resolve(detailResponse({ id: 'req-hr', fieldName: 'DESIGNATION', existingValue: 'Associate', requestedValue: 'Manager', requestReason: 'Current selection' })));
  const user = userEvent.setup();
  renderAdminPage();
  await user.click(within((await screen.findByText('Emma Employee')).closest('tr')).getByRole('button', { name: 'Review Request' }));
  await user.click(within(screen.getByText('Hank HR').closest('tr')).getByRole('button', { name: 'Review Request' }));
  const pane = screen.getByRole('region', { name: 'Review Profile Change Request' });
  expect(await within(pane).findByText('Current selection')).toBeInTheDocument();
  await act(async () => resolveOlder(detailResponse({ id: 'req-emp', requestReason: 'Earlier selection' })));
  expect(within(pane).queryByText('Earlier selection')).not.toBeInTheDocument();
  expect(within(pane).getByText('On file').nextElementSibling).toHaveTextContent('Associate');
  expect(within(pane).getByText('Requested').nextElementSibling).toHaveTextContent('Manager');
});

const listResponse = (overrides = {}) => ({
  data: {
    data: [{
      id: 'request-1',
      requesterUserId: 'employee-1',
      profileId: 'profile-1',
      assignedToUserId: 'hr-1',
      fieldName: 'PHONE_NUMBER',
      existingValue: '(312) 555-0100',
      requestedValue: '(312) 555-0199',
      requestReason: 'Number changed',
      status: 'PENDING',
      processedByUserId: null,
      processingComment: null,
      requestedAt: '2026-08-25T13:10:00',
      processedAt: null,
      employeeId: 'TD002',
      employeeName: 'Priya Shah',
      reportingManager: { userId: 'hr-1', employeeId: 'TD001', name: 'Riley Chen', loginId: null, email: null },
      requesterRole: 'EMPLOYEE',
      ...overrides,
    }],
  },
});
// Two requests with distinct requester roles/names for the Request Type filter tests.
const mixedListResponse = () => ({
  data: {
    data: [
      {
        id: 'req-emp', requesterUserId: 'employee-1', profileId: 'p1', assignedToUserId: 'hr-1',
        fieldName: 'PHONE_NUMBER', existingValue: '(312) 555-0100', requestedValue: '(312) 555-0199',
        requestReason: 'Number changed', status: 'PENDING', processedByUserId: null, processingComment: null,
        requestedAt: '2026-08-25T13:10:00', processedAt: null,
        employeeId: 'TD002', employeeName: 'Emma Employee',
        reportingManager: { userId: 'hr-1', employeeId: 'TD001', name: 'Riley Chen', loginId: null, email: null },
        requesterRole: 'EMPLOYEE',
      },
      {
        id: 'req-hr', requesterUserId: 'hr-9', profileId: 'p2', assignedToUserId: 'admin-1',
        fieldName: 'DESIGNATION', existingValue: 'HR Associate', requestedValue: 'HR Manager',
        requestReason: 'Promotion', status: 'PENDING', processedByUserId: null, processingComment: null,
        requestedAt: '2026-08-24T09:00:00', processedAt: null,
        employeeId: 'TD005', employeeName: 'Hank HR',
        reportingManager: { userId: 'admin-1', employeeId: null, name: null, loginId: 'TDADMIN', email: 'admin@x.test' },
        requesterRole: 'HR',
      },
    ],
  },
});

// requester is deliberately minimized to {userId, employeeId, name, role} -
// matching the real backend's ProfileChangeRequestDetailResponse.RequesterSummary,
// which never includes address/personal email/phone/designation (reviewer
// data-minimization). See ownProfileResponse() below for the caller's own
// full EmployeeProfile, used only for self-viewing, never for reviewing
// someone else's request.
const detailResponse = (overrides = {}) => ({
  data: {
    data: {
      id: 'request-1',
      fieldName: 'PHONE_NUMBER',
      existingValue: '(312) 555-0100',
      requestedValue: '(312) 555-0199',
      requestReason: 'Number changed',
      status: 'PENDING',
      processedByUserId: null,
      processingComment: null,
      requestedAt: '2026-08-25T13:10:00',
      processedAt: null,
      requester: { userId: 'employee-1', employeeId: 'TD002', name: 'Priya Shah', role: 'EMPLOYEE' },
      reportingManager: { userId: 'hr-1', employeeId: 'TD001', name: 'Riley Chen', loginId: null, email: null },
      ...overrides,
    },
  },
});

const ownProfileResponse = (overrides = {}) => ({
  data: {
    data: {
      userId: 'employee-1', employeeId: 'TD002', name: 'Priya Shah',
      firstName: 'Priya', lastName: 'Shah',
      personalEmail: 'priya.personal@example.com', workEmail: 'priya@example.com',
      phoneNumber: '(312) 555-0100', designation: 'Backend Engineer',
      streetAddress: '900 Wacker Drive', city: 'Chicago', state: 'IL', zipCode: '60601',
      accountStatus: 'ACTIVE',
      ...overrides,
    },
  },
});

describe('ProfileChangeRequestsPage (COMPANY_ADMIN)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders real employee/manager display information from the list API, never fixture names', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    renderAdminPage();

    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
    expect(screen.getAllByText(/TD001 · Riley Chen/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Jordan Lee/)).not.toBeInTheDocument();
  });

  it('shows a loading state while the organization list is being fetched', async () => {
    let resolveList;
    myHrProfileChangeApi.listForAdmin.mockReturnValue(new Promise((resolve) => { resolveList = resolve; }));
    renderAdminPage();

    expect(await screen.findByText('Loading requests…')).toBeInTheDocument();
    resolveList(listResponse());
    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
  });

  it('shows an error state with a working retry when the list fails to load', async () => {
    myHrProfileChangeApi.listForAdmin.mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();
    renderAdminPage();

    expect(await screen.findByText("Couldn't load requests")).toBeInTheDocument();

    myHrProfileChangeApi.listForAdmin.mockResolvedValueOnce(listResponse());
    await user.click(screen.getByRole('button', { name: 'Try Again' }));

    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
  });

  it('loads real request detail on Review Request, and never shows unrelated personal data', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    const user = userEvent.setup();
    renderAdminPage();

    await user.click(await screen.findByRole('button', { name: 'Review Request' }));

    expect(myHrProfileChangeApi.getDetail).toHaveBeenCalledWith('request-1');
    // Real detail data (the request reason) loaded, replacing any fixture.
    expect(await screen.findByText('Number changed')).toBeInTheDocument();
    expect(screen.queryByText('Jordan Lee')).not.toBeInTheDocument();
    // The backend's minimized RequesterSummary never includes address/personal
    // email/phone/designation - the reviewer view has nothing to render for
    // them, so they never appear even though the field under review is PHONE_NUMBER.
    expect(screen.queryByText(/priya.personal@example.com/)).not.toBeInTheDocument();
    expect(screen.queryByText(/900 Wacker Drive/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View Full Profile' })).not.toBeInTheDocument();
  });

  it('renders current and requested ADDRESS values in streetAddress -> city -> state -> zipCode order regardless of key order in the payload', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse({ fieldName: 'ADDRESS' }));
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse({
      fieldName: 'ADDRESS',
      existingValue: { zipCode: '60601', state: 'IL', city: 'Chicago', streetAddress: '900 Wacker Drive' },
      requestedValue: { city: 'USA', zipCode: '63146', streetAddress: '2079, canakin court', state: 'MO' },
    }));
    const user = userEvent.setup();
    renderAdminPage();

    await user.click(await screen.findByRole('button', { name: 'Review Request' }));

    expect((await screen.findAllByText('streetAddress: 900 Wacker Drive, city: Chicago, state: IL, zipCode: 60601'))[0]).toBeInTheDocument();
    expect(screen.getAllByText('streetAddress: 2079, canakin court, city: USA, state: MO, zipCode: 63146')[0]).toBeInTheDocument();
  });

  it('approves a pending request and refreshes the list', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    myHrProfileChangeApi.approve.mockResolvedValue({
      data: { data: { status: 'APPROVED', processedByUserId: 'admin-1', processingComment: 'Confirmed', processedAt: '2026-08-25T14:00:00' } },
    });
    const user = userEvent.setup();
    renderAdminPage();

    await user.click(await screen.findByRole('button', { name: 'Review Request' }));
    await screen.findByText('Number changed');
    await user.click(screen.getByRole('button', { name: 'Apply Change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm Change' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.approve).toHaveBeenCalledWith('request-1', ''));
    expect(await screen.findByText('Change applied.')).toBeInTheDocument();
    expect(myHrProfileChangeApi.listForAdmin).toHaveBeenCalledTimes(2);
  });

  it('rejects a pending request and refreshes the list', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    myHrProfileChangeApi.reject.mockResolvedValue({
      data: { data: { status: 'REJECTED', processedByUserId: 'admin-1', processingComment: 'Needs verification', processedAt: '2026-08-25T14:00:00' } },
    });
    const user = userEvent.setup();
    renderAdminPage();

    await user.click(await screen.findByRole('button', { name: 'Review Request' }));
    await screen.findByText('Number changed');
    await user.click(screen.getByRole('button', { name: 'Reject Request' }));
    await user.type(screen.getByLabelText(/Processing Comment/), 'Needs verification');
    await user.click(screen.getByRole('button', { name: 'Confirm Rejection' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.reject).toHaveBeenCalledWith('request-1', 'Needs verification'));
    expect(await screen.findByText('Request rejected.')).toBeInTheDocument();
    expect(myHrProfileChangeApi.listForAdmin).toHaveBeenCalledTimes(2);
  });

  it('shows an API error on a failed decision without losing the selected request', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    myHrProfileChangeApi.approve.mockRejectedValue(new Error('server error'));
    const user = userEvent.setup();
    renderAdminPage();

    await user.click(await screen.findByRole('button', { name: 'Review Request' }));
    await screen.findByText('Number changed');
    await user.click(screen.getByRole('button', { name: 'Apply Change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm Change' }));

    expect(await screen.findByText(/Something went wrong applying this change/)).toBeInTheDocument();
    // The review modal underneath is still showing the same request, not cleared.
    expect(screen.getByText('Number changed')).toBeInTheDocument();
  });

  it('never shows Request a Change for COMPANY_ADMIN', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    renderAdminPage();

    await screen.findByText('Priya Shah');
    expect(screen.queryByRole('button', { name: 'Request a Change' })).not.toBeInTheDocument();
    expect(myHrProfileChangeApi.listForAdmin).toHaveBeenCalled();
  });

  it('a submission notification\'s ?requestId opens that exact request for review automatically', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    render(
      <MemoryRouter initialEntries={['/myhr/admin/profile-change-requests?view=assigned&requestId=request-1']}>
        <ProfileChangeRequestsPage role="COMPANY_ADMIN" />
      </MemoryRouter>,
    );

    // No click on "Review Request" - the request-1 detail loads and its
    // review modal opens on its own, straight from the notification link.
    expect(await screen.findByRole('region', { name: 'Review Profile Change Request' })).toBeInTheDocument();
    expect(await screen.findByText('Number changed')).toBeInTheDocument();
    expect(myHrProfileChangeApi.getDetail).toHaveBeenCalledWith('request-1');
  });

  it('an unknown/inaccessible ?requestId falls back gracefully to the normal organization requests view', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    render(
      <MemoryRouter initialEntries={['/myhr/admin/profile-change-requests?view=assigned&requestId=does-not-exist']}>
        <ProfileChangeRequestsPage role="COMPANY_ADMIN" />
      </MemoryRouter>,
    );

    // The organization requests list still renders normally...
    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
    // ...but nothing was ever fetched or opened for an id that isn't in this
    // reviewer's own authorized, already-scoped queue - no blank/broken modal.
    expect(myHrProfileChangeApi.getDetail).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

function renderHrPage() {
  return render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
}

// HR lands on two action cards first (Request a Change / Assigned Requests) -
// every reviewer-queue assertion needs to open that card before the list,
// filters, or Review Request buttons exist in the document.
async function openHrAssignedRequests(user) {
  await user.click(await screen.findByRole('button', { name: /Assigned Requests/ }));
}

describe('ProfileChangeRequestsPage (HR reviewer)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrProfileChangeApi.listMine.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue(ownProfileResponse());
  });

  it('offers request and review workspaces as filter pills before either section', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    renderHrPage();

    expect(await screen.findByRole('button', { name: /Request a Change/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Assigned Requests/ })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Profile request workspace' })).toBeInTheDocument();
    expect(screen.queryByText('Priya Shah')).not.toBeInTheDocument();
  });

  it('loads requests assigned to HR through the same reviewer API as admin', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    const user = userEvent.setup();
    renderHrPage();
    await openHrAssignedRequests(user);

    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
    expect(myHrProfileChangeApi.listForAdmin).toHaveBeenCalled();
  });

  it('a submission notification\'s ?view=assigned skips the two-card menu and opens the specific request', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    render(
      <MemoryRouter initialEntries={['/myhr/hr/profile-change-requests?view=assigned&requestId=request-1']}>
        <ProfileChangeRequestsPage role="HR" />
      </MemoryRouter>,
    );

    // Lands directly on the Assigned Requests queue - the two-card chooser
    // (Request a Change / Assigned Requests) never appears.
    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Assigned Requests' })).not.toBeInTheDocument();
    // And the request that triggered the notification opens for review on its own.
    expect(await screen.findByRole('region', { name: 'Review Profile Change Request' })).toBeInTheDocument();
    expect(await screen.findByText('Number changed')).toBeInTheDocument();
    expect(myHrProfileChangeApi.getDetail).toHaveBeenCalledWith('request-1');
  });

  it('sorts requests Pending first, then Rejected, then Completed, newest first within each group', async () => {
    const base = listResponse().data.data[0];
    myHrProfileChangeApi.listForAdmin.mockResolvedValue({
      data: {
        data: [
          { ...base, id: 'req-approved', employeeId: 'TD003', employeeName: 'Approved Person', status: 'APPROVED', requestedAt: '2026-08-20T09:00:00' },
          { ...base, id: 'req-pending-old', employeeId: 'TD004', employeeName: 'Pending Old', status: 'PENDING', requestedAt: '2026-08-10T09:00:00' },
          { ...base, id: 'req-pending-new', employeeId: 'TD005', employeeName: 'Pending New', status: 'PENDING', requestedAt: '2026-08-25T09:00:00' },
          { ...base, id: 'req-rejected', employeeId: 'TD006', employeeName: 'Rejected Person', status: 'REJECTED', requestedAt: '2026-08-22T09:00:00' },
        ],
      },
    });
    const user = userEvent.setup();
    renderHrPage();
    await openHrAssignedRequests(user);

    await screen.findByText('Pending New');
    const names = within(screen.getByRole('table')).getAllByRole('row').slice(1).map((row) => row.querySelector('strong').textContent);
    expect(names).toEqual([
      'Pending New',
      'Pending Old',
      'Rejected Person',
      'Approved Person',
    ]);
  });

  it('never shows the admin-only Reporting Manager filter for HR', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    const user = userEvent.setup();
    renderHrPage();
    await openHrAssignedRequests(user);

    await screen.findByText('Priya Shah');
    expect(screen.queryByLabelText('Reporting Manager')).not.toBeInTheDocument();
  });

  it('approves a request assigned to HR and refreshes the queue', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    myHrProfileChangeApi.approve.mockResolvedValue({
      data: { data: { status: 'APPROVED', processedByUserId: 'hr-1', processingComment: 'Confirmed', processedAt: '2026-08-25T14:00:00' } },
    });
    const user = userEvent.setup();
    renderHrPage();
    await openHrAssignedRequests(user);

    await user.click(await screen.findByRole('button', { name: 'Review Request' }));
    await screen.findByText('Number changed');
    await user.click(screen.getByRole('button', { name: 'Apply Change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm Change' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.approve).toHaveBeenCalledWith('request-1', ''));
    expect(await screen.findByText('Change applied.')).toBeInTheDocument();
    expect(myHrProfileChangeApi.listForAdmin).toHaveBeenCalledTimes(2);
  });
});

describe('ProfileChangeRequestsPage (EMPLOYEE/HR self-service)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrProfileChangeApi.listMine.mockResolvedValue({ data: { data: [] } });
    myHrProfileChangeApi.listForAdmin.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue(ownProfileResponse());
  });

  it('still shows Request a Change for EMPLOYEE', async () => {
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    expect(screen.getByRole('button', { name: 'Request a Change' })).toBeInTheDocument();
    expect(myHrProfileChangeApi.listForAdmin).not.toHaveBeenCalled();
    await screen.findByText('No profile change requests available.');
  });

  it('shows the inline Request a Profile Change form for HR after opening the card', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: /Request a Change/ }));

    expect(screen.getByRole('heading', { name: 'Request a Profile Change' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit Request' })).toBeInTheDocument();
    await screen.findByText("You haven't submitted any profile change requests yet.");
  });

  it('loads the requester\'s own submission history from the real mine API', async () => {
    myHrProfileChangeApi.listMine.mockResolvedValue(listResponse());
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);

    expect(await screen.findByText('Priya Shah')).toBeInTheDocument();
    expect(myHrProfileChangeApi.listMine).toHaveBeenCalled();
  });

  it('an approval/rejection notification\'s ?requestId opens that own request automatically, with no reviewer detail call', async () => {
    myHrProfileChangeApi.listMine.mockResolvedValue(listResponse());
    render(
      <MemoryRouter initialEntries={['/myhr/employee/profile-change-requests?requestId=request-1']}>
        <ProfileChangeRequestsPage role="EMPLOYEE" />
      </MemoryRouter>,
    );

    // Opens the requester's own read-only detail view directly - never the
    // reviewer-only getDetail endpoint, which an EMPLOYEE has no access to.
    expect(await screen.findByRole('dialog', { name: 'Profile Change Request Details' })).toBeInTheDocument();
    expect(myHrProfileChangeApi.getDetail).not.toHaveBeenCalled();
  });

  it('submits a new request through the real create API and refreshes history', async () => {
    myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-2', status: 'PENDING' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    await screen.findByText('No profile change requests available.');

    await user.click(screen.getByRole('button', { name: 'Request a Change' }));
    await user.selectOptions(screen.getByLabelText('Field Name'), 'PERSONAL_EMAIL');
    await user.type(screen.getByLabelText('Requested Value'), 'new.email@example.com');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.create).toHaveBeenCalledWith('PERSONAL_EMAIL', 'new.email@example.com', undefined));
    expect(myHrProfileChangeApi.listMine).toHaveBeenCalledTimes(2);
  });

  it('accepts valid US phone numbers with normal formatting in the employee request form', async () => {
    const validNumbers = ['3142201123', '314-220-1123', '(314) 220-1123', '314 220 1123', '+1 314-220-1123', '+1 (314) 220-1123'];
    for (const value of validNumbers) {
      const user = userEvent.setup();
      myHrProfileChangeApi.create.mockReset();
      myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-2', status: 'PENDING' } } });
      const { unmount } = render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
      await screen.findByText('No profile change requests available.');

      await user.click(screen.getByRole('button', { name: 'Request a Change' }));
      await user.selectOptions(screen.getByLabelText('Field Name'), 'PHONE_NUMBER');
      fireEvent.change(screen.getByLabelText('Requested Value'), { target: { value } });
      await user.click(screen.getByRole('button', { name: 'Submit Request' }));

      await vi.waitFor(() => expect(myHrProfileChangeApi.create).toHaveBeenCalledWith('PHONE_NUMBER', '+13142201123', undefined));
      unmount();
    }
  });

  it('rejects invalid US phone numbers in the employee request form', async () => {
    const invalidNumbers = ['314220112', 'abcdefghij'];
    for (const value of invalidNumbers) {
      const user = userEvent.setup();
      myHrProfileChangeApi.create.mockReset();
      myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-2', status: 'PENDING' } } });
      const { unmount } = render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
      await screen.findByText('No profile change requests available.');

      await user.click(screen.getByRole('button', { name: 'Request a Change' }));
      await user.selectOptions(screen.getByLabelText('Field Name'), 'PHONE_NUMBER');
      fireEvent.change(screen.getByLabelText('Requested Value'), { target: { value } });
      await user.click(screen.getByRole('button', { name: 'Submit Request' }));

      expect(await screen.findByText('Enter a valid 10-digit phone number.')).toBeInTheDocument();
      expect(myHrProfileChangeApi.create).not.toHaveBeenCalled();
      unmount();
    }
  });

  it('clears a phone validation message once a valid number is completed', async () => {
    myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-phone', status: 'PENDING' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    await screen.findByText('No profile change requests available.');

    await user.click(screen.getByRole('button', { name: 'Request a Change' }));
    await user.selectOptions(screen.getByLabelText('Field Name'), 'PHONE_NUMBER');
    const phone = screen.getByLabelText('Requested Value');
    await user.type(phone, '616');
    expect(phone).toHaveValue('616');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText('Enter a valid 10-digit phone number.')).toBeInTheDocument();

    await user.type(phone, '3496888');
    expect(screen.queryByText('Enter a valid 10-digit phone number.')).not.toBeInTheDocument();
    expect(phone).toHaveValue('(616) 349-6888');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    await vi.waitFor(() => expect(myHrProfileChangeApi.create).toHaveBeenCalledWith('PHONE_NUMBER', '+16163496888', undefined));
  });

  it('shows an error and keeps the form open when submission fails', async () => {
    myHrProfileChangeApi.create.mockRejectedValue({ response: { data: { message: 'No active reporting manager found' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    await screen.findByText('No profile change requests available.');

    await user.click(screen.getByRole('button', { name: 'Request a Change' }));
    await user.selectOptions(screen.getByLabelText('Field Name'), 'PERSONAL_EMAIL');
    await user.type(screen.getByLabelText('Requested Value'), 'new.email@example.com');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));

    expect(await screen.findByText('No active reporting manager found')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit Request' })).toBeInTheDocument();
  });

  it('never offers DESIGNATION as a self-service field for EMPLOYEE', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    await screen.findByText('No profile change requests available.');
    await user.click(screen.getByRole('button', { name: 'Request a Change' }));

    const options = Array.from(screen.getByLabelText('Field Name').options).map((option) => option.value);
    expect(options).not.toContain('DESIGNATION');
  });

  it('never offers DESIGNATION as a self-service field for HR', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: /Request a Change/ }));

    const options = Array.from(screen.getByLabelText('Field Name').options).map((option) => option.value);
    expect(options).not.toContain('DESIGNATION');
  });

  it('submits a structured NAME request with firstName and lastName, not a free-text value', async () => {
    myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-2', status: 'PENDING' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    await screen.findByText('No profile change requests available.');

    await user.click(screen.getByRole('button', { name: 'Request a Change' }));
    await user.selectOptions(screen.getByLabelText('Field Name'), 'NAME');
    await user.type(screen.getByLabelText('First Name'), 'Jordan');
    await user.type(screen.getByLabelText('Last Name'), 'Lee');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.create).toHaveBeenCalledWith(
        'NAME', { firstName: 'Jordan', lastName: 'Lee' }, undefined));
  });

  it('submits a structured ADDRESS request with only the filled-in keys', async () => {
    myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-2', status: 'PENDING' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="EMPLOYEE" /></MemoryRouter>);
    await screen.findByText('No profile change requests available.');

    await user.click(screen.getByRole('button', { name: 'Request a Change' }));
    await user.selectOptions(screen.getByLabelText('Field Name'), 'ADDRESS');
    await user.type(screen.getByLabelText('ZIP Code'), '60603');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.create).toHaveBeenCalledWith(
        'ADDRESS', { zipCode: '60603' }, undefined));
  });

  it('submits a new request through HR\'s inline form and refreshes history', async () => {
    myHrProfileChangeApi.create.mockResolvedValue({ data: { data: { id: 'request-2', status: 'PENDING' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: /Request a Change/ }));

    await user.selectOptions(screen.getByLabelText('Field Name'), 'PERSONAL_EMAIL');
    await user.type(screen.getByLabelText('Requested Value'), 'new.email@example.com');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));

    await vi.waitFor(() => expect(myHrProfileChangeApi.create).toHaveBeenCalledWith('PERSONAL_EMAIL', 'new.email@example.com', undefined));
    expect(myHrProfileChangeApi.listMine).toHaveBeenCalledTimes(2);
  });

  it('Cancel clears the inline form fields without submitting', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: /Request a Change/ }));

    await user.selectOptions(screen.getByLabelText('Field Name'), 'PERSONAL_EMAIL');
    await user.type(screen.getByLabelText('Requested Value'), 'new.email@example.com');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByLabelText('Field Name').value).toBe('');
    expect(myHrProfileChangeApi.create).not.toHaveBeenCalled();
  });

  describe('My Previous Requests (HR own history)', () => {
    const ownRequests = () => ({
      data: {
        data: [
          {
            id: 'own-pending', requesterUserId: 'hr-actor', profileId: 'p1', assignedToUserId: 'manager-1',
            fieldName: 'PHONE_NUMBER', existingValue: '(312) 555-0100', requestedValue: '(312) 555-0199',
            requestReason: 'Number changed', status: 'PENDING', processedByUserId: null, processingComment: null,
            requestedAt: '2026-09-10T13:23:00', processedAt: null,
            employeeId: 'TD010', employeeName: 'Hank HR',
            reportingManager: { userId: 'manager-1', employeeId: 'TD001', name: 'Riley Chen', loginId: null, email: null },
            requesterRole: 'HR',
          },
          {
            id: 'own-approved', requesterUserId: 'hr-actor', profileId: 'p1', assignedToUserId: 'manager-1',
            fieldName: 'PERSONAL_EMAIL', existingValue: 'old@example.com', requestedValue: 'new@example.com',
            requestReason: null, status: 'APPROVED', processedByUserId: 'manager-1', processingComment: 'Looks good',
            requestedAt: '2026-09-08T10:20:00', processedAt: '2026-09-09T09:00:00',
            employeeId: 'TD010', employeeName: 'Hank HR',
            reportingManager: { userId: 'manager-1', employeeId: 'TD001', name: 'Riley Chen', loginId: null, email: null },
            requesterRole: 'HR',
          },
        ],
      },
    });

    beforeEach(() => {
      myHrProfileChangeApi.listMine.mockResolvedValue(ownRequests());
    });

    it('uses the streamlined card structure on the request list (Field, Submitted, status, and View Details)', async () => {
      const user = userEvent.setup();
      render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
      await user.click(await screen.findByRole('button', { name: /Request a Change/ }));
      await screen.findByText('My Previous Requests');
      const history = screen.getByText('My Previous Requests').closest('section');

      expect(within(history).getByText('Pending')).toBeInTheDocument();
      expect(within(history).getByText('Phone Number')).toBeInTheDocument();
      expect(within(history).getByText('09-10-2026, 1:23 PM')).toBeInTheDocument();
      // A requester always gets "View Details", never "Review Request"/Approve/Reject.
      expect(within(history).getAllByRole('button', { name: 'View Details' })).toHaveLength(2);
      expect(within(history).queryByRole('button', { name: 'Review Request' })).not.toBeInTheDocument();
    });

    it('View Details opens successfully without calling the reviewer-only detail API, and shows the full request', async () => {
      const user = userEvent.setup();
      render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
      await user.click(await screen.findByRole('button', { name: /Request a Change/ }));
      await user.click((await screen.findAllByRole('button', { name: 'View Details' }))[0]);
      const dialog = await screen.findByRole('dialog');

      expect(myHrProfileChangeApi.getDetail).not.toHaveBeenCalled();
      expect(within(dialog).queryByText("Couldn't load request details")).not.toBeInTheDocument();
      expect(within(dialog).getByRole('heading', { name: 'Profile Change Request Details' })).toBeInTheDocument();
      expect(within(dialog).getByText('(312) 555-0100')).toBeInTheDocument();
      expect(within(dialog).getByText('(312) 555-0199')).toBeInTheDocument();
      expect(within(dialog).getByText('Number changed')).toBeInTheDocument();
      expect(within(dialog).getByText('09-10-2026, 1:23 PM')).toBeInTheDocument();
      // No reviewer actions on a requester's own read-only view.
      expect(within(dialog).queryByRole('button', { name: 'Apply Change' })).not.toBeInTheDocument();
      expect(within(dialog).queryByRole('button', { name: 'Reject Request' })).not.toBeInTheDocument();
    });

    it('shows Processed date and Processing Comment for an already-decided own request', async () => {
      const user = userEvent.setup();
      render(<MemoryRouter><ProfileChangeRequestsPage role="HR" /></MemoryRouter>);
      await user.click(await screen.findByRole('button', { name: /Request a Change/ }));
      const detailButtons = await screen.findAllByRole('button', { name: 'View Details' });
      await user.click(detailButtons[1]);

      expect(screen.getByRole('heading', { name: 'Profile Change Request Details' })).toBeInTheDocument();
      expect(screen.getByText('09-09-2026, 9:00 AM')).toBeInTheDocument();
      expect(screen.getByText('Looks good')).toBeInTheDocument();
    });
  });
});

describe('ProfileChangeRequestsPage (Request Type filter)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(mixedListResponse());
  });

  it('does not render a Reporting Manager filter control', async () => {
    renderAdminPage();
    await screen.findByText('Emma Employee');
    expect(screen.queryByLabelText('Reporting Manager')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Request Type')).toBeInTheDocument();
  });

  it('Request Type "All requests" shows both Employee and HR requests', async () => {
    renderAdminPage();
    expect(await screen.findByText('Emma Employee')).toBeInTheDocument();
    expect(screen.getByText('Hank HR')).toBeInTheDocument();
  });

  it('Request Type "Employee requests" shows only Employee requests', async () => {
    const user = userEvent.setup();
    renderAdminPage();
    await screen.findByText('Emma Employee');

    await user.selectOptions(screen.getByLabelText('Request Type'), 'EMPLOYEE');

    expect(screen.getByText('Emma Employee')).toBeInTheDocument();
    expect(screen.queryByText('Hank HR')).not.toBeInTheDocument();
  });

  it('Request Type "HR requests" shows only HR requests', async () => {
    const user = userEvent.setup();
    renderAdminPage();
    await screen.findByText('Emma Employee');

    await user.selectOptions(screen.getByLabelText('Request Type'), 'HR');

    expect(screen.getByText('Hank HR')).toBeInTheDocument();
    expect(screen.queryByText('Emma Employee')).not.toBeInTheDocument();
  });

  it('reporting-manager information still appears on the request card', async () => {
    renderAdminPage();
    await screen.findByText('Emma Employee');
    // Removed from the filters, but still shown as card metadata.
    expect(screen.getAllByText(/Reporting Manager:/).length).toBeGreaterThan(0);
  });
});

describe('MyHrFeaturePage back control (via Profile Change Requests page)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(mixedListResponse());
  });

  it('uses the shared page header and shell navigation without a duplicate Back control', async () => {
    const previousSession = localStorage.getItem('myHrAuthenticatedUser');
    localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ role: 'COMPANY_ADMIN', loginId: 'TDADMIN' }));
    renderAdminPage();
    await screen.findByText('Emma Employee');
    expect(screen.getByRole('heading', { name: 'Profile Change Requests' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Back$/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /workspace home/ })).toHaveAttribute('href', '/myhr/admin/dashboard');
    if (previousSession === null) localStorage.removeItem('myHrAuthenticatedUser');
    else localStorage.setItem('myHrAuthenticatedUser', previousSession);
  });
});

it('filters the assigned queue from a dashboard attention link', async () => {
  vi.clearAllMocks();
  myHrProfileChangeApi.listForAdmin.mockResolvedValue({ data: { data: [
    { ...listResponse().data.data[0], id: 'pending', employeeName: 'Pending Person', status: 'PENDING' },
    { ...listResponse().data.data[0], id: 'approved', employeeName: 'Approved Person', status: 'APPROVED' },
  ] } });
  render(<MemoryRouter initialEntries={['/myhr/admin/profile-change-requests?view=assigned&status=PENDING']}><ProfileChangeRequestsPage role="COMPANY_ADMIN" /></MemoryRouter>);
  expect(await screen.findByText(/Pending Person/)).toBeInTheDocument();
  expect(screen.queryByText(/Approved Person/)).not.toBeInTheDocument();
});


describe('Profile change request workspace', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('keeps summary counts stable while status shortcuts filter the table', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue({ data: { data: [
      ...listResponse().data.data,
      ...listResponse({ id: 'approved-request', employeeId: 'TD009', employeeName: 'Alex Approved', status: 'APPROVED' }).data.data,
    ] } });
    const user = userEvent.setup();
    renderAdminPage();
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    await user.click(screen.getByRole('button', { name: 'Pending 1' }));
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).queryByText(/Alex Approved/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'All requests 2' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Approved 1' }));
    expect(within(table).getByText(/Alex Approved/)).toBeInTheDocument();
    expect(within(table).queryByText(/Priya Shah/)).not.toBeInTheDocument();
  });

  it('closes the detail panel with Escape and returns focus to the request action', async () => {
    myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
    myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse());
    const user = userEvent.setup();
    renderAdminPage();
    const action = await screen.findByRole('button', { name: 'Review Request' });
    await user.click(action);
    await screen.findByText('Number changed');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(action).toHaveFocus();
  });
});


it('resolves the reviewer label only when its user ID matches the returned manager', async () => {
  vi.clearAllMocks();
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse({ status: 'REJECTED' }));
  myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse({ status: 'REJECTED', processedByUserId: 'hr-1', processedAt: '2026-08-26T13:10:00' }));
  const user = userEvent.setup();
  renderAdminPage();
  await user.click(await screen.findByRole('button', { name: 'View Details' }));
  const dialog = await screen.findByRole('region', { name: 'Review Profile Change Request' });
  const label = await within(dialog).findByText('Reviewed By');
  expect(label.nextElementSibling).toHaveTextContent('Riley Chen');
  expect(label.nextElementSibling).not.toHaveTextContent('hr-1');
});


it('shows the selected request ID even when the detail response omits its ID', async () => {
  vi.clearAllMocks();
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse());
  myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse({ id: undefined }));
  const user = userEvent.setup();
  renderAdminPage();
  await user.click(await screen.findByRole('button', { name: 'Review Request' }));
  const dialog = await screen.findByRole('region', { name: 'Review Profile Change Request' });
  expect(await within(dialog).findByText('request-1')).toBeInTheDocument();
});


it('displays a short request reference while retaining the database ID for API calls', async () => {
  vi.clearAllMocks();
  const id = '6aaafe3886c2918d2fe2b38a';
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse({ id }));
  myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse({ id }));
  const user = userEvent.setup();
  renderAdminPage();
  await user.click(await screen.findByRole('button', { name: 'Review Request' }));
  const dialog = await screen.findByRole('region', { name: 'Review Profile Change Request' });
  expect(await within(dialog).findByText('REQ-2FE2B38A')).toHaveAttribute('title', id);
  expect(within(dialog).queryByText(id)).not.toBeInTheDocument();
  expect(myHrProfileChangeApi.getDetail).toHaveBeenCalledWith(id);
});


it('shows a readable reviewer reference when the API does not supply the reviewer identity', async () => {
  vi.clearAllMocks();
  const reviewerId = '6a8df03a155fb754b49c1b93';
  myHrProfileChangeApi.listForAdmin.mockResolvedValue(listResponse({ status: 'REJECTED' }));
  myHrProfileChangeApi.getDetail.mockResolvedValue(detailResponse({ status: 'REJECTED', processedByUserId: reviewerId }));
  const user = userEvent.setup();
  renderAdminPage();
  await user.click(await screen.findByRole('button', { name: 'View Details' }));
  const dialog = await screen.findByRole('region', { name: 'Review Profile Change Request' });
  expect(await within(dialog).findByText('USER-B49C1B93')).toHaveAttribute('title', reviewerId);
  expect(within(dialog).queryByText(reviewerId)).not.toBeInTheDocument();
});
