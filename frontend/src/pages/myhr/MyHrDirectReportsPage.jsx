import { useNavigate } from 'react-router-dom';
import { Button, EmptyState, PageHeader, Table } from '../../components';
import { getDirectReports, getMyHrSession } from './mockDirectory';
import '../EmployerDashboardPage.css';

const COLUMNS = [
  { key: 'loginId', header: 'Employee ID' },
  { key: 'name', header: 'Name' },
  { key: 'designation', header: 'Designation' },
  { key: 'personalEmail', header: 'Personal Email' },
];

/** Lists only Employees whose active Reporting Manager is the signed-in HR user. */
export default function MyHrDirectReportsPage() {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const directReports = getDirectReports(session?.loginId);

  return (
    <div className="employer-dashboard__container">
      <PageHeader
        title="My Direct Reports"
        subtitle="Employees whose Reporting Manager is you. Other HR users cannot see this list."
      />

      {directReports.length === 0 ? (
        <EmptyState
          title="No direct reports yet"
          description="Employees you create and assign as your Reporting Manager will appear here."
          action={
            <Button variant="primary" onClick={() => navigate('/myhr/hr/employees/new')}>
              Create Employee
            </Button>
          }
        />
      ) : (
        <Table columns={COLUMNS} rows={directReports} />
      )}
    </div>
  );
}
