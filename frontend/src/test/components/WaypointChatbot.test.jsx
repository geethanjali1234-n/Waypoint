import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WaypointChatbot from '../../components/chatbot/WaypointChatbot';
import { queryKnowledgeBase, QUICK_SUGGESTIONS, getQuickSuggestions } from '../../components/chatbot/knowledgeBase';

const mockedNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockedNavigate,
  };
});

describe('knowledgeBase query engine', () => {
  it('returns timesheet clearance guidance and actions for HR', () => {
    const res = queryKnowledgeBase('how do I clear timesheets?', 'HR');
    expect(res.text).toContain('1-Tap Express Clearance');
    expect(res.actions[0].path).toBe('/myhr/hr/timesheets?view=team&subtab=actions');
  });

  it('returns timesheet entry guidance for EMPLOYEE', () => {
    const res = queryKnowledgeBase('how do I submit my hours?', 'EMPLOYEE');
    expect(res.text).toContain('Log Standard Week (40h)');
    expect(res.actions[0].label).toBe('Enter Timesheet');
  });

  it('returns payroll cutoff details', () => {
    const res = queryKnowledgeBase('when is the payroll cutoff date?', 'HR');
    expect(res.text).toContain('10th of every month');
    expect(res.actions[0].label).toBe('Check Payroll Gate');
  });

  it('returns profile update guidance', () => {
    const res = queryKnowledgeBase('I need to update my address', 'EMPLOYEE');
    expect(res.text).toContain('Profile Change Requests');
    expect(res.actions[0].path).toBe('/myhr/employee/profile-change-requests');
  });

  it('accurately resolves natural query "how do i update my personal information"', () => {
    const res = queryKnowledgeBase('how do i update my personal information', 'EMPLOYEE');
    expect(res.text).toContain('Updating Personal Details');
    expect(res.text).toContain('Profile Change Requests');
    expect(res.actions[0].label).toBe('Request Update');
    expect(res.actions[0].path).toBe('/myhr/employee/profile-change-requests');
  });

  it('accurately resolves variations like "can i change my personal info"', () => {
    const res = queryKnowledgeBase('can i change my personal info', 'HR');
    expect(res.text).toContain('Profile Change Review');
    expect(res.actions[0].label).toBe('Review Profile Queue');
  });

  it('accurately resolves home address update query', () => {
    const res = queryKnowledgeBase('i need to change my home address', 'EMPLOYEE');
    expect(res.text).toContain('Updating Personal Details');
    expect(res.actions[0].path).toBe('/myhr/employee/profile-change-requests');
  });

  it('accurately resolves contact details update query', () => {
    const res = queryKnowledgeBase('how do i edit my contact details', 'EMPLOYEE');
    expect(res.text).toContain('Updating Personal Details');
  });

  it('returns document vault guidance', () => {
    const res = queryKnowledgeBase('where are tax forms and w4?', 'HR');
    expect(res.text).toContain('Document Vault');
    expect(res.actions[0].path).toBe('/myhr/hr/document-requests');
  });

  it('returns timesheet submission deadlines and handles typos like timesheety', () => {
    const res = queryKnowledgeBase('i want to know by when should i submit my timesheety', 'HR');
    expect(res.text).toContain('Timesheet Submission Deadlines');
    expect(res.text).toContain('Friday 5:00 PM');
    expect(res.text).toContain('10th of every month');
    expect(res.actions[0].path).toBe('/myhr/hr/timesheets');
  });

  it('returns guidance on resolving rejected timesheets', () => {
    const res = queryKnowledgeBase('why was my timesheet rejected and how do I fix it?', 'EMPLOYEE');
    expect(res.text).toContain('Resolving a Rejected Timesheet');
    expect(res.text).toContain('Reason for Change');
  });

  it('returns overtime and weekend policy', () => {
    const res = queryKnowledgeBase('what is the overtime and weekend hours policy?', 'HR');
    expect(res.text).toContain('Standard Hours & Overtime Policy');
    expect(res.text).toContain('8 hours/day, 40 hours/week');
  });

  it('returns missed cutoff consequences', () => {
    const res = queryKnowledgeBase('what happens if i missed payroll cutoff?', 'HR');
    expect(res.text).toContain('Late Submission & Missed Cutoff');
  });

  it('returns sick leave and zero hours rules', () => {
    const res = queryKnowledgeBase('can i enter 0 hours for sick leave?', 'EMPLOYEE');
    expect(res.text).toContain('Recording Sick Leave & Zero-Hour Days');
    expect(res.text).toContain('provide a note or reason');
  });

  it('returns guidance for job search and applications', () => {
    const res = queryKnowledgeBase('how do I search and apply for jobs?', 'EMPLOYEE');
    expect(res.text).toContain('Browsing & Searching Careers');
    expect(res.actions[0].path).toBe('/careers');
  });

  it('returns guidance for employers posting a job opening', () => {
    const res = queryKnowledgeBase('how do employers post a job opening?', 'HR');
    expect(res.text).toContain('Posting a Job Opening');
    expect(res.actions[0].path).toBe('/employer/jobs/new');
  });

  it('returns onboarding 6 mandatory documents requirements', () => {
    const res = queryKnowledgeBase('what documents are needed for offer letter onboarding?', 'EMPLOYEE');
    expect(res.text).toContain('6 mandatory onboarding documents');
    expect(res.text).toContain('Direct Deposit Authorization');
    expect(res.actions[0].path).toBe('/myhr/employee/document-requests');
  });

  it('returns employment verification letter workflow guidance', () => {
    const res = queryKnowledgeBase('how do i get an employment verification letter for mortgage?', 'EMPLOYEE');
    expect(res.text).toContain('Employment Verification Letters');
    expect(res.actions[0].label).toBe('Request Letter');
  });

  it('returns STEM OPT Form I-983 guidance', () => {
    const res = queryKnowledgeBase('how do I complete form i-983 stem opt training plan?', 'EMPLOYEE');
    expect(res.text).toContain('STEM OPT Form I-983 Training Plan');
  });

  it('returns profile strict validation rules', () => {
    const res = queryKnowledgeBase('why is my zip code format showing invalid on profile?', 'EMPLOYEE');
    expect(res.text).toContain('5 numeric digits');
    expect(res.text).toContain('(XXX) XXX-XXXX');
  });

  it('does not reflect browse job or post job in HR workspace suggestions', () => {
    const hrSuggestions = getQuickSuggestions('HR');
    expect(hrSuggestions.some(s => s.label.includes('Browse Jobs'))).toBe(false);
    expect(hrSuggestions.some(s => s.label.includes('Post a Job'))).toBe(false);

    const empSuggestions = getQuickSuggestions('EMPLOYEE');
    expect(empSuggestions.some(s => s.label.includes('Browse Jobs'))).toBe(false);
    expect(empSuggestions.some(s => s.label.includes('Post a Job'))).toBe(false);
  });

  it('returns fallback with common shortcuts for unknown query', () => {
    const res = queryKnowledgeBase('random unknown query xyzz', 'HR');
    expect(res.text).toContain("couldn't find an exact match");
    expect(res.actions.length).toBeGreaterThan(0);
  });
});

