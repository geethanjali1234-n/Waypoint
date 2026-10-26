import { useEffect, useRef } from 'react';
import StatusBadge from './StatusBadge';
import StrokeIcon from './StrokeIcon';
import './DataTable.css';

const rowId = (row) => row.id;
const rowLabel = (row) => row.name || row.employeeName || String(row.id);

/** Controlled table: callers own fetching, sorting, selection and pagination. */
export default function DataTable({ columns = [], rows = [], getRowId = rowId, getRowLabel = rowLabel, selectedRowIds = [], onSelectionChange, isRowSelectable = () => true, sort, onSort, onOpenRow, rowOpenable = false, pagination, loading = false, emptyMessage = 'No records to show.', label = 'Records', className = '', showFooter = true }) {
  const selectAll = useRef(null);
  const selectable = rows.filter(isRowSelectable).map(getRowId);
  const selected = new Set(selectedRowIds);
  const all = selectable.length > 0 && selectable.every((id) => selected.has(id));
  const some = selectable.some((id) => selected.has(id));
  useEffect(() => { if (selectAll.current) selectAll.current.indeterminate = some && !all; }, [some, all]);
  const togglePage = () => onSelectionChange(all ? selectedRowIds.filter((id) => !selectable.includes(id)) : [...new Set([...selectedRowIds, ...selectable])]);
  const total = pagination?.total ?? rows.length;
  const start = rows.length ? ((pagination?.page ?? 1) - 1) * (pagination?.pageSize ?? rows.length) + 1 : 0;
  const pages = Math.max(1, Math.ceil(total / (pagination?.pageSize || 1)));
  const cell = (column, row) => {
    if (column.render) return column.render(row[column.key], row);
    const value = row[column.key];
    if (column.type === 'status') return <StatusBadge status={value} />;
    if (column.type === 'person' && value) return <div className="wp-data-table__person">
      {value.name && <span className="wp-data-table__avatar" aria-hidden="true">{value.avatarUrl ? <img src={value.avatarUrl} alt="" /> : value.name.slice(0, 1)}</span>}
      <span>{onOpenRow ? <button type="button" onClick={() => onOpenRow(row)}>{value.name || value.id}</button> : <strong>{value.name || value.id}</strong>}{value.id && <small>{value.id}</small>}</span>
    </div>;
    return value ?? '\u2014';
  };
  const openRowFromEvent = (event, row) => {
    if (event.target.closest('button, input, label, a, select, textarea')) return;
    onOpenRow(row);
  };
  const openRowFromKey = (event, row) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    onOpenRow(row);
  };
  return <section className={`wp-data-table ${className}`} aria-label={label} aria-busy={loading}>
    <div className="wp-data-table__scroll" tabIndex={0} role="region" aria-label={`${label} table`}>
      <table><caption className="wp-data-table__sr">{label}</caption><thead><tr>
        {onSelectionChange && <th scope="col"><label className="wp-data-table__check"><input ref={selectAll} type="checkbox" aria-label="Select this page" aria-checked={some && !all ? 'mixed' : all} checked={all} disabled={!selectable.length || loading} onChange={togglePage} /></label></th>}
        {columns.map((column) => <th key={column.key} scope="col" style={{ width: column.width, textAlign: column.align }} aria-sort={column.sortable && onSort ? sort?.key === column.key ? sort.direction === 'asc' ? 'ascending' : 'descending' : 'none' : undefined}>
          {column.sortable && onSort ? <button type="button" onClick={() => onSort({ key: column.key, direction: sort?.key === column.key && sort.direction === 'asc' ? 'desc' : 'asc' })}>{column.header}<StrokeIcon name="sort" size={16} /></button> : column.header}
        </th>)}{onOpenRow && <th scope="col">Action</th>}
      </tr></thead><tbody>
        {loading ? <tr><td colSpan={columns.length + Number(Boolean(onSelectionChange)) + Number(Boolean(onOpenRow))}><span role="status">Loading records...</span></td></tr> : rows.length ? rows.map((row) => <tr key={getRowId(row)} className={`${selected.has(getRowId(row)) ? 'is-selected ' : ''}${rowOpenable && onOpenRow ? 'is-openable' : ''}`} tabIndex={rowOpenable && onOpenRow ? 0 : undefined} aria-label={rowOpenable && onOpenRow ? `Open ${getRowLabel(row)}` : undefined} onClick={rowOpenable && onOpenRow ? (event) => openRowFromEvent(event, row) : undefined} onKeyDown={rowOpenable && onOpenRow ? (event) => openRowFromKey(event, row) : undefined}>
          {onSelectionChange && <td><label className="wp-data-table__check"><input type="checkbox" aria-label={`Select ${getRowLabel(row)}`} checked={selected.has(getRowId(row))} disabled={!isRowSelectable(row)} onChange={() => onSelectionChange(selected.has(getRowId(row)) ? selectedRowIds.filter((id) => id !== getRowId(row)) : [...selectedRowIds, getRowId(row)])} /></label></td>}
          {columns.map((column) => <td key={column.key} style={{ textAlign: column.align }}>{cell(column, row)}</td>)}
          {onOpenRow && <td><button type="button" aria-label={`View ${getRowLabel(row)}`} onClick={() => onOpenRow(row)}>View<StrokeIcon name="arrow-right" size={16} /></button></td>}
        </tr>) : <tr><td colSpan={columns.length + Number(Boolean(onSelectionChange)) + Number(Boolean(onOpenRow))}>{emptyMessage}</td></tr>}
      </tbody></table>
    </div>
    {showFooter && <footer><span>{loading ? 'Loading records...' : `Showing ${start}\u2013${rows.length ? Math.min(start + rows.length - 1, total) : 0} of ${total}`}</span>
      {pagination && <nav aria-label={`${label} pagination`}><button type="button" aria-label="Previous page" disabled={pagination.page <= 1 || loading} onClick={() => pagination.onPageChange(pagination.page - 1)}><StrokeIcon name="chevron-left" /></button><span>Page {pagination.page} of {pages}</span><button type="button" aria-label="Next page" disabled={pagination.page >= pages || loading} onClick={() => pagination.onPageChange(pagination.page + 1)}><StrokeIcon name="chevron-right" /></button></nav>}
    </footer>}
  </section>;
}
