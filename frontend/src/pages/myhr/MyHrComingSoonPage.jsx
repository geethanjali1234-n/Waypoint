import { useNavigate } from 'react-router-dom';
import { Button, EmptyState } from '../../components';
import '../EmployerDashboardPage.css';

/** Placeholder for HR dashboard sections that will be built incrementally. */
export default function MyHrComingSoonPage({ title }) {
  const navigate = useNavigate();

  return (
    <div className="employer-dashboard__container">
      <EmptyState
        title={`${title} - coming soon`}
        description="This section isn't built yet in the My HR preview."
        action={
          <Button variant="secondary" onClick={() => navigate('/myhr/hr/dashboard')}>
            Back to dashboard
          </Button>
        }
      />
    </div>
  );
}
