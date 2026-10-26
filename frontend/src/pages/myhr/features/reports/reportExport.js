import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

function escapeCsvValue(value) {
  const str = String(value ?? '');
  return /[",\r\n]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str;
}

function escapeHtmlValue(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

export function downloadReportCsv({ filename, columns, rows, resolveValue }) {
  const headerLine = columns.map((column) => escapeCsvValue(column.header)).join(',');
  const rowLines = rows.map((row) =>
    columns.map((column) => escapeCsvValue(resolveValue(column, row))).join(','));
  const csv = [headerLine, ...rowLines].join('\r\n');
  triggerBlobDownload(new Blob([csv], { type: 'text/csv;charset=utf-8;' }), filename);
}

export function downloadReportExcel({ filename, title, subtitle, columns, rows, resolveValue }) {
  const header = columns.map((column) => `<th>${escapeHtmlValue(column.header)}</th>`).join('');
  const body = rows.map((row) => `<tr>${columns.map((column) => `<td>${escapeHtmlValue(resolveValue(column, row))}</td>`).join('')}</tr>`).join('');
  const workbook = `<!doctype html>
<html>
<head>
  <meta charset="UTF-8" />
  <style>
    table { border-collapse: collapse; font-family: Arial, sans-serif; }
    th { background: #0b624f; color: #ffffff; font-weight: 700; }
    th, td { border: 1px solid #d0d7de; padding: 8px 10px; }
    caption { caption-side: top; text-align: left; font-size: 16px; font-weight: 700; padding: 0 0 8px; }
    caption small { display: block; color: #667085; font-size: 12px; font-weight: 400; margin-top: 4px; }
  </style>
</head>
<body>
  <table>
    <caption>${escapeHtmlValue(title)}${subtitle ? `<small>${escapeHtmlValue(subtitle)}</small>` : ''}</caption>
    <thead><tr>${header}</tr></thead>
    <tbody>${body}</tbody>
  </table>
</body>
</html>`;
  triggerBlobDownload(new Blob([workbook], { type: 'application/vnd.ms-excel;charset=utf-8;' }), filename);
}

export function downloadReportPdf({ filename, title, subtitle, columns, rows, resolveValue }) {
  const doc = new jsPDF();

  doc.setFontSize(15);
  doc.setTextColor(11, 62, 49);
  doc.text(title, 14, 18);

  doc.setFontSize(10);
  doc.setTextColor(102, 112, 133);
  doc.text(subtitle, 14, 25);

  autoTable(doc, {
    startY: 31,
    head: [columns.map((column) => column.header)],
    body: rows.map((row) => columns.map((column) => String(resolveValue(column, row)))),
    headStyles: { fillColor: [11, 98, 79], textColor: 255, fontSize: 9 },
    styles: { fontSize: 9, cellPadding: 4 },
    alternateRowStyles: { fillColor: [246, 249, 247] },
  });

  doc.save(filename);
}
