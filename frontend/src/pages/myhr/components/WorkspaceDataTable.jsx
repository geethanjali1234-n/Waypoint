import { DataTable } from '../../../components';

/** Retain caller-owned cells/actions while adopting the shared table frame. */
export default function WorkspaceDataTable({ columns, rows = [], renderCell, ...props }) {
  const identityKey = columns.find((column) => ['employee', 'name'].includes(column.key))?.key;
  const adapted = columns.filter((column) => !identityKey || column.key !== 'employeeId').map((column) => ({
    ...column,
    width: undefined,
    render: (value, row) => {
      const original = row.__originalRow;
      const content = renderCell ? renderCell(column, original) : value ?? '—';
      if (column.key === identityKey) {
        const name = row.employeeName || row.name || (typeof row.employee === 'string' ? row.employee : null);
        return <div className="wp-data-table__person"><span className="wp-data-table__avatar" aria-hidden="true">{name?.slice(0, 1)}</span><span>{content}{row.employeeId && <small>{row.employeeId}</small>}</span></div>;
      }
      if (column.key === 'select') return <label className="wp-data-table__check">{content}</label>;
      return content;
    },
  }));
  return <DataTable getRowId={(row) => row.__tableKey} {...props} columns={adapted} rows={rows.map((row, index) => ({ ...row, __tableKey: row.id ?? row.userId ?? row.employeeUserId ?? index, __originalRow: row }))} />;
}