describe('WaypointChatbot Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the floating launcher button initially', () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    const trigger = screen.getByRole('button', { name: /Waypoint Assistant/i });
    expect(trigger).toBeInTheDocument();
    expect(trigger).toHaveAttribute('title', 'Waypoint Assistant');
  });

  it('opens chat window on click with header, greeting, and suggestions', () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));

    expect(screen.getByRole('dialog', { name: /Waypoint Assistant Chat/i })).toBeInTheDocument();
    expect(screen.getAllByText('Waypoint Assistant').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/Online · Instant Answers/i)).toBeInTheDocument();
    expect(screen.getByText(/How can I help you today\?/i)).toBeInTheDocument();
    expect(screen.getByText(QUICK_SUGGESTIONS[0].label)).toBeInTheDocument();
  });

  it('does not render browse jobs or post a job chips in HR workspace chat', () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));

    expect(screen.queryByRole('button', { name: /Browse Jobs/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Post a Job/i })).not.toBeInTheDocument();
  });

  it('sends message via suggestion chip and receives assistant reply with action button', async () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));

    // Click the Express Clearance suggestion chip
    const chip = screen.getByText(/Express Clearance/i);
    fireEvent.click(chip);

    // Wait for typing to finish and response to appear
    await waitFor(() => {
      expect(screen.getByText(/1-Tap Express Clearance Criteria/i)).toBeInTheDocument();
    }, { timeout: 1500 });

    // Verify action button is clickable and navigates
    const actionBtn = screen.getByRole('button', { name: /Review & Clear Timesheets/i });
    expect(actionBtn).toBeInTheDocument();

    fireEvent.click(actionBtn);
    expect(mockedNavigate).toHaveBeenCalledWith('/myhr/hr/timesheets?view=team&subtab=actions');
  });

  it('accurately answers submission deadline query even with typos like "timesheety"', async () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));

    const input = screen.getByPlaceholderText(/Ask anything or search workflows/i);
    fireEvent.change(input, { target: { value: 'i want to know by when should i submit my timesheety' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    await waitFor(() => {
      expect(screen.getByText(/Timesheet Submission Deadlines:/i)).toBeInTheDocument();
    }, { timeout: 1500 });

    expect(screen.getByText(/Friday 5:00 PM/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Check Timesheets/i })).toBeInTheDocument();
  });

  it('allows user to type custom query and submit via Enter key', async () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));

    const input = screen.getByPlaceholderText(/Ask anything or search workflows/i);
    fireEvent.change(input, { target: { value: 'when is payroll cutoff?' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    await waitFor(() => {
      expect(screen.getByText(/10th of every month/i)).toBeInTheDocument();
    }, { timeout: 1500 });
  });

  it('clears conversation when trash button is clicked', () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));
    fireEvent.click(screen.getByRole('button', { name: /Clear chat/i }));

    expect(screen.getByText(/Chat cleared!/i)).toBeInTheDocument();
  });

  it('closes the chat window when close button is clicked', () => {
    render(
      <MemoryRouter>
        <WaypointChatbot />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Close assistant/i }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Waypoint Assistant/i })).toBeInTheDocument();
  });
  it('closes on Escape and restores focus to the launcher', () => {
    render(<MemoryRouter><WaypointChatbot /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: /Waypoint Assistant/i }));
    const input = screen.getByRole('textbox', { name: 'Ask assistant a question' });
    input.focus();
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Waypoint Assistant/i })).toHaveFocus();
  });
});
