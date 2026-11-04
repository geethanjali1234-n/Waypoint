import { useEffect, useState } from 'react';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';
import { getMyHrSession } from '../../auth/myHrSession';
import MyHrShell from './MyHrShell';
import WorkspaceDashboard from './components/WorkspaceDashboard';
import EmployeeTodayPanel from './components/EmployeeTodayPanel';

const ROLE_PATHS = { COMPANY_ADMIN: '/myhr/admin', HR: '/myhr/hr', EMPLOYEE: '/myhr/employee' };

export default function MyHrRoleLandingPage({ role }) {
  const session = getMyHrSession();
  const basePath = ROLE_PATHS[role];
  const loginId = session?.loginId || 'there';
  const [welcomeName, setWelcomeName] = useState(null);

  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => myHrEmployeeApi.getMine()).then((response) => {
      if (!active) return;
      const profile = response.data?.data;
      setWelcomeName(role === 'COMPANY_ADMIN' ? profile?.organizationName?.trim() || 'Company Admin' : profile?.firstName?.trim() || loginId);
    }).catch(() => active && setWelcomeName(role === 'COMPANY_ADMIN' ? 'Company Admin' : loginId));
    return () => { active = false; };
  }, [loginId, role]);

  return <MyHrShell>
    {role === 'EMPLOYEE'
      ? <EmployeeTodayPanel name={welcomeName} basePath={basePath} />
      : <WorkspaceDashboard role={role} name={welcomeName} basePath={basePath} />}
  </MyHrShell>;
}
