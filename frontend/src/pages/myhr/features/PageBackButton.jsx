import { useNavigate } from 'react-router-dom';
import { Button } from '../../../components';
import { getMyHrHomePath, getMyHrSession } from '../../../auth/myHrSession';

export default function PageBackButton({ onClick, backTo, label = 'Back' }) {
  const navigate = useNavigate();
  const role = getMyHrSession()?.role;
  const defaultTarget = role ? `${getMyHrHomePath(role)}/dashboard` : null;
  const handleClick = onClick || (() => navigate(backTo || defaultTarget || -1));

  return (
    <Button type="button" variant="secondary" className="myhr-page-back" onClick={handleClick} aria-label={label}>
      <span aria-hidden="true">←</span> {label}
    </Button>
  );
}
