import ActionCard from './ActionCard';

/**
 * QuickActionCard - shortcut card linking to a common next step
 * (e.g. "Post a job", "Track applications").
 *
 * @param {Object} props
 * @param {import('react').ReactNode} [props.icon] - Optional icon.
 * @param {string} props.title - Card title.
 * @param {string} [props.description] - Supporting description text.
 * @param {string} [props.linkLabel='Explore'] - Label for the trailing link/action.
 * @param {() => void} [props.onClick] - Called when the card or link is activated.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <QuickActionCard title="Post a job" description="Reach ranked candidate matches" linkLabel="New posting" onClick={openJobForm} />
 */
export default function QuickActionCard({
  icon,
  title,
  description,
  linkLabel = 'Explore',
  onClick,
  className = '',
}) {
  return <ActionCard icon={icon} title={title} description={description} actionLabel={linkLabel} onClick={onClick} className={className} />;
}
