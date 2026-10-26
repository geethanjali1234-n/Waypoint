import './DashboardGrid.css';

/**
 * DashboardGrid - responsive grid wrapper for dashboard widgets
 * (stat cards, quick action cards, feature cards).
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.children - Grid items.
 * @param {number} [props.columns=3] - Number of columns at the widest breakpoint.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <DashboardGrid columns={3}>
 *   <StatCard value={1} label="Open postings" />
 *   <StatCard value={3} label="Total applications" />
 * </DashboardGrid>
 */
export default function DashboardGrid({ children, columns = 3, className = '' }) {
  const classes = ['wp-dashboard-grid', className].filter(Boolean).join(' ');

  return (
    <div className={classes} style={{ '--wp-dashboard-grid-columns': columns }}>
      {children}
    </div>
  );
}
