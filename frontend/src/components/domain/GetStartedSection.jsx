import { useNavigate } from 'react-router-dom';
import QuickActionCard from './QuickActionCard';
import './GetStartedSection.css';

const ACTIONS = [
  {
    title: 'Browse careers',
    description: 'Search open roles and events by title, distance, and job type.',
    linkLabel: 'Explore',
    path: '/careers',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <path d="M14 2v6h6" />
      </svg>
    ),
  },
  {
    title: 'Track applications',
    description: "See status updates on every role you've applied to, in one place.",
    linkLabel: 'View applications',
    path: '/job-seeker/applications',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <rect x="4" y="4" width="16" height="16" rx="2" />
        <path d="M8 9h8M8 13h5" />
      </svg>
    ),
  },
  {
    title: 'Complete your profile',
    description: 'Add skills and a resume so matches get sharper over time.',
    linkLabel: 'Edit profile',
    path: '/job-seeker/profile/edit',
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <circle cx="12" cy="8" r="3.4" />
        <path d="M5 20c0-4 3.1-6.5 7-6.5s7 2.5 7 6.5" />
      </svg>
    ),
  },
];

/**
 * GetStartedSection - "Get started" section: three navigation shortcut
 * cards, each routing to its corresponding page.
 *
 * @param {Object} props
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <GetStartedSection />
 */
export default function GetStartedSection({ className = '' }) {
  const navigate = useNavigate();
  const classes = ['wp-get-started-section', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <p className="wp-get-started-section__eyebrow">Get started</p>
      <div className="wp-get-started-section__grid">
        {ACTIONS.map((action) => (
          <QuickActionCard
            key={action.title}
            icon={action.icon}
            title={action.title}
            description={action.description}
            linkLabel={action.linkLabel}
            onClick={() => navigate(action.path)}
          />
        ))}
      </div>
    </div>
  );
}
