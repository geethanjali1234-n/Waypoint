/**
 * MyHrPagination - simplified Previous / Page X of Y / Next control used
 * across My HR list pages.
 *
 * The shared app-wide Pagination component (src/components/feedback) renders
 * a full row of numbered page buttons (1 2 3 4 5 ...), which gets wide and
 * noisy on lists with many pages; that component is still used elsewhere in
 * the app (Job Seeker/Employer pages) and is left untouched. This is the My
 * HR-only replacement: a non-clickable "Page X of Y" indicator between two
 * buttons, matching every current call site's existing
 * currentPage/totalPages/onPageChange contract so it's a drop-in swap.
 *
 * @param {Object} props
 * @param {number} props.currentPage - Currently active page (1-indexed).
 * @param {number} props.totalPages - Total number of pages.
 * @param {(page: number) => void} [props.onPageChange] - Called with the target page number.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <MyHrPagination currentPage={2} totalPages={6} onPageChange={setPage} />
 */
export default function MyHrPagination({ currentPage, totalPages, onPageChange, className = '' }) {
  const classes = ['myhr-pagination', className].filter(Boolean).join(' ');
  // Defensive against a zero/undefined totalPages so this never renders a
  // misleading "Page 1 of 0" if a future caller ever invokes it without the
  // existing totalPages > 1 guard every current call site already uses.
  const safeTotalPages = Math.max(totalPages || 0, 1);
  const safeCurrentPage = Math.min(Math.max(currentPage || 1, 1), safeTotalPages);

  return (
    <nav className={classes} aria-label="Pagination">
      <button
        type="button"
        className="myhr-pagination__control"
        onClick={() => onPageChange && onPageChange(safeCurrentPage - 1)}
        disabled={safeCurrentPage <= 1}
      >
        Previous
      </button>
      <span className="myhr-pagination__indicator">
        Page {safeCurrentPage} of {safeTotalPages}
      </span>
      <button
        type="button"
        className="myhr-pagination__control"
        onClick={() => onPageChange && onPageChange(safeCurrentPage + 1)}
        disabled={safeCurrentPage >= safeTotalPages}
      >
        Next
      </button>
    </nav>
  );
}
