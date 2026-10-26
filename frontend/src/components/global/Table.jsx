import { useCallback, useEffect, useRef, useState } from 'react';
import './Table.css';

/**
 * Table - generic reusable data table for lists such as applicants,
 * registrants, or job postings with smooth horizontal sliding on mobile.
 *
 * @param {Object} props
 * @param {{key: string, header: import('react').ReactNode, width?: string}[]} props.columns - Column definitions.
 * @param {Object[]} props.rows - Row data; each row should expose the keys referenced by `columns`.
 * @param {(column: Object, row: Object) => import('react').ReactNode} [props.renderCell] - Optional custom cell renderer, defaults to `row[column.key]`.
 * @param {string} [props.emptyMessage='No records found.'] - Message shown when `rows` is empty.
 * @param {string} [props.className=''] - Additional class names.
 * @param {boolean} [props.showScrollControls=true] - Whether to show slide buttons when scrollable.
 */
export default function Table({
  columns,
  rows,
  renderCell,
  emptyMessage = 'No records found.',
  className = '',
  showScrollControls = true,
}) {
  const scrollContainerRef = useRef(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);
  const [isScrollable, setIsScrollable] = useState(false);

  const checkScroll = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    const hasOverflow = el.scrollWidth > el.clientWidth + 6;
    setIsScrollable(hasOverflow);
    setCanScrollLeft(el.scrollLeft > 6);
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 6);
  }, []);

  useEffect(() => {
    checkScroll();
    const el = scrollContainerRef.current;
    if (!el) return;
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(checkScroll) : null;
    ro?.observe(el);
    window.addEventListener('resize', checkScroll);
    return () => {
      ro?.disconnect();
      window.removeEventListener('resize', checkScroll);
    };
  }, [checkScroll, rows, columns]);

  const slide = (direction) => {
    const el = scrollContainerRef.current;
    if (!el) return;
    const offset = direction === 'left' ? -260 : 260;
    el.scrollBy({ left: offset, behavior: 'smooth' });
  };

  const classes = ['wp-table-wrapper', className].filter(Boolean).join(' ');

  return (
    <div className="wp-table-outer">
      {showScrollControls && isScrollable && (
        <div className="wp-table-scroll-hint" aria-hidden="true">
          <button
            type="button"
            className="wp-table-scroll-btn wp-table-scroll-btn--left"
            onClick={() => slide('left')}
            disabled={!canScrollLeft}
            tabIndex={-1}
            title="Slide left to view previous columns"
          >
            ◀ Slide Left
          </button>
          <span className="wp-table-scroll-indicator">
            ⇄ Swipe or slide to explore all columns & actions
          </span>
          <button
            type="button"
            className="wp-table-scroll-btn wp-table-scroll-btn--right"
            onClick={() => slide('right')}
            disabled={!canScrollRight}
            tabIndex={-1}
            title="Slide right to view more columns"
          >
            Slide Right ▶
          </button>
        </div>
      )}
      <div
        ref={scrollContainerRef}
        className={classes}
        onScroll={checkScroll}
      >
        <table className="wp-table">
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key} style={{ width: column.width }}>
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td className="wp-table__empty" colSpan={columns.length}>
                  {emptyMessage}
                </td>
              </tr>
            ) : (
              rows.map((row, rowIndex) => (
                <tr key={row.id ?? rowIndex}>
                  {columns.map((column) => (
                    <td
                      key={column.key}
                      className={`wp-table__cell wp-table__cell--${column.key}`}
                      data-label={typeof column.header === 'string' ? column.header : column.key}
                    >
                      {renderCell ? renderCell(column, row) : row[column.key]}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
