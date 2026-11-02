export function getContextActions(role, route = '') {
  const base = role === 'COMPANY_ADMIN' ? '/myhr/admin' : role === 'HR' ? '/myhr/hr' : '/myhr/employee';
  let actions;
  if (role === 'EMPLOYEE') {
    actions = [
      { label: 'My Timesheets', description: 'Log hours & track submissions', query: 'How do I submit my hours?', path: `${base}/timesheets`, match: 'timesheets' },
      { label: 'Document Requests', description: 'Letters, forms & documents', query: 'How do I request an employment verification letter?', path: `${base}/document-requests`, match: 'document' },
      { label: 'Profile Requests', description: 'Request a personal detail update', query: 'How do I update my personal information?', path: `${base}/profile-change-requests`, match: 'profile-change' },
      { label: 'My Profile', description: 'Find your personal information', query: 'How do I view my profile?', path: `${base}/profile`, match: '/profile' },
    ];
  } else if (role === 'HR' || role === 'COMPANY_ADMIN') {
    actions = [
      { label: 'Employee Management', description: 'Find a person by name, ID or role', query: 'I need to find an employee', path: `${base}/employees`, match: 'employees' },
      { label: 'Approvals', description: 'Review profile change requests', query: 'How do I review profile changes?', path: `${base}/profile-change-requests`, match: 'profile-change' },
      { label: 'Timesheets', description: 'Review & clear submitted hours', query: 'How does 1-tap express clearance work?', path: `${base}/timesheets`, match: 'timesheets' },
      { label: 'Payroll Readiness', description: 'Check the payroll readiness gate', query: 'How does the payroll readiness gate work?', path: `${base}/payroll-readiness`, match: 'payroll-readiness' },
    ];
    if (route.includes('offer-letter-approvals') && role === 'COMPANY_ADMIN') {
      actions[1] = { label: 'Document Approvals', description: 'Review documents awaiting approval', query: 'How do I approve offer letters?', path: `${base}/offer-letter-approvals`, match: 'offer-letter-approvals' };
    }
    if (route.includes('document-requests')) {
      actions[1] = { label: 'Document Requests', description: 'Manage letters and employee documents', query: 'How do I request an employment verification letter?', path: `${base}/document-requests`, match: 'document-requests' };
    }
  } else {
    const topics = role === 'EMPLOYER'
      ? [['Post a Job', 'How do employers post a job opening?', '/employer/jobs/new'], ['Applicants', 'How do I evaluate applicants in the ATS?', '/employer/jobs'], ['Host an Event', 'How do I create recruiting events?', '/employer/events'], ['Company Profile', 'How do I update employer branding?', '/employer/profile']]
      : [['Browse Jobs', 'How do I search and apply for jobs?', '/careers'], ['Applications', 'How do I track my job applications?', '/job-seeker/applications'], ['Career Events', 'How do I register for career events?', '/events'], ['My Profile', 'How do I update my candidate resume?', '/job-seeker/profile']];
    return topics.map(([label, query, path]) => ({ label, query, path, description: 'Explore this workflow' }));
  }
  const current = actions.find((action) => route.includes(action.match));
  const first = current;
  return first ? [first, ...actions.filter((action) => action !== first)] : actions;
}
