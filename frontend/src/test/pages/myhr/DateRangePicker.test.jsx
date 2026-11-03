import { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { DateRangePicker } from '../../../pages/myhr/features/timesheets/TimesheetsPage';

afterEach(() => { document.body.style.overflow = ''; });

function setup({ maxDate } = {}) {
  const changed = vi.fn();
  function Harness() {
    const [range, setRange] = useState(['2026-10-03', '2026-10-05']);
    return <DateRangePicker label="Timesheet period" fromDate={range[0]} toDate={range[1]} maxDate={maxDate}
      onChange={(start, end) => { changed(start, end); setRange([start, end]); }} />;
  }
  const { container } = render(<Harness />);
  return { user: userEvent.setup(), changed, container, trigger: screen.getByLabelText('Timesheet period') };
}
const day = (dialog, number) => within(dialog).getByRole('button', { name: new RegExp(`^Oct ${number}, 2026`) });

it('applies a reversed range once and keeps the calendar outside the page layout', async () => {
  const { user, changed, container, trigger } = setup();
  await user.click(trigger);
  const dialog = screen.getByRole('dialog', { name: 'Select timesheet period' });
  expect(container).not.toContainElement(dialog);
  expect(document.body.style.overflow).toBe('hidden');
  await user.click(day(dialog, 10));
  await user.click(day(dialog, 2));
  await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
  expect(changed).toHaveBeenCalledExactlyOnceWith('2026-10-02', '2026-10-10');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
  expect(trigger).toHaveTextContent('Oct 2 - Oct 10, 2026');
  expect(document.body.style.overflow).toBe('');
});

it('does not commit a hovered or touch-focused end date', async () => {
  const { user, changed, trigger } = setup();
  await user.click(trigger);
  const dialog = screen.getByRole('dialog');
  await user.click(day(dialog, 10));
  await user.hover(day(dialog, 20));
  expect(day(dialog, 20)).toHaveAttribute('aria-pressed', 'true');
  fireEvent.focus(day(dialog, 22));
  await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
  expect(changed).toHaveBeenCalledExactlyOnceWith('2026-10-10', '2026-10-10');
});

it('cancels pending changes with Escape and restores the applied month on reopening', async () => {
  const { user, changed, trigger } = setup();
  await user.click(trigger);
  const dialog = screen.getByRole('dialog');
  await user.click(day(dialog, 10));
  await user.click(within(dialog).getByRole('button', { name: 'Previous month' }));
  await user.keyboard('{Escape}');
  expect(changed).not.toHaveBeenCalled();
  expect(trigger).toHaveFocus();
  await user.click(trigger);
  expect(within(screen.getByRole('dialog')).getByText('October 2026')).toBeInTheDocument();
  expect(day(screen.getByRole('dialog'), 3)).toHaveAttribute('aria-pressed', 'true');
});

it('keeps keyboard focus inside the dialog and clears the applied range', async () => {
  const { user, changed, trigger } = setup();
  await user.click(trigger);
  const dialog = screen.getByRole('dialog');
  const apply = within(dialog).getByRole('button', { name: 'Apply' });
  apply.focus();
  await user.tab();
  expect(within(dialog).getByRole('button', { name: 'Close' })).toHaveFocus();
  await user.tab({ shift: true });
  expect(apply).toHaveFocus();
  await user.click(within(dialog).getByRole('button', { name: 'Clear' }));
  expect(changed).toHaveBeenCalledExactlyOnceWith('', '');
  expect(trigger).toHaveTextContent('Any date');
});

it('retains the rule that a range must start by today but may end later', async () => {
  const { user, changed, trigger } = setup({ maxDate: '2026-10-12' });
  await user.click(trigger);
  const dialog = screen.getByRole('dialog');
  expect(day(dialog, 20)).toBeDisabled();
  await user.click(day(dialog, 10));
  expect(day(dialog, 20)).toBeEnabled();
  await user.click(day(dialog, 20));
  await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
  expect(changed).toHaveBeenCalledExactlyOnceWith('2026-10-10', '2026-10-20');
});
