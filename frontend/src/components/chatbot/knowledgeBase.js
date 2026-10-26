/**
 * Waypoint Comprehensive Knowledge Base & Scored NLP Intent Engine
 * Features:
 * - Advanced tokenization & n-gram extraction (unigrams, bigrams, trigrams)
 * - Semantic concept dictionary mapping natural language to canonical intents
 * - Levenshtein fuzzy distance matching for misspellings and typos
 * - 50+ enterprise workforce, career, employer, and compliance scenarios
 */

export const QUICK_SUGGESTIONS = [
  { label: '⏰ Submission Deadlines', query: 'When should I submit my timesheet?' },
  { label: '🛡️ Payroll Cutoff', query: 'When is the monthly payroll cutoff date?' },
  { label: '⚡ Express Clearance', query: 'How does 1-tap express clearance work?' },
  { label: '❌ Rejected Timesheets', query: 'Why was my timesheet rejected and how do I fix it?' },
  { label: '📝 Profile Updates', query: 'How do I update my personal information?' },
  { label: '📑 Tax Forms & W-4', query: 'Where do I find my W-4 and I-9 documents?' }
];

/**
 * Returns role-tailored quick suggestions.
 * In the MyHR workspace (HR, COMPANY_ADMIN, EMPLOYEE), external job posting
 * and browsing chips are omitted to keep the workspace focused.
 */
export function getQuickSuggestions(role = 'HR') {
  if (role === 'JOB_SEEKER') {
    return [
      { label: '💼 Browse Jobs', query: 'How do I search and apply for jobs?' },
      { label: '📋 Application Status', query: 'How do I track my job applications?' },
      { label: '📅 Career Events', query: 'How do I register for career events?' },
      { label: '📝 Candidate Profile', query: 'How do I update my candidate resume?' }
    ];
  }
  if (role === 'EMPLOYER') {
    return [
      { label: '👥 Post a Job', query: 'How do employers post a job opening?' },
      { label: '📊 ATS Pipeline', query: 'How do I evaluate applicants in the ATS?' },
      { label: '🎪 Host an Event', query: 'How do I create and host recruiting events?' },
      { label: '🏢 Company Profile', query: 'How do I update employer branding and profile?' }
    ];
  }
  // Internal MyHR Workspace (HR, COMPANY_ADMIN, EMPLOYEE)
  return QUICK_SUGGESTIONS;
}

/**
 * Levenshtein distance algorithm for calculating string edit distance.
 */
function levenshtein(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  const matrix = [];
  for (let i = 0; i <= b.length; i++) matrix[i] = [i];
  for (let j = 0; j <= a.length; j++) matrix[0][j] = j;
  for (let i = 1; i <= b.length; i++) {
    for (let j = 1; j <= a.length; j++) {
      if (b.charAt(i - 1) === a.charAt(j - 1)) {
        matrix[i][j] = matrix[i - 1][j - 1];
      } else {
        matrix[i][j] = Math.min(
          matrix[i - 1][j - 1] + 1,
          matrix[i][j - 1] + 1,
          matrix[i - 1] + 1
        );
      }
    }
  }
  return matrix[b.length][a.length];
}

/**
 * Normalizes input text: cleans punctuation, expands contractions, maps common typos & stems.
 */
