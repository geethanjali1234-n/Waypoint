import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ExperiencePage from '../../../pages/experience/ExperiencePage';
import TimesheetSimulator from '../../../pages/experience/components/TimesheetSimulator';
import WorkforceRadar from '../../../pages/experience/components/WorkforceRadar';
import DocumentScannerWidget from '../../../pages/experience/components/DocumentScannerWidget';

// Mock canvas getContext
beforeEach(() => {
  HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue({
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
    stroke: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    roundRect: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    createLinearGradient: vi.fn().mockReturnValue({ addColorStop: vi.fn() }),
    createRadialGradient: vi.fn().mockReturnValue({ addColorStop: vi.fn() }),
    measureText: vi.fn().mockReturnValue({ width: 50 }),
    fillText: vi.fn(),
  });
});

describe('Waypoint Next-Gen Experience Page', () => {
  it('renders the experience page with main headline, canvas, and navigation', () => {
    render(
      <MemoryRouter>
        <ExperiencePage />
      </MemoryRouter>
    );

    expect(screen.getByText(/PEOPLE OPERATIONS/i)).toBeInTheDocument();
    expect(screen.getByText(/AT THE SPEED OF LIGHT/i)).toBeInTheDocument();

    const nav = screen.getByRole('navigation', { name: /Primary Experience Navigation/i });
    expect(within(nav).getByRole('link', { name: /^Topology$/i })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: /^Timesheets$/i })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: /^Workforce$/i })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: /^Documents$/i })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: /^Architecture$/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /careers/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Events$/i })).not.toBeInTheDocument();
  });

  it('selects constellation nodes via quick-selector pills and inspector actions', () => {
    render(
      <MemoryRouter>
        <ExperiencePage />
      </MemoryRouter>
    );

    // Initial state has 'timesheets' selected by default
    expect(screen.getByText('ACTIVE NODE FOCUS')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Jump to Timesheets Sandbox/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open Live Module/i })).toBeInTheDocument();

    // Mock scrollIntoView
    const scrollMock = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scrollMock;

    // Double-click Timesheet Core pill triggers smooth navigation to second section
    const timesheetPill = screen.getByRole('tab', { name: /Timesheet Core/i });
    fireEvent.doubleClick(timesheetPill);
    expect(scrollMock).toHaveBeenCalled();

    // Click Compliance Radar pill
    const compliancePill = screen.getByRole('tab', { name: /Compliance Radar/i });
    fireEvent.click(compliancePill);

    expect(screen.getByText(/Continuously monitors FLSA overtime policies/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Test Overtime Lab/i })).toBeInTheDocument();

    // Click Direct Reports pill
    const reportsPill = screen.getByRole('tab', { name: /Direct Reports/i });
    fireEvent.click(reportsPill);
    expect(screen.getByText(/Routes submissions to designated reporting managers/i)).toBeInTheDocument();

    // Dismiss inspector
    const dismissBtn = screen.getByRole('button', { name: /Dismiss/i });
    fireEvent.click(dismissBtn);
    expect(screen.queryByText('ACTIVE NODE FOCUS')).not.toBeInTheDocument();
  });

  it('toggles sound button state', () => {
    render(
      <MemoryRouter>
        <ExperiencePage />
      </MemoryRouter>
    );

    const soundBtn = screen.getByRole('button', { name: /Sound FX/i });
    expect(soundBtn).toBeInTheDocument();

    fireEvent.click(soundBtn);
    expect(screen.getByText(/Sound ON/i)).toBeInTheDocument();
  });

  it('interacts with TimesheetSimulator: presets, sliders, and approval simulation', () => {
    render(
      <MemoryRouter>
        <TimesheetSimulator />
      </MemoryRouter>
    );

    expect(screen.getByText(/Live Timesheet & Compliance Sandbox/i)).toBeInTheDocument();

    // Standard preset has 40 hours
    const standardBtn = screen.getByRole('button', { name: /Standard 40h/i });
    fireEvent.click(standardBtn);
    expect(screen.getAllByText('40.0').length).toBeGreaterThan(0);

    // Sprint preset has overtime
    const sprintBtn = screen.getByRole('button', { name: /Sprint 54h/i });
    fireEvent.click(sprintBtn);
    expect(screen.getByText('54.0')).toBeInTheDocument();

    // Simulate 1-Click Approval
    const approveBtn = screen.getByRole('button', { name: /Simulate 1-Click Approval/i });
    fireEvent.click(approveBtn);

    expect(screen.getByText('✓ APPROVED')).toBeInTheDocument();
    expect(screen.getByText(/WAYPOINT CERTIFIED/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Close approval stamp/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /← Back to Sandbox/i })).toBeInTheDocument();

    // Dismiss using Close Button
    const closeStampBtn = screen.getByRole('button', { name: /Close approval stamp/i });
    fireEvent.click(closeStampBtn);
    expect(screen.queryByText('✓ APPROVED')).not.toBeInTheDocument();

    // Re-trigger and dismiss using Back to Sandbox button
    fireEvent.click(approveBtn);
    expect(screen.getByText('✓ APPROVED')).toBeInTheDocument();
    const backBtn = screen.getByRole('button', { name: /← Back to Sandbox/i });
    fireEvent.click(backBtn);
    expect(screen.queryByText('✓ APPROVED')).not.toBeInTheDocument();

    // Re-trigger and dismiss using Escape key
    fireEvent.click(approveBtn);
    expect(screen.getByText('✓ APPROVED')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByText('✓ APPROVED')).not.toBeInTheDocument();
  });

  it('interacts with WorkforceRadar: switches personas and updates active details', () => {
    render(
      <MemoryRouter>
        <WorkforceRadar />
      </MemoryRouter>
    );

    expect(screen.getByText(/Workforce Orbit & Persona Ecosystem/i)).toBeInTheDocument();

    // Click HR Manager tab
    const hrTab = screen.getByRole('tab', { name: /HR Manager/i });
    fireEvent.click(hrTab);
    expect(screen.getByText('28 Assigned')).toBeInTheDocument();

    // Click Employee tab
    const empTab = screen.getByRole('tab', { name: /Employee/i });
    fireEvent.click(empTab);
    expect(screen.getByText('40.0 hrs')).toBeInTheDocument();

    // Click Candidate tab
    const candidateTab = screen.getByRole('tab', { name: /Candidate/i });
    fireEvent.click(candidateTab);
    expect(screen.getByText('140+ Roles')).toBeInTheDocument();
  });

  it('interacts with DocumentScannerWidget: switches samples and triggers laser scan', () => {
    vi.useFakeTimers();

    render(
      <DocumentScannerWidget />
    );

    expect(screen.getByText(/OCR & Document Intelligence Scanner/i)).toBeInTheDocument();

    // Trigger Laser OCR Scan
    const scanBtn = screen.getByRole('button', { name: /Trigger Laser OCR Scan/i });
    fireEvent.click(scanBtn);

    expect(screen.getByText(/Optical Laser Sweeping/i)).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2000);
    });

    expect(screen.getByText(/Zero-loss OCR parsing verified/i)).toBeInTheDocument();

    vi.useRealTimers();
  });
});
