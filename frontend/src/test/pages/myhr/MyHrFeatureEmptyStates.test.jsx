import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DocumentRequestsPage from '../../../pages/myhr/features/documentRequests/DocumentRequestsPage';
import ProfileChangeRequestsPage from '../../../pages/myhr/features/profileChanges/ProfileChangeRequestsPage';
import ReportsPage from '../../../pages/myhr/features/reports/ReportsPage';
import TimesheetsPage from '../../../pages/myhr/features/timesheets/TimesheetsPage';
import myHrDocumentRequestApi from '../../../api/myHrDocumentRequestApi';
import myHrProfileChangeApi from '../../../api/myHrProfileChangeApi';
import myHrEmployeeApi from '../../../api/myHrEmployeeApi';
import timesheetApi from '../../../api/timesheetApi';
import myHrReportApi from '../../../api/myHrReportApi';

vi.mock('../../../api/myHrDocumentRequestApi');
vi.mock('../../../api/myHrProfileChangeApi');
vi.mock('../../../api/myHrEmployeeApi');
vi.mock('../../../api/timesheetApi', () => ({
  default: {
    list: vi.fn(),
    getPeriod: vi.fn(),
    getSettings: vi.fn(),
  },
}));
vi.mock('../../../api/myHrReportApi', () => ({
  default: {
    getWorkforceReport: vi.fn(),
    getEmployeeHoursReport: vi.fn(),
  },
}));

function renderPage(page) {
  return render(<MemoryRouter>{page}</MemoryRouter>);
}

describe('active My HR feature empty states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    myHrDocumentRequestApi.list.mockResolvedValue({ data: { data: [] } });
    myHrProfileChangeApi.listMine.mockResolvedValue({ data: { data: [] } });
    myHrEmployeeApi.getMine.mockResolvedValue({ data: { data: null } });
    timesheetApi.list.mockResolvedValue({ data: { data: [] } });
    timesheetApi.getPeriod.mockResolvedValue({ data: { data: { periodStartDate: '2026-09-01', periodEndDate: '2026-09-07' } } });
    timesheetApi.getSettings.mockResolvedValue({ data: { data: {} } });
    myHrReportApi.getWorkforceReport.mockResolvedValue({ data: { data: { employees: [], managerSummary: [] } } });
  });

  it('does not display sample organization timesheets', async () => {
    renderPage(<TimesheetsPage role="COMPANY_ADMIN" />);

    expect(await screen.findByText('No organization timesheets found.')).toBeInTheDocument();
    expect(screen.queryByText('Jordan Lee')).not.toBeInTheDocument();
    expect(screen.queryByText('Taylor Reed')).not.toBeInTheDocument();
  });

  it('does not display sample employee timesheet entries or history', async () => {
    render(
      <MemoryRouter initialEntries={['/myhr/employee/timesheets?mode=history']}>
        <TimesheetsPage role="EMPLOYEE" />
      </MemoryRouter>,
    );

    expect(await screen.findByText('No submitted timesheets yet.')).toBeInTheDocument();
    expect(screen.queryByText('Project planning')).not.toBeInTheDocument();
  });

  it('does not display sample profile-change requests', async () => {
    renderPage(<ProfileChangeRequestsPage role="EMPLOYEE" />);

    expect(await screen.findByText('No profile change requests available.')).toBeInTheDocument();
    expect(screen.queryByText('Jordan Lee')).not.toBeInTheDocument();
  });

  it('shows empty states for connected document requests and connected reports catalog', async () => {
    const { rerender } = renderPage(<DocumentRequestsPage />);
    expect(await screen.findByText('No document requests available.')).toBeInTheDocument();

    rerender(<MemoryRouter><ReportsPage role="HR" /></MemoryRouter>);
    expect(await screen.findByText('Workforce Overview')).toBeInTheDocument();
    expect(screen.getByText('Timesheet Report')).toBeInTheDocument();
    expect(screen.queryByText('Alex Morgan')).not.toBeInTheDocument();
  });
});
