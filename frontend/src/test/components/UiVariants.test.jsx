import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import UiVariantProvider from '../../context/UiVariantProvider';
import { UI_VARIANT_STORAGE_KEY, UiVariantContext } from '../../context/uiVariant';
import UiExperimenterDock from '../../components/dev/UiExperimenterDock';
import WorkspaceExplorer from '../../components/branding/WorkspaceExplorer';
import TimesheetChronometer from '../../pages/myhr/features/timesheets/components/TimesheetChronometer';
import InteractiveProfileBadge from '../../pages/myhr/features/profileChanges/components/InteractiveProfileBadge';

function variantRender(variant, content) {
  return render(<UiVariantContext.Provider value={{ activeVariant: variant }}>{content}</UiVariantContext.Provider>);
}

describe('Waypoint UI Versions & WP Rocket UX Adaptations', () => {
  beforeEach(() => { localStorage.clear(); });

  it('keeps the production design fixed despite a saved experiment and preserves navigation', () => {
    localStorage.setItem(UI_VARIANT_STORAGE_KEY, 'v2');
    const tree = (
      <UiVariantProvider>
        <MemoryRouter>
          <UiExperimenterDock />
          <Routes>
            <Route path="/" element={<><WorkspaceExplorer /><Link to="/hours">Open hours</Link></>} />
            <Route path="/hours" element={<TimesheetChronometer hoursLogged={24} />} />
          </Routes>
        </MemoryRouter>
      </UiVariantProvider>
    );
    const view = render(tree);
    expect(document.documentElement).toHaveAttribute('data-ui-variant', 'v1');
    expect(screen.queryByTitle('Toggle UI Experimenter')).not.toBeInTheDocument();
    expect(screen.getByText('Explore your workspace')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: 'Open hours' }));
    expect(screen.getByLabelText(/Timesheet Chronometer: 24 hours total/)).toBeInTheDocument();
    expect(localStorage.getItem(UI_VARIANT_STORAGE_KEY)).toBe('v2');
    view.unmount();
    render(tree);
    expect(document.documentElement).toHaveAttribute('data-ui-variant', 'v1');
  });

  it('ignores an invalid saved experiment without rewriting it', () => {
    localStorage.setItem(UI_VARIANT_STORAGE_KEY, 'retired');
    render(<UiVariantProvider><WorkspaceExplorer /></UiVariantProvider>);
    expect(document.documentElement).toHaveAttribute('data-ui-variant', 'v1');
    expect(screen.getByText('Explore your workspace')).toBeInTheDocument();
  });

  describe('Timesheet Chronometer Variants', () => {
    it('v1 (Chrono-Precision): renders horology analog clock and handles winding crown entry', () => {
      const onEntry = vi.fn();
      variantRender('v1', <TimesheetChronometer hoursLogged={98} hoursTarget={80} onCrownClick={onEntry} />);
      expect(screen.getByLabelText(/Timesheet Chronometer: 98 hours total/)).toBeInTheDocument();
      expect(screen.getByText(/STANDARD 80h • OVERTIME \+18h/)).toBeInTheDocument();
      expect(screen.getByText(/98h TOTAL/)).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Enter hours using clock' }));
      expect(onEntry).toHaveBeenCalledOnce();
    });

    it('v2 (The Tank Studio): renders tactile punch clock with segment meter and lever recoil', () => {
      const onEntry = vi.fn();
      variantRender('v2', <TimesheetChronometer hoursLogged={88} hoursTarget={80} onCrownClick={onEntry} />);
      expect(screen.getByRole('region', { name: /Tactile Punch Clock: 88h total/ })).toBeInTheDocument();
      expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '80');
      fireEvent.click(screen.getByRole('button', { name: 'Audit breakdown' }));
      expect(screen.getAllByText(/80h/).length).toBeGreaterThan(0);
      expect(screen.getByText('+8h')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /PUNCH TIME/ }));
      expect(onEntry).toHaveBeenCalledOnce();
    });

    it('v3 (Executive Sleek): renders minimal HUD with dual progress and single direct CTA', () => {
      const onEntry = vi.fn();
      variantRender('v3', <TimesheetChronometer hoursLogged={98} hoursTarget={80} onCrownClick={onEntry} />);
      expect(screen.getByRole('region', { name: /Executive HUD: 98h logged/ })).toBeInTheDocument();
      expect(screen.getByText('80h Standard')).toBeInTheDocument();
      expect(screen.getByText('+18h Overtime')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /Enter Hours/ }));
      expect(onEntry).toHaveBeenCalledOnce();
    });
  });

  describe('Interactive Profile Record Variants', () => {
    it('v1 (Chrono-Precision): renders clean record card with diff values, copy buttons, and inspect action', () => {
      const onInspect = vi.fn();
      variantRender('v1', <InteractiveProfileBadge employeeName="Taylor" existingValue="Old" requestedValue="New" onInspect={onInspect} />);
      expect(screen.getByText('Old')).toBeInTheDocument();
      expect(screen.getByText('New')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Copy current value' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Copy requested value' })).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Inspect Request Details →' }));
      expect(onInspect).toHaveBeenCalledOnce();
    });

    it('v2 (The Tank Studio): renders audit details directly without flipping', () => {
      const onInspect = vi.fn();
      variantRender('v2', <InteractiveProfileBadge employeeName="Taylor" manager="Alex Rivera" reason="Office relocation" existingValue="Old" requestedValue="New" onInspect={onInspect} />);
      expect(screen.getByText('Old')).toBeInTheDocument();
      expect(screen.getByText('New')).toBeInTheDocument();
      expect(screen.getByText('Alex Rivera')).toBeInTheDocument();
      expect(screen.getByText('Office relocation')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Inspect Request Details →' }));
      expect(onInspect).toHaveBeenCalledOnce();
    });

    it('v3 (Executive Sleek): renders copyable values and supports copying to clipboard', () => {
      variantRender('v3', <InteractiveProfileBadge employeeName="Taylor" existingValue="Old Address" requestedValue="New Address" />);
      expect(screen.getByText('Old Address')).toBeInTheDocument();
      expect(screen.getByText('New Address')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Copy requested value' }));
      expect(screen.getByText('✓ Copied')).toBeInTheDocument();
    });
  });
});