function normalizeInput(text) {
  let cleaned = text.toLowerCase()
    .replace(/’|'/g, '')
    .replace(/[^a-z0-9\s-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  // Typo & stem replacements
  cleaned = cleaned
    .replace(/\btimesheety\b|\btimesheets\b|\btimesheetz\b|\btimesheeting\b|\btimecard\b|\btimecards\b/g, 'timesheet')
    .replace(/\bpayrolls\b|\bpayrol\b/g, 'payroll')
    .replace(/\bcutoffs\b|\bcut-off\b/g, 'cutoff')
    .replace(/\bdeadlines\b|\bdue-date\b|\bdues\b/g, 'deadline')
    .replace(/\bsubmits\b|\bsubmitting\b|\bsubmition\b|\bsubmission\b/g, 'submit')
    .replace(/\bapprovals\b|\bapproving\b|\bapprov\b/g, 'approve')
    .replace(/\brejections\b|\brejecting\b|\brejected\b/g, 'reject')
    .replace(/\bdocuments\b|\bdoc\b|\bdocs\b/g, 'document')
    .replace(/\bprofiles\b/g, 'profile')
    .replace(/\baddresses\b|\baddres\b/g, 'address')
    .replace(/\bpasswords\b|\bpasscode\b/g, 'password')
    .replace(/\bovertimes\b|\bot\b/g, 'overtime')
    .replace(/\bresumes\b|\bcv\b/g, 'resume')
    .replace(/\bapplicants\b|\bcandidats\b|\bcandidates\b/g, 'applicant')
    .replace(/\bsignatures\b|\bsigning\b|\be-sign\b|\besign\b|\bsign\b/g, 'signature');

  return cleaned;
}

/**
 * Tokenizes text and extracts unigrams, bigrams, and trigrams.
 */
function tokenizeText(text) {
  const words = text.split(/\s+/).filter(Boolean);
  const ngrams = new Set(words);
  for (let i = 0; i < words.length - 1; i++) {
    ngrams.add(`${words[i]} ${words[i + 1]}`);
    if (i < words.length - 2) {
      ngrams.add(`${words[i]} ${words[i + 1]} ${words[i + 2]}`);
    }
  }
  return { words, ngrams: Array.from(ngrams) };
}

/**
 * Semantic Concept Mappings: Maps common natural phrases to canonical intent targets.
 */
const CONCEPT_MAPPINGS = [
  {
    targetId: 'profile-update',
    patterns: [
      'personal information', 'personal info', 'personal details', 'my details', 'my info',
      'contact info', 'contact details', 'residential address', 'home address', 'change address',
      'update address', 'update phone', 'change phone', 'emergency contact', 'legal name',
      'change name', 'update profile', 'edit profile', 'change personal', 'update personal',
      'update my personal', 'change my info', 'edit personal'
    ]
  },
  {
    targetId: 'timesheet-deadline',
    patterns: [
      'when submit', 'deadline', 'due date', 'by when', 'due', 'last day to submit',
      'submission deadline', 'timesheet deadline', 'cutoff to submit', 'when should i submit',
      'submit my timesheet'
    ]
  },
  {
    targetId: 'payroll-late',
    patterns: [
      'missed payroll', 'late payroll', 'missed cutoff', 'late timesheet',
      'missed deadline', 'what happens if i missed', 'submitted late', 'what happens if i miss'
    ]
  },
  {
    targetId: 'payroll-cutoff',
    patterns: [
      'payroll cutoff', 'cutoff date', 'payroll date', 'monthly payroll',
      'cutoff schedule', 'when is cutoff', 'payroll deadline', 'when is payroll'
    ]
  },
  {
    targetId: 'express-clearance',
    patterns: [
      '1 tap', '1-tap', 'express clearance', 'fast pass', 'fast-pass',
      'clear timesheet', 'clear timesheets', 'batch clear', 'how do i clear timesheets'
    ]
  },
  {
    targetId: 'timesheet-rejected',
    patterns: [
      'rejected timesheet', 'fix rejection', 'why was my timesheet rejected',
      'timesheet rejected', 'denied timesheet', 'reason for change', 'resubmit rejected'
    ]
  },
  {
    targetId: 'employment-verification-letter',
    patterns: [
      'employment verification', 'proof of employment', 'job verification',
      'verification letter', 'mortgage letter', 'lease letter', 'apartment letter',
      'verify employment'
    ]
  },
  {
    targetId: 'salary-verification-letter',
    patterns: [
      'salary verification', 'income verification', 'proof of income',
      'pay verification', 'compensation letter', 'earnings letter'
    ]
  },
  {
    targetId: 'onboarding-packet',
    patterns: [
      'offer letter', 'onboarding packet', '6 documents', 'onboarding documents',
      'pre-employment', 'new hire packet', 'mandatory documents', 'sign offer'
    ]
  },
  {
    targetId: 'i983-stem-opt',
    patterns: [
      'i983', 'i-983', 'stem opt', 'stem extension', 'training plan', 'dso', 'opt i-983'
    ]
  },
  {
    targetId: 'tax-forms',
    patterns: [
      'tax form', 'tax forms', 'w4', 'w-4', 'i9', 'i-9', 'w-2', 'w2', 'withholding certificate'
    ]
  },
  {
    targetId: 'digital-signature',
    patterns: [
      'e-sign', 'esign', 'draw signature', 'sign document', 'signature placement',
      'sign contract', 'digital signature', 'upload signature'
    ]
  },
  {
    targetId: 'career-search',
    patterns: [
      'search jobs', 'browse jobs', 'find jobs', 'job openings', 'careers',
      'apply for jobs', 'open positions', 'job listings', 'search and apply'
    ]
  },
  {
    targetId: 'employer-post-job',
    patterns: [
      'post a job', 'post job', 'create job', 'new job posting', 'post opening'
    ]
  },
  {
    targetId: 'employer-ats',
    patterns: [
      'applicant tracking', 'review candidate', 'candidate status', 'ats pipeline',
      'evaluate applicant', 'candidate resume'
    ]
  },
  {
    targetId: 'password-security',
    patterns: [
      'forgot password', 'reset password', 'change password', 'locked out',
      'password security', 'login password'
    ]
  }
];

export const TOPIC_SCENARIOS = [
  // ==========================================
  // SECTION 1: TIMESHEETS & HOURS
  // ==========================================

  // 1. Timesheet Submission Deadline & Schedules
  {
    id: 'timesheet-deadline',
    primary: ['deadline', 'due date', 'when submit', 'submit deadline', 'timesheet deadline', 'by when', 'due', 'when should i submit'],
    secondary: ['timesheet', 'submit', 'when', 'date', 'day', 'schedule', 'hours', 'by when', 'friday', 'sunday', 'cutoff'],
    scoreModifier: 6,
    respond: (role, basePath) => ({
      text: "**Timesheet Submission Deadlines:**\n• **Weekly Cadence**: Submit your hours at the end of each work week (by **Friday 5:00 PM** or **Sunday 11:59 PM**).\n• **Monthly Payroll Cutoff**: All timesheets must be submitted and approved by the **10th of every month at midnight**.\n• **Important**: Early submission is encouraged to allow your manager adequate time to review and sign off.",
      actions: [
        { label: role === 'EMPLOYEE' ? 'Enter Timesheet Now' : 'Check Timesheets', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 2. 1-Tap Express Clearance
  {
    id: 'express-clearance',
    primary: ['express clearance', 'express', 'fast pass', 'fast-pass', '1 tap', '1-tap', 'clearance', 'clear timesheet', 'clear timesheets', 'clear', 'how do i clear timesheets'],
    secondary: ['how', 'work', 'criteria', 'qualify', 'approve', 'timesheet'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**1-Tap Express Clearance Criteria:**\n• Applies to timesheets with standard hours (**≤8 hours/day**, **40 hours/week**).\n• Requires **zero date conflicts** and an attached supporting document.\n• Zero overtime or abnormal days.\n• Managers can clear all qualifying compliant submissions simultaneously in **1 tap** from the **Required Actions** view.",
      actions: [
        { label: 'Review & Clear Timesheets', path: `${basePath}/timesheets?view=team&subtab=actions` }
      ]
    })
  },

  // 3. Rejected Timesheets & Resubmission
  {
    id: 'timesheet-rejected',
    primary: ['timesheet', 'rejected', 'rejection'],
    secondary: ['reject', 'fix', 'resubmit', 'denied', 'correction', 'change reason', 'why was', 'feedback'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Resolving a Rejected Timesheet:**\n1. Go to **Timesheets** → **Submitted Timesheets**.\n2. Look for the submission flagged with a red **Rejected** banner.\n3. Click **View Details** to read your manager's mandatory feedback.\n4. Click **Resubmit**, provide a **Reason for Change**, adjust conflicted dates/hours, and submit again.",
      actions: [
        { label: 'View Submitted Timesheets', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 4. Overtime & Weekend Hours Policy
  {
    id: 'overtime-policy',
    primary: ['overtime', 'weekend', 'extra hours'],
    secondary: ['policy', 'standard', 'rate', '40', 'hours', 'saturday', 'sunday', '40 hours'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Standard Hours & Overtime Policy:**\n• **Standard Hours**: 8 hours/day, 40 hours/week (Monday to Friday).\n• **Overtime**: Any hours exceeding 40h/week are flagged for managerial justification.\n• **Weekend Coverage**: Weekend hours (Saturday/Sunday) require project description and supporting documentation before submission.",
      actions: [
        { label: 'Enter Hours', path: `${basePath}/timesheets?mode=enter` }
      ]
    })
  },

  // 5. Conflicted & Overlapping Dates
  {
    id: 'timesheet-conflict',
    primary: ['conflict', 'overlap', 'duplicate'],
    secondary: ['timesheet', 'dates', 'error', 'already exists'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Timesheet Date Conflict Error:**\n• Waypoint prevents submitting two timesheets that cover the same calendar dates.\n• If a submission already exists for those days, an **Existing Submission Conflict** prompt appears.\n• If the prior timesheet was approved, you must contact HR to unlock it. If it was rejected, use the **Resubmit with Reason** flow.",
      actions: [
        { label: 'Check Timesheet History', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 6. Supporting Documents for Timesheets
  {
    id: 'timesheet-attachments',
    primary: ['supporting document', 'attachment', 'upload timesheet', 'proof', 'file format'],
    secondary: ['timesheet', 'size', 'pdf', 'image', 'photo', 'max size'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Timesheet Supporting Document Guidelines:**\n• **Mandatory**: All manual entries require proof (client-signed timecard, portal screenshot, punch log).\n• **Supported Formats**: PDF, PNG, JPG, JPEG.\n• **Max File Size**: Up to **10MB** per attachment.\n• High-resolution images and clear document scans ensure faster manager approval.",
      actions: [
        { label: 'Go to Timesheets', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 7. General Timesheet Entry & Submission
  {
    id: 'timesheet-entry',
    primary: ['timesheet', 'hours', 'log week', 'submit hours', 'enter hours'],
    secondary: ['enter', 'submit', 'how to', 'record', 'create', 'how do i'],
    scoreModifier: 3,
    respond: (role, basePath) => ({
      text: role === 'EMPLOYEE'
        ? "**How to Enter & Submit Timesheets:**\n1. Go to **Timesheets**.\n2. Click **Log Standard Week (40h)** for fast 8h/day autofill, or **Daily Hours** for custom hours.\n3. Upload your supporting document.\n4. Click **Submit for Approval** (or **Save Draft** to complete later)."
        : "**Timesheet Management:**\n• Review direct report hours, inspect attached documents, and approve or reject submissions.\n• Use **1-Tap Express Clearance** to clear all compliant submissions simultaneously.",
      actions: [
        { label: role === 'EMPLOYEE' ? 'Enter Timesheet' : 'Review Timesheets', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 8. Sick Leave & Zero-Hour Days
  {
    id: 'sick-leave-pto',
    primary: ['sick leave', 'zero hours', 'pto', 'time off', 'holiday'],
    secondary: ['take day off', 'absence', 'sick', 'vacation', '0 hours', 'provide a note or reason'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Recording Sick Leave & Zero-Hour Days:**\n• If you did not work on a regular workday, enter **0 hours** for that date.\n• **Mandatory**: You must provide a note or reason (e.g. *'Sick leave'*, *'Company holiday'*).\n• Days left at 0 hours without a description will be blocked from submission.",
      actions: [
        { label: 'Enter Timesheet', path: `${basePath}/timesheets?mode=enter` }
      ]
    })
  },

  // 9. Timesheet Drafts
  {
    id: 'timesheet-drafts',
    primary: ['draft', 'save draft', 'unfinished timesheet'],
    secondary: ['timesheet', 'save', 'later', 'incomplete'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Saving Timesheet Drafts:**\n• You can save partial hours at any point by clicking **Save Draft**.\n• Drafts do **not** trigger manager notifications and do not require a supporting document until final submission.\n• When ready, return to **Timesheets** to upload proof and click **Submit for Approval**.",
      actions: [
        { label: 'Open Timesheets', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 10. Timesheet History & Pay Period PDF Summary
  {
    id: 'timesheet-history',
    primary: ['timesheet history', 'past timesheet', 'period details', 'pay period pdf', 'download timesheet'],
    secondary: ['filter', 'history', 'month', 'pdf', 'previous'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Timesheet History & Period Downloads:**\n• View past submissions grouped by monthly calendar buckets or custom date range.\n• Click **View Period Details** on any approved period card to inspect daily breakdown.\n• Click **Download Pay Period PDF** to save an official signed timesheet summary.",
      actions: [
        { label: 'View Timesheet History', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 11. Manager Detailed Timesheet Review & Rejection Feedback
  {
    id: 'manager-timesheet-review',
    primary: ['review timesheet', 'reject timesheet', 'approve timesheet', 'rejection reason'],
    secondary: ['manager', 'detailed review', 'comment', 'feedback', 'timesheet'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Manager Review & Approval Workflow:**\n1. In **Timesheets** → **Required Actions**, select any submission.\n2. Click the supporting document to preview it in the interactive document viewer.\n3. Click **Approve** to clear hours, or **Reject**.\n4. When rejecting, entering specific corrective feedback in the **Rejection Reason Modal** is mandatory.",
      actions: [
        { label: 'Open Review Actions', path: `${basePath}/timesheets?view=team&subtab=actions` }
      ]
    })
  },

  // ==========================================
  // SECTION 2: PAYROLL & CUTOFF SCHEDULES
  // ==========================================

  // 12. Payroll Cutoff Date & Times
  {
    id: 'payroll-cutoff',
    primary: ['payroll cutoff', 'cutoff date', 'payroll schedule', 'monthly cutoff', 'cutoff day'],
    secondary: ['when', 'date', 'time', 'monthly', 'schedule', 'day', '10th', 'payroll', 'cutoff'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Monthly Payroll Cutoff Schedule:**\n• **Cutoff Date**: The **10th of every month at 11:59 PM**.\n• **Automated Reminders**: Sent to all employees on days **5, 7, 9, and 10**.\n• **Cutoff Enforcement**: Submissions not approved by the 10th will not be processed in the regular monthly payroll cycle.",
      actions: role === 'EMPLOYEE'
        ? [{ label: 'Check My Timesheets', path: `${basePath}/timesheets` }]
        : [{ label: 'Check Payroll Gate', path: `${basePath}/timesheets?view=payroll` }]
    })
  },

  // 13. Missed Cutoff / Late Submission
  {
    id: 'payroll-late',
    primary: ['missed payroll', 'late payroll', 'missed cutoff', 'late timesheet', 'late', 'missed', 'forgot'],
    secondary: ['payroll', 'timesheet', 'cutoff', 'salary', 'next cycle', 'what happens'],
    scoreModifier: 8,
    respond: (role, basePath) => ({
      text: "**Late Submission & Missed Cutoff:**\n• Timesheets submitted after the 10th cutoff cannot pass the automated Payroll Gate.\n• **Resolution**: They will automatically roll over to the following payroll cycle, or require HR to initiate an off-cycle supplemental clearance.",
      actions: [
        { label: 'View Timesheets', path: `${basePath}/timesheets` }
      ]
    })
  },

  // 14. Payroll Readiness Gate Status & Exports
  {
    id: 'payroll-gate',
    primary: ['payroll gate', 'readiness gate', 'payroll readiness', 'gate status', 'export payroll'],
    secondary: ['check', 'status', 'score', 'export', 'blocked', 'ready'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Payroll Readiness Gate:**\n• Automated calculation engine that verifies 100% compliance across all active employees before payroll processing.\n• **Status**: Shows **Ready** when all hours are approved, or **Blocked** highlighting missing timesheets or pending manager approvals.\n• Includes 1-click **Export to CSV / Excel** for finance and accounting.",
      actions: [
        { label: 'Check Payroll Gate', path: `${basePath}/timesheets?view=payroll` }
      ]
    })
  },

  // ==========================================
  // SECTION 3: PROFILE & PERSONAL DATA
  // ==========================================

  // 15. Profile Updates (Personal Information, Address, Name, Phone)
  {
    id: 'profile-update',
    primary: [
      'profile', 'personal information', 'personal info', 'personal details', 'my details', 'my info',
      'address', 'phone', 'emergency contact', 'legal name', 'contact details'
    ],
    secondary: ['change', 'update', 'request', 'edit', 'how to', 'modify', 'how do i update', 'how do i change'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: role === 'EMPLOYEE'
        ? "**Updating Personal Details:**\n1. Navigate to **Profile Change Requests**.\n2. Tap **Request Profile Update**.\n3. Modify your residential address, phone, or emergency contacts.\n4. Submit for HR verification (typically processed within 24-48 hours)."
        : "**Profile Change Review:**\n• Inspect pending employee address, contact, and legal name updates in the approval queue.\n• Compare current vs requested changes and approve or reject with comments.",
      actions: [
        { label: role === 'EMPLOYEE' ? 'Request Update' : 'Review Profile Queue', path: `${basePath}/profile-change-requests` }
      ]
    })
  },

  // 16. Profile Validation Rules (Phone, ZIP, Name)
  {
    id: 'profile-validation',
    primary: ['zip code', 'phone format', 'validation error', 'invalid phone', 'zip format'],
    secondary: ['profile', 'format', '5 digits', '5 numeric digits', 'rule', 'address', '(xxx) xxx-xxxx'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Profile Field Validation Standards:**\n• **ZIP Code**: Must be exactly **5 numeric digits** (US postal standard).\n• **Phone Number**: 10 digits formatted as `(XXX) XXX-XXXX` or digits only.\n• **Names**: Letters and single spaces only (no symbols or numbers allowed).",
      actions: [
        { label: 'Profile Requests', path: `${basePath}/profile-change-requests` }
      ]
    })
  },

  // 17. Profile Request Status & Timeline
  {
    id: 'profile-status',
    primary: ['profile request', 'change request'],
    secondary: ['status', 'pending', 'how long', 'approved', 'timeline'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Profile Request Review Timeline:**\n• Submissions enter the HR Review Queue with status **PENDING**.\n• Standard review turnaround is **1–2 business days**.\n• Once approved by HR, your employee record updates instantly across the system.",
      actions: [
        { label: 'View Change Requests', path: `${basePath}/profile-change-requests` }
      ]
    })
  },

  // ==========================================
  // SECTION 4: DOCUMENT WORKFLOWS & E-SIGNATURES
  // ==========================================

  // 18. Onboarding Packet & Offer Letter (6 Mandatory Docs)
  {
    id: 'onboarding-packet',
    primary: ['offer letter', 'onboarding packet', 'pre-employment', 'onboarding documents', '6 documents', '6 mandatory documents', 'direct deposit authorization'],
    secondary: ['mandatory', 'required', 'sign offer', 'upload', 'new hire'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Onboarding & Offer Letter Package:**\n• The Offer Letter workflow requires **6 mandatory onboarding documents**:\n  1. Government ID Proof\n  2. SSN Card / Verification\n  3. Federal Form W-4\n  4. Form I-9 with supporting eligibility documents\n  5. Direct Deposit Authorization\n  6. Emergency Contact Form\n• Once uploaded and labeled, HR reviews the packet, applies signature fields, and dispatches for e-signature.",
      actions: [
        { label: 'Document Requests', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 19. Employment Verification Letter
  {
    id: 'employment-verification-letter',
    primary: ['employment verification', 'proof of employment', 'job verification', 'employment verification letters'],
    secondary: ['letter', 'mortgage', 'lease', 'apartment', 'request', 'how do i get'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Employment Verification Letters:**\n• Official documentation certifying job title, start date, active employment status, and office location.\n• Commonly used for mortgage applications, apartment leases, and visa renewals.\n• Prepared by HR and immediately available for PDF download once approved.",
      actions: [
        { label: role === 'EMPLOYEE' ? 'Request Letter' : 'Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 20. Salary Verification Letter
  {
    id: 'salary-verification-letter',
    primary: ['salary verification', 'income verification', 'proof of income', 'pay verification'],
    secondary: ['letter', 'compensation', 'bank', 'loan'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Salary Verification Letters:**\n• Verifies employee compensation, pay frequency, and bonus structure on corporate letterhead.\n• Generated securely through HR with authorized company signatory details.",
      actions: [
        { label: 'Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 21. Experience & Relieving Letter (Separation)
  {
    id: 'experience-relieving-letter',
    primary: ['experience letter', 'relieving letter', 'separation letter', 'service certificate'],
    secondary: ['leaving', 'resignation', 'tenure', 'relieving'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Experience & Relieving Letters (Separation):**\n• Single unified document confirming total tenure, final job title, department, and formal release of corporate liabilities.\n• Both HR and employee review and complete digital signing prior to archiving.",
      actions: [
        { label: 'Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 22. Immigration Filings (LCA, PERM, Form I-140)
  {
    id: 'immigration-documents',
    primary: ['lca', 'perm', 'i-140', 'i140', 'immigration', 'visa document'],
    secondary: ['upload', 'h-1b', 'h1b', 'labor certification', 'green card'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Immigration Compliance Filings (LCA, PERM, I-140):**\n• Filed documents that HR or Company Admins upload directly to the employee's compliance record.\n• Employees can securely preview and download certified copies without requiring signature return.",
      actions: [
        { label: 'Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 23. STEM OPT Form I-983 Training Plan
  {
    id: 'i983-stem-opt',
    primary: ['i-983', 'i983', 'stem opt', 'training plan', 'form i-983'],
    secondary: ['f-1', 'opt', 'university', 'dso', 'mentor', 'stem'],
    scoreModifier: 6,
    respond: (role, basePath) => ({
      text: "**STEM OPT Form I-983 Training Plan:**\n• Specialized workflow for F-1 visa STEM extension candidates.\n• Includes employer information, site location, customized training curriculum, and mentor evaluations.\n• Features dedicated dual-party digital signing (`/i983-sign`).",
      actions: [
        { label: 'Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 24. Tax Forms & W-4 / I-9
  {
    id: 'tax-forms',
    primary: ['tax', 'w4', 'w-4', 'i9', 'i-9', 'withholding'],
    secondary: ['form', 'document', 'download', 'upload', 'exempt', 'document vault'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Tax Forms & Employment Eligibility:**\n• **Form W-4**: Employee's Withholding Certificate for federal income tax.\n• **Form I-9**: Verification of employment authorization and identity.\n• All tax documents and signed forms are encrypted and stored in the **Document Vault**.",
      actions: [
        { label: 'Open Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 25. Digital Signatures & e-Signing Placement
  {
    id: 'digital-signature',
    primary: ['signature', 'e-sign', 'contract', 'draw signature', 'upload signature'],
    secondary: ['digital', 'how to', 'draw', 'type', 'sign document', 'coordinates', 'place'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Digital Signatures & Contract e-Signing:**\n• **Setting up your signature**: Draw your signature or upload an image (with automatic background contrast cleanup) in your **Profile**.\n• **Signing**: Open any pending document, click or drag to position your signature on the page canvas, and click **Confirm & Apply Signature**.",
      actions: [
        { label: 'Go to Document Vault', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 26. Returning Documents Unsigned with Reason
  {
    id: 'signature-return',
    primary: ['return document', 'reject document', 'wrong details document', 'decline to sign'],
    secondary: ['document', 'reason', 'incorrect', 'fix'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Returning Documents Unsigned:**\n• If a sent document has errors (e.g. incorrect compensation, misspelled name, wrong dates), do not sign.\n• Click **Return to HR**, select the issue category, and enter mandatory notes.\n• The status changes to **Returned**, allowing HR to make corrections and re-dispatch.",
      actions: [
        { label: 'Document Requests', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 27. HR Sending Document Requests to Employees
  {
    id: 'request-documents-hr',
    primary: ['request document', 'send form', 'request doc'],
    secondary: ['employee', 'onboarding', 'hr', 'send to'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Requesting Documents from Employees:**\n1. Go to **Request Documents**.\n2. Select the target employee and document template (Offer Letter, Non-Disclosure, W-4, I-983).\n3. Set deadline and click **Send Request**. The employee will be notified immediately.",
      actions: [
        { label: 'Request Documents', path: `${basePath}/document-requests` }
      ]
    })
  },

  // 28. Employee Requesting Documents from HR
  {
    id: 'employee-request-document',
    primary: ['ask hr for letter', 'request letter from hr', 'request certificate'],
    secondary: ['document', 'employee', 'need letter', 'apply letter'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Requesting HR Letters as an Employee:**\n1. Go to **Document Requests** → **New Request**.\n2. Choose request type (Employment Verification, Salary Verification, Separation Letter, or Other).\n3. Add notes or specific deadlines and click **Submit Request**.",
      actions: [
        { label: 'Request Document', path: `${basePath}/document-requests/new` }
      ]
    })
  },

  // ==========================================
  // SECTION 5: STAFF MANAGEMENT & ADMIN
  // ==========================================

  // 29. Employee Directory & Roster
  {
    id: 'employee-roster',
    primary: ['employee', 'staff', 'roster', 'headcount', 'worker'],
    secondary: ['add', 'invite', 'new', 'hire', 'directory', 'list', 'manage', 'create', 'onboarding'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: role === 'EMPLOYEE'
        ? "You can view your assigned manager and team information in your **Profile**."
        : "**Employee Management:**\n• View active, inactive, and onboarding personnel.\n• Invite new team members with automatic employee ID generation (e.g. TD001, TD002).\n• Assign reporting managers, departments, and workspace roles.",
      actions: role === 'EMPLOYEE'
        ? [{ label: 'View Profile', path: `${basePath}/profile` }]
        : [{ label: 'Employee Management', path: `${basePath}/employees` }]
    })
  },

  // 30. Account Creation & Dev Credentials
  {
    id: 'create-employee',
    primary: ['add employee', 'create account', 'new staff', 'dev credential'],
    secondary: ['login id', 'temporary password', 'admin', 'hr', 'role'],
    scoreModifier: 5,
    respond: (role, basePath) => ({
      text: "**Creating Employee & HR Accounts:**\n• Company Admins can create **HR** or **Employee** accounts.\n• When creating an Employee, selecting an **Active Reporting HR Manager** is required.\n• The system generates a unique Login ID (e.g. `LOGIN-010`, `TD011`) and a secure temporary password.",
      actions: [
        { label: 'Manage Accounts', path: `${basePath}/employees` }
      ]
    })
  },

  // 31. Manager Assignments & Reassignment
  {
    id: 'manager-assignment',
    primary: ['manager', 'supervisor', 'lead'],
    secondary: ['reporting', 'relationship', 'assign', 'change', 'who is', 'reassign'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Manager Assignments:**\n• Timesheets and profile changes are automatically routed to the employee's direct reporting manager for first-level review.\n• HR & Admins can reassign reporting relationships directly in **Employee Management** by editing the employee record.",
      actions: role === 'EMPLOYEE'
        ? [{ label: 'View My Manager', path: `${basePath}/profile` }]
        : [{ label: 'Manage Employees', path: `${basePath}/employees` }]
    })
  },

  // 32. Workforce Reports & Exports
  {
    id: 'reports-export',
    primary: ['report', 'analytics', 'export', 'excel', 'csv'],
    secondary: ['workforce', 'timesheet', 'headcount', 'download', 'summary'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Reports & Analytics Catalog:**\n• **Workforce Overview**: Real-time headcount KPIs, role distributions, and PDF export.\n• **Monthly Timesheets Report**: Paginated search, status filtering, and **Excel (.xlsx)** export named automatically for the selected month.",
      actions: role === 'EMPLOYEE'
        ? []
        : [{ label: 'Open Reports Hub', path: `${basePath}/reports` }]
    })
  },

  // 33. Organization Settings & Authorized Signatory
  {
    id: 'org-settings',
    primary: ['organization', 'company settings', 'branding', 'tax id', 'signatory'],
    secondary: ['logo', 'fein', 'ein', 'period', 'config', 'authorized signatory'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Organization Configuration (Admins only):**\n• Configure Company Legal Name, Federal Employer Identification Number (FEIN), state tax ID, and payroll frequency.\n• Setup the **Authorized Signatory** (Name, Title, Signature) to automate document endorsements.\n• Upload corporate logo to brand offer letters and timesheet printouts.",
      actions: role === 'COMPANY_ADMIN'
        ? [{ label: 'Organization Settings', path: `${basePath}/organization` }]
        : []
    })
  },

  // 34. Audit Trail & Compliance
  {
    id: 'audit-trail',
    primary: ['audit', 'audit trail', 'history', 'security log'],
    secondary: ['compliance', 'track', 'records', 'activity', 'timestamp'],
    scoreModifier: 4,
    respond: (role, basePath) => ({
      text: "**Audit & Activity Logs:**\n• Every critical workforce action (timesheet approvals, profile updates, payroll exports, role changes) is recorded with an immutable audit log, IP, and actor ID.",
      actions: role === 'COMPANY_ADMIN'
        ? [{ label: 'Audit History', path: `${basePath}/audit` }]
        : []
    })
  },

  // ==========================================
  // SECTION 6: CAREERS & JOB SEEKERS
  // ==========================================

  // 35. Job Search & Filtering
  {
    id: 'career-search',
    primary: ['search jobs', 'browse jobs', 'find jobs', 'job openings', 'careers', 'apply for jobs', 'search and apply', 'job listings', 'jobs'],
    secondary: ['remote', 'salary', 'filter', 'apply', 'openings', 'positions', 'search'],
    scoreModifier: 5,
    respond: () => ({
      text: "**Browsing & Searching Careers:**\n• Explore open roles across engineering, design, operations, and management.\n• Filter by keyword, department, location, work type (**Remote**, **Hybrid**, **On-site**), and salary range.\n• Click any job card to view full descriptions, qualifications, and benefits.",
      actions: [
        { label: 'Explore Careers', path: '/careers' }
      ]
    })
  },

  // 36. Applying for a Job
  {
    id: 'job-apply',
    primary: ['apply job', 'job application', 'submit resume', 'how to apply'],
    secondary: ['cover letter', 'upload resume', 'questionnaire', 'apply'],
    scoreModifier: 5,
    respond: () => ({
      text: "**Submitting a Job Application:**\n1. Select a job opening in **Careers**.\n2. Click **Apply Now**.\n3. Upload your resume (PDF or DOCX), add optional cover letter, and complete screening questions.\n4. Submit to immediately track application status in your Job Seeker portal.",
      actions: [
        { label: 'Browse Jobs', path: '/careers' }
      ]
    })
  },

  // 37. Job Seeker Application Tracking
  {
    id: 'job-seeker-applications',
    primary: ['my applications', 'application status', 'applied jobs', 'track application'],
    secondary: ['interview', 'shortlisted', 'offer', 'withdraw', 'candidate'],
    scoreModifier: 5,
    respond: () => ({
      text: "**Application Tracking:**\n• Monitor your submissions across statuses: **Applied**, **Under Review**, **Interview Scheduled**, **Offer Extended**, or **Archived**.\n• Review submitted answers or withdraw an application at any time.",
      actions: [
        { label: 'My Applications', path: '/job-seeker/applications' }
      ]
    })
  },

  // 38. Job Seeker Profile & Resume Builder
  {
    id: 'job-seeker-profile',
    primary: ['job seeker profile', 'candidate profile', 'edit resume', 'skills'],
    secondary: ['bio', 'experience', 'education', 'upload resume', 'headline'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Candidate Profile & Resume:**\n• Highlight your professional headline, bio, and key skills.\n• Upload and maintain your master resume for 1-click applications.\n• Detail your work experience and education history.",
      actions: [
        { label: 'Edit Profile', path: '/job-seeker/profile' }
      ]
    })
  },

  // 39. Career Events & .ICS Calendar Invites
  {
    id: 'career-events',
    primary: ['career events', 'job fair', 'events', 'webinar', 'register event'],
    secondary: ['calendar', 'ics', 'networking', 'attend'],
    scoreModifier: 5,
    respond: () => ({
      text: "**Career Events & Fairs:**\n• Discover virtual and on-site career fairs, recruiting webinars, and networking meetups.\n• Register in 1 click and download **.ics calendar files** to sync directly with Google Calendar, Outlook, or Apple Calendar.",
      actions: [
        { label: 'Browse Events', path: '/events' },
        { label: 'My Registrations', path: '/job-seeker/registrations' }
      ]
    })
  },

  // 40. Job Seeker Support Contact
  {
    id: 'job-seeker-contact',
    primary: ['contact support', 'recruiter contact', 'job seeker help', 'contact us'],
    secondary: ['inquiry', 'assistance', 'problem', 'question'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Contacting Support:**\n• For inquiries regarding interview scheduling, technical issues, or recruiter follow-ups, submit a ticket via our Contact Us portal.",
      actions: [
        { label: 'Contact Support', path: '/job-seeker/contact-us' }
      ]
    })
  },

  // ==========================================
  // SECTION 7: EMPLOYERS & RECRUITING
  // ==========================================

  // 41. Employer Dashboard & Hiring Overview
  {
    id: 'employer-dashboard',
    primary: ['employer dashboard', 'hiring dashboard', 'recruiting overview'],
    secondary: ['metrics', 'postings', 'candidates', 'stats'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Employer Recruitment Dashboard:**\n• Real-time snapshot of active job postings, total applicant volume, pending reviews, and upcoming interviews.",
      actions: [
        { label: 'Employer Dashboard', path: '/employer/dashboard' }
      ]
    })
  },

  // 42. Posting & Managing Jobs
  {
    id: 'employer-post-job',
    primary: ['post a job', 'create job', 'new job posting', 'post job', 'post an opening'],
    secondary: ['salary', 'description', 'publish', 'draft', 'employer', 'how do employers post'],
    scoreModifier: 5,
    respond: () => ({
      text: "**Posting a Job Opening:**\n1. In the Employer Portal, navigate to **Jobs** → **Post New Job**.\n2. Define title, department, employment type (Full-time, Part-time, Contract), and work model (Remote, Hybrid, On-site).\n3. Set salary range and rich job requirements.\n4. Save as Draft or Publish live immediately to the Careers marketplace.",
      actions: [
        { label: 'Post a Job', path: '/employer/jobs/new' }
      ]
    })
  },

  // 43. Candidate Evaluation & ATS Pipeline
  {
    id: 'employer-ats',
    primary: ['applicant tracking', 'review candidate', 'candidate status', 'ats pipeline'],
    secondary: ['interview', 'hire', 'reject candidate', 'resume review', 'applicant'],
    scoreModifier: 5,
    respond: () => ({
      text: "**Candidate Review & ATS Pipeline:**\n• Inspect applicant resumes, experience, and custom questionnaire answers.\n• Advance candidates through hiring stages: **Applied** → **Under Review** → **Interview Scheduled** → **Hired** or **Rejected**.\n• Add internal team notes and feedback for each applicant.",
      actions: [
        { label: 'Manage Jobs & Applicants', path: '/employer/jobs' }
      ]
    })
  },

  // 44. Hosting Employer Events
  {
    id: 'employer-host-events',
    primary: ['host event', 'create event', 'employer event', 'company job fair'],
    secondary: ['attendees', 'registrants', 'webinar', 'fair'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Hosting Recruiting Events:**\n• Create branded virtual or on-site career events.\n• Track RSVP numbers and download attendee lists before the event begins.",
      actions: [
        { label: 'Employer Events', path: '/employer/events' }
      ]
    })
  },

  // 45. Employer Company Profile & Branding
  {
    id: 'employer-profile',
    primary: ['employer profile', 'company page', 'employer branding'],
    secondary: ['logo', 'description', 'industry', 'website'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Employer Company Profile:**\n• Manage your public company profile, logo, headquarters address, industry category, and cultural values to attract top talent.",
      actions: [
        { label: 'Company Profile', path: '/employer/profile' }
      ]
    })
  },

  // ==========================================
  // SECTION 8: AUTH, SECURITY & SYSTEM
  // ==========================================

  // 46. Password Change & Security
  {
    id: 'password-security',
    primary: ['password', 'reset password', 'change password'],
    secondary: ['forgot', 'update', 'security', 'login id'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Password & Account Security:**\n• To change your password, click your avatar in the top right → **Change Password**.\n• Password must be at least 8 characters with numbers and symbols.\n• If you are locked out, use the **Forgot Password** link on the login screen to verify via email OTP.",
      actions: [
        { label: 'Change Password', path: '/myhr/change-password' }
      ]
    })
  },

  // 47. First Login Password Setup
  {
    id: 'first-login-flow',
    primary: ['first login', 'temporary password', 'initial login'],
    secondary: ['new account', 'password change', 'force change'],
    scoreModifier: 5,
    respond: () => ({
      text: "**First-Time Login Security Flow:**\n• Upon initial sign-in with your temporary password, Waypoint automatically requires setting a permanent, secure password.\n• Once saved, your account is fully activated and you are routed to your workspace dashboard.",
      actions: [
        { label: 'MyHR Login', path: '/myhr/login' }
      ]
    })
  },

  // 48. Session Expiration & Auto-Logout
  {
    id: 'session-timeout',
    primary: ['session', 'logout', 'logged out', 'jwt', 'timeout'],
    secondary: ['expire', 'automatic', 'security', 'why'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Session & Security Safeguards:**\n• For compliance with enterprise workforce security standards, sessions automatically expire after **3 hours of inactivity**.\n• When a session expires, you are safely redirected to the login page to re-authenticate.",
      actions: [
        { label: 'Sign In Again', path: '/myhr/login' }
      ]
    })
  },

  // 49. Mobile App & Android APK Updates
  {
    id: 'mobile-apk',
    primary: ['mobile app', 'android app', 'apk', 'mobile application', 'download apk'],
    secondary: ['download', 'update', 'install', 'version', 'wifi', 'android', 'mobile'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Waypoint Mobile Application:**\n• Built natively for Android using Capacitor.\n• You can download the latest APK directly from the web app or login page.\n• In-app updates automatically prompt the Android Package Installer for smooth in-place upgrades.",
      actions: [
        { label: 'Download Android APK', path: '/downloads/waypoint.apk' }
      ]
    })
  },

  // 50. Quick Jump & Navigation Shortcuts
  {
    id: 'quick-navigation',
    primary: ['quick jump', 'shortcut', 'search workflow', 'ctrl k', 'theme'],
    secondary: ['navigation', 'dark mode', 'how to navigate', 'find page'],
    scoreModifier: 4,
    respond: () => ({
      text: "**Workspace Navigation Shortcuts:**\n• **Quick Jump**: Press **Ctrl + K** (or tap the search bar in the top navigation) to instantly open any feature or document.\n• **Theme Toggle**: Switch between sleek Dark Mode and Crisp Light Mode using the moon/sun icon in the top header.\n• **Notification Bell**: Shows unread alerts on timesheets, approvals, and signatures.",
      actions: []
    })
  },

  // 51. Greetings & Bot Intro
  {
    id: 'greeting',
    primary: ['hi', 'hello', 'hey', 'greetings', 'who are you', 'help'],
    secondary: ['what can you do', 'features', 'assist'],
    scoreModifier: 2,
    respond: (role) => {
      const isMyHr = role === 'HR' || role === 'COMPANY_ADMIN' || role === 'EMPLOYEE';
      const topics = isMyHr
        ? `• ⏰ **Timesheet Deadlines & 1-Tap Clearance**\n• 🛡️ **Monthly Payroll Cutoffs (10th @ midnight)**\n• ❌ **Resolving Rejected Timesheets**\n• 📝 **Updating Personal Details & Strict US Validations**\n• 📑 **Offer Letters, Tax W-4, I-9 & Digital Signatures**\n• 📊 **Workforce Reports & Direct Navigation**`
        : `• 💼 **Careers, Job Applications & Events**\n• 👥 **Employer Hiring & ATS Candidate Pipelines**\n• ⏰ **Timesheet Deadlines & Clearance**\n• 🛡️ **Monthly Payroll Cutoffs**`;
      return {
        text: `Hello! I'm your **Waypoint AI Assistant**.\nI'm trained on Waypoint workflows, compliance rules, deadlines, and direct shortcuts.\n\nHere are popular topics I can assist with:\n${topics}\n\nWhat would you like help with?`,
        actions: []
      };
    }
  }
];

/**
 * Advanced query matcher:
 * 1. Normalizes input and extracts token n-grams (unigrams, bigrams, trigrams).
 * 2. Checks semantic concept mappings to match natural intent expressions.
 * 3. Calculates weighted scores for primary keywords, secondary modifiers, phrase matches, and fuzzy distance.
 * 4. Selects the most accurate scenario with highest confidence score.
 */
export function queryKnowledgeBase(input, role = 'HR') {
  if (!input || !input.trim()) {
    return { text: "How can I help you today?", actions: [] };
  }

  const rawInput = input.trim();
  const normalized = normalizeInput(rawInput);
  const basePath = role === 'COMPANY_ADMIN' ? '/myhr/admin' : role === 'HR' ? '/myhr/hr' : '/myhr/employee';

  const tokens = tokenizeText(normalized);
  const ngramsSet = new Set(tokens.ngrams);

  // Check semantic concept triggers
  const matchedConcepts = new Set();
  for (const mapping of CONCEPT_MAPPINGS) {
    for (const pattern of mapping.patterns) {
      if (normalized.includes(pattern) || ngramsSet.has(pattern)) {
        matchedConcepts.add(mapping.targetId);
        break;
      }
    }
  }

  let bestScenario = null;
  let highestScore = 0;

  for (const scenario of TOPIC_SCENARIOS) {
    let score = 0;

    // Concept match gives high confidence boost
    if (matchedConcepts.has(scenario.id)) {
      score += 18;
    }

    // Check primary keywords
    let primaryHitCount = 0;
    for (const p of scenario.primary) {
      if (normalized.includes(p) || ngramsSet.has(p)) {
        primaryHitCount++;
        score += (p.length > 8 ? 6 : 4) + (scenario.scoreModifier || 1);
      } else {
        // Fuzzy token check for single words of length >= 5
        if (!p.includes(' ') && p.length >= 5) {
          for (const word of tokens.words) {
            if (word.length >= 5 && Math.abs(word.length - p.length) <= 1 && levenshtein(word, p) === 1) {
              primaryHitCount++;
              score += 3;
              break;
            }
          }
        }
      }
    }

    // Check secondary modifiers (only if primary hits or concept matched)
    if (primaryHitCount > 0 || matchedConcepts.has(scenario.id)) {
      if (scenario.secondary) {
        for (const sec of scenario.secondary) {
          if (normalized.includes(sec) || ngramsSet.has(sec)) {
            score += (sec.length > 6 ? 3 : 2);
          }
        }
      }
    }

    if (score > highestScore) {
      highestScore = score;
      bestScenario = scenario;
    }
  }

  if (bestScenario && highestScore >= 3) {
    return bestScenario.respond(role, basePath);
  }

  // Helpful fallback with smart universal shortcuts tailored to workspace
  const isMyHr = role === 'HR' || role === 'COMPANY_ADMIN' || role === 'EMPLOYEE';
  const tryAsking = isMyHr
    ? `• **'When should I submit timesheets?'**\n• **'When is the payroll cutoff?'**\n• **'How do I fix a rejected timesheet?'**\n• **'How do I update my personal information?'**`
    : `• **'When should I submit timesheets?'**\n• **'When is the payroll cutoff?'**\n• **'How do I search and apply for jobs?'**\n• **'How do employers post a job opening?'**`;

  return {
    text: `I couldn't find an exact match for that question. Here are shortcuts to common workforce tools, or try asking about:\n${tryAsking}`,
    actions: [
      { label: 'Timesheets', path: `${basePath}/timesheets` },
      { label: 'Profile Requests', path: `${basePath}/profile-change-requests` },
      { label: 'Document Vault', path: `${basePath}/document-requests` }
    ]
  };
}
