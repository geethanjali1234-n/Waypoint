import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EmployerEventsPage from './EmployerEventsPage';
import employerEventApi from '../api/employerEventApi';
import { MemoryRouter } from 'react-router-dom';

const { navigateMock } = vi.hoisted(() => ({
  navigateMock: vi.fn(),
}));

vi.mock('react-router-dom', async (importOriginal) => ({
  ...await importOriginal(),
  useNavigate: () => navigateMock,
}));

vi.mock('../api/employerEventApi', () => ({
  default: {
    listEmployerEvents: vi.fn(),
    closeEvent: vi.fn(),
    reopenEvent: vi.fn(),
  },
}));

vi.mock('./EmployerEventFormPage', () => ({
  default: () => null,
}));

function makeEvent(overrides = {}) {
  return {
    id: 'event-1',
    title: 'Career fair',
    eventCode: 'EVT-001',
    eventType: 'JOB_FAIR',
    eventMode: 'OFFLINE',
    venueName: 'Waypoint Hall',
    address: 'Austin, Texas',
    startDateTime: '2026-08-14T14:00:00',
    registrationCount: 2,
    capacity: 50,
    status: 'PUBLISHED',
    ...overrides,
  };
}

describe('EmployerEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    employerEventApi.listEmployerEvents.mockResolvedValue({
      data: {
        data: [
          makeEvent({ id: 'event-texas-name', title: 'Austin fair', address: 'Austin, Texas' }),
          makeEvent({ id: 'event-texas-code', title: 'Dallas fair', address: 'Dallas, TX' }),
          makeEvent({ id: 'event-california', title: 'Los Angeles fair', address: 'Los Angeles, California' }),
        ],
      },
    });
  });

  it('matches state abbreviations and full names in the search box', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><EmployerEventsPage /></MemoryRouter>);

    expect(await screen.findByText('Austin fair')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View registrants for Austin fair' })).toHaveAttribute('href', '/employer/events/event-texas-name/registrants');
    const search = screen.getByRole('searchbox', { name: 'Search events' });

    await user.type(search, 'TX');
    expect(screen.getByText('Austin fair')).toBeInTheDocument();
    expect(screen.getByText('Dallas fair')).toBeInTheDocument();
    expect(screen.queryByText('Los Angeles fair')).not.toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'California');
    expect(screen.getByText('Los Angeles fair')).toBeInTheDocument();
    expect(screen.queryByText('Austin fair')).not.toBeInTheDocument();
    expect(screen.queryByText('Dallas fair')).not.toBeInTheDocument();
  });

  it('filters events by active and closed status', async () => {
    const user = userEvent.setup();
    employerEventApi.listEmployerEvents.mockResolvedValue({
      data: {
        data: [
          makeEvent({ id: 'active-event', title: 'Active workshop' }),
          makeEvent({ id: 'cancelled-event', title: 'Cancelled workshop', status: 'CANCELLED' }),
          makeEvent({ id: 'completed-event', title: 'Completed workshop', status: 'COMPLETED' }),
        ],
      },
    });
    render(<MemoryRouter><EmployerEventsPage /></MemoryRouter>);

    expect(await screen.findByText('Active workshop')).toBeInTheDocument();
    const statusFilter = screen.getByRole('combobox', { name: 'Filter events by status' });

    await user.selectOptions(statusFilter, 'ACTIVE');
    expect(screen.getByText('Active workshop')).toBeInTheDocument();
    expect(screen.queryByText('Cancelled workshop')).not.toBeInTheDocument();
    expect(screen.queryByText('Completed workshop')).not.toBeInTheDocument();

    await user.selectOptions(statusFilter, 'CLOSED');
    expect(screen.queryByText('Active workshop')).not.toBeInTheDocument();
    expect(screen.getByText('Cancelled workshop')).toBeInTheDocument();
    expect(screen.getByText('Completed workshop')).toBeInTheDocument();
  });
});
