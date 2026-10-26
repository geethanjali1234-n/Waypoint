import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ActionCard } from '../../components';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';
import { getMyHrSession } from '../../auth/myHrSession';
import MyHrShell from './MyHrShell';
import OfferLetterApprovalCard from './components/OfferLetterApprovalCard';
import './MyHrRoleLandingPage.css';

const ICON_PATHS = {
  employees: ['M10 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z', 'M4 17a6 6 0 0 1 12 0'],
  'profile-change-requests': ['M4 3h9l3 3v11H4Z', 'M13 3v4h4M7 11h6M7 14h4'],
  timesheets: ['M3 5h14v12H3Z', 'M6 3v4M14 3v4M3 9h14M7 12h3v3H7Z'],
  'document-requests': ['M4 2.5h8l4 4V17.5H4Z', 'M12 2.5v4h4M7 11h6M7 14h4'],
  reports: ['M4 17V9h3v8M9 17V4h3v13M14 17v-6h3v6', 'M3 17h15'],
  audit: ['M10 3a7 7 0 1 0 7 7', 'M10 6v4l3 2M14 3h3v3'],
  organization: ['M4 17V4h9v13M13 8h3v9M7 7h2M7 10h2M7 13h2'],
};

function ActionIcon({ type }) {
  return <svg viewBox="0 0 20 20">{ICON_PATHS[type].map((path) => <path key={path} d={path} />)}</svg>;
}

const ROLE_CONTENT = {
  COMPANY_ADMIN: { heading: 'Admin Actions', basePath: '/myhr/admin', actions: [
    ['employees', 'Employee Management', 'Manage employees, HR accounts, roles, and reporting relationships.'],
    ['profile-change-requests', 'Profile Change Requests', 'Review profile updates.'],
    ['timesheets', 'Timesheets', 'Review HR and employee timesheets across the organization.'],
    ['document-requests', 'Request Documents', 'Request onboarding, tax, benefits, immigration, and offboarding documents.'],
    ['reports', 'Reports', 'View workforce, employee, and timesheet reports.'],
    ['audit', 'Audit History', 'Review significant My HR activity.'],
    ['organization', 'Organization Settings', 'Review organization information and branding.'],
  ] },
  HR: { heading: 'HR Actions', basePath: '/myhr/hr', actions: [
    ['employees', 'Employee Management', 'View employees and their reporting relationships.'],
    ['profile-change-requests', 'Profile Change Requests', 'Review assigned updates.'],
    ['timesheets', 'Timesheets', 'View your timesheets and submissions from direct reports.'],
    ['document-requests', 'Request Documents', 'Request onboarding, tax, benefits, immigration, and offboarding documents.'],
    ['reports', 'Reports', 'View workforce and timesheet reports.'],
  ] },
  EMPLOYEE: { heading: 'Employee Actions', basePath: '/myhr/employee', actions: [
    ['profile-change-requests', 'Profile Change Requests', 'Request changes and track status.'],
    ['timesheets', 'My Timesheets', 'Enter hours, attach supporting documents, and view history.'],
    ['document-requests', 'Document Requests', 'Request documents and view request history.'],
  ] },
};

export default function MyHrRoleLandingPage({ role }) {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const content = ROLE_CONTENT[role];
  const loginId = session?.loginId || 'there';
  const [welcomeName, setWelcomeName] = useState(null);

  useEffect(() => {
    let active = true;
    myHrEmployeeApi.getMine().then((response) => {
      if (!active) return;
      const profile = response.data?.data;
      setWelcomeName(role === 'COMPANY_ADMIN' ? profile?.organizationName?.trim() : profile?.firstName?.trim());
    }).catch(() => active && setWelcomeName(role === 'COMPANY_ADMIN' ? 'Company Admin' : loginId));
    return () => { active = false; };
  }, [loginId, role]);

  return <MyHrShell>
    <section className="myhr-role-landing__hero"><div className="myhr-role-landing__intro">
      <span className="myhr-role-landing__eyebrow">YOUR WORKSPACE</span>
      <h1>Welcome{welcomeName ? `, ${welcomeName}` : ''}</h1>
      <p>Your people, requests, and daily operations in one place.</p>
    </div></section>
    <section className="myhr-role-landing">
      {role === 'COMPANY_ADMIN' && <OfferLetterApprovalCard />}
      <div className="myhr-role-landing__section-heading"><h2>{content.heading}</h2></div>
      <div className="myhr-role-landing__actions">
        {content.actions.map(([path, title, description], index) => <ActionCard
          key={path} icon={<ActionIcon type={path} />} eyebrow={`Workspace ${String(index + 1).padStart(2, '0')}`}
          title={title} description={description} actionLabel="Open"
          onClick={() => navigate(`${content.basePath}/${path}`)}
        />)}
      </div>
    </section>
  </MyHrShell>;
}
