import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WeekHoursStrip from '../../../pages/myhr/features/timesheets/components/WeekHoursStrip';

describe('Timesheet weekday strip', () => {
  it('adds real entries by date, preserves zero hours, and leaves missing days empty', () => {
    render(<WeekHoursStrip weekStart="2026-10-12" entries={[
      { workDate: '2026-10-12', hoursWorked: 3 },
      { workDate: '2026-10-12', hoursWorked: 4.5 },
      { workDate: '2026-10-13', hoursWorked: 0 },
      { workDate: '2026-10-14', hoursWorked: null },
      { workDate: '2026-10-17', hoursWorked: 8 },
    ]} />);
    const route = screen.getByRole('list', { name: 'Monday to Friday hours' });
    expect(within(route).getByText('7.5h')).toBeInTheDocument();
    expect(within(route).getByText('0h')).toBeInTheDocument();
    expect(within(route).getAllByText('-')).toHaveLength(3);
    expect(within(route).queryByText('8h')).not.toBeInTheDocument();
    expect(route.querySelector('.week-hours__day--different')).toBeNull();
  });

  it('announces a schedule difference only when a real standard is supplied', () => {
    render(<WeekHoursStrip weekStart="2026-10-12" standardHours={8} entries={[
      { workDate: '2026-10-12', hoursWorked: 8 },
      { workDate: '2026-10-13', hoursWorked: 6 },
    ]} />);
    const route = screen.getByRole('list', { name: 'Monday to Friday hours' });
    expect(route.querySelectorAll('.week-hours__day--different')).toHaveLength(1);
    expect(within(route).getByText(/differs from standard schedule/).closest('li')).toHaveTextContent('Tue');
  });

  it('omits daily progress when no dates exist', () => {
    render(<WeekHoursStrip />);
    expect(screen.getByText('Daily hours unavailable')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('marks an explicit zero-hour day as a schedule difference, leaving absent days empty', () => {
    render(<WeekHoursStrip weekStart="2026-10-12" standardHours={8} entries={[
      { workDate: '2026-10-12', hoursWorked: 0 },
    ]} />);
    const route = screen.getByRole('list', { name: 'Monday to Friday hours' });
    expect(route.querySelectorAll('.week-hours__day--different')).toHaveLength(1);
    expect(within(route).getByText(/differs from standard schedule/).closest('li')).toHaveTextContent('0h');
    expect(within(route).getAllByText('-')).toHaveLength(4);
  });
});
