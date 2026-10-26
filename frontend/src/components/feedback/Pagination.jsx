import './Pagination.css';

/**
 * Pagination - page navigation control for lists and tables.
 *
 * @param {Object} props
 * @param {number} props.currentPage - Currently active page (1-indexed).
 * @param {number} props.totalPages - Total number of pages.
 * @param {(page: number) => void} [props.onPageChange] - Called with the target page number.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <Pagination currentPage={2} totalPages={6} onPageChange={setPage} />
 */
export default function Pagination({ currentPage, totalPages, onPageChange, className = '' }) {
  const classes = ['wp-pagination', className].filter(Boolean).join(' ');
  const pages = Array.from({ length: totalPages }, (_, index) => index + 1);

  return (
    <nav className={classes} aria-label="Pagination">
      <button
        type="button"
        className="wp-pagination__control"
        onClick={() => onPageChange && onPageChange(currentPage - 1)}
        disabled={currentPage <= 1}
      >
        Previous
      </button>
      <ul className="wp-pagination__pages">
        {pages.map((page) => (
          <li key={page}>
            <button
              type="button"
              className={`wp-pagination__page ${page === currentPage ? 'wp-pagination__page--active' : ''}`}
              aria-current={page === currentPage ? 'page' : undefined}
              onClick={() => onPageChange && onPageChange(page)}
            >
              {page}
            </button>
          </li>
        ))}
      </ul>
      <button
        type="button"
        className="wp-pagination__control"
        onClick={() => onPageChange && onPageChange(currentPage + 1)}
        disabled={currentPage >= totalPages}
      >
        Next
      </button>
    </nav>
  );
}
