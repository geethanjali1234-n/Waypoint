import { useMemo } from 'react';
import './DocumentTablePreview.css';

// Spreadsheet exports may be HTML with an .xls extension. Render only their
// text and table structure as React nodes; never execute uploaded markup.
export default function DocumentTablePreview({ html }) {
  const preview = useMemo(() => {
    const parsed = new DOMParser().parseFromString(html, 'text/html');
    parsed.querySelectorAll('script,style,iframe,object,embed,template').forEach((node) => node.remove());
    const headings = [...parsed.querySelectorAll('h1,h2,h3,p')]
      .filter((node) => !node.closest('table')).slice(0, 6).map((node) => node.textContent.trim()).filter(Boolean);
    const tables = [...parsed.querySelectorAll('table')].filter((table) => !table.parentElement?.closest('table')).map((table) => {
      const rows = [...table.rows];
      return { truncated: rows.length > 500, rows: rows.slice(0, 500).map((row) => [...row.cells].map((cell) => ({
        text: cell.textContent.trim(), header: cell.tagName === 'TH',
        colSpan: Math.min(Math.max(cell.colSpan || 1, 1), 100), rowSpan: Math.min(Math.max(cell.rowSpan || 1, 1), 500),
      }))) };
    });
    return { headings, tables };
  }, [html]);

  if (!preview.tables.length) return <p role="status">This document has no table preview. Download the original to view its full layout.</p>;
  return <div className="timesheet-document-tables">
    {preview.headings.map((heading, index) => <p className="timesheet-document-tables__heading" key={index}>{heading}</p>)}
    {preview.tables.map((table, index) => <div key={index}>
      <div className="timesheet-document-tables__scroll" role="region" tabIndex={0} aria-label={`Spreadsheet preview ${index + 1}`}>
        <table><caption>Document table {index + 1}</caption><tbody>
          {table.rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => {
            const Cell = cell.header ? 'th' : 'td';
            return <Cell key={cellIndex} colSpan={cell.colSpan} rowSpan={cell.rowSpan}>{cell.text}</Cell>;
          })}</tr>)}
        </tbody></table>
      </div>
      {table.truncated && <p>Showing the first 500 rows. Download the original for the complete document.</p>}
    </div>)}
  </div>;
}
