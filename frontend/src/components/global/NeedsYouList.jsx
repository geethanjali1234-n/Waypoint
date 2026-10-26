import { Link } from 'react-router-dom';
import './NeedsYouList.css';

export default function NeedsYouList({ items = [], hideZeroCounts = true, emptyMessage = 'No items to show.', label = 'Needs you today', className = '' }) {
  const visible = items.filter((item) => !hideZeroCounts || item.count !== 0).map((item, index) => ({ ...item, order: index })).sort((a, b) => (a.priority ?? Infinity) - (b.priority ?? Infinity) || a.order - b.order);
  return <section className={`wp-needs-you ${className}`} aria-label={label}>
    {visible.length ? <ul>{visible.map((item) => <li key={item.id}>
      {typeof item.count === 'number' && <span className="wp-needs-you__count">{item.count.toLocaleString()}</span>}
      <div className="wp-needs-you__copy"><strong>{item.title}</strong>{item.context && <p>{item.context}</p>}</div>
      {item.to ? <Link className="wp-needs-you__action" to={item.to}>{item.actionLabel || 'Review'}</Link> : item.href ? <a className="wp-needs-you__action" href={item.href}>{item.actionLabel || 'Review'}</a> : item.onAction ? <button className="wp-needs-you__action" type="button" onClick={item.onAction} disabled={item.disabled}>{item.actionLabel || 'Review'}</button> : null}
    </li>)}</ul> : <p className="wp-needs-you__empty">{emptyMessage}</p>}
  </section>;
}
