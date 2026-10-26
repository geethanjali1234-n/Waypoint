import './offerLetterApproval.css';
import { StatusBadge } from '../../../../components';

/** A calm status banner for the Company Admin approval workflow (pending / approved / rejected / sent). */
export default function ApprovalBanner({ variant, label, message, children = null }) {
  return (
    <div className={`offer-approval__banner offer-approval__banner--${variant}`} role="status">
      <StatusBadge status={variant === 'sent' ? 'approved' : variant} label={label} />
      <p>{message}</p>
      {children}
    </div>
  );
}
