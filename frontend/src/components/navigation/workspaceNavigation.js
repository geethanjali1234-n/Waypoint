// Existing destinations only. The route guards remain the permission boundary.
export const employerNavigation = [
  { label: '', items: [{ label: 'Home', path: '/employer/dashboard', icon: 'home' }] },
  { label: 'Recruiting', items: [{ label: 'Job Postings', path: '/employer/jobs', icon: 'briefcase' }, { label: 'Events', path: '/employer/events', icon: 'calendar' }] },
  { label: 'Account', items: [{ label: 'Notifications', path: '/employer/notifications', icon: 'bell' }, { label: 'Profile', path: '/employer/profile', icon: 'profile' }] },
];
export const jobSeekerNavigation = [
  { label: '', items: [{ label: 'Home', path: '/job-seeker/dashboard', icon: 'home' }] },
  { label: 'Opportunities', items: [{ label: 'Careers', path: '/careers', icon: 'briefcase' }, { label: 'Events', path: '/events', icon: 'calendar' }] },
  { label: 'Activity', items: [{ label: 'Applications', path: '/job-seeker/applications', icon: 'document' }, { label: 'Registrations', path: '/job-seeker/registrations', icon: 'calendar' }] },
  { label: 'People', items: [{ label: 'My Profile', path: '/job-seeker/profile', icon: 'profile' }, { label: 'Notifications', path: '/job-seeker/notifications', icon: 'bell' }] },
];
export const platformNavigation = [
  { label: '', items: [{ label: 'Dashboard', path: '/waypoint-admin/dashboard', icon: 'home' }] },
  { label: 'Governance', items: [{ label: 'Organizations', path: '/waypoint-admin/organizations', icon: 'organization' }] },
];
export function navigationActions(groups) {
  return groups.flatMap((group) => group.items.map((item) => ({ id: item.path, title: item.label, subtitle: '', category: group.label || 'Workspace', path: item.path })));
}
