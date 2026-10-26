import { Badge } from '../../../components';
import formatLocationWithoutZip from '../../../utils/formatLocation';
import './EventListItem.css';

const MODE_LABEL = { ONLINE: 'Virtual', OFFLINE: 'In-person', HYBRID: 'Hybrid' };
const MODE_BADGE_VARIANT = { ONLINE: 'info', OFFLINE: 'success', HYBRID: 'warning' };

function formatShortDate(dateStr) {
  if (!dateStr) return '—';
  return new Date(dateStr).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
  });
}

export default function EventListItem({ event, selected, onClick }) {
  const classes = [
    'wp-event-list-item',
    selected ? 'wp-event-list-item--selected' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button type="button" className={classes} aria-pressed={selected} onClick={() => onClick(event.id)}>
      <div className="wp-event-list-item__top">
        <span className="wp-event-list-item__title">{event.title}</span>
        <Badge
          label={MODE_LABEL[event.eventMode] ?? event.eventMode}
          variant={MODE_BADGE_VARIANT[event.eventMode] ?? 'neutral'}
        />
      </div>
      <div className="wp-event-list-item__meta">
        <span className="wp-event-list-item__host">{event.companyName}</span>
        <span className="wp-event-list-item__sep" aria-hidden="true">·</span>
        <span className="wp-event-list-item__date">{formatShortDate(event.startDateTime)}</span>
        {event.address && (
          <>
            <span className="wp-event-list-item__sep" aria-hidden="true">·</span>
            <span className="wp-event-list-item__location">{formatLocationWithoutZip(event.address)}</span>
          </>
        )}
      </div>
    </button>
  );
}
