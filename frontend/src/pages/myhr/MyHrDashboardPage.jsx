import { useNavigate } from 'react-router-dom';
import { DashboardGrid, QuickActionCard, WelcomeBanner } from '../../components';
import { MYHR_ORGANIZATION, getDirectReports, getMyHrSession } from './mockDirectory';
import '../EmployerDashboardPage.css';

const SECTIONS = [
  { title: 'Create Employee', description: 'Add a new Employee and assign their Reporting Manager.', path: '/myhr/hr/employees/new' },
  { title: 'My Direct Reports', description: 'View Employees who report to you.', path: '/myhr/hr/direct-reports' },
  { title: 'My Profile', description: 'View your own HR profile.', path: '/myhr/hr/profile' },
  { title: 'My Timesheets', description: 'Enter and submit your own hours.', path: '/myhr/hr/timesheets' },
  { title: 'My Paystubs', description: 'View and download your paystubs.', path: '/myhr/hr/paystubs' },
  { title: 'Direct Report Timesheets', description: 'Review timesheets submitted by your direct reports.', path: '/myhr/hr/direct-reports/timesheets' },
  { title: 'Payroll for Direct Reports', description: 'Process payroll for Employees who report to you.', path: '/myhr/hr/direct-reports/payroll' },
  { title: 'Direct Report Reports', description: 'Generate reports scoped to your direct reports.', path: '/myhr/hr/direct-reports/reports' },
  { title: 'Applications', description: 'Review applicants for organization jobs.', path: '/myhr/hr/applications' },
  { title: 'Events', description: 'Manage organization events.', path: '/myhr/hr/events' },
  { title: 'Registrations', description: 'View registrants for organization events.', path: '/myhr/hr/registrations' },
  { title: 'Notifications', description: 'View your notifications.', path: '/myhr/hr/notifications' },
];

/** HR role landing page restored from the My HR preview flow. */
export default function MyHrDashboardPage() {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const directReports = getDirectReports(session?.loginId);

  return (
    <div className="employer-dashboard__container">
      <WelcomeBanner
        className="employer-dashboard__quote"
        title={`Welcome, ${session?.name || ''}`}
        subtitle={`HR · ${MYHR_ORGANIZATION.name}`}
        stats={[{ value: directReports.length, label: 'Direct reports' }]}
      />

      <div className="employer-dashboard__section">
        <DashboardGrid columns={3}>
          {SECTIONS.map((section) => (
            <QuickActionCard
              key={section.title}
              title={section.title}
              description={section.description}
              linkLabel="Open"
              onClick={() => navigate(section.path)}
            />
          ))}
        </DashboardGrid>
      </div>
    </div>
  );
}
