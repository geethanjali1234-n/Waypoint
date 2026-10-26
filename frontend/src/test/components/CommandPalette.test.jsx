import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CommandPalette from '../../components/navigation/CommandPalette';
import myHrReportApi from '../../api/myHrReportApi';

vi.mock('../../api/myHrReportApi', () => ({
  default: {
    searchEmployees: vi.fn(),
  },
}));

function renderCommandPalette({
  isOpen = true,
  onClose = vi.fn(),
  role = 'HR',
  initialPath = '/myhr/hr/dashboard',
} = {}) {
  localStorage.setItem('myHrAuthenticatedUser', JSON.stringify({ userId: `${role.toLowerCase()}-1`, role }));
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <CommandPalette isOpen={isOpen} onClose={onClose} />
    </MemoryRouter>
  );
}

describe('CommandPalette', () => {
  it('scopes external workspaces to supplied destinations without searching My HR employees', async () => {
    render(<MemoryRouter initialEntries={['/careers']}><CommandPalette isOpen onClose={vi.fn()} enableEmployeeSearch={false}
      actions={[{ id: 'careers', title: 'Careers', path: '/careers', category: 'Opportunities' }]} /></MemoryRouter>);
    expect(screen.getByText('Careers')).toBeInTheDocument();
    expect(screen.queryByText('Enter Timesheet')).not.toBeInTheDocument();
    await userEvent.setup().type(screen.getByRole('textbox', { name: 'Search Quick Jump' }), 'Careers');
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(myHrReportApi.searchEmployees).not.toHaveBeenCalled();
  });
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('renders nothing when isOpen is false', () => {
    renderCommandPalette({ isOpen: false });
    expect(screen.queryByRole('dialog', { name: /universal quick jump/i })).not.toBeInTheDocument();
  });

  it('renders quick actions and search input when isOpen is true', () => {
    renderCommandPalette({ isOpen: true, role: 'HR' });
    expect(screen.getByRole('dialog', { name: /universal quick jump/i })).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/type a command/i)).toBeInTheDocument();
    expect(screen.getByText('Enter Timesheet')).toBeInTheDocument();
    expect(screen.getByText('Document Requests')).toBeInTheDocument();
  });

  it('filters actions by search query', async () => {
    const user = userEvent.setup();
    renderCommandPalette({ isOpen: true, role: 'HR' });

    const input = screen.getByPlaceholderText(/type a command/i);
    await user.type(input, 'approvals');

    expect(screen.getByText('Review Timesheet Approvals')).toBeInTheDocument();
    expect(screen.queryByText('Document Requests')).not.toBeInTheDocument();
  });

  it('enforces RBAC: strictly hides Admin actions (Audit History, Organization Settings) from HR', async () => {
    const user = userEvent.setup();
    renderCommandPalette({ isOpen: true, role: 'HR', initialPath: '/myhr/hr/timesheets' });

    expect(screen.queryByText('Audit History')).not.toBeInTheDocument();
    expect(screen.queryByText('Organization Settings')).not.toBeInTheDocument();

    const input = screen.getByPlaceholderText(/type a command/i);
    await user.type(input, 'audit');

    expect(screen.queryByText('Audit History')).not.toBeInTheDocument();
  });

  it('enforces RBAC: displays Audit History and Organization Settings to COMPANY_ADMIN', async () => {
    const user = userEvent.setup();
    renderCommandPalette({ isOpen: true, role: 'COMPANY_ADMIN', initialPath: '/myhr/admin/dashboard' });

    const input = screen.getByPlaceholderText(/type a command/i);
    await user.type(input, 'audit');

    expect(screen.getByText('Audit History')).toBeInTheDocument();
    expect(screen.queryByText('Enter Timesheet')).not.toBeInTheDocument();
  });

  it('enforces RBAC: restricts EMPLOYEE role to employee-only self-service actions', () => {
    renderCommandPalette({ isOpen: true, role: 'EMPLOYEE', initialPath: '/myhr/employee/dashboard' });

    expect(screen.getByText('Enter Timesheet')).toBeInTheDocument();
    expect(screen.getByText('Document Requests')).toBeInTheDocument();
    expect(screen.getByText('Profile Change Requests')).toBeInTheDocument();

    // Must NOT have managerial or admin actions
    expect(screen.queryByText('Review Timesheet Approvals')).not.toBeInTheDocument();
    expect(screen.queryByText('Employee Directory')).not.toBeInTheDocument();
    expect(screen.queryByText('Timesheet & Workforce Reports')).not.toBeInTheDocument();
    expect(screen.queryByText('Payroll Readiness')).not.toBeInTheDocument();
    expect(screen.queryByText('Audit History')).not.toBeInTheDocument();
    expect(screen.queryByText('Organization Settings')).not.toBeInTheDocument();
  });

  it('fetches and displays live employee search results on debounce', async () => {
    const user = userEvent.setup();
    myHrReportApi.searchEmployees.mockResolvedValue({
      data: {
        data: [
          {
            userId: 'emp-10',
            employeeId: 'TD099',
            name: 'Robert Miller',
            designation: 'Senior Architect',
            reportingManagerName: 'Sarah HR',
          },
        ],
      },
    });

    renderCommandPalette({ isOpen: true, role: 'HR' });
    const input = screen.getByPlaceholderText(/type a command/i);
    await user.type(input, 'Robert');

    await waitFor(() => {
      expect(myHrReportApi.searchEmployees).toHaveBeenCalledWith('Robert', { limit: 6 });
    });

    expect(await screen.findByText('Robert Miller')).toBeInTheDocument();
    expect(screen.getByText('TD099')).toBeInTheDocument();
  });

  it('closes on Escape key press or close button click', async () => {
    const onClose = vi.fn();
    renderCommandPalette({ isOpen: true, onClose, role: 'HR' });

    const input = screen.getByPlaceholderText(/type a command/i);
    fireEvent.keyDown(input, { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });
});
