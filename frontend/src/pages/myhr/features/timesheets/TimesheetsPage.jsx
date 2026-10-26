import { createPortal } from 'react-dom';
import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Button,
  ActionCard,
  Dropdown,
  FileUpload,
  FormLabel,
  Modal,
  SearchInput,
  StatusTag,
  Table,
  TextInput,
} from '../../../../components';
import timesheetApi from '../../../../api/timesheetApi';
import documentApi from '../../../../api/documentApi';
import myHrEmployeeApi from '../../../../api/myHrEmployeeApi';
import { getMyHrHomePath, getMyHrSession } from '../../../../auth/myHrSession';
import { formatDateOnly, formatDateTime as formatDateTimeShared } from '../../../../utils/dateFormat';
import { PayrollReadinessWorkspace } from '../payrollReadiness/PayrollReadinessPage';
import MyHrFeaturePage from '../MyHrFeaturePage';
import MyHrPagination from '../MyHrPagination';
import './timesheets.css';
import TimesheetChronometer from './components/TimesheetChronometer';
import ApplePayBloom from '../../components/ApplePayBloom';
import {
  addDays,
  daysBetween,
  isRecordCleanAndAuditReady,
  parseDateKey,
  toDateKey,
} from './timesheetPeriodRules';

function isWithinPeriodPreset(startStr, endStr, preset, today) {
  if (!preset || preset === 'ALL') return true;
  if (!startStr) return true;

  const now = today ? new Date(`${today}T00:00:00`) : new Date();
  const currentYear = now.getFullYear();
  const currentMonth = now.getMonth(); // 0-indexed

  if (preset === 'CURRENT_MONTH') {
    const monthStart = `${currentYear}-${String(currentMonth + 1).padStart(2, '0')}-01`;
    const lastDay = new Date(currentYear, currentMonth + 1, 0).getDate();
    const monthEnd = `${currentYear}-${String(currentMonth + 1).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
    // A submission belongs to whichever month its period STARTS in - not any
    // month it merely overlaps. Otherwise a period like "Aug 31 - Sep 29" (which
    // barely touches September) would wrongly show up under "Current month".
    return startStr >= monthStart && startStr <= monthEnd;
  }
  if (preset === 'PREVIOUS_MONTH') {
    const prevDate = new Date(currentYear, currentMonth - 1, 1);
    const pYear = prevDate.getFullYear();
    const pMonth = prevDate.getMonth();
    const prevStart = `${pYear}-${String(pMonth + 1).padStart(2, '0')}-01`;
    const lastDay = new Date(pYear, pMonth + 1, 0).getDate();
    const prevEnd = `${pYear}-${String(pMonth + 1).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
    return startStr >= prevStart && startStr <= prevEnd;
  }
  if (preset === 'LAST_90_DAYS') {
    const cutoffDate = new Date(now);
    cutoffDate.setDate(cutoffDate.getDate() - 90);
    const cutoff = toDateKey(cutoffDate);
    return (endStr || startStr) >= cutoff;
  }
  if (preset === 'THIS_YEAR') {
    const yStart = `${currentYear}-01-01`;
    const yEnd = `${currentYear}-12-31`;
    return startStr <= yEnd && (endStr || startStr) >= yStart;
  }
  return true;
}

// Custom From/End range filter: does this record's period START fall inside
// the selected window? (Same rule as the Current/Previous month presets -
// a period is only ever "in" the range it starts in, not every range it
// happens to overlap - so "Sep 1 - Sep 30" never pulls in an Aug 31 start.)
// Either bound can be left open (only From, or only End).
function isWithinCustomRange(startStr, fromDate, toDate) {
  if (!startStr) return true;
  if (fromDate && startStr < fromDate) return false;
  if (toDate && startStr > toDate) return false;
  return true;
}

function addMonths(dateKey, amount) {
  const date = parseDateKey(`${dateKey.slice(0, 7)}-01`);
  return toDateKey(new Date(date.getFullYear(), date.getMonth() + amount, 1));
}

function endOfMonth(dateKey) {
  const date = parseDateKey(`${dateKey.slice(0, 7)}-01`);
  return toDateKey(new Date(date.getFullYear(), date.getMonth() + 1, 0));
}

function currentWeek() {
  const today = new Date();
  const mondayOffset = today.getDay() === 0 ? -6 : 1 - today.getDay();
  today.setDate(today.getDate() + mondayOffset);
  return { start: toDateKey(today), end: addDays(toDateKey(today), 6) };
}

function monthCells(monthKey) {
  const monthStart = parseDateKey(`${monthKey.slice(0, 7)}-01`);
  const mondayOffset = monthStart.getDay() === 0 ? 6 : monthStart.getDay() - 1;
  const firstCell = addDays(toDateKey(monthStart), -mondayOffset);
  return Array.from({ length: 42 }, (_, index) => {
    const date = addDays(firstCell, index);
    return { date, currentMonth: date.slice(0, 7) === monthKey.slice(0, 7) };
  });
}

function formatDate(value, options = { month: 'short', day: 'numeric', year: 'numeric' }) {
  return parseDateKey(value).toLocaleDateString('en-US', options);
}

const TIMESHEET_ENTRY_TYPE_OPTIONS = [
  { value: 'WORK', label: 'Work' },
  { value: 'SICK_LEAVE', label: 'Sick leave' },
  { value: 'PLANNED_LEAVE', label: 'Planned leave' },
  { value: 'UNPLANNED_LEAVE', label: 'Unplanned leave' },
  { value: 'COMP_OFF', label: 'Comp off' },
  { value: 'HOLIDAY', label: 'Holiday' },
  { value: 'NON_WORKING_DAY', label: 'Non-working day' },
];

function normalizeEntryType(value) {
  return TIMESHEET_ENTRY_TYPE_OPTIONS.some((option) => option.value === value) ? value : 'WORK';
}

function entryTypeLabel(value) {
  return TIMESHEET_ENTRY_TYPE_OPTIONS.find((option) => option.value === normalizeEntryType(value))?.label || 'Work';
}

function inferEntryTypeFromDescription(description) {
  const text = String(description || '').toLowerCase();
  if (text.includes('sick') || text.includes('illness') || text.includes('medical')) return 'SICK_LEAVE';
  if (text.includes('planned') || text.includes('pto') || text.includes('vacation')) return 'PLANNED_LEAVE';
  if (text.includes('unplanned') || text.includes('emergency')) return 'UNPLANNED_LEAVE';
  if (text.includes('comp off') || text.includes('compoff') || text.includes('compensatory')) return 'COMP_OFF';
  if (text.includes('holiday')) return 'HOLIDAY';
  if (text.includes('non-working') || text.includes('non working') || text.includes('weekend')) return 'NON_WORKING_DAY';
  return 'WORK';
}


// Standalone date-VALUE displays (a table row's date, an error message about
// a specific date) render as MM-DD-YYYY, matching every other page - unlike
// formatDate's calendar-navigation/period-range callers above and below,
// which intentionally keep their own compact "Mon, Sep 15" / "Sep 1 - 15,
// 2026" shapes and are left untouched.
function formatWorkDate(value) {
  return formatDateOnly(parseDateKey(value));
}

function formatPeriod(start, end) {
  if (!start || !end) return 'Current period';
  const startDate = parseDateKey(start);
  const endDate = parseDateKey(end);
  if (startDate.getFullYear() === endDate.getFullYear() && startDate.getMonth() === endDate.getMonth()) {
    return `${startDate.toLocaleDateString('en-US', { month: 'short' })} ${startDate.getDate()} - ${endDate.getDate()}, ${endDate.getFullYear()}`;
  }
  return `${formatDate(start, { month: 'short', day: 'numeric' })} - ${formatDate(end)}`;
}

function formatDateTime(value) {
  return value ? formatDateTimeShared(value) : 'Not submitted';
}

function formatHours(value) { return `${Number(value || 0).toFixed(2)}h`; }
function toneForStatus(status) {
  if (status === 'APPROVED') return 'success';
  if (status === 'REJECTED') return 'error';
  if (status === 'SUBMITTED') return 'warning';
  return 'neutral';
}

function normalizeRecord(record) {
  const entries = (record.entries || []).map((entry) => ({ ...entry, hoursWorked: Number(entry.hoursWorked || 0) }));
  const totalHours = record.totalHours == null ? entries.reduce((total, entry) => total + entry.hoursWorked, 0) : Number(record.totalHours);
  return { ...record, entries, totalHours };
}

function historySortTime(record) {
  return new Date(record?.submittedAt || record?.updatedAt || record?.createdAt || record?.periodEndDate || 0).getTime() || 0;
}

function sortHistoryNewestFirst(history = []) {
  return [...history].sort((a, b) => {
    const timeDiff = historySortTime(b) - historySortTime(a);
    if (timeDiff !== 0) return timeDiff;
    return (b?.periodEndDate || '').localeCompare(a?.periodEndDate || '');
  });
}

function emptyRecord(period, userId) {
  return normalizeRecord({ id: null, employeeUserId: userId, periodStartDate: period.start, periodEndDate: period.end, entries: [], totalHours: 0, status: 'DRAFT' });
}

function errorMessage(error) { return error?.response?.data?.message || 'The timesheet service is unavailable right now.'; }
async function extractErrorMessage(error) {
  if (error?.response?.data instanceof Blob) {
    try {
      const text = await error.response.data.text();
      const parsed = JSON.parse(text);
      if (parsed?.message) return parsed.message;
    } catch {
      // ignore
    }
  }
  return errorMessage(error);
}
function recordForPeriod(records, start, end) { return records.find((record) => record.periodStartDate === start && record.periodEndDate === end); }

function findConflictingRecord(records, start, end) {
  if (!start || !end || !records?.length) return null;

  // Prioritize records that have been submitted, rejected, or approved
  const filedRecords = records.filter((r) => r.status !== 'DRAFT');
  const pool = filedRecords.length ? filedRecords : records;

  // 1. Exact match on period dates (prioritize non-draft)
  const exact = pool.find(
    (record) => record.periodStartDate === start && record.periodEndDate === end
  ) || records.find(
    (record) => record.periodStartDate === start && record.periodEndDate === end
  );
  if (exact) return exact;

  // 2. Overlapping records (date range overlap or concrete entry dates overlap)
  const overlapping = pool.filter((record) => {
    const rangeOverlap = record.periodStartDate <= end && record.periodEndDate >= start;
    const entriesOverlap = (record.entries || []).some(
      (entry) => entry.workDate && entry.workDate >= start && entry.workDate <= end && Number(entry.hoursWorked || 0) > 0
    );
    return rangeOverlap || entriesOverlap;
  });

  if (!overlapping.length) return null;

  // Prioritize exact start date match, status priority (SUBMITTED > REJECTED > APPROVED > DRAFT), overlap length, and recency
  overlapping.sort((a, b) => {
    const aStartMatch = a.periodStartDate === start ? 1 : 0;
    const bStartMatch = b.periodStartDate === start ? 1 : 0;
    if (bStartMatch !== aStartMatch) return bStartMatch - aStartMatch;

    const statusPriority = { SUBMITTED: 4, REJECTED: 3, APPROVED: 2, DRAFT: 1 };
    const priorityDiff = (statusPriority[b.status] || 0) - (statusPriority[a.status] || 0);
    if (priorityDiff !== 0) return priorityDiff;

    const aOverlap = Math.max(0, Math.min(parseDateKey(end).getTime(), parseDateKey(a.periodEndDate).getTime()) - Math.max(parseDateKey(start).getTime(), parseDateKey(a.periodStartDate).getTime()));
    const bOverlap = Math.max(0, Math.min(parseDateKey(end).getTime(), parseDateKey(b.periodEndDate).getTime()) - Math.max(parseDateKey(start).getTime(), parseDateKey(b.periodStartDate).getTime()));
    if (bOverlap !== aOverlap) return bOverlap - aOverlap;

    const bTime = new Date(b.submittedAt || b.updatedAt || b.createdAt || 0).getTime();
    const aTime = new Date(a.submittedAt || a.updatedAt || a.createdAt || 0).getTime();
    return bTime - aTime;
  });

  return overlapping[0];
}

function responseHeader(response, name) {
  const headers = response?.headers || {};
  return headers[name] || headers[name.toLowerCase()] || headers[name.toUpperCase()];
}

function contentDispositionFileName(response) {
  const disposition = responseHeader(response, 'content-disposition');
  if (!disposition) return null;
  const utfMatch = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utfMatch?.[1]) return decodeURIComponent(utfMatch[1].replace(/"/g, ''));
  const plainMatch = disposition.match(/filename="?([^";]+)"?/i);
  return plainMatch?.[1] || null;
}

function downloadFileName(response, fallback) {
  return contentDispositionFileName(response) || fallback || 'supporting-document';
}

async function sniffBlobType(blob, response, fileName = '') {
  const headerType = (blob?.type || responseHeader(response, 'content-type') || '').toLowerCase();
  const extensionName = fileName || '';
  const sample = blob instanceof Blob && typeof blob.slice === 'function'
    ? new Uint8Array(await blob.slice(0, 32).arrayBuffer())
    : new Uint8Array();
  const ascii = Array.from(sample).map((value) => String.fromCharCode(value)).join('');

  if (ascii.startsWith('%PDF')) return { kind: 'pdf', type: 'application/pdf' };
  if (sample.length >= 3 && sample[0] === 0xFF && sample[1] === 0xD8 && sample[2] === 0xFF) return { kind: 'image', type: 'image/jpeg' };
  if (sample.length >= 8 && sample[0] === 0x89 && ascii.slice(1, 4) === 'PNG') return { kind: 'image', type: 'image/png' };
  if (ascii.startsWith('GIF87a') || ascii.startsWith('GIF89a')) return { kind: 'image', type: 'image/gif' };
  if (ascii.startsWith('RIFF') && ascii.slice(8, 12) === 'WEBP') return { kind: 'image', type: 'image/webp' };
  if (headerType.includes('pdf') || /\.pdf$/i.test(extensionName)) return { kind: 'pdf', type: 'application/pdf' };

  const looksText = sample.length > 0 && Array.from(sample).every((value) => (
    value === 0x09 || value === 0x0A || value === 0x0D || value >= 0x20
  ));
  if (looksText || headerType.startsWith('text/')) return { kind: 'text', type: headerType || 'text/plain' };

  if ((headerType.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(extensionName)) && !headerType.includes('svg')) {
    return { kind: 'image', type: headerType.startsWith('image/') ? headerType : 'image/png' };
  }
  if (/\.(csv|tsv|txt|log|md|json)$/i.test(extensionName)) return { kind: 'text', type: headerType || 'text/plain' };
  return { kind: 'generic', type: headerType || 'application/octet-stream' };
}

function saveBlob(blob, fileName) {
  const safeBlob = blob instanceof Blob ? blob : new Blob([blob]);
  const url = URL.createObjectURL(safeBlob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.setAttribute('download', fileName);
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  setTimeout(() => {
    anchor.remove();
    URL.revokeObjectURL(url);
  }, 10000);
}

async function downloadSupportingDocument(documentId, fileName, onError) {
  try {
    const response = await documentApi.download(documentId);
    saveBlob(response.data, downloadFileName(response, fileName));
  } catch (error) {
    const message = await extractErrorMessage(error);
    onError?.(message);
  }
}

async function downloadBlobAsPdf(blob, originalFileName = 'supporting_document', metadata = {}, onError) {
  try {
    const rawType = (blob?.type || '').toLowerCase();
    const cleanBaseName = (originalFileName || 'supporting_document').replace(/\.[^/.]+$/, '');
    const pdfFileName = `${cleanBaseName}.pdf`;

    const isPdf = rawType.includes('pdf') || /\.pdf$/i.test(originalFileName || '');
    if (isPdf) {
      const pdfBlob = blob instanceof Blob && blob.type === 'application/pdf' ? blob : new Blob([blob], { type: 'application/pdf' });
      saveBlob(pdfBlob, pdfFileName);
      return;
    }

    const isImage = (rawType.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(originalFileName || '')) && !rawType.includes('svg');
    if (isImage) {
      try {
        const { default: jsPDF } = await import('jspdf');
        const imgUrl = URL.createObjectURL(blob);
        const img = new Image();
        img.src = imgUrl;

        await new Promise((resolve) => {
          img.onload = () => resolve(true);
          img.onerror = () => resolve(false);
          setTimeout(() => resolve(false), 500);
        });

        const naturalWidth = img.naturalWidth || img.width || 800;
        const naturalHeight = img.naturalHeight || img.height || 600;
        const isLandscape = naturalWidth > naturalHeight;

        const doc = new jsPDF({
          orientation: isLandscape ? 'landscape' : 'portrait',
          unit: 'pt',
          format: 'a4',
        });

        const pageWidth = doc.internal.pageSize.getWidth();
        const pageHeight = doc.internal.pageSize.getHeight();
        const margin = 36;
        const headerSpace = 40;
        const maxWidth = pageWidth - margin * 2;
        const maxHeight = pageHeight - margin * 2 - headerSpace;

        doc.setFontSize(11);
        doc.setTextColor(11, 62, 49);
        doc.text(`Supporting Document: ${originalFileName}`, margin, 26);

        if (metadata.employeeName || metadata.period) {
          doc.setFontSize(9);
          doc.setTextColor(102, 112, 133);
          const sub = [metadata.employeeName, metadata.period ? `Period: ${metadata.period}` : ''].filter(Boolean).join(' • ');
          doc.text(sub, margin, 38);
        }

        let drawWidth = naturalWidth;
        let drawHeight = naturalHeight;
        const scale = Math.min(maxWidth / drawWidth, maxHeight / drawHeight, 1);
        drawWidth *= scale;
        drawHeight *= scale;

        const x = margin + (maxWidth - drawWidth) / 2;
        const y = margin + headerSpace + (maxHeight - drawHeight) / 2;

        let added = false;
        if (typeof document !== 'undefined' && typeof document.createElement === 'function') {
          try {
            const canvas = document.createElement('canvas');
            canvas.width = naturalWidth;
            canvas.height = naturalHeight;
            const ctx = canvas.getContext('2d');
            if (ctx) {
              ctx.drawImage(img, 0, 0);
              const dataUrl = canvas.toDataURL('image/jpeg', 0.95);
              doc.addImage(dataUrl, 'JPEG', x, y, drawWidth, drawHeight);
              added = true;
            }
          } catch {
            // canvas fallback
          }
        }
        if (!added) {
          try {
            doc.addImage(imgUrl, 'JPEG', x, y, drawWidth, drawHeight);
            added = true;
          } catch {
            doc.setFontSize(11);
            doc.setTextColor(60, 60, 60);
            doc.text(`[Attached Image: ${originalFileName}]`, margin, 80);
          }
        }

        URL.revokeObjectURL(imgUrl);
        const pdfBlob = doc.output('blob');
        saveBlob(pdfBlob, pdfFileName);
        return;
      } catch (err) {
        console.warn('Image PDF conversion encountered issue, falling back to text:', err);
      }
    }

    let textContent = '';
    try {
      if (typeof blob.text === 'function') {
        textContent = await blob.text();
      }
    } catch {
      textContent = '';
    }

    const { default: jsPDF } = await import('jspdf');
    const isCsv = /\.csv$/i.test(originalFileName || '') || (rawType.includes('csv') && textContent.includes(','));

    if (isCsv && textContent.trim()) {
      try {
        const { default: autoTable } = await import('jspdf-autotable');
        const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' });
        doc.setFontSize(13);
        doc.setTextColor(11, 62, 49);
        doc.text(`Document: ${originalFileName}`, 36, 32);

        if (metadata.employeeName || metadata.period) {
          doc.setFontSize(9);
          doc.setTextColor(102, 112, 133);
          const sub = [metadata.employeeName, metadata.period ? `Period: ${metadata.period}` : ''].filter(Boolean).join(' • ');
          doc.text(sub, 36, 46);
        }

        const lines = textContent.trim().split(/\r?\n/).filter(Boolean);
        const parseRow = (line) => {
          const row = [];
          let current = '';
          let inQuotes = false;
          for (let i = 0; i < line.length; i++) {
            const char = line[i];
            if (char === '"') {
              inQuotes = !inQuotes;
            } else if (char === ',' && !inQuotes) {
              row.push(current.trim());
              current = '';
            } else {
              current += char;
            }
          }
          row.push(current.trim());
          return row;
        };

        const head = lines.length > 0 ? [parseRow(lines[0])] : [];
        const body = lines.slice(1).map(parseRow);

        autoTable(doc, {
          startY: 56,
          head,
          body,
          headStyles: { fillColor: [11, 98, 79], textColor: 255, fontSize: 8 },
          styles: { fontSize: 8, cellPadding: 3 },
          alternateRowStyles: { fillColor: [246, 249, 247] },
          margin: { left: 36, right: 36 },
        });

        const pdfBlob = doc.output('blob');
        saveBlob(pdfBlob, pdfFileName);
        return;
      } catch (csvErr) {
        console.warn('CSV autoTable PDF fallback:', csvErr);
      }
    }

    const doc = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' });
    const pageWidth = doc.internal.pageSize.getWidth();
    const pageHeight = doc.internal.pageSize.getHeight();
    const margin = 36;
    const maxTextWidth = pageWidth - margin * 2;

    doc.setFontSize(13);
    doc.setTextColor(11, 62, 49);
    doc.text(`Document: ${originalFileName}`, margin, 36);

    if (metadata.employeeName || metadata.period) {
      doc.setFontSize(9);
      doc.setTextColor(102, 112, 133);
      const sub = [metadata.employeeName, metadata.period ? `Period: ${metadata.period}` : ''].filter(Boolean).join(' • ');
      doc.text(sub, margin, 50);
    }

    doc.setDrawColor(220, 225, 230);
    doc.line(margin, 58, pageWidth - margin, 58);

    if (textContent.trim()) {
      doc.setFont('courier', 'normal');
      doc.setFontSize(9);
      doc.setTextColor(40, 40, 40);
      const splitLines = doc.splitTextToSize(textContent, maxTextWidth);
      let y = 74;
      const lineHeight = 12;
      for (let i = 0; i < splitLines.length; i++) {
        if (y + lineHeight > pageHeight - margin) {
          doc.addPage();
          y = margin;
        }
        doc.text(splitLines[i], margin, y);
        y += lineHeight;
      }
    } else {
      doc.setFontSize(10);
      doc.setTextColor(80, 80, 80);
      doc.text('Supporting document export to PDF.', margin, 80);
      doc.text(`Original filename: ${originalFileName}`, margin, 96);
      if (rawType) doc.text(`Content type: ${rawType}`, margin, 112);
      if (blob?.size) doc.text(`File size: ${(blob.size / 1024).toFixed(1)} KB`, margin, 128);
    }

    const pdfBlob = doc.output('blob');
    saveBlob(pdfBlob, pdfFileName);
  } catch (err) {
    console.error('PDF export error:', err);
    onError?.('Failed to convert document to PDF.');
  }
}

function timesheetPdfName(record) {
  const start = record?.periodStartDate || 'period';
  const end = record?.periodEndDate ? `_${record.periodEndDate}` : '';
  return `timesheet_${start}${end}.pdf`;
}

async function downloadPeriodPdf(start, end, onError) {
  try {
    const response = await timesheetApi.downloadPeriodPdf(start, end);
    saveBlob(response.data, `timesheet_${start}_${end}.pdf`);
  } catch (error) {
    onError?.(errorMessage(error));
  }
}

async function downloadTimesheetPdf(record, onError) {
  try {
    if (record?.id && !record.id.startsWith('period-')) {
      const response = await timesheetApi.downloadPdf(record.id);
      saveBlob(response.data, timesheetPdfName(record));
    } else if (record?.periodStartDate && record?.periodEndDate) {
      await downloadPeriodPdf(record.periodStartDate, record.periodEndDate, onError);
    }
  } catch (error) {
    onError?.(errorMessage(error));
  }
}

function consolidatePeriodEntries(history, periodStart, periodEnd, includeDrafts = false) {
  const relevantTimesheets = [...(history || [])]
    .filter((ts) => (includeDrafts ? true : ts.status !== 'DRAFT'))
    .sort((a, b) => {
      const aTime = new Date(a.updatedAt || a.submittedAt || a.createdAt || 0).getTime();
      const bTime = new Date(b.updatedAt || b.submittedAt || b.createdAt || 0).getTime();
      return aTime - bTime;
    });

  let latestManager = null;
  let latestManagerName = null;
  relevantTimesheets.forEach((ts) => {
    if (ts.reportingManagerLoginId) latestManager = ts.reportingManagerLoginId;
    if (ts.reportingManagerName) latestManagerName = ts.reportingManagerName;
  });

  // Group all entries across timesheets by workDate in chronological order
  const entriesByDate = new Map();
  relevantTimesheets.forEach((ts) => {
    (ts.entries || []).forEach((entry) => {
      if (entry.workDate && entry.workDate >= periodStart && entry.workDate <= periodEnd) {
        if (!entriesByDate.has(entry.workDate)) {
          entriesByDate.set(entry.workDate, []);
        }
        entriesByDate.get(entry.workDate).push({
          workDate: entry.workDate,
          dayOfWeek: entry.dayOfWeek || parseDateKey(entry.workDate).toLocaleDateString('en-US', { weekday: 'short' }),
          hoursWorked: Number(entry.hoursWorked || 0),
          description: entry.description || '',
          sourceTimesheetId: ts.id,
          status: ts.status,
          submittedAt: ts.submittedAt,
        });
      }
    });
  });

  const allDays = daysBetween(periodStart, periodEnd);
  const dailyMap = new Map();
  let hasPending = false;
  let hasApproved = false;
  let hasDraft = false;

  allDays.forEach((date) => {
    const historyList = entriesByDate.get(date) || [];
    if (!historyList.length) {
      dailyMap.set(date, {
        workDate: date,
        dayOfWeek: parseDateKey(date).toLocaleDateString('en-US', { weekday: 'short' }),
        hoursWorked: 0,
        description: '',
        status: null,
        isPending: false,
        isApproved: false,
        badge: null,
      });
      return;
    }

    // Latest non-draft record for this date
    const latest = historyList[historyList.length - 1];

    if (latest.status === 'SUBMITTED') {
      hasPending = true;
      dailyMap.set(date, {
        ...latest,
        isPending: true,
        isApproved: false,
        badge: 'Pending',
      });
    } else if (latest.status === 'APPROVED') {
      hasApproved = true;
      dailyMap.set(date, {
        ...latest,
        isPending: false,
        isApproved: true,
        badge: 'Approved',
      });
    } else if (latest.status === 'REJECTED') {
      // Revert to previous approved entry if present, else empty
      const prevApproved = [...historyList].reverse().find((item) => item.status === 'APPROVED' && item.hoursWorked > 0);
      if (prevApproved) {
        hasApproved = true;
        dailyMap.set(date, {
          ...prevApproved,
          isPending: false,
          isApproved: true,
          badge: 'Approved',
        });
      } else {
        dailyMap.set(date, {
          workDate: date,
          dayOfWeek: parseDateKey(date).toLocaleDateString('en-US', { weekday: 'short' }),
          hoursWorked: 0,
          description: '',
          status: null,
          isPending: false,
          isApproved: false,
          badge: null,
        });
      }
    } else {
      // DRAFT or any other in-progress state — show recorded hours with no status badge.
      if (latest.hoursWorked > 0) hasDraft = true;
      dailyMap.set(date, {
        ...latest,
        isPending: false,
        isApproved: false,
        badge: null,
      });
    }
  });

  const consolidatedList = allDays.map((date) => dailyMap.get(date));
  const totalHours = consolidatedList.reduce((sum, item) => sum + item.hoursWorked, 0);
  const workedDays = consolidatedList.filter((item) => item.hoursWorked > 0).length;

  const status = workedDays === 0
    ? 'NO_HOURS'
    : hasPending
    ? 'SUBMITTED'
    : hasApproved
    ? 'APPROVED'
    : hasDraft
    ? 'DRAFT'
    : 'SUBMITTED';

  return {
    periodStartDate: periodStart,
    periodEndDate: periodEnd,
    entries: consolidatedList,
    entryMap: dailyMap,
    totalHours,
    workedDays,
    status,
    reportingManagerLoginId: latestManager || 'Assigned manager',
    reportingManagerName: latestManagerName || null,
  };
}

export default function TimesheetsPage({ role }) {
  const session = getMyHrSession();
  const period = useMemo(() => currentWeek(), []);
  const today = toDateKey(new Date());
  const canEditOwn = role === 'EMPLOYEE' || role === 'HR';
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const defaultView = role === 'EMPLOYEE' ? 'mine' : 'team';
  const [activeView, setActiveView] = useState(role === 'EMPLOYEE' ? 'mine' : 'team');
  const [ownMode, setOwnMode] = useState('landing');
  const [records, setRecords] = useState([]);
  const [loading, setLoading] = useState(true);
  const [reloadKey, setReloadKey] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [operationMessage, setOperationMessage] = useState('');
  const [operationError, setOperationError] = useState('');
  const [saving, setSaving] = useState(false);
  const [reviewingId, setReviewingId] = useState(null);
  const [rejectingRecord, setRejectingRecord] = useState(null);
  const [draftRecord, setDraftRecord] = useState(null);
  const [viewedRecord, setViewedRecord] = useState(null);
  const [entryStep, setEntryStep] = useState('source');
  const [importedEntries, setImportedEntries] = useState(null);
  const [dateOrder, setDateOrder] = useState('MDY');
  const [rangeStart, setRangeStart] = useState(period.start);
  const [rangeEnd, setRangeEnd] = useState(period.end);
  const [rangeHours, setRangeHours] = useState({});
  const [rangeDescriptions, setRangeDescriptions] = useState({});
  const [rangeEntryTypes, setRangeEntryTypes] = useState({});
  const [rangeEntries, setRangeEntries] = useState([]);
  const [reeditModalRecord, setReeditModalRecord] = useState(null);
  const [reeditSubmitted, setReeditSubmitted] = useState(false);
  const [changeReason, setChangeReason] = useState('');
  const [rejectionFeedback, setRejectionFeedback] = useState('');
  const [, setConfirmReedit] = useState(false);
  const [submittedRecord, setSubmittedRecord] = useState(null);
  const [supportingDocuments, setSupportingDocuments] = useState([]);
  const [uploadingDocument, setUploadingDocument] = useState(false);
  const [ocrProgress, setOcrProgress] = useState('');
  const [documentError, setDocumentError] = useState('');
  const [query, setQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [managerFilter, setManagerFilter] = useState('ALL');
  const [periodFilter, setPeriodFilter] = useState('ALL');
  const [fromDateFilter, setFromDateFilter] = useState('');
  const [toDateFilter, setToDateFilter] = useState('');
  const [submissionsSort, setSubmissionsSort] = useState('NEWEST_SUBMITTED');
  const [timesheetSettings, setTimesheetSettings] = useState({ payrollPeriod: 'MONTHLY' });
  const [ownProfile, setOwnProfile] = useState(null);
  const [teamSubTab, setTeamSubTab] = useState('submissions');
  const [selectedEmployee, setSelectedEmployee] = useState(null);
  const [viewingDoc, setViewingDoc] = useState(null);

  // Sync mode and view from search parameters on mount/update. Payroll
  // Readiness is available as a view for HR and Company Admin (mirroring the
  // old standalone /payroll-readiness route it replaced); Employees only
  // ever have their own timesheets.
  useEffect(() => {
    const rawMode = searchParams.get('mode');
    const effectiveMode = rawMode === 'entry' ? 'enter' : rawMode || 'landing';
    setOwnMode(effectiveMode);

    const subtabParam = searchParams.get('subtab');
    if (subtabParam && ['actions', 'submissions', 'employees'].includes(subtabParam)) {
      setTeamSubTab(subtabParam);
    }

    const viewParam = searchParams.get('view');
    const validViews = role === 'EMPLOYEE' ? ['mine'] : role === 'COMPANY_ADMIN' ? ['team', 'payroll'] : ['mine', 'team', 'payroll'];
    if (subtabParam && role !== 'EMPLOYEE') {
      setActiveView('team');
    } else {
      setActiveView(validViews.includes(viewParam) ? viewParam : defaultView);
    }

    const targetId = searchParams.get('timesheetId');
    if (targetId) {
      const match = records.find((r) => r.id === targetId);
      if (match) {
        setViewedRecord(match);
      }
    }
  }, [searchParams, defaultView, records, role]);

  function closeRecord() {
    setViewedRecord(null);
    if (searchParams.has('timesheetId')) {
      pushQuery((q) => q.delete('timesheetId'));
    }
  }

  function pushQuery(mutate) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      mutate(next);
      return next;
    }, { replace: true });
  }

  function goToMode(mode) {
    setOwnMode(mode);
    setViewedRecord(null);
    pushQuery((q) => {
      q.delete('timesheetId');
      if (!mode || mode === 'landing') q.delete('mode'); else q.set('mode', mode);
    });
  }

  function goToView(view) {
    setActiveView(view);
    pushQuery((q) => { if (!view || view === defaultView) q.delete('view'); else q.set('view', view); });
  }

  function goToSubTab(subTab) {
    setTeamSubTab(subTab);
    pushQuery((q) => {
      if (!subTab || subTab === 'submissions') q.delete('subtab');
      else q.set('subtab', subTab);
    });
  }

  function openRecord(record) {
    if (!record) return;
    setViewedRecord(record);
  }

  useEffect(() => {
    let active = true;
    timesheetApi.getSettings()
      .then((response) => {
        if (!active) return;
        const periodSetting = response.data?.data?.payrollPeriod;
        if (periodSetting) {
          setTimesheetSettings({ payrollPeriod: periodSetting });
        }
      })
      .catch(() => {
        // Fallback to default MONTHLY
      });
    return () => { active = false; };
  }, [reloadKey]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    timesheetApi.list()
      .then((response) => {
        if (!active) return;
        setRecords((response.data?.data || []).map(normalizeRecord));
        setLoadError('');
      })
      .catch((error) => {
        if (!active) return;
        setRecords([]);
        setLoadError(errorMessage(error));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [reloadKey]);

  useEffect(() => {
    let active = true;
    if (role !== 'COMPANY_ADMIN') {
      myHrEmployeeApi.getMine()
        .then((response) => {
          if (!active) return;
          setOwnProfile(response.data?.data || null);
        })
        .catch(() => {});
    }
    return () => { active = false; };
  }, [role]);

  const [allManagers, setAllManagers] = useState([]);
  useEffect(() => {
    let active = true;
    if (role === 'COMPANY_ADMIN' && typeof myHrEmployeeApi?.reportingManagers === 'function') {
      const promise = myHrEmployeeApi.reportingManagers();
      if (promise && typeof promise.then === 'function') {
        promise
          .then((response) => {
            if (!active) return;
            const list = response?.data?.data || [];
            setAllManagers(list.map((m) => m.name || m.loginId).filter(Boolean));
          })
          .catch(() => {});
      }
    }
    return () => { active = false; };
  }, [role, reloadKey]);

  const [employeeDirectory, setEmployeeDirectory] = useState([]);
  useEffect(() => {
    let active = true;
    if ((role === 'HR' || role === 'COMPANY_ADMIN') && typeof myHrEmployeeApi?.list === 'function') {
      const promise = myHrEmployeeApi.list();
      if (promise && typeof promise.then === 'function') {
        promise
          .then((response) => {
            if (!active) return;
            setEmployeeDirectory(response?.data?.data || []);
          })
          .catch(() => {});
      }
    }
    return () => { active = false; };
  }, [role, reloadKey]);

  const ownRecords = useMemo(() => {
    return records.filter((record) => record.employeeUserId === session?.userId);
  }, [records, session?.userId]);

  const currentOwnRecord = useMemo(() => {
    const forPeriod = recordForPeriod(ownRecords, period.start, period.end);
    if (forPeriod) return forPeriod;
    const activeDraft = ownRecords.find((record) => record.status === 'DRAFT');
    return activeDraft || emptyRecord(period, session?.userId || 'current-user');
  }, [ownRecords, period, session?.userId]);

  useEffect(() => { setDraftRecord(currentOwnRecord); }, [currentOwnRecord]);

  const teamRecords = useMemo(() => {
    const base = role === 'COMPANY_ADMIN'
      ? records
      : (() => {
          const ownIds = new Set(ownRecords.map((record) => record.id));
          return records.filter((record) => !ownIds.has(record.id));
        })();
    return base.filter((record) => record.status !== 'DRAFT');
  }, [ownRecords, records, role]);

  const visibleTeam = useMemo(() => teamRecords.filter((record) => {
    const searchText = `${record.employeeId || ''} ${record.employeeName || ''} ${record.reportingManagerName || ''} ${record.reportingManagerLoginId || ''}`.toLowerCase();
    const mgr = record.reportingManagerName || record.reportingManagerLoginId;
    // A custom From/End range (once either bound is set) takes over from the
    // Current/Previous month presets entirely, rather than being ANDed with
    // them - picking a custom range is an explicit "ignore the quick preset".
    const periodMatches = (fromDateFilter || toDateFilter)
      ? isWithinCustomRange(record.periodStartDate, fromDateFilter, toDateFilter)
      : isWithinPeriodPreset(record.periodStartDate, record.periodEndDate, periodFilter, today);
    return searchText.includes(query.toLowerCase())
      && (managerFilter === 'ALL' || mgr === managerFilter || record.reportingManagerLoginId === managerFilter || record.reportingManagerName === managerFilter)
      && (roleFilter === 'ALL' || record.employeeRole === roleFilter || record.role === roleFilter)
      && (statusFilter === 'ALL' || record.status === statusFilter)
      && periodMatches;
  }), [fromDateFilter, managerFilter, periodFilter, query, roleFilter, statusFilter, teamRecords, toDateFilter, today]);

  const currentEntries = draftRecord?.entries || [];
  const currentDays = daysBetween(period.start, period.end);
  const currentTotalHours = currentEntries.reduce((total, entry) => total + Number(entry.hoursWorked || 0), 0);
  const history = ownRecords.filter((record) => record.status !== 'DRAFT' || record.id !== draftRecord?.id);
  const managers = useMemo(() => {
    const fromRecords = teamRecords.map((record) => record.reportingManagerName || record.reportingManagerLoginId).filter(Boolean);
    return [...new Set([...allManagers, ...fromRecords])].sort();
  }, [allManagers, teamRecords]);

  const managerDisplayName = submittedRecord?.reportingManagerName
    || submittedRecord?.reportingManagerLoginId
    || ownProfile?.reportingManager?.name
    || draftRecord?.reportingManagerName
    || draftRecord?.reportingManagerLoginId
    || (role === 'HR' ? 'Assigned manager' : 'Assigned manager');
  const teamStats = useMemo(() => {
    let submitted = 0;
    let approved = 0;
    let rejected = 0;
    for (let i = 0; i < teamRecords.length; i++) {
      const s = teamRecords[i].status;
      if (s === 'SUBMITTED') submitted++;
      else if (s === 'APPROVED') approved++;
      else if (s === 'REJECTED') rejected++;
    }
    return { total: teamRecords.length, submitted, approved, rejected };
  }, [teamRecords]);
  const canReview = role === 'COMPANY_ADMIN' || role === 'HR';
  const editable = canEditOwn && draftRecord?.status !== 'SUBMITTED' && draftRecord?.status !== 'APPROVED';

  function replaceRecord(record) {
    const normalized = normalizeRecord(record);
    setRecords((current) => [normalized, ...current.filter((item) => item.id !== normalized.id)]);
    setDraftRecord(normalized);
    return normalized;
  }

  function beginEntryFlow() {
    setImportedEntries(null);
    setRangeStart(draftRecord?.periodStartDate || period.start);
    setRangeEnd(draftRecord?.periodEndDate || period.end);
    setRangeHours({});
    setRangeDescriptions({});
    setRangeEntryTypes({});
    setRangeEntries([]);
    setEntryStep('source');
    setReeditSubmitted(false);
    setConfirmReedit(false);
    setChangeReason('');
    setRejectionFeedback('');
    setReeditModalRecord(null);
    setSubmittedRecord(null);
    setOperationError('');
    setOperationMessage('');
    setDocumentError('');
    setSupportingDocuments(draftRecord?.supportingDocuments || []);
    goToMode('enter');
  }

  function beginResubmitRejected(record) {
    if (!record) return;
    setImportedEntries(null);
    setRangeStart(record.periodStartDate);
    setRangeEnd(record.periodEndDate);
    const dates = daysBetween(record.periodStartDate, record.periodEndDate);
    const entriesMap = {};
    (record.entries || []).forEach((e) => {
      if (e.workDate) entriesMap[e.workDate] = e;
    });
    setRangeHours(Object.fromEntries(dates.map((d) => [d, entriesMap[d]?.hoursWorked ?? ''])));
    setRangeDescriptions(Object.fromEntries(dates.map((d) => [d, entriesMap[d]?.description || ''])));
    setRangeEntryTypes(Object.fromEntries(dates.map((d) => [d, normalizeEntryType(entriesMap[d]?.entryType)])));
    setRangeEntries([]);
    setSupportingDocuments(record.supportingDocuments || []);
    setReeditSubmitted(true);
    setConfirmReedit(true);
    setChangeReason('');
    setRejectionFeedback(record.reviewComment || 'Timesheet was rejected. Please review feedback, update hours, and resubmit.');
    setReeditModalRecord(null);
    setSubmittedRecord(null);
    setOperationError('');
    setOperationMessage('');
    setEntryStep('hours');
    goToMode('enter');
  }

  async function uploadSupportingDocuments(fileList) {
    const files = Array.from(fileList || []).filter(Boolean);
    if (!files.length) return;
    setDocumentError('');
    setOperationError('');
    setUploadingDocument(true);
    const uploaded = [];
    const errors = [];
    try {
      for (const file of files) {
        try {
          const response = await documentApi.upload(file);
          const data = response.data?.data || {};
          uploaded.push({ id: data.id || null, fileName: data.fileName || file.name });
        } catch (error) {
          errors.push(`${file.name}: ${errorMessage(error)}`);
        }
      }
    } finally {
      setUploadingDocument(false);
    }
    if (uploaded.length) setSupportingDocuments((prev) => [...prev, ...uploaded]);
    if (errors.length) setDocumentError(errors.join(' '));
  }

  function removeSupportingDocument(doc) {
    setSupportingDocuments((prev) => prev.filter((d) => d.id !== doc.id));
  }

  async function importOneTimesheetDocument(file) {
    let uploaded = null;
    let ocrText = null;

    const isImage = file.type?.startsWith('image/') || /\.(png|jpe?g|webp)$/i.test(file.name);
    if (isImage) {
      try {
        setOcrProgress(`Scanning ${file.name} with OCR...`);
        const { createWorker } = await import('tesseract.js');
        const worker = await createWorker('eng');
        let imageSource = file;
        try {
          if (typeof window !== 'undefined' && typeof document !== 'undefined' && window.createImageBitmap) {
            const bitmap = await createImageBitmap(file);
            const scale = Math.min(2.5, Math.max(1.0, 2600 / Math.max(bitmap.width || 1, bitmap.height || 1)));
            const canvas = document.createElement('canvas');
            canvas.width = Math.round((bitmap.width || 1) * scale);
            canvas.height = Math.round((bitmap.height || 1) * scale);
            const ctx = canvas.getContext('2d');
            if (ctx) {
              ctx.imageSmoothingEnabled = true;
              ctx.imageSmoothingQuality = 'high';
              ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
              const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
              const d = imgData.data;
              for (let i = 0; i < d.length; i += 4) {
                const gray = (d[i] * 299 + d[i + 1] * 587 + d[i + 2] * 114) / 1000;
                const stretched = gray > 230 ? 255 : (gray < 160 ? 0 : Math.round((gray - 160) * 255 / 70));
                d[i] = stretched;
                d[i + 1] = stretched;
                d[i + 2] = stretched;
              }
              ctx.putImageData(imgData, 0, 0);
              imageSource = canvas;
            }
          }
        } catch {
          imageSource = file;
        }
        const ret = await worker.recognize(imageSource);
        ocrText = ret?.data?.text || '';
        await worker.terminate();
      } catch (ocrErr) {
        console.warn('Client-side OCR scanning unavailable, continuing with document upload', ocrErr);
      } finally {
        setOcrProgress('');
      }
    }

    try {
      const response = await documentApi.upload(file);
      const document = response.data?.data;
      if (!document?.id) throw new Error('Missing document id');
      uploaded = { id: document.id, fileName: document.fileName || file.name };
      const parsedResponse = ocrText
        ? await documentApi.parseTimesheet(document.id, dateOrder, ocrText)
        : await documentApi.parseTimesheet(document.id, dateOrder);
      const parsed = parsedResponse.data?.data;
      return { uploaded, parsed, error: null };
    } catch (error) {
      return {
        uploaded,
        parsed: null,
        error: uploaded ? 'Document attached, but extraction failed. Enter hours manually.' : `${file.name}: ${errorMessage(error)}`,
      };
    }
  }

  async function importTimesheetDocuments(fileList) {
    const files = Array.from(fileList || []).filter(Boolean);
    if (!files.length || uploadingDocument) return;
    const oversized = files.find((file) => file.size > 5 * 1024 * 1024);
    if (oversized) { setDocumentError(`${oversized.name} exceeds 5 MB.`); return; }

    setDocumentError('');
    setOperationMessage('');
    setUploadingDocument(true);

    const uploadedDocs = [];
    const errors = [];
    const messages = [];
    const unreadableFiles = [];
    const mergedEntries = new Map();
    let combinedStart = null;
    let combinedEnd = null;
    let anyParsed = false;

    try {
      for (const file of files) {
        const result = await importOneTimesheetDocument(file);
        if (result.uploaded) uploadedDocs.push(result.uploaded);
        if (result.error) errors.push(result.error);
        const parsed = result.parsed;
        if (parsed?.entries?.length && parsed.periodStartDate && parsed.periodEndDate) {
          anyParsed = true;
          if (parsed.message) messages.push(parsed.message);
          for (const entry of parsed.entries) {
            if (entry?.workDate) mergedEntries.set(entry.workDate, entry);
          }
          if (!combinedStart || parsed.periodStartDate < combinedStart) combinedStart = parsed.periodStartDate;
          if (!combinedEnd || parsed.periodEndDate > combinedEnd) combinedEnd = parsed.periodEndDate;
        } else if (!result.error) {
          // Upload succeeded but nothing could be extracted (e.g. a scanned PDF
          // with no readable text layer) - still attached above, but silently
          // defaulting its dates would mislead the user, so flag it instead.
          unreadableFiles.push(`${file.name}${parsed?.message ? `: ${parsed.message}` : ''}`);
        }
      }
    } finally {
      setUploadingDocument(false);
      setOcrProgress('');
    }

    if (uploadedDocs.length) {
      setSupportingDocuments((prev) => [...prev, ...uploadedDocs]);
    }

    if (anyParsed) {
      const entries = Array.from(mergedEntries.values()).sort((a, b) => (a.workDate < b.workDate ? -1 : 1));
      setImportedEntries(entries);
      setRangeStart(combinedStart);
      setRangeEnd(combinedEnd);
      const notices = [...errors, ...unreadableFiles.map((notice) => `Could not read ${notice}`)];
      setDocumentError(notices.join(' '));
      setOperationMessage(messages.length
        ? messages.join(' ')
        : 'Check the attached document and enter hours manually.');
      continueToHours(combinedStart, combinedEnd, entries, [...supportingDocuments, ...uploadedDocs]);
    } else if (uploadedDocs.length) {
      const notices = [...errors, ...unreadableFiles.map((notice) => `Could not read ${notice}`)];
      const suffix = unreadableFiles.length
        ? ' Enter the correct date range and hours manually below - the document(s) are still attached.'
        : '';
      setDocumentError(notices.length
        ? `${notices.join(' ')}${suffix}`
        : 'Enter the correct date range and hours manually below - the document(s) are still attached.');
      const fallbackStart = rangeStart || draftRecord?.periodStartDate || period.start;
      const fallbackEnd = rangeEnd || draftRecord?.periodEndDate || period.end;
      setImportedEntries(null);
      setRangeStart(fallbackStart);
      setRangeEnd(fallbackEnd);
      setRangeHours({});
      setRangeDescriptions({});
      setEntryStep('dates');
    } else if (errors.length) {
      setDocumentError(errors.join(' '));
    }
  }

  function clearTransientOverlays() {
    setViewingDoc(null);
    setReeditModalRecord(null);
    setSubmittedRecord(null);
    setRejectionFeedback('');
    setOperationError('');
  }

  // In-view "Back"/"Cancel"/"Back to overview" buttons: pop one URL step so the
  // browser's own back button and this button stay in sync.
  function backToOverview() {
    clearTransientOverlays();
    setEntryStep('dates');
    goToMode('landing');
  }

  // Top "Back" button: dismiss transient overlays or in-progress steps, otherwise
  // navigate back to the previous page (dashboard) rather than stepping through actions.
  function handleFeatureBack() {
    if (uploadingDocument) return;
    if (viewingDoc) { setViewingDoc(null); return; }
    if (rejectingRecord) { setRejectingRecord(null); return; }
    if (reeditModalRecord) { setReeditModalRecord(null); return; }
    if (viewedRecord) { closeRecord(); return; }
    if (selectedEmployee) {
      setSelectedEmployee(null);
      setTeamSubTab('directory');
      return;
    }
    if (ownMode === 'enter' && entryStep === 'review') { setEntryStep('hours'); return; }
    if (ownMode === 'enter' && entryStep === 'hours') { setEntryStep('dates'); return; }
    if (ownMode === 'enter' || ownMode === 'history' || ownMode === 'hours') {
      goToMode('landing');
      return;
    }
    clearTransientOverlays();
    const dashboardRoute = getMyHrHomePath(role) ? `${getMyHrHomePath(role)}/dashboard` : '/myhr';
    navigate(dashboardRoute);
  }

  function proceedToHours(targetRecord, dates, isReedit = false, imported = importedEntries, uploaded = supportingDocuments) {
    const existingEntriesMap = {};
    ownRecords.forEach((rec) => {
      (rec.entries || []).forEach((entry) => {
        if (entry.workDate && entry.hoursWorked != null) {
          existingEntriesMap[entry.workDate] = entry;
        }
      });
    });
    if (targetRecord?.entries) {
      targetRecord.entries.forEach((entry) => {
        existingEntriesMap[entry.workDate] = entry;
      });
    }

    const importedMap = {};
    (imported || []).forEach((entry) => {
      existingEntriesMap[entry.workDate] = entry;
      importedMap[entry.workDate] = entry;
    });
    setRangeHours(Object.fromEntries(dates.map((date) => [
      date,
      importedMap[date]?.hoursWorked != null
        ? String(importedMap[date].hoursWorked)
        : (rangeHours[date] ?? existingEntriesMap[date]?.hoursWorked ?? '')
    ])));
    setRangeDescriptions(Object.fromEntries(dates.map((date) => [
      date,
      importedMap[date]?.description != null && importedMap[date]?.description !== ''
        ? importedMap[date].description
        : (rangeDescriptions[date] ?? existingEntriesMap[date]?.description ?? '')
    ])));
    setRangeEntryTypes(Object.fromEntries(dates.map((date) => [
      date,
      normalizeEntryType(importedMap[date]?.entryType || rangeEntryTypes[date] || existingEntriesMap[date]?.entryType)
    ])));
    if (!uploaded?.length && targetRecord?.supportingDocuments?.length) {
      setSupportingDocuments(targetRecord.supportingDocuments);
    }
    if (isReedit) {
      setReeditSubmitted(true);
      setConfirmReedit(true);
      setChangeReason(targetRecord?.changeReason || '');
    } else {
      setReeditSubmitted(false);
      setConfirmReedit(false);
      setChangeReason('');
    }
    setOperationError('');
    setEntryStep('hours');
  }

  function continueToHours(start, end, imported = importedEntries, uploaded = supportingDocuments) {
    const s = start || rangeStart || draftRecord?.periodStartDate || period.start;
    const e = end || rangeEnd || draftRecord?.periodEndDate || period.end;
    if (s !== rangeStart) setRangeStart(s);
    if (e !== rangeEnd) setRangeEnd(e);
    const dates = daysBetween(s, e);
    if (!dates.length) { setOperationError('Choose a valid start and end date.'); return; }
    if (s > today) { setOperationError('Future dates cannot be recorded. Choose a start date of today or earlier.'); return; }
    if (dates.length > 31) { setOperationError('Choose a date range of 31 days or fewer.'); return; }

    const targetRecord = findConflictingRecord(ownRecords, s, e);

    // Map existing entries across non-draft filed records (SUBMITTED, REJECTED, APPROVED)
    const filedEntriesMap = {};
    ownRecords
      .filter((rec) => rec.status !== 'DRAFT')
      .forEach((rec) => {
        (rec.entries || []).forEach((entry) => {
          if (entry.workDate && Number(entry.hoursWorked || 0) > 0) {
            filedEntriesMap[entry.workDate] = entry;
          }
        });
      });

    // Conflicted dates are dates in the selected range that already have hours in filed timesheets
    const conflictedEntries = dates
      .filter((date) => Boolean(filedEntriesMap[date]))
      .map((date) => filedEntriesMap[date]);

    // Only warn about a conflict when the selected range actually re-covers days that
    // already have recorded hours on a filed timesheet. A plain period overlap (e.g.
    // submitting a new week while an earlier week for the same month is already filed)
    // is not a conflict — it becomes its own record and the days are merged for display.
    const hasConflict = conflictedEntries.length > 0;

    if (hasConflict) {
      setOperationError('');
      const modalRecord = {
        ...(targetRecord || {}),
        id: targetRecord?.id || null,
        periodStartDate: targetRecord?.periodStartDate || s,
        periodEndDate: targetRecord?.periodEndDate || e,
        status: targetRecord?.status || 'SUBMITTED',
        submittedAt: targetRecord?.submittedAt || targetRecord?.updatedAt || new Date().toISOString(),
        totalHours: targetRecord?.totalHours != null ? targetRecord.totalHours : conflictedEntries.reduce((sum, e) => sum + Number(e.hoursWorked || 0), 0),
        entries: conflictedEntries.length ? conflictedEntries : (targetRecord?.entries || []),
        conflictedEntries: conflictedEntries.length ? conflictedEntries : (targetRecord?.entries || []),
      };
      setReeditModalRecord(modalRecord);
      return;
    }

    proceedToHours(targetRecord, dates, false, imported, uploaded);
  }

  function handleConfirmReedit() {
    if (!reeditModalRecord) return;
    const dates = daysBetween(rangeStart, rangeEnd);
    goToMode('enter');
    proceedToHours(reeditModalRecord, dates, true);
    setReeditModalRecord(null);
  }

  function beginReeditSubmitted(record) {
    setImportedEntries(null);
    setRangeHours({});
    setRangeDescriptions({});
setRangeEntryTypes({});
    setSupportingDocuments(record.supportingDocuments || []);
    setRangeStart(record.periodStartDate);
    setRangeEnd(record.periodEndDate);
    setReeditModalRecord(record);
  }

  function reviewEntry() {
    if (reeditSubmitted && !changeReason.trim()) {
      setOperationError('Please provide a reason for the change before reviewing the timesheet.');
      return;
    }
    const entries = [];
    for (const date of daysBetween(rangeStart, rangeEnd)) {
      if (date > today) continue; // future days are read-only
      const rawHours = rangeHours[date] === '' || rangeHours[date] == null ? 0 : Number(rangeHours[date]);
      if (!Number.isFinite(rawHours) || rawHours < 0 || rawHours > 24) { setOperationError(`Enter between 0 and 24 hours for ${formatWorkDate(date)}.`); return; }
      const description = String(rangeDescriptions[date] || '').trim();
      const selectedEntryType = normalizeEntryType(rangeEntryTypes[date]);
      const inferredEntryType = selectedEntryType === 'WORK' && rawHours === 0 && description ? inferEntryTypeFromDescription(description) : selectedEntryType;
      const entryType = normalizeEntryType(inferredEntryType);
      const isTypedNonWorkDay = entryType !== 'WORK';
      // A day is recorded once it has worked hours, an explicit note, or a structured
      // non-work type (sick leave, planned leave, holiday, comp-off, non-working day).
      // A day left as Work with both 0 hours and no note stays untouched/missing.
      if (rawHours > 0 || description || isTypedNonWorkDay) entries.push({ workDate: date, dayOfWeek: parseDateKey(date).toLocaleDateString('en-US', { weekday: 'long' }).toUpperCase(), hoursWorked: Number(rawHours.toFixed(2)), entryType, description });
    }
    if (!entries.length) { setOperationError('Add at least one hour, a non-work entry type, or a note for a day with zero hours before reviewing the timesheet.'); return; }
    setRangeEntries(entries);
    setOperationError('');
    setEntryStep('review');
  }

  async function persistRange(status) {
    if (!rangeEntries.length) { setOperationError('Add at least one hour, a non-work entry type, or a note for a day with zero hours before saving or submitting.'); return; }
    const existing = findConflictingRecord(ownRecords, rangeStart, rangeEnd);

    // Does this submission re-cover days that already have recorded hours on a filed
    // (non-draft) timesheet? Only then is it a "correction" that needs a reason.
    const submittedDates = new Set(rangeEntries.map((entry) => entry.workDate));
    const overridesFiledHours = Boolean(existing)
      && existing.status !== 'DRAFT'
      && (existing.entries || []).some(
        (entry) => submittedDates.has(entry.workDate) && Number(entry.hoursWorked || 0) > 0,
      );

    // Update a record in place only for an in-progress draft or an explicit correction
    // the user opted into (the re-edit / resubmit flow, which sets reeditSubmitted). A
    // plain new submission always creates its own record, so previously recorded /
    // approved days for other dates stay intact and per-day status is resolved by
    // recency when the month is consolidated for display.
    const updateInPlace = Boolean(existing?.id)
      && !existing.id.startsWith('prototype-') && !existing.id.startsWith('period-')
      && (existing.status === 'DRAFT' || (reeditSubmitted && existing.status !== 'REJECTED'));

    const needsChangeReason = reeditSubmitted || overridesFiledHours;
    if (needsChangeReason && !changeReason.trim() && !existing?.changeReason) {
      setOperationError('A reason for change is required when modifying hours you already submitted.');
      return;
    }

    const resolvedDocuments = supportingDocuments.length
      ? supportingDocuments
      : (draftRecord?.supportingDocuments || existing?.supportingDocuments || []);
    const hasSupportingDoc = resolvedDocuments.length > 0;
    if (status === 'SUBMITTED' && !hasSupportingDoc) {
      setDocumentError('A supporting document is mandatory to submit your timesheet.');
      setOperationError('Please upload a supporting timesheet document before submitting.');
      return;
    }

    const resolvedChangeReason = (changeReason || existing?.changeReason || '').trim();
    const payload = {
      periodStartDate: rangeStart,
      periodEndDate: rangeEnd,
entries: rangeEntries.map((entry) => ({ workDate: entry.workDate, hoursWorked: Number(entry.hoursWorked), entryType: normalizeEntryType(entry.entryType), description: entry.description || '' })),
      supportingDocumentIds: resolvedDocuments.map((doc) => doc.id).filter(Boolean),
      status,
      ...(needsChangeReason ? { changeReason: resolvedChangeReason || 'Correction to submitted timesheet', confirmReedit: true } : {}),
    };
    const existingId = updateInPlace ? existing.id : null;
    setSaving(true);
    setOperationError('');
    setOperationMessage('');
    try {
      const response = existingId ? await timesheetApi.update(existingId, payload) : await timesheetApi.create(payload);
      const saved = response.data?.data || { ...existing, ...payload, id: existingId || `local-${Date.now()}` };
      const normalizedSaved = normalizeRecord(saved);
      replaceRecord(normalizedSaved);
      setSubmittedRecord(normalizedSaved);
      // Re-sync with the server so every timesheet (previously approved, still pending,
      // and the one just saved) is shown from the authoritative DB state, not just local
      // optimistic state.
      setReloadKey((current) => current + 1);
      if (status === 'SUBMITTED') {
        setEntryStep('success');
      } else {
        setOperationMessage('Draft saved.');
        goToMode('landing');
      }
      setReeditSubmitted(false);
      setConfirmReedit(false);
    } catch (error) {
      setOperationError(errorMessage(error));
    } finally { setSaving(false); }
  }

  async function reviewTimesheet(record, status, comment = '') {
    if (!record?.id) {
      setOperationError('This timesheet does not have a server record yet.');
      return;
    }

    setReviewingId(record.id);
    setOperationError('');
    setOperationMessage('');
    try {
      const response = await timesheetApi.review(record.id, {
        status,
        comment: String(comment || '').trim() || null,
      });
      const reviewed = normalizeRecord(response.data?.data || {
        ...record,
        status,
        reviewComment: String(comment || '').trim() || null,
      });
      setRecords((current) => current.map((item) => item.id === reviewed.id ? reviewed : item));
      setViewedRecord((current) => current?.id === reviewed.id ? reviewed : current);
      setOperationMessage(`Timesheet ${status === 'APPROVED' ? 'approved' : 'rejected'}.`);
    } catch (error) {
      setOperationError(errorMessage(error));
    } finally {
      setReviewingId(null);
    }
  }

  async function handleConfirmReject(record, reason) {
    await reviewTimesheet(record, 'REJECTED', reason);
    setRejectingRecord(null);
  }

  const title = role === 'EMPLOYEE' ? 'My Timesheets' : 'Timesheets';
  const description = role === 'HR' ? 'Record your own hours and review submissions from your direct reports.' : role === 'COMPANY_ADMIN' ? 'Review submitted timesheets across the organization.' : 'Record your hours, review the period, and submit to your reporting manager.';
  const serviceMessage = loadError;
  const isOwnTaskOpen = role !== 'COMPANY_ADMIN' && activeView === 'mine' && ownMode !== 'landing';

  const workspaceContent = activeView === 'payroll' && (role === 'HR' || role === 'COMPANY_ADMIN') ? (
    <PayrollReadinessWorkspace role={role} />
  ) : role === 'COMPANY_ADMIN' || (role !== 'EMPLOYEE' && activeView === 'team') ? (
    <TeamWorkspace
      role={role}
      canReview={canReview}
      reviewingId={reviewingId}
      onReview={reviewTimesheet}
      onReject={(record) => setRejectingRecord(record)}
      loading={loading}
      teamStats={teamStats}
      visibleTeam={visibleTeam}
      teamRecords={teamRecords}
      managers={managers}
      query={query}
      setQuery={setQuery}
      roleFilter={roleFilter}
      setRoleFilter={setRoleFilter}
      statusFilter={statusFilter}
      setStatusFilter={setStatusFilter}
      managerFilter={managerFilter}
      setManagerFilter={setManagerFilter}
      periodFilter={periodFilter}
      setPeriodFilter={setPeriodFilter}
      fromDateFilter={fromDateFilter}
      setFromDateFilter={setFromDateFilter}
      toDateFilter={toDateFilter}
      setToDateFilter={setToDateFilter}
      submissionsSort={submissionsSort}
      setSubmissionsSort={setSubmissionsSort}
      onView={openRecord}
      onDownloadPdf={(record) => downloadTimesheetPdf(record, setOperationError)}
      onDownloadDoc={(id, name) => downloadSupportingDocument(id, name, setOperationError)}
      onViewDoc={(doc) => setViewingDoc(doc)}
      today={today}
      payrollPeriod={timesheetSettings.payrollPeriod}
      employeeDirectory={employeeDirectory}
      subTab={teamSubTab}
      setSubTab={goToSubTab}
      selectedEmployee={selectedEmployee}
      setSelectedEmployee={setSelectedEmployee}
    />
  ) : (
    <EmployeeWorkspace
      loading={loading}
      mode={ownMode}
      setMode={goToMode}
      today={today}
      period={period}
      currentDays={currentDays}
      monthKey={today.slice(0, 7)}
      records={ownRecords}
      draftRecord={draftRecord}
      currentEntries={currentEntries}
      currentTotalHours={currentTotalHours}
      history={history}
      editable={editable}
      entryStep={entryStep}
      setEntryStep={setEntryStep}
      rangeStart={rangeStart}
      setRangeStart={setRangeStart}
      rangeEnd={rangeEnd}
      setRangeEnd={setRangeEnd}
      rangeHours={rangeHours}
      setRangeHours={setRangeHours}
      rangeDescriptions={rangeDescriptions}
      setRangeDescriptions={setRangeDescriptions}
      rangeEntryTypes={rangeEntryTypes}
      setRangeEntryTypes={setRangeEntryTypes}
      rangeEntries={rangeEntries}
      supportingDocuments={supportingDocuments}
      uploadingDocument={uploadingDocument}
      ocrProgress={ocrProgress}
      documentError={documentError}
      onImportDocument={importTimesheetDocuments}
      dateOrder={dateOrder}
      setDateOrder={setDateOrder}
      onUploadSupportingDocument={uploadSupportingDocuments}
      onRemoveSupportingDocument={removeSupportingDocument}
      saving={saving}
      role={role}
      onBegin={beginEntryFlow}
      onBack={backToOverview}
      onViewHours={() => goToMode('hours')}
      onContinue={() => continueToHours()}
      onReview={reviewEntry}
      onSave={() => persistRange('DRAFT')}
      onSubmit={() => persistRange('SUBMITTED')}
      onView={openRecord}
      onDownloadPdf={(record) => downloadTimesheetPdf(record, setOperationError)}
      onDownloadPeriodPdf={(start, end) => downloadPeriodPdf(start, end, setOperationError)}
      reeditSubmitted={reeditSubmitted}
      changeReason={changeReason}
      setChangeReason={setChangeReason}
      onReeditSubmitted={beginReeditSubmitted}
      payrollPeriod={timesheetSettings.payrollPeriod}
      submittedRecord={submittedRecord}
      onViewHistory={() => goToMode('history')}
      rejectionFeedback={rejectionFeedback}
      onResubmit={beginResubmitRejected}
      managerDisplayName={managerDisplayName}
    />
  );

  return <MyHrFeaturePage
    className={activeView === 'payroll' ? 'timesheets--payroll-readiness' : activeView === 'team' ? 'timesheets--review-compact' : ''}
    title={isOwnTaskOpen ? undefined : title}
    description={isOwnTaskOpen ? undefined : description}
    onBack={handleFeatureBack}
  >
    {(serviceMessage || operationError || operationMessage) && <div className={`timesheets__message${operationError || serviceMessage ? ' timesheets__message--error' : ''}`} role="status"><span>{operationError || operationMessage || serviceMessage}</span>{serviceMessage && <Button type="button" variant="secondary" onClick={() => setReloadKey((current) => current + 1)}>Retry</Button>}</div>}
    {(role === 'HR' || role === 'COMPANY_ADMIN') && !isOwnTaskOpen && <div className="timesheets__workspace-tabs" role="tablist" aria-label={role === 'HR' ? 'HR timesheet workspace' : 'Admin timesheet workspace'}>
      {role === 'HR' && <button type="button" role="tab" aria-selected={activeView === 'mine'} className={activeView === 'mine' ? 'active' : ''} onClick={() => goToView('mine')}>My Timesheets</button>}
      <button type="button" role="tab" aria-selected={activeView === 'team'} className={activeView === 'team' ? 'active' : ''} onClick={() => goToView('team')}>Team Timesheets</button>
      <button type="button" role="tab" aria-selected={activeView === 'payroll'} className={activeView === 'payroll' ? 'active' : ''} onClick={() => goToView('payroll')}>Payroll Readiness</button>
    </div>}
    {workspaceContent}
    <TimesheetDetails record={viewedRecord} canReview={canReview} reviewingId={reviewingId} onReview={(status, comment) => reviewTimesheet(viewedRecord, status, comment)} onReject={(record) => setRejectingRecord(record)} onClose={closeRecord} onDownload={(id, name) => downloadSupportingDocument(id, name, setOperationError)} onDownloadPdf={(record) => downloadTimesheetPdf(record, setOperationError)} onDownloadPeriodPdf={(start, end) => downloadPeriodPdf(start, end, setOperationError)} onResubmit={(record) => beginResubmitRejected(record)} onViewDoc={(doc) => setViewingDoc(doc)} />
    <RejectTimesheetModal record={rejectingRecord} isSubmitting={Boolean(reviewingId)} onConfirm={handleConfirmReject} onClose={() => setRejectingRecord(null)} />
    <ReeditConfirmationModal record={reeditModalRecord} selectedStart={rangeStart} selectedEnd={rangeEnd} onConfirm={handleConfirmReedit} onClose={() => setReeditModalRecord(null)} />
    <DocumentViewerModal doc={viewingDoc} onClose={() => setViewingDoc(null)} onError={setOperationError} />
  </MyHrFeaturePage>;
}

function EmployeeWorkspace({ loading, mode, setMode, today, records, draftRecord, history, entryStep, setEntryStep, rangeStart, setRangeStart, rangeEnd, setRangeEnd, rangeHours, setRangeHours, rangeDescriptions, setRangeDescriptions, rangeEntryTypes, setRangeEntryTypes, rangeEntries, supportingDocuments, uploadingDocument, ocrProgress, documentError, onImportDocument, dateOrder, setDateOrder, onUploadSupportingDocument, onRemoveSupportingDocument, saving, role, onBegin, onBack, onViewHours, onContinue, onReview, onSave, onSubmit, onView, onDownloadPdf, onDownloadPeriodPdf, reeditSubmitted, changeReason, setChangeReason, payrollPeriod, submittedRecord, onViewHistory, rejectionFeedback, onResubmit, managerDisplayName }) {
  const reviewTotal = rangeEntries.reduce((sum, entry) => sum + Number(entry.hoursWorked || 0), 0);
  if (mode === 'landing') {
    const recentHours = (history || []).length > 0 ? (history[0].totalHours || 0) : 0;
    return (
      <section className="timesheets__landing" aria-label="Timesheet actions">
        <div className="timesheets__landing-top-container">
          <div className="timesheets__landing-heading">
            <h2>What would you like to do?</h2>
            <p>Log work hours, inspect monthly calendar records, or track approval history.</p>
          </div>
          <div className="timesheets__landing-chronometer-wrap">
            <TimesheetChronometer
              hoursLogged={recentHours}
              hoursTarget={80}
              periodLabel="Active Pay Period"
              onCrownClick={onBegin}
            />
          </div>
        </div>
        <div className="timesheets__action-grid">
          <ActionCard primary eyebrow="Enter hours" title="Enter Timesheet" description="Choose a date range, enter daily hours, review, and submit." actionLabel="Start entry" onClick={onBegin} />
          <ActionCard eyebrow="Overview" title="View Hours" description="See your recorded hours for the month with approval status." actionLabel="Explore hours" onClick={onViewHours} />
          <ActionCard eyebrow="History" title="Submitted Timesheets" description="Review previous periods, totals, status, and manager routing." actionLabel="View history" onClick={() => setMode('history')} />
        </div>
      </section>
    );
  }
  if (mode === 'history') return <HistoryWorkspace history={history} draftRecord={draftRecord} payrollPeriod={payrollPeriod} onView={onView} onDownloadPdf={onDownloadPdf} onDownloadPeriodPdf={onDownloadPeriodPdf} onResubmit={onResubmit} />;
  if (mode === 'enter' || mode === 'entry') return <EntryWorkspace step={entryStep} setStep={setEntryStep} today={today} rangeStart={rangeStart} setRangeStart={setRangeStart} rangeEnd={rangeEnd} setRangeEnd={setRangeEnd} rangeDays={daysBetween(rangeStart, rangeEnd)} rangeHours={rangeHours} setRangeHours={setRangeHours} rangeDescriptions={rangeDescriptions} setRangeDescriptions={setRangeDescriptions} rangeEntryTypes={rangeEntryTypes} setRangeEntryTypes={setRangeEntryTypes} rangeEntries={rangeEntries} reviewTotal={reviewTotal} supportingDocuments={supportingDocuments} supportingDocument={supportingDocuments?.[0]} uploadingDocument={uploadingDocument} ocrProgress={ocrProgress} documentError={documentError} onImportDocument={onImportDocument} dateOrder={dateOrder} setDateOrder={setDateOrder} onUploadSupportingDocument={onUploadSupportingDocument} onRemoveSupportingDocument={onRemoveSupportingDocument} saving={saving} role={role} onBack={onBack} onContinue={onContinue} onReview={onReview} onSave={onSave} onSubmit={onSubmit} reeditSubmitted={reeditSubmitted} changeReason={changeReason} setChangeReason={setChangeReason} submittedRecord={submittedRecord} onViewHistory={onViewHistory} onDownloadPdf={onDownloadPdf} rejectionFeedback={rejectionFeedback} managerDisplayName={managerDisplayName} />;
  return <HoursWorkspace loading={loading} records={records} today={today} />;
}

function HoursWorkspace({ loading, records, today }) {
  const [selectedMonth, setSelectedMonth] = useState(today.slice(0, 7));
  const [rangeStart, setRangeStart] = useState(null);
  const [rangeEnd, setRangeEnd] = useState(null);
  const [hoverDate, setHoverDate] = useState(null);
  const [datePickerOpen, setDatePickerOpen] = useState(false);

  const monthStart = `${selectedMonth}-01`;
  const monthEnd = endOfMonth(monthStart);
  const monthLabel = parseDateKey(monthStart).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const cells = useMemo(() => monthCells(selectedMonth), [selectedMonth]);

  const consolidated = useMemo(
    () => consolidatePeriodEntries(records, monthStart, monthEnd, true),
    [records, monthStart, monthEnd],
  );

  const displayStatus = consolidated.status === 'NO_HOURS' ? 'DRAFT' : consolidated.status;
  const activeRangeEnd = rangeEnd || hoverDate || rangeStart;
  const selectedRange = rangeStart && activeRangeEnd
    ? {
        start: rangeStart <= activeRangeEnd ? rangeStart : activeRangeEnd,
        end: rangeStart <= activeRangeEnd ? activeRangeEnd : rangeStart,
      }
    : null;
  const selectedRangeDays = selectedRange ? daysBetween(selectedRange.start, selectedRange.end) : [];
  const selectedRangeWorkedDays = selectedRangeDays.filter((date) => Number(consolidated.entryMap.get(date)?.hoursWorked || 0) > 0).length;
  const selectedRangeTotal = selectedRangeDays.reduce((sum, date) => sum + Number(consolidated.entryMap.get(date)?.hoursWorked || 0), 0);
  const selectedRangePendingTotal = selectedRangeDays.reduce((sum, date) => {
    const entry = consolidated.entryMap.get(date);
    return sum + (entry?.isPending ? Number(entry.hoursWorked || 0) : 0);
  }, 0);
  const selectedRangeApprovedTotal = selectedRangeDays.reduce((sum, date) => {
    const entry = consolidated.entryMap.get(date);
    return sum + (entry?.isApproved ? Number(entry.hoursWorked || 0) : 0);
  }, 0);

  function navigate(direction) {
    const next = addMonths(monthStart, direction);
    setSelectedMonth(next.slice(0, 7));
    clearSelectedRange();
  }

  function selectToday() {
    setSelectedMonth(today.slice(0, 7));
    setRangeStart(today);
    setRangeEnd(today);
    setHoverDate(today);
  }

  function clearSelectedRange() {
    setRangeStart(null);
    setRangeEnd(null);
    setHoverDate(null);
  }

  function selectRangeDate(date) {
    if (!date || date > today) return;
    if (!rangeStart || rangeEnd) {
      setRangeStart(date);
      setRangeEnd(null);
      setHoverDate(date);
      return;
    }
    setRangeEnd(date);
    setHoverDate(date);
  }

  function onDatePickerSelect(date) {
    selectRangeDate(date);
  }

  return <div className="timesheets__hours-workspace">
    <section className="timesheets__overview-panel" aria-label="Hours overview">
      <div className="timesheets__section-heading">
        <div><span>VIEW HOURS</span><h2>Hours overview</h2><p>Your recorded hours for the month, with approval status shown in place.</p></div>
        <div className="timesheets__date-picker-shell">
          <Button
            type="button"
            variant="secondary"
            className="timesheets__date-picker-toggle"
            aria-expanded={datePickerOpen}
            onClick={() => setDatePickerOpen((open) => !open)}
          >
            <span aria-hidden="true">📅</span>
            Select date range
          </Button>
          {datePickerOpen && (
            <div className="timesheets__date-picker-popover" role="dialog" aria-label="Select date range">
              <div className="timesheets__date-picker-header">
                <button type="button" aria-label="Previous month" onClick={() => navigate(-1)}>←</button>
                <strong>{monthLabel}</strong>
                <button type="button" aria-label="Next month" onClick={() => navigate(1)}>→</button>
              </div>
              <div className="timesheets__date-picker-weekdays">
                {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((day) => <span key={day}>{day}</span>)}
              </div>
              <div className="timesheets__date-picker-grid">
                {cells.map((cell) => {
                  const entry = consolidated.entryMap.get(cell.date);
                  const hours = Number(entry?.hoursWorked || 0);
                  const isSelectable = cell.currentMonth && cell.date <= today;
                  const isSelected = selectedRange && cell.date >= selectedRange.start && cell.date <= selectedRange.end;
                  const isRangeEdge = isSelected && (cell.date === selectedRange.start || cell.date === selectedRange.end);
                  const classes = [
                    cell.currentMonth ? '' : 'is-adjacent',
                    hours > 0 ? 'has-hours' : '',
                    entry?.isPending ? 'is-pending' : '',
                    entry?.isApproved ? 'is-approved' : '',
                    !isSelectable ? 'is-disabled' : '',
                    isSelected ? 'is-selected' : '',
                    isRangeEdge ? 'is-edge' : '',
                  ].filter(Boolean).join(' ');
                  return (
                    <button
                      key={cell.date}
                      type="button"
                      className={classes}
                      disabled={!isSelectable}
                      onClick={() => onDatePickerSelect(cell.date)}
                      onMouseEnter={() => {
                        if (isSelectable && rangeStart && !rangeEnd) setHoverDate(cell.date);
                      }}
                      onFocus={() => {
                        if (isSelectable && rangeStart && !rangeEnd) setHoverDate(cell.date);
                      }}
                      aria-label={`${formatDate(cell.date)}${hours ? ` ${formatHours(hours)}` : ' no hours recorded'}${isSelected ? ', selected' : ''}`}
                    >
                      <span>{parseDateKey(cell.date).getDate()}</span>
                    </button>
                  );
                })}
              </div>
              <div className="timesheets__date-picker-footer">
                <button type="button" onClick={clearSelectedRange}>Clear</button>
                <button type="button" onClick={selectToday}>Today</button>
                <button type="button" className="is-primary" onClick={() => setDatePickerOpen(false)}>Apply</button>
              </div>
            </div>
          )}
        </div>
        {loading && <span className="timesheets__loading" role="status">Loading...</span>}
      </div>
      <div className="timesheets__summary">
        <article><span>Selected month</span><strong>{monthLabel}</strong><small>{consolidated.workedDays} worked {consolidated.workedDays === 1 ? 'day' : 'days'}</small></article>
        <article><span>Total hours</span><strong>{formatHours(consolidated.totalHours)}</strong><small>Recorded &amp; pending in place</small></article>
        <article><span>Status</span><div><StatusTag status={displayStatus} tone={toneForStatus(displayStatus)} /></div><small>Read-only overview</small></article>
      </div>
      <div className="timesheets__range-summary" aria-live="polite">
        <div>
          <span>Selected date range</span>
          <strong>
            {selectedRange
              ? `${formatDate(selectedRange.start, { month: 'short', day: 'numeric' })} - ${formatDate(selectedRange.end, { month: 'short', day: 'numeric', year: 'numeric' })}`
              : 'Click a day, then hover and click another day'}
          </strong>
          <small>{selectedRange ? `${selectedRangeWorkedDays} worked ${selectedRangeWorkedDays === 1 ? 'day' : 'days'} across ${selectedRangeDays.length} selected ${selectedRangeDays.length === 1 ? 'day' : 'days'}` : 'Use the calendar below to calculate hours for particular days.'}</small>
        </div>
        <div className="timesheets__range-hour-breakdown">
          <article>
            <span>Submitted / Pending</span>
            <strong>{formatHours(selectedRangePendingTotal)}</strong>
          </article>
          <article>
            <span>Approved</span>
            <strong>{formatHours(selectedRangeApprovedTotal)}</strong>
          </article>
          <article>
            <span>Total hours</span>
            <strong>{formatHours(selectedRangeTotal)}</strong>
          </article>
          <small>{rangeStart && !rangeEnd ? 'Hover over a date to preview the total.' : 'Calculated from recorded daily hours.'}</small>
        </div>
        {selectedRange && (
          <Button type="button" variant="secondary" onClick={clearSelectedRange}>
            Clear range
          </Button>
        )}
      </div>
      <div className="timesheets__hours-navigation" aria-label="Hours period navigation"><button type="button" aria-label="Previous period" onClick={() => navigate(-1)}>←</button><strong>{monthLabel}</strong><button type="button" aria-label="Next period" onClick={() => navigate(1)}>→</button></div>
      <PeriodStatusCalendar
        cadence="MONTHLY"
        consolidated={consolidated}
        cells={cells}
        monthLabel={monthLabel}
        caption="Calculated daily hours (pending review & approved hours in place)"
        today={today}
        selectedRange={selectedRange}
        rangeStart={rangeStart}
        onHoverDate={(date) => {
          if (rangeStart && !rangeEnd) setHoverDate(date);
        }}
        onSelectDate={selectRangeDate}
      />
    </section>
  </div>;
}

function PeriodStatusCalendar({ cadence, consolidated, cells, monthLabel, caption, today, selectedRange, rangeStart, onHoverDate, onSelectDate }) {
  if (cadence === 'MONTHLY') {
    return (
      <div className="timesheets__history-calendar">
        <div className="timesheets__calendar">
          <div className="timesheets__calendar-heading">
            <strong>{monthLabel}</strong>
            <span>{caption}</span>
          </div>
          <div className="timesheets__calendar-weekdays">
            {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => <span key={d}>{d}</span>)}
          </div>
          <div className="timesheets__calendar-grid">
            {cells.map((cell) => {
              const entry = consolidated.entryMap.get(cell.date);
              const hours = entry?.hoursWorked || 0;
              const isWorked = hours > 0;
              const isPending = entry?.isPending;
              const isApproved = entry?.isApproved;
              const isSelectable = cell.currentMonth && (!today || cell.date <= today);
              const isSelected = selectedRange && cell.date >= selectedRange.start && cell.date <= selectedRange.end;
              const isRangeEdge = isSelected && (cell.date === selectedRange.start || cell.date === selectedRange.end);

              const cellClasses = [
                'timesheets__history-cell',
                'timesheets__history-cell--selectable',
                isWorked ? 'is-worked' : '',
                isPending ? 'is-pending' : '',
                isApproved ? 'is-approved' : '',
                cell.currentMonth ? '' : 'is-adjacent',
                !isSelectable ? 'is-disabled' : '',
                isSelected ? 'is-range-selected' : '',
                isRangeEdge ? 'is-range-edge' : '',
                rangeStart === cell.date && !selectedRange?.end ? 'is-range-start' : '',
              ].filter(Boolean).join(' ');

              return (
                <button
                  key={cell.date}
                  type="button"
                  className={cellClasses}
                  disabled={!isSelectable}
                  onClick={() => onSelectDate?.(cell.date)}
                  onMouseEnter={() => isSelectable && onHoverDate?.(cell.date)}
                  onFocus={() => isSelectable && onHoverDate?.(cell.date)}
                  aria-label={`${formatDate(cell.date)} ${isWorked ? formatHours(hours) : 'no hours recorded'}${isSelected ? ', selected' : ''}`}
                >
                  <div className="timesheets__history-cell-header">
                    <small>{parseDateKey(cell.date).getDate()}</small>
                    {isPending && (
                      <span className="timesheets__history-cell-badge timesheets__history-cell-badge--pending">Pending</span>
                    )}
                    {isApproved && (
                      <span className="timesheets__history-cell-badge timesheets__history-cell-badge--approved">Approved</span>
                    )}
                  </div>
                  {isWorked ? (
                    <div className="timesheets__history-cell-hours">{formatHours(hours)}</div>
                  ) : (
                    <span className="timesheets__history-cell-empty">—</span>
                  )}
                </button>
              );
            })}
          </div>
          <div className="timesheets__calendar-legend">
            <span><i className="is-approved" /> Approved</span>
            <span><i className="is-pending" /> Waiting for approval</span>
            <span><i className="is-empty" /> No hours recorded</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="timesheets__history-calendar">
      <div className="timesheets__week-overview">
        {consolidated.entries.map((entry) => {
          const isWorked = entry.hoursWorked > 0;
          const isPending = entry.isPending;
          const isApproved = entry.isApproved;

          const cardClasses = [
            'timesheets__history-cell',
            isWorked ? 'is-worked' : '',
            isPending ? 'is-pending' : '',
            isApproved ? 'is-approved' : '',
          ].filter(Boolean).join(' ');

          return (
            <div key={entry.workDate} className={cardClasses}>
              <div className="timesheets__history-cell-header">
                <span>{entry.dayOfWeek}</span>
                {isPending && (
                  <span className="timesheets__history-cell-badge timesheets__history-cell-badge--pending">Pending</span>
                )}
                {isApproved && (
                  <span className="timesheets__history-cell-badge timesheets__history-cell-badge--approved">Approved</span>
                )}
              </div>
              <strong>{formatDate(entry.workDate, { month: 'short', day: 'numeric' })}</strong>
              <b>{isWorked ? formatHours(entry.hoursWorked) : 'No hours'}</b>
              {entry.description && <small>{entry.description}</small>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function EntryWorkspace({ step, setStep, today, rangeStart, setRangeStart, rangeEnd, setRangeEnd, rangeDays, rangeHours, setRangeHours, rangeDescriptions, setRangeDescriptions, rangeEntryTypes, setRangeEntryTypes, rangeEntries, reviewTotal, supportingDocuments, supportingDocument, uploadingDocument, ocrProgress, documentError, onImportDocument, dateOrder, setDateOrder, onUploadSupportingDocument, onRemoveSupportingDocument, saving, onBack, onContinue, onReview, onSave, onSubmit, reeditSubmitted, changeReason, setChangeReason, submittedRecord, onViewHistory, onDownloadPdf, rejectionFeedback, managerDisplayName }) {
  const currentTotalHours = useMemo(() => {
    return (rangeDays || []).reduce((acc, date) => {
      const val = parseFloat(rangeHours[date]);
      return acc + (isNaN(val) ? 0 : val);
    }, 0);
  }, [rangeDays, rangeHours]);

  const standardWeeklyTarget = useMemo(() => {
    const weekdayCount = (rangeDays || []).filter((date) => {
      const day = parseDateKey(date).getDay();
      return day !== 0 && day !== 6;
    }).length;
    return Math.max(40, (weekdayCount || 5) * 8);
  }, [rangeDays]);

  const regularHours = Math.min(currentTotalHours, standardWeeklyTarget);
  const overtimeHours = Math.max(0, currentTotalHours - standardWeeklyTarget);
  const capacityScore = standardWeeklyTarget > 0 ? Math.min(100, Math.round((currentTotalHours / standardWeeklyTarget) * 100)) : 0;
  const maxScale = Math.max(standardWeeklyTarget * 1.5, currentTotalHours + 10, 60);

  const handlePreset = () => {
    setRangeHours((prev) => {
      const next = { ...prev };
      (rangeDays || []).forEach((date) => {
        if (date > today) return; // Future days stay locked
        const dayOfWeek = parseDateKey(date).getDay();
        next[date] = (dayOfWeek === 0 || dayOfWeek === 6) ? '0' : '8';
      });
      return next;
    });
  };
  if (step === 'source') return (
    <section className="timesheets__flow-panel timesheets__source" aria-label="Choose timesheet entry method">
      <div className="timesheets__flow-header">
        <div>
          <span className="timesheets__flow-kicker">ENTER TIMESHEET</span>
          <h2>Upload your timesheet or enter it manually</h2>
          <p>Choose an entry method: upload a document to prefill dates and daily hours, then check and correct them before submitting.</p>
        </div>
      </div>

      <div className="timesheets__entry-options-grid">
        <div className="timesheets__entry-card timesheets__entry-card--upload">
          <div className="timesheets__entry-card-badge">Automated • OCR Supported</div>
          <div className="timesheets__entry-card-title">
            <span className="timesheets__entry-card-icon" aria-hidden="true">📄</span>
            <div>
              <h3>Upload Timesheet Document</h3>
              <p>Upload a PDF, CSV, Excel, TXT, or image (PNG, JPG, WEBP). Dates and hours are automatically extracted and calculated.</p>
            </div>
          </div>

          <div className="timesheets__upload-zone">
            <label htmlFor="timesheet-date-order">Document date format</label>
            <select id="timesheet-date-order" value={dateOrder} disabled={uploadingDocument} onChange={(event) => setDateOrder(event.target.value)}>
              <option value="MDY">MM/DD/YYYY (Month / Day / Year)</option>
              <option value="DMY">DD/MM/YYYY (Day / Month / Year)</option>
            </select>
            <FileUpload
              name="timesheetImport"
              label="Timesheet document"
              accept=".pdf,.xlsx,.xls,.docx,.csv,.tsv,.txt,.png,.jpg,.jpeg,.webp"
              disabled={uploadingDocument}
              multiple
              helperText={uploadingDocument ? (ocrProgress || 'Uploading and extracting...') : 'Up to 5 MB each. Select multiple files to combine their hours into one timesheet. PDF, Excel, Word, CSV or text. Scanned PDFs and images are read with OCR; verify the extracted values.'}
              onUpload={(event) => onImportDocument(event.target.files)}
            />
            {ocrProgress && (
              <div className="timesheets__ocr-status" role="status">
                <span className="timesheets__ocr-spinner" aria-hidden="true" />
                <span>{ocrProgress}</span>
              </div>
            )}
            {documentError && <p role="alert" className="timesheets__field-error">{documentError}</p>}
          </div>
        </div>

        <div className="timesheets__entry-card timesheets__entry-card--manual">
          <div className="timesheets__entry-card-badge">Manual Entry</div>
          <div className="timesheets__entry-card-title">
            <span className="timesheets__entry-card-icon" aria-hidden="true">✏️</span>
            <div>
              <h3>Enter Hours Manually</h3>
              <p>Pick your custom date range and input daily work hours day-by-day. A supporting document is required before submitting.</p>
            </div>
          </div>
          <div className="timesheets__manual-actions">
            <Button type="button" variant="primary" disabled={uploadingDocument} onClick={() => setStep('dates')}>
              Enter manually
            </Button>
          </div>
        </div>

        <div className="timesheets__entry-card timesheets__entry-card--express">
          <div className="timesheets__entry-card-badge">⚡ 1-Tap Express</div>
          <div className="timesheets__entry-card-title">
            <span className="timesheets__entry-card-icon" aria-hidden="true">⚡</span>
            <div>
              <h3>Standard Week (40h)</h3>
              <p>Auto-populate Monday–Friday 8.00h (40.00h total) for the active work week and skip directly to review.</p>
            </div>
          </div>
          <div className="timesheets__manual-actions">
            <Button
              type="button"
              variant="primary"
              disabled={uploadingDocument}
              className="timesheets__btn-express-fill"
              onClick={() => {
                const wk = currentWeek();
                setRangeStart(wk.start);
                setRangeEnd(wk.end);
                handlePreset();
                setStep('hours');
              }}
            >
              ⚡ 1-Tap Fill (40h)
            </Button>
          </div>
        </div>
      </div>

      <div className="timesheets__flow-actions">
        <Button type="button" variant="secondary" disabled={uploadingDocument} onClick={onBack}>Cancel</Button>
        <Button type="button" variant="primary" disabled={uploadingDocument} onClick={() => setStep('dates')}>Skip to manual entry</Button>
      </div>
    </section>
  );

  if (step === 'success') {
    return (
      <section className="timesheets__flow-panel timesheets__flow-panel--success" role="status">
        <span className="timesheets__success-mark">✓</span>
        <span className="timesheets__flow-kicker">SUBMITTED</span>
        <h2>Timesheet submitted</h2>
        <p>{formatPeriod(rangeStart, rangeEnd)} was sent to your reporting manager ({managerDisplayName}).</p>
        <strong>{formatHours(reviewTotal)} total</strong>
        <div>
          <Button type="button" variant="secondary" onClick={onBack}>
            Back to overview
          </Button>
          <Button type="button" variant="secondary" onClick={() => setStep('submitted_view')}>
            View submission
          </Button>
          <Button type="button" variant="primary" onClick={onViewHistory}>
            View submitted timesheets
          </Button>
        </div>
      </section>
    );
  }

  const isSubmittedView = step === 'submitted_view';

  return (
    <section className="timesheets__flow-panel" aria-label={isSubmittedView ? 'Submitted timesheet details' : 'Enter timesheet workflow'}>
      <div className="timesheets__flow-header">
        <div>
          <span className="timesheets__flow-kicker">
            {isSubmittedView ? 'SUBMITTED TIMESHEET' : 'ENTER TIMESHEET'}
          </span>
          <h2>
            {step === 'dates'
              ? 'Choose your date range'
              : step === 'hours'
              ? 'Add daily hours'
              : isSubmittedView
              ? 'Timesheet submission (Read-only)'
              : 'Review and submit'}
          </h2>
          <p>
            {step === 'dates'
              ? 'Start with the period you want to record.'
              : step === 'hours'
              ? 'Enter one total for each day. Descriptions are optional.'
              : isSubmittedView
              ? 'This timesheet has been submitted to your reporting manager and is pending review.'
              : 'Confirm the totals before submitting.'}
          </p>
        </div>
        <ol aria-label="Timesheet progress">
          <li className={isSubmittedView || step === 'hours' || step === 'review' ? 'complete' : 'active'}>
            1 <span>Dates</span>
          </li>
          <li className={isSubmittedView || step === 'review' ? 'complete' : step === 'hours' ? 'active' : ''}>
            2 <span>Hours</span>
          </li>
          <li className={isSubmittedView ? 'complete' : step === 'review' ? 'active' : ''}>
            3 <span>Review</span>
          </li>
        </ol>
      </div>

      {step === 'dates' && (
        <div className="timesheets__flow-content">
          {documentError && <p role="alert">{documentError}</p>}
          <div className="timesheets__range-fields">
            <DateRangePicker
              label="Timesheet period"
              fromDate={rangeStart}
              toDate={rangeEnd}
              maxDate={today}
              onChange={(start, end) => { setRangeStart(start); setRangeEnd(end); }}
            />
          </div>
          <div className="timesheets__period-callout">
            <strong>Selected period</strong>
            <span>{formatPeriod(rangeStart, rangeEnd)}</span>
            <small>Maximum range: 31 days. Upcoming days stay locked until they arrive.</small>
          </div>
          <div className="timesheets__flow-actions">
            <Button type="button" variant="secondary" onClick={onBack}>Cancel</Button>
            <Button type="button" variant="primary" onClick={onContinue}>Continue to hours</Button>
          </div>
        </div>
      )}

      {step === 'hours' && (
        <div className="timesheets__flow-content timesheets__flow-content--cockpit">
          <div className="timesheets__sandbox-hero">
            <div className="timesheets__sandbox-info">
              <div className="timesheets__sandbox-badge-row">
                <span className="timesheets__sim-badge">LIVE COMPLIANCE SANDBOX</span>
                <span className="timesheets__sim-period-badge">{formatPeriod(rangeStart, rangeEnd)}</span>
                <button type="button" className="timesheets__change-dates-btn" onClick={() => setStep('dates')}>
                  Change dates
                </button>
              </div>
              <p className="timesheets__sandbox-sub">
                Drag daily hours or input project tasks below to test real-time payroll calculations, overtime compliance detection, and instant approval readiness.
              </p>
            </div>

            <div className="timesheets__sim-presets" role="group" aria-label="Timesheet quick presets">
              <button type="button" className="timesheets__preset-btn" onClick={handlePreset}>
                Standard {standardWeeklyTarget}h
              </button>
            </div>
          </div>
          <div className="timesheets__sim-metrics-bar" aria-label="Timesheet live telemetry">
            <div className="timesheets__sim-metric">
              <span className="timesheets__sim-metric-label">TOTAL HOURS</span>
              <span className="timesheets__sim-metric-value">
                {currentTotalHours.toFixed(1)}<small>h</small>
              </span>
            </div>
            <div className="timesheets__sim-metric">
              <span className="timesheets__sim-metric-label">REGULAR</span>
              <span className="timesheets__sim-metric-value timesheets__sim-metric-value--cyan">
                {regularHours.toFixed(1)}<small>h</small>
              </span>
            </div>
            <div className="timesheets__sim-metric">
              <span className="timesheets__sim-metric-label">OVERTIME</span>
              <span className={`timesheets__sim-metric-value ${overtimeHours > 0 ? 'timesheets__sim-metric-value--gold' : ''}`}>
                {overtimeHours.toFixed(1)}<small>h</small>
              </span>
            </div>
            <div className="timesheets__sim-metric">
              <span className="timesheets__sim-metric-label">CAPACITY SCORE</span>
              <span className="timesheets__sim-metric-value timesheets__sim-metric-value--emerald">
                {capacityScore}%
              </span>
            </div>
          </div>

          {/* Progress capacity bar */}
          <div className="timesheets__sim-bar-wrap" aria-hidden="true">
            <div
              className="timesheets__sim-bar-fill"
              style={{
                width: `${Math.min(100, (currentTotalHours / maxScale) * 100)}%`,
                background: overtimeHours > 0
                  ? 'linear-gradient(90deg, #10b981, #00ff87, #ffb800)'
                  : 'linear-gradient(90deg, #0b624f, #00ff87)',
              }}
            />
            <div
              className="timesheets__sim-bar-marker"
              style={{ left: `${Math.min(92, Math.max(8, (standardWeeklyTarget / maxScale) * 100))}%` }}
            >
              <span>{standardWeeklyTarget}h Threshold</span>
            </div>
          </div>

          {(supportingDocuments?.length > 0 || supportingDocument) && (
            <div className="timesheets__extracted-banner" role="region" aria-label="Extracted hours summary">
              <span className="timesheets__extracted-banner-icon" aria-hidden="true">⚡</span>
              <div>
                <strong>
                  {supportingDocuments.length > 1
                    ? `Hours pre-filled from ${supportingDocuments.length} documents`
                    : `Hours pre-filled from ${supportingDocuments[0].fileName}`}
                </strong>
                <p>Calculated hours are loaded below. You can edit any day&apos;s hours or description before submitting for manager approval.</p>
              </div>
            </div>
          )}
          {rejectionFeedback && (
            <div className="timesheets__rejection-hours-notice" role="alert">
              <span className="timesheets__rejection-hours-icon" aria-hidden="true">❌</span>
              <div>
                <strong>Reviewer feedback to address:</strong>
                <p>&ldquo;{rejectionFeedback}&rdquo;</p>
                <small>Please adjust the relevant hours below and enter your reason for change before submitting.</small>
              </div>
            </div>
          )}
          {reeditSubmitted && (
            <div className="timesheets__reedit-banner" role="alert">
              <span className="timesheets__reedit-banner-icon" aria-hidden="true">⚠️</span>
              <div>
                <strong>Re-editing submitted timesheet</strong>
                <p>Existing hours for this period are loaded below for you to update or replace. A reason for change is required before submitting.</p>
              </div>
            </div>
          )}
          {reeditSubmitted && (
            <div className="timesheets__reason-field">
              <label htmlFor="changeReason">Reason for change <span className="required-star" aria-hidden="true">*</span></label>
              <textarea id="changeReason" name="changeReason" rows={3} placeholder="Describe the reason for correcting this timesheet (e.g., Schedule change approved by manager, corrected Friday overtime)..." value={changeReason} onChange={(event) => setChangeReason(event.target.value)} required />
            </div>
          )}

          <div className="timesheets__daily-hours timesheets__daily-hours--grid">
            {rangeDays.map((date) => {
              const isFuture = date > today;
              const rawVal = rangeHours[date];
              const parsedVal = parseFloat(rawVal);
              const numVal = isNaN(parsedVal) ? 0 : parsedVal;
              return (
                <div
                  key={date}
                  className={`timesheets__daily-row timesheets__day-card${isFuture ? ' timesheets__daily-row--locked timesheets__day-card--locked' : ''}${numVal > 8 ? ' timesheets__day-card--ot' : ''}`}
                >
                  <div className="timesheets__day-card-header">
                    <div>
                      <strong>{formatDate(date, { weekday: 'short', month: 'short', day: 'numeric' })}</strong>
                      <small>{isFuture ? 'Future — read-only' : date === today ? 'Today' : ''}</small>
                    </div>
                    <span className="timesheets__day-val">{isFuture ? '—' : `${numVal.toFixed(1)}h`}</span>
                  </div>

                  <div className="timesheets__day-slider-wrap">
                    <input
                      type="range"
                      min="0"
                      max="16"
                      step="0.5"
                      disabled={isFuture}
                      value={isFuture ? 0 : Math.min(16, Math.max(0, numVal))}
                      onChange={(e) => setRangeHours((current) => ({ ...current, [date]: e.target.value }))}
                      className="timesheets__day-slider"
                      aria-label={`Hours slider for ${formatDate(date, { weekday: 'short', month: 'short', day: 'numeric' })}`}
                    />
                  </div>

                  <div className="timesheets__day-card-tag-row">
                    <span className={`timesheets__day-tag timesheets__day-tag--${isFuture ? 'future' : numVal === 0 ? 'off' : numVal <= 8 ? 'standard' : 'extended'}`}>
                      {isFuture ? 'Future' : numVal === 0 ? 'Off' : numVal <= 8 ? 'Standard' : 'Extended (+OT)'}
                    </span>
                  </div>

                  <div className="timesheets__day-inputs">
                    <TextInput
                      name={`hours-${date}`}
                      label="Hours"
                      type="number"
                      min="0"
                      max="24"
                      step="0.25"
                      disabled={isFuture}
                      value={isFuture ? '' : rangeHours[date] ?? ''}
                      onChange={(event) => setRangeHours((current) => ({ ...current, [date]: event.target.value }))}
                    />
                    <Dropdown
                      name={`entry-type-${date}`}
                      label="Entry type"
                      options={TIMESHEET_ENTRY_TYPE_OPTIONS}
                      disabled={isFuture}
                      value={isFuture ? 'WORK' : normalizeEntryType(rangeEntryTypes[date])}
                      onChange={(event) => setRangeEntryTypes((current) => ({ ...current, [date]: event.target.value }))}
                    />
                    <TextInput
                      name={`description-${date}`}
                      label="Description (optional)"
                      placeholder="Task / project details..."
                      disabled={isFuture}
                      value={isFuture ? '' : rangeDescriptions[date] || ''}
                      onChange={(event) => setRangeDescriptions((current) => ({ ...current, [date]: event.target.value }))}
                    />
                  </div>
                </div>
              );
            })}
          </div>

          <div className="timesheets__sim-compliance-pill">
            <span className="timesheets__sim-compliance-dot" aria-hidden="true" />
            <span>
              {overtimeHours > 0
                ? `Compliance: Overtime policy auto-flagged (+${overtimeHours.toFixed(1)}h 1.5x)`
                : 'Compliance: Within FLSA bi-weekly standard tolerance'}
            </span>
          </div>

          <SupportingDocumentList
            documents={supportingDocuments?.length ? supportingDocuments : supportingDocument ? [supportingDocument] : []}
            onRemove={onRemoveSupportingDocument}
          />
          {documentError && <p role="alert">{documentError}</p>}
          <div className="timesheets__flow-actions">
            <Button type="button" variant="secondary" onClick={() => setStep('dates')}>Back</Button>
            <Button type="button" variant="primary" onClick={onReview}>Review timesheet</Button>
          </div>
        </div>
      )}

      {step === 'review' && (
        <div className="timesheets__flow-content">
          <div className="timesheets__review-summary">
            <article>
              <span>Date range</span>
              <strong>{formatPeriod(rangeStart, rangeEnd)}</strong>
            </article>
            <article>
              <span>Total hours</span>
              <strong>{formatHours(reviewTotal)}</strong>
            </article>
            <article>
              <span>Reporting manager</span>
              <strong>{managerDisplayName}</strong>
            </article>
            {reeditSubmitted && changeReason && (
              <article>
                <span>Reason for change</span>
                <strong>{changeReason}</strong>
              </article>
            )}
          </div>
          <div className="timesheets__review-table">
            <table aria-label="Timesheet review">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Day</th>
                  <th>Hours</th>
                  <th>Type</th>
                  <th>Description</th>
                </tr>
              </thead>
              <tbody>
                {rangeEntries.map((entry) => (
                  <tr key={entry.workDate}>
                    <td>{formatWorkDate(entry.workDate)}</td>
                    <td>{entry.dayOfWeek}</td>
                    <td>{formatHours(entry.hoursWorked)}</td>
                    <td>{entryTypeLabel(entry.entryType)}</td>
                    <td>{entry.description || 'No description'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="timesheets__review-footer">
            {reeditSubmitted && (
              <div className="timesheets__recommendation-callout">
                <strong>📄 Approved changed timesheet mandatory</strong>
                <p>When modifying previously submitted hours, uploading your approved changed timesheet (manager written or email confirmation) is mandatory before submitting for approval.</p>
              </div>
            )}
            <SupportingDocumentList documents={supportingDocuments} onRemove={onRemoveSupportingDocument} />
            <FileUpload
              name="supportingDocument"
              label={reeditSubmitted ? 'Supporting document (Approved changed timesheet mandatory)' : 'Supporting document (mandatory)'}
              helperText={uploadingDocument ? 'Uploading document...' : supportingDocuments.length ? 'Stored securely. It will be attached when you submit. You can add more.' : reeditSubmitted ? 'Mandatory: Upload manager-approved amended timesheet (PDF or image up to 5 MB).' : 'PDF, Excel, Word, CSV, text or image up to 5 MB. A supporting document is mandatory before submitting.'}
              multiple
              accept=".pdf,.xlsx,.xls,.docx,.csv,.tsv,.txt,.png,.jpg,.jpeg"
              disabled={uploadingDocument}
              onUpload={(event) => onUploadSupportingDocument(event.target.files)}
            />
            {documentError && <p role="alert" className="timesheets__field-error">{documentError}</p>}
            <div className="timesheets__flow-actions">
              <Button type="button" variant="secondary" onClick={() => setStep('hours')}>Back to hours</Button>
              <Button type="button" variant="secondary" disabled={saving || uploadingDocument} onClick={onSave}>{saving ? 'Saving...' : 'Save draft'}</Button>
              <Button type="button" variant="primary" disabled={saving || uploadingDocument} onClick={onSubmit}>{saving ? 'Submitting...' : 'Submit timesheet'}</Button>
            </div>
          </div>
        </div>
      )}

      {isSubmittedView && (
        <div className="timesheets__flow-content">
          <div className="timesheets__submitted-callout" role="status">
            <span className="timesheets__success-mark timesheets__success-mark--small">✓</span>
            <div>
              <strong>Timesheet Submitted & Pending Review</strong>
              <p>
                Your timesheet for {formatPeriod(rangeStart, rangeEnd)} has been sent to your reporting manager ({managerDisplayName}). This record is read-only.
              </p>
            </div>
          </div>

          <div className="timesheets__review-summary">
            <article>
              <span>Date range</span>
              <strong>{formatPeriod(rangeStart, rangeEnd)}</strong>
            </article>
            <article>
              <span>Total hours</span>
              <strong>{formatHours(reviewTotal)}</strong>
            </article>
            <article>
              <span>Status</span>
              <div>
                <StatusTag status="SUBMITTED" tone="warning" />
              </div>
            </article>
            <article>
              <span>Reporting manager</span>
              <strong>{managerDisplayName}</strong>
            </article>
            {(changeReason || submittedRecord?.changeReason) && (
              <article>
                <span>Reason for change</span>
                <strong>{changeReason || submittedRecord?.changeReason}</strong>
              </article>
            )}
          </div>

          <div className="timesheets__review-table">
            <table aria-label="Submitted timesheet review">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Day</th>
                  <th>Hours</th>
                  <th>Type</th>
                  <th>Description</th>
                </tr>
              </thead>
              <tbody>
                {rangeEntries.map((entry) => (
                  <tr key={entry.workDate}>
                    <td>{formatWorkDate(entry.workDate)}</td>
                    <td>{entry.dayOfWeek}</td>
                    <td>{formatHours(entry.hoursWorked)}</td>
                    <td>{entryTypeLabel(entry.entryType)}</td>
                    <td>{entry.description || 'No description'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="timesheets__review-footer">
            {supportingDocuments.length > 0 ? (
              <div className="timesheets__attachment-display">
                <span>Supporting document{supportingDocuments.length > 1 ? 's' : ''}:</span>
                {supportingDocuments.map((doc) => (
                  <strong key={doc.id}>📎 {doc.fileName}</strong>
                ))}
              </div>
            ) : (
              <p className="timesheets__attachment-none">No supporting document attached.</p>
            )}

            <div className="timesheets__flow-actions">
              <Button type="button" variant="secondary" onClick={onBack}>
                Back to overview
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => onDownloadPdf?.(submittedRecord || { id: null, periodStartDate: rangeStart, periodEndDate: rangeEnd })}
              >
                Download PDF
              </Button>
              <Button type="button" variant="primary" onClick={onViewHistory}>
                View submitted timesheets
              </Button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function HistoryWorkspace({ history, draftRecord, payrollPeriod, onView, onDownloadPeriodPdf, onResubmit }) {
  const cadence = (payrollPeriod || 'MONTHLY').toUpperCase();
  const today = toDateKey(new Date());
  const sortedHistory = useMemo(() => sortHistoryNewestFirst(history), [history]);

  const [dismissedRejectionIds, setDismissedRejectionIds] = useState(() => new Set());

  const rejectedRecord = useMemo(() => {
    const rejectedList = sortedHistory.filter((r) => r.status === 'REJECTED' && !dismissedRejectionIds.has(r.id));
    for (const rej of rejectedList) {
      const rejTime = new Date(rej.updatedAt || rej.submittedAt || rej.createdAt || 0).getTime();
      const hasNewerActive = sortedHistory.some((r) => {
        if (r.id === rej.id || r.status === 'DRAFT' || r.status === 'REJECTED') return false;
        const rTime = new Date(r.updatedAt || r.submittedAt || r.createdAt || 0).getTime();
        const overlaps = !(r.periodStartDate > rej.periodEndDate || r.periodEndDate < rej.periodStartDate);
        return overlaps && rTime > rejTime;
      });
      if (!hasNewerActive) {
        return rej;
      }
    }
    return null;
  }, [sortedHistory, dismissedRejectionIds]);

  const defaultMonth = useMemo(() => {
    if (sortedHistory.length && sortedHistory[0].periodStartDate) {
      return sortedHistory[0].periodStartDate.slice(0, 7);
    }
    return today.slice(0, 7);
  }, [sortedHistory, today]);

  const [selectedMonth, setSelectedMonth] = useState(defaultMonth);
  const [selectedWeeklyIndex, setSelectedWeeklyIndex] = useState(0);

  const monthStart = `${selectedMonth}-01`;
  const monthEnd = endOfMonth(monthStart);
  const daysInMonth = parseDateKey(monthEnd).getDate();

  const weeklyPeriods = useMemo(() => {
    const periods = [
      { label: '1st – 7th', start: `${selectedMonth}-01`, end: `${selectedMonth}-07` },
      { label: '8th – 14th', start: `${selectedMonth}-08`, end: `${selectedMonth}-14` },
      { label: '15th – 21st', start: `${selectedMonth}-15`, end: `${selectedMonth}-21` },
      { label: '22nd – 28th', start: `${selectedMonth}-22`, end: `${selectedMonth}-28` },
    ];
    if (daysInMonth > 28) {
      const endDayStr = String(daysInMonth).padStart(2, '0');
      periods.push({
        label: `29th – ${daysInMonth}th`,
        start: `${selectedMonth}-29`,
        end: `${selectedMonth}-${endDayStr}`,
      });
    }
    return periods;
  }, [selectedMonth, daysInMonth]);

  const biweeklyPeriods = useMemo(() => {
    const endDayStr = String(daysInMonth).padStart(2, '0');
    return [
      { label: '1st – 15th', start: `${selectedMonth}-01`, end: `${selectedMonth}-15` },
      { label: `16th – ${daysInMonth}th`, start: `${selectedMonth}-16`, end: `${selectedMonth}-${endDayStr}` },
    ];
  }, [selectedMonth, daysInMonth]);

  const activePeriod = useMemo(() => {
    if (cadence === 'MONTHLY') {
      return {
        label: `${parseDateKey(monthStart).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })} (1st – ${daysInMonth}th)`,
        start: monthStart,
        end: monthEnd,
      };
    }
    if (cadence === 'BIWEEKLY') {
      const clampedIndex = Math.min(selectedWeeklyIndex, biweeklyPeriods.length - 1);
      const biweekly = biweeklyPeriods[clampedIndex] || biweeklyPeriods[0];
      return {
        label: `Period ${clampedIndex + 1} (${biweekly.label})`,
        start: biweekly.start,
        end: biweekly.end,
      };
    }
    const clampedIndex = Math.min(selectedWeeklyIndex, weeklyPeriods.length - 1);
    const weekly = weeklyPeriods[clampedIndex] || weeklyPeriods[0];
    return {
      label: `Week ${clampedIndex + 1} (${weekly.label})`,
      start: weekly.start,
      end: weekly.end,
    };
  }, [cadence, monthStart, monthEnd, selectedWeeklyIndex, weeklyPeriods, biweeklyPeriods, daysInMonth]);

  const consolidated = useMemo(() => {
    return consolidatePeriodEntries(history, activePeriod.start, activePeriod.end);
  }, [history, activePeriod.start, activePeriod.end]);

  const activePeriodRecord = useMemo(() => {
    const rep = sortedHistory[0] || draftRecord || {};
    return {
      ...consolidated,
      id: `period-${activePeriod.start}-${activePeriod.end}`,
      periodStartDate: activePeriod.start,
      periodEndDate: activePeriod.end,
      totalHours: consolidated.totalHours,
      status: consolidated.status === 'NO_HOURS' ? 'SUBMITTED' : consolidated.status,
      employeeId: rep.employeeId || 'Employee',
      employeeName: rep.employeeName || 'My Timesheet',
      employeeRole: rep.employeeRole || 'Employee',
      reportingManagerLoginId: consolidated.reportingManagerLoginId || rep.reportingManagerLoginId || 'Assigned manager',
      entries: (consolidated.entries || []).filter((e) => e.hoursWorked > 0),
    };
  }, [consolidated, activePeriod, sortedHistory, draftRecord]);

  const monthLabel = parseDateKey(monthStart).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

  function shiftMonth(direction) {
    const nextMonthDate = addMonths(monthStart, direction);
    setSelectedMonth(nextMonthDate.slice(0, 7));
    setSelectedWeeklyIndex(0);
  }

  return (
    <section className="timesheets__history-panel" aria-label="Submitted timesheets">
      <div className="timesheets__section-heading">
        <div>
          <span>HISTORY</span>
          <h2>Submitted timesheets</h2>
          <p>Pay period totals and submission records. See View Hours for the day-by-day calendar.</p>
        </div>
      </div>

      {rejectedRecord && (
        <div className="timesheets__rejected-banner" role="alert">
          <span className="timesheets__rejected-icon" aria-hidden="true">⚠️</span>
          <div className="timesheets__rejected-content">
            <div className="timesheets__rejected-header">
              <strong>Timesheet rejected by {rejectedRecord.reportingManagerLoginId || 'manager'}</strong>
              <span className="timesheets__rejected-badge">Action Required</span>
            </div>
            <p className="timesheets__rejected-reason">
              <strong>Reason:</strong> {rejectedRecord.reviewComment || 'Please revise your hours and resubmit.'}
            </p>
            <span className="timesheets__rejected-meta">
              Period: {formatPeriod(rejectedRecord.periodStartDate, rejectedRecord.periodEndDate)}
              {rejectedRecord.reviewedAt ? ` • Reviewed ${formatDateTime(rejectedRecord.reviewedAt)}` : ''}
            </span>
          </div>
          <div className="timesheets__rejected-actions">
            <Button type="button" variant="primary" onClick={() => onResubmit?.(rejectedRecord)}>
              Revise &amp; Resubmit
            </Button>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setDismissedRejectionIds((prev) => new Set(prev).add(rejectedRecord.id))}
            >
              Ignore
            </Button>
          </div>
        </div>
      )}

      <div className="timesheets__history-controls">
        <div className="timesheets__cadence-group">
          <span className="timesheets__cadence-label">Pay Period Cadence</span>
          <div className="timesheets__cadence-fixed">
            <span className="timesheets__cadence-badge">
              {cadence === 'WEEKLY'
                ? 'Weekly (1st–7th, 8th–14th...)'
                : cadence === 'BIWEEKLY'
                ? `Bi-weekly (1st–15th, 16th–${daysInMonth}th)`
                : `Monthly (1st–${daysInMonth}th)`}
            </span>
            <span className="timesheets__cadence-policy-tag">Organization Policy</span>
          </div>
        </div>

        <div className="timesheets__month-nav">
          <button type="button" className="timesheets__nav-btn" onClick={() => shiftMonth(-1)} aria-label="Previous month">
            ← Prev
          </button>
          <strong>{monthLabel}</strong>
          <button type="button" className="timesheets__nav-btn" onClick={() => shiftMonth(1)} aria-label="Next month">
            Next →
          </button>
        </div>
      </div>

      {cadence === 'WEEKLY' && (
        <div className="timesheets__weekly-pills" role="tablist" aria-label="Weekly intervals">
          {weeklyPeriods.map((wp, idx) => (
            <button
              key={wp.start}
              type="button"
              role="tab"
              aria-selected={selectedWeeklyIndex === idx}
              className={`timesheets__weekly-pill${selectedWeeklyIndex === idx ? ' active' : ''}`}
              onClick={() => setSelectedWeeklyIndex(idx)}
            >
              Week {idx + 1}: {wp.label}
            </button>
          ))}
        </div>
      )}

      {cadence === 'BIWEEKLY' && (
        <div className="timesheets__weekly-pills" role="tablist" aria-label="Bi-weekly intervals">
          {biweeklyPeriods.map((bp, idx) => (
            <button
              key={bp.start}
              type="button"
              role="tab"
              aria-selected={selectedWeeklyIndex === idx}
              className={`timesheets__weekly-pill${selectedWeeklyIndex === idx ? ' active' : ''}`}
              onClick={() => setSelectedWeeklyIndex(idx)}
            >
              Period {idx + 1}: {bp.label}
            </button>
          ))}
        </div>
      )}

      <div className="timesheets__history-summary">
        <div className="timesheets__chronometer-wrap">
          <TimesheetChronometer
            hoursLogged={consolidated.totalHours}
            hoursTarget={cadence === 'WEEKLY' ? 40 : cadence === 'BIWEEKLY' ? 80 : 160}
            periodLabel={activePeriod.label}
            size="small"
          />
        </div>
        <div className="timesheets__summary-metrics">
          <article>
            <span>Active Pay Period</span>
            <strong>{formatPeriod(activePeriod.start, activePeriod.end)}</strong>
            <small>{activePeriod.label}</small>
          </article>
          <article>
            <span>Calculated Total Hours</span>
            <strong>{formatHours(consolidated.totalHours)}</strong>
            <small>{consolidated.workedDays} worked {consolidated.workedDays === 1 ? 'day' : 'days'}</small>
          </article>
          <article>
            <span>Status</span>
            <div>
              <StatusTag
                status={consolidated.status === 'NO_HOURS' ? 'SUBMITTED' : consolidated.status}
                tone={toneForStatus(consolidated.status === 'NO_HOURS' ? 'SUBMITTED' : consolidated.status)}
              />
            </div>
            <small>Updated in place</small>
          </article>
          <article>
            <span>Reporting Manager</span>
            <strong>{consolidated.reportingManagerLoginId}</strong>
            <small>Timesheet approver</small>
          </article>
        </div>

        <div className="timesheets__summary-actions">
          <Button
            type="button"
            variant="secondary"
            onClick={() => onView(activePeriodRecord)}
          >
            View Period Details
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={() => onDownloadPeriodPdf?.(activePeriod.start, activePeriod.end)}
          >
            Download Period PDF
          </Button>
        </div>
      </div>

      <div className="timesheets__submissions-section">
        <div className="timesheets__section-heading" style={{ marginTop: '28px', marginBottom: '14px' }}>
          <div>
            <h3 style={{ margin: '0 0 4px', fontSize: '1.05rem', fontWeight: 700 }}>Submitted timesheet records</h3>
            <p style={{ margin: 0, fontSize: '0.85rem' }}>Submission audit and document downloads.</p>
          </div>
        </div>

        <Table
          className="timesheets__history-table"
          columns={[
            { key: 'period', header: 'Period', width: '18%' },
            { key: 'hours', header: 'Total hours', width: '12%' },
            { key: 'manager', header: 'Reporting manager', width: '17%' },
            { key: 'submittedAt', header: 'Submitted', width: '20%' },
            { key: 'status', header: 'Status', width: '13%' },
            { key: 'action', header: 'Action', width: '20%' },
          ]}
          rows={sortedHistory.map((record) => ({
            ...record,
            period: formatPeriod(record.periodStartDate, record.periodEndDate),
            hours: formatHours(record.totalHours),
            manager: record.reportingManagerName || record.reportingManagerLoginId || 'Assigned manager',
            submittedAt: formatDateTime(record.submittedAt),
          }))}
          renderCell={(column, row) =>
            column.key === 'status' ? (
              <div className="timesheets__status-col">
                <StatusTag status={row.status} tone={toneForStatus(row.status)} />
                {row.status === 'REJECTED' && row.reviewComment && (
                  <small className="timesheets__rejected-row-reason" title={row.reviewComment}>
                    Reason: {row.reviewComment}
                  </small>
                )}
              </div>
            ) : column.key === 'action' ? (
              <span className="timesheets__row-actions">
                {row.status === 'REJECTED' && (
                  <Button type="button" variant="primary" onClick={() => onResubmit?.(row)}>
                    Resubmit
                  </Button>
                )}
                <Button type="button" variant="secondary" onClick={() => onView(row)}>
                  View details
                </Button>
              </span>
            ) : (
              row[column.key]
            )
          }
          emptyMessage="No submitted timesheets yet."
        />
      </div>
    </section>
  );
}

function getInitials(name) {
  if (!name) return 'EMP';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function getPresetDates(preset, todayKey, payrollPeriod = 'MONTHLY') {
  const today = parseDateKey(todayKey);
  const yearMonth = todayKey.slice(0, 7);

  if (preset === 'CURRENT_MONTH') {
    const start = `${yearMonth}-01`;
    return { start, end: endOfMonth(start) };
  }
  if (preset === 'PREVIOUS_MONTH') {
    const prev = addMonths(`${yearMonth}-01`, -1);
    return { start: prev, end: endOfMonth(prev) };
  }
  if (preset === 'LAST_90_DAYS') {
    return { start: addDays(todayKey, -90), end: todayKey };
  }
  if (preset === 'CURRENT_PERIOD') {
    if (payrollPeriod === 'SEMI_MONTHLY') {
      const day = today.getDate();
      if (day <= 15) {
        return { start: `${yearMonth}-01`, end: `${yearMonth}-15` };
      } else {
        return { start: `${yearMonth}-16`, end: endOfMonth(todayKey) };
      }
    }
    const start = `${yearMonth}-01`;
    return { start, end: endOfMonth(start) };
  }
  if (preset === 'PREVIOUS_PERIOD') {
    if (payrollPeriod === 'SEMI_MONTHLY') {
      const day = today.getDate();
      if (day <= 15) {
        const prevMonth = addMonths(`${yearMonth}-01`, -1);
        return { start: `${prevMonth.slice(0, 7)}-16`, end: endOfMonth(prevMonth) };
      } else {
        return { start: `${yearMonth}-01`, end: `${yearMonth}-15` };
      }
    }
    const prev = addMonths(`${yearMonth}-01`, -1);
    return { start: prev, end: endOfMonth(prev) };
  }
  return { start: `${yearMonth}-01`, end: endOfMonth(todayKey) };
}

function computeBreakdowns(consolidatedList) {
  const dayRows = (consolidatedList || []).map((entry) => ({
    id: entry.workDate,
    workDate: entry.workDate,
    formattedDate: formatWorkDate(entry.workDate),
    dayOfWeek: entry.dayOfWeek || parseDateKey(entry.workDate).toLocaleDateString('en-US', { weekday: 'short' }),
    hours: entry.hoursWorked || 0,
    formattedHours: formatHours(entry.hoursWorked || 0),
    description: entry.description || (entry.hoursWorked > 0 ? 'Recorded hours' : '—'),
    status: entry.badge || (entry.hoursWorked > 0 ? 'Logged' : '—'),
    isPending: entry.isPending,
    isApproved: entry.isApproved,
  }));

  const weekMap = new Map();
  (consolidatedList || []).forEach((entry) => {
    const d = parseDateKey(entry.workDate);
    const day = d.getDay();
    const mondayOffset = day === 0 ? -6 : 1 - day;
    const monday = new Date(d);
    monday.setDate(d.getDate() + mondayOffset);
    const sunday = new Date(monday);
    sunday.setDate(monday.getDate() + 6);
    const wStart = toDateKey(monday);
    const wEnd = toDateKey(sunday);
    const key = `${wStart}_${wEnd}`;
    if (!weekMap.has(key)) {
      weekMap.set(key, {
        id: key,
        weekStart: wStart,
        weekEnd: wEnd,
        label: `${formatDate(wStart, { month: 'short', day: 'numeric' })} - ${formatDate(wEnd, { month: 'short', day: 'numeric', year: 'numeric' })}`,
        totalHours: 0,
        workingDays: 0,
        approvedHours: 0,
        pendingHours: 0,
      });
    }
    const w = weekMap.get(key);
    const hrs = Number(entry.hoursWorked || 0);
    w.totalHours += hrs;
    if (hrs > 0) w.workingDays += 1;
    if (entry.isApproved) w.approvedHours += hrs;
    if (entry.isPending) w.pendingHours += hrs;
  });
  const weekRows = Array.from(weekMap.values()).map((w) => {
    let status = 'NO_HOURS';
    if (w.pendingHours > 0) status = 'SUBMITTED';
    else if (w.approvedHours > 0) status = 'APPROVED';
    else if (w.totalHours > 0) status = 'LOGGED';
    return {
      ...w,
      formattedHours: formatHours(w.totalHours),
      status,
    };
  });

  const monthMap = new Map();
  (consolidatedList || []).forEach((entry) => {
    const mKey = entry.workDate.slice(0, 7);
    if (!monthMap.has(mKey)) {
      const mDate = parseDateKey(`${mKey}-01`);
      monthMap.set(mKey, {
        id: mKey,
        monthKey: mKey,
        label: mDate.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
        totalHours: 0,
        workingDays: 0,
        approvedHours: 0,
        pendingHours: 0,
      });
    }
    const m = monthMap.get(mKey);
    const hrs = Number(entry.hoursWorked || 0);
    m.totalHours += hrs;
    if (hrs > 0) m.workingDays += 1;
    if (entry.isApproved) m.approvedHours += hrs;
    if (entry.isPending) m.pendingHours += hrs;
  });
  const monthRows = Array.from(monthMap.values()).map((m) => {
    let status = 'NO_HOURS';
    if (m.pendingHours > 0) status = 'SUBMITTED';
    else if (m.approvedHours > 0) status = 'APPROVED';
    else if (m.totalHours > 0) status = 'LOGGED';
    return {
      ...m,
      formattedHours: formatHours(m.totalHours),
      status,
    };
  });

  return { dayRows, weekRows, monthRows };
}

// A single popover calendar for picking a From/End range in one place - click
// a start day, hover to preview, click an end day, Apply. Used to filter
// Team Timesheets lists by period instead of two separate date inputs each
// popping up their own (browser-native) calendar.
function DateRangePicker({ label, fromDate, toDate, onChange, disabled = false, maxDate = null }) {
  const [open, setOpen] = useState(false);
  const [viewMonth, setViewMonth] = useState((fromDate || toDate || toDateKey(new Date())).slice(0, 7));
  const [pendingStart, setPendingStart] = useState(fromDate || null);
  const [pendingEnd, setPendingEnd] = useState(toDate || null);
  const [hoverDate, setHoverDate] = useState(null);
  const shellRef = useRef(null);

  // Mutual exclusivity with a sibling Period preset: if this control becomes
  // disabled while its popover happens to be open, close it too.
  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  useEffect(() => {
    if (!open) return undefined;
    // Re-seed from the applied value each time it opens, so a "Clear filters"
    // elsewhere in the page is reflected instead of showing a stale pick.
    setPendingStart(fromDate || null);
    setPendingEnd(toDate || null);
    setViewMonth((fromDate || toDate || toDateKey(new Date())).slice(0, 7));
    function handleOutsideClick(event) {
      if (shellRef.current && !shellRef.current.contains(event.target)) setOpen(false);
    }
    document.addEventListener('mousedown', handleOutsideClick);
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, [open, fromDate, toDate]);

  const monthStart = `${viewMonth}-01`;
  const monthLabel = parseDateKey(monthStart).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const cells = useMemo(() => monthCells(viewMonth), [viewMonth]);

  const pickingEnd = Boolean(pendingStart && !pendingEnd);
  const activeEnd = pendingEnd || hoverDate || pendingStart;
  const previewRange = pendingStart && activeEnd
    ? { start: pendingStart <= activeEnd ? pendingStart : activeEnd, end: pendingStart <= activeEnd ? activeEnd : pendingStart }
    : null;

  function selectDate(date) {
    // Only the start of a range is capped at maxDate (e.g. today) - the end is
    // left open so an already-started, still-ongoing period can be picked.
    if (!pickingEnd && maxDate && date > maxDate) return;
    if (!pendingStart || pendingEnd) {
      setPendingStart(date);
      setPendingEnd(null);
      setHoverDate(date);
      return;
    }
    setPendingEnd(date);
    setHoverDate(date);
  }

  function apply() {
    onChange(previewRange?.start || '', previewRange?.end || '');
    setOpen(false);
  }

  function clear() {
    setPendingStart(null);
    setPendingEnd(null);
    setHoverDate(null);
    onChange('', '');
    setOpen(false);
  }

  const toggleLabel = fromDate && toDate
    ? `${formatDate(fromDate, { month: 'short', day: 'numeric' })} - ${formatDate(toDate, { month: 'short', day: 'numeric', year: 'numeric' })}`
    : fromDate ? `From ${formatDate(fromDate)}`
    : toDate ? `Through ${formatDate(toDate)}`
    : 'Any date';

  return (
    <div className="wp-field timesheets__daterange-field">
      <FormLabel htmlFor={`${label}-toggle`}>{label}</FormLabel>
      <div className="timesheets__date-picker-shell timesheets__daterange-shell" ref={shellRef}>
        {/* A plain button, not the shared Button component: Button doesn't
            forward id/aria-expanded, which this needs for its FormLabel
            association and open/closed state. Same wp-button classes, so it
            still looks identical to every other secondary button. */}
        <button
          type="button"
          id={`${label}-toggle`}
          className="wp-button wp-button--secondary timesheets__date-picker-toggle"
          aria-expanded={open}
          disabled={disabled}
          onClick={() => setOpen((current) => !current)}
        >
          <span aria-hidden="true">📅</span>
          <span>{toggleLabel}</span>
        </button>
        {open && (
          <div className="timesheets__date-picker-popover" role="dialog" aria-label={`Select ${label.toLowerCase()}`}>
            <div className="timesheets__date-picker-header">
              <button type="button" aria-label="Previous month" onClick={() => setViewMonth(addMonths(monthStart, -1).slice(0, 7))}>←</button>
              <strong>{monthLabel}</strong>
              <button type="button" aria-label="Next month" onClick={() => setViewMonth(addMonths(monthStart, 1).slice(0, 7))}>→</button>
            </div>
            <div className="timesheets__date-picker-weekdays">
              {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((day) => <span key={day}>{day}</span>)}
            </div>
            <div className="timesheets__date-picker-grid">
              {cells.map((cell) => {
                const isFuture = Boolean(!pickingEnd && maxDate && cell.date > maxDate);
                const isSelected = previewRange && cell.date >= previewRange.start && cell.date <= previewRange.end;
                const isEdge = isSelected && (cell.date === previewRange.start || cell.date === previewRange.end);
                const classes = [
                  cell.currentMonth ? '' : 'is-adjacent',
                  isSelected ? 'is-selected' : '',
                  isEdge ? 'is-edge' : '',
                  isFuture ? 'is-disabled' : '',
                ].filter(Boolean).join(' ');
                return (
                  <button
                    key={cell.date}
                    type="button"
                    className={classes}
                    disabled={isFuture}
                    onClick={() => selectDate(cell.date)}
                    onMouseEnter={() => { if (pendingStart && !pendingEnd) setHoverDate(cell.date); }}
                    onFocus={() => { if (pendingStart && !pendingEnd) setHoverDate(cell.date); }}
                    aria-label={`${formatDate(cell.date)}${isSelected ? ', selected' : ''}${isFuture ? ', unavailable' : ''}`}
                  >
                    <span>{parseDateKey(cell.date).getDate()}</span>
                  </button>
                );
              })}
            </div>
            <div className="timesheets__date-picker-footer">
              <button type="button" onClick={clear}>Clear</button>
              <button type="button" className="is-primary" onClick={apply}>Apply</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function TeamWorkspace({
  role,
  loading,
  teamStats,
  visibleTeam,
  teamRecords,
  managers,
  query,
  setQuery,
  roleFilter,
  setRoleFilter,
  statusFilter,
  setStatusFilter,
  managerFilter,
  setManagerFilter,
  periodFilter,
  setPeriodFilter,
  fromDateFilter,
  setFromDateFilter,
  toDateFilter,
  setToDateFilter,
  submissionsSort,
  setSubmissionsSort,
  onView,
  onReview,
  onReject,
  reviewingId,
  onDownloadPdf,
  onDownloadDoc,
  onViewDoc,
  today,
  payrollPeriod = 'MONTHLY',
  employeeDirectory = [],
  subTab,
  setSubTab,
  selectedEmployee,
  setSelectedEmployee,
}) {

  const [filterAnchor, setFilterAnchor] = useState(null);
  const combinedEmployees = useMemo(() => {
    const map = new Map();
    (employeeDirectory || []).forEach((emp) => {
      const key = emp.userId || emp.employeeId;
      if (key) {
        map.set(key, {
          userId: emp.userId,
          employeeId: emp.employeeId || emp.userId,
          name: emp.name || 'Unknown',
          role: emp.personType || 'Employee',
          designation: emp.designation || '',
          workEmail: emp.workEmail || '',
          manager: emp.reportingManagerName || '',
        });
      }
    });
    (teamRecords || []).forEach((rec) => {
      const key = rec.employeeUserId || rec.employeeId;
      if (key && !map.has(key)) {
        map.set(key, {
          userId: rec.employeeUserId,
          employeeId: rec.employeeId || rec.employeeUserId,
          name: rec.employeeName || 'Unknown',
          role: rec.employeeRole || 'Employee',
          designation: rec.designation || '',
          workEmail: '',
          manager: rec.reportingManagerName || rec.reportingManagerLoginId || '',
        });
      }
    });
    return Array.from(map.values()).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
  }, [employeeDirectory, teamRecords]);

  const teamRows = useMemo(() => {
    return visibleTeam.map((record) => ({
      ...record,
      employee: record.employeeName || 'Unknown employee',
      role: record.employeeRole || 'Employee',
      manager: record.reportingManagerName || record.reportingManagerLoginId || 'Assigned manager',
      period: formatPeriod(record.periodStartDate, record.periodEndDate),
      hours: formatHours(record.totalHours),
    }));
  }, [visibleTeam]);

  const hasFilters = Boolean(
    query
    || (roleFilter && roleFilter !== 'ALL')
    || (statusFilter && statusFilter !== 'ALL')
    || (managerFilter && managerFilter !== 'ALL')
    || (periodFilter && periodFilter !== 'ALL')
    || fromDateFilter
    || toDateFilter
    || (submissionsSort && submissionsSort !== 'NEWEST_SUBMITTED')
  );

  const resetFilters = () => {
    setQuery('');
    setRoleFilter('ALL');
    setStatusFilter('ALL');
    setManagerFilter('ALL');
    setPeriodFilter?.('ALL');
    setFromDateFilter?.('');
    setToDateFilter?.('');
    setSubmissionsSort?.('NEWEST_SUBMITTED');
  };

  const handleStatCardClick = (status) => {
    setSelectedEmployee(null);
    setSubTab('submissions');
    if (status === 'ALL') {
      setStatusFilter('ALL');
    } else {
      setStatusFilter((current) => current === status ? 'ALL' : status);
    }
  };

  return (
    <TimesheetFilterAnchor.Provider value={filterAnchor}><div className="timesheets__team-workspace">
      <div className="timesheets__team-heading">
        <div>
          <span>REVIEW WORKSPACE</span>
          <h2>{role === 'HR' ? 'Direct-report timesheets' : 'Organization timesheets'}</h2>
          <p>{role === 'HR' ? 'Review timesheets submitted by employees assigned to you.' : 'Review and approve or reject timesheets across your organization.'}</p>
        </div>
        <div className="timesheets__stats" role="group" aria-label="Filter by status">
          <button
            type="button"
            aria-label={`Filter by total: ${teamStats.total}`}
            className={`timesheets__stat-card${subTab === 'submissions' && statusFilter === 'ALL' ? ' is-active' : ''}`}
            onClick={() => handleStatCardClick('ALL')}
          >
            <span>Total</span><strong>{teamStats.total}</strong>
          </button>
          <button
            type="button"
            aria-label={`Filter by submitted: ${teamStats.submitted}`}
            className={`timesheets__stat-card${subTab === 'actions' ? ' is-active' : ''}`}
            onClick={() => handleStatCardClick('SUBMITTED')}
          >
            <span>Submitted</span><strong>{teamStats.submitted}</strong>
          </button>
          <button
            type="button"
            aria-label={`Filter by approved: ${teamStats.approved}`}
            className={`timesheets__stat-card${subTab === 'submissions' && statusFilter === 'APPROVED' ? ' is-active' : ''}`}
            onClick={() => handleStatCardClick('APPROVED')}
          >
            <span>Approved</span><strong>{teamStats.approved}</strong>
          </button>
          <button
            type="button"
            aria-label={`Filter by rejected: ${teamStats.rejected}`}
            className={`timesheets__stat-card${subTab === 'submissions' && statusFilter === 'REJECTED' ? ' is-active' : ''}`}
            onClick={() => handleStatCardClick('REJECTED')}
          >
            <span>Rejected</span><strong>{teamStats.rejected}</strong>
          </button>
        </div>
      </div>

      <nav className="timesheets__subtabs-bar" aria-label="Review workspace views">
        <button
          type="button"
          id="team-tab-submissions"
          aria-pressed={subTab === 'submissions'}
          className={`timesheets__subtab-btn${subTab === 'submissions' ? ' is-active' : ''}`}
          onClick={() => {
            setSelectedEmployee(null);
            setSubTab('submissions');
          }}
        >
          <span className="timesheets__subtab-icon" aria-hidden="true">📋</span>
          <span className="timesheets__subtab-text">
            <span className="timesheets__subtab-text--full">All Submissions</span>
            <span className="timesheets__subtab-text--compact">All</span>
          </span>
          <span className="timesheets__subtab-badge">{teamRecords.length}</span>
        </button>
        <button
          type="button"
          id="team-tab-actions"
          aria-pressed={subTab === 'actions'}
          className={`timesheets__subtab-btn${subTab === 'actions' ? ' is-active' : ''}`}
          onClick={() => {
            setSelectedEmployee(null);
            setSubTab('actions');
          }}
        >
          <span className="timesheets__subtab-icon" aria-hidden="true">⚡</span>
          <span className="timesheets__subtab-text">
            <span className="timesheets__subtab-text--full">Required Actions</span>
            <span className="timesheets__subtab-text--compact">Actions</span>
          </span>
          {teamStats.submitted > 0 ? (
            <span className="timesheets__subtab-badge timesheets__subtab-badge--alert" aria-label={`${teamStats.submitted} items pending`}>
              {teamStats.submitted}
            </span>
          ) : (
            <span className="timesheets__subtab-badge">0</span>
          )}
        </button>
        <button
          type="button"
          id="team-tab-employees"
          aria-pressed={subTab === 'employees'}
          className={`timesheets__subtab-btn${subTab === 'employees' ? ' is-active' : ''}`}
          onClick={() => setSubTab('employees')}
        >
          <span className="timesheets__subtab-icon" aria-hidden="true">👥</span>
          <span className="timesheets__subtab-text">
            <span className="timesheets__subtab-text--full">By Employee</span>
            <span className="timesheets__subtab-text--compact">Employees</span>
          </span>
          <span className="timesheets__subtab-badge">{combinedEmployees.length}</span>
        </button>
        <div ref={setFilterAnchor} className="timesheets__filter-anchor" />
      </nav>

      {loading ? (
        <p role="status">Loading timesheets...</p>
      ) : subTab === 'actions' ? (
        <RequiredActionsWorkspace
          teamRecords={teamRecords}
          reviewingId={reviewingId}
          onReview={onReview}
          onReject={onReject}
          onView={onView}
          onViewDoc={onViewDoc}
          onDownloadDoc={onDownloadDoc}
          role={role}
        />
      ) : subTab === 'employees' ? (
        selectedEmployee ? (
          <EmployeeProfileWorkspace
            employee={selectedEmployee}
            teamRecords={teamRecords}
            onBack={() => setSelectedEmployee(null)}
            onView={onView}
            onDownloadPdf={onDownloadPdf}
            today={today}
            payrollPeriod={payrollPeriod}
          />
        ) : (
          <EmployeeDirectoryWorkspace
            employees={combinedEmployees}
            teamRecords={teamRecords}
            onSelectEmployee={(emp) => setSelectedEmployee(emp)}
          />
        )
      ) : (
        <AllSubmissionsWorkspace
          role={role}
          teamRows={teamRows}
          query={query}
          setQuery={setQuery}
          roleFilter={roleFilter}
          setRoleFilter={setRoleFilter}
          statusFilter={statusFilter}
          setStatusFilter={setStatusFilter}
          managers={managers}
          managerFilter={managerFilter}
          setManagerFilter={setManagerFilter}
          periodFilter={periodFilter}
          setPeriodFilter={setPeriodFilter}
          fromDateFilter={fromDateFilter}
          setFromDateFilter={setFromDateFilter}
          toDateFilter={toDateFilter}
          setToDateFilter={setToDateFilter}
          submissionsSort={submissionsSort}
          setSubmissionsSort={setSubmissionsSort}
          resetFilters={resetFilters}
          hasFilters={hasFilters}
          onView={onView}
        />
      )}
    </div></TimesheetFilterAnchor.Provider>
  );
}

function RequiredActionsWorkspace({
  teamRecords,
  reviewingId,
  onReview,
  onReject,
  onView,
  onViewDoc,
}) {
  const [actionsQuery, setActionsQuery] = useState('');
  const [actionsRole, setActionsRole] = useState('ALL');
  const [actionsManager, setActionsManager] = useState('ALL');
  const [actionsFromDate, setActionsFromDate] = useState('');
  const [actionsToDate, setActionsToDate] = useState('');
  const [actionsSort, setActionsSort] = useState('NEWEST_SUBMITTED');
  const [actionsPage, setActionsPage] = useState(1);
  const [isExpressClearing, setIsExpressClearing] = useState(false);
  const [expressProgress, setExpressProgress] = useState({ done: 0, total: 0 });
  const [appleBloom, setAppleBloom] = useState(null);

  const submittedRecords = useMemo(() => {
    return (teamRecords || []).filter((r) => r.status === 'SUBMITTED');
  }, [teamRecords]);

  const cleanRecords = useMemo(() => {
    return submittedRecords.filter(isRecordCleanAndAuditReady);
  }, [submittedRecords]);

  const handleExpressApproveClean = async () => {
    if (cleanRecords.length === 0 || isExpressClearing) return;
    setIsExpressClearing(true);
    setExpressProgress({ done: 0, total: cleanRecords.length });

    const startTime = performance.now();
    let approvedCount = 0;
    for (let i = 0; i < cleanRecords.length; i++) {
      const rec = cleanRecords[i];
      try {
        await onReview(rec, 'APPROVED');
        approvedCount++;
        setExpressProgress({ done: approvedCount, total: cleanRecords.length });
      } catch (err) {
        console.error('Express clearance error for item', rec.id, err);
      }
    }
    const elapsedSec = ((performance.now() - startTime) / 1000).toFixed(2);
    setIsExpressClearing(false);

    if (approvedCount > 0) {
      setAppleBloom({
        visible: true,
        title: `${approvedCount} Timesheet${approvedCount === 1 ? '' : 's'} Approved`,
        subtitle: `Cleared in ${elapsedSec}s`,
        detail: `All ${approvedCount} standard submissions verified compliant and approved with immutable audit timestamps.`,
      });
    }
  };

  const availableRoles = useMemo(() => {
    return Array.from(new Set(submittedRecords.map((r) => r.employeeRole || r.role).filter(Boolean))).sort();
  }, [submittedRecords]);

  const availableManagers = useMemo(() => {
    return Array.from(new Set(submittedRecords.map((r) => r.reportingManagerName || r.reportingManagerLoginId).filter(Boolean))).sort();
  }, [submittedRecords]);

  const filteredSubmitted = useMemo(() => {
    const q = actionsQuery.trim().toLowerCase();
    return submittedRecords.filter((rec) => {
      if (q) {
        const text = `${rec.employeeId || ''} ${rec.employeeName || ''} ${rec.reportingManagerName || ''} ${rec.reportingManagerLoginId || ''}`.toLowerCase();
        if (!text.includes(q)) return false;
      }
      if (actionsRole !== 'ALL' && (rec.employeeRole || rec.role) !== actionsRole) return false;
      const mgr = rec.reportingManagerName || rec.reportingManagerLoginId;
      if (actionsManager !== 'ALL' && mgr !== actionsManager) return false;
      // Match on the period's START date, same rule as the Current/Previous
      // month presets - a period only belongs to the range it starts in, so
      // picking Sep 1-30 never pulls in a submission that starts Aug 31.
      if (actionsFromDate && rec.periodStartDate < actionsFromDate) return false;
      if (actionsToDate && rec.periodStartDate > actionsToDate) return false;
      return true;
    });
  }, [submittedRecords, actionsQuery, actionsRole, actionsManager, actionsFromDate, actionsToDate]);

  const sortedSubmitted = useMemo(() => {
    const list = [...filteredSubmitted];
    if (actionsSort === 'NEWEST_SUBMITTED') {
      return list.sort((a, b) => new Date(b.submittedAt || b.createdAt || b.periodEndDate || 0) - new Date(a.submittedAt || a.createdAt || a.periodEndDate || 0));
    }
    if (actionsSort === 'OLDEST_SUBMITTED') {
      return list.sort((a, b) => new Date(a.submittedAt || a.createdAt || a.periodEndDate || 0) - new Date(b.submittedAt || b.createdAt || b.periodEndDate || 0));
    }
    if (actionsSort === 'NAME_ASC') {
      return list.sort((a, b) => (a.employeeName || '').localeCompare(b.employeeName || ''));
    }
    if (actionsSort === 'HOURS_DESC') {
      return list.sort((a, b) => Number(b.totalHours || 0) - Number(a.totalHours || 0));
    }
    return list;
  }, [filteredSubmitted, actionsSort]);

  const rows = useMemo(() => {
    return sortedSubmitted.map((record) => ({
      ...record,
      employee: record.employeeName || 'Unknown employee',
      role: record.employeeRole || 'Employee',
      manager: record.reportingManagerName || record.reportingManagerLoginId || 'Assigned manager',
      period: formatPeriod(record.periodStartDate, record.periodEndDate),
      hours: formatHours(record.totalHours),
      submittedDate: formatDateTime(record.submittedAt),
    }));
  }, [sortedSubmitted]);

  useEffect(() => {
    setActionsPage(1);
  }, [actionsQuery, actionsRole, actionsManager, actionsFromDate, actionsToDate, actionsSort]);

  const hasFilters = Boolean(
    actionsQuery
    || actionsRole !== 'ALL'
    || actionsManager !== 'ALL'
    || actionsFromDate
    || actionsToDate
    || actionsSort !== 'NEWEST_SUBMITTED'
  );

  const resetFilters = () => {
    setActionsQuery('');
    setActionsFromDate('');
    setActionsToDate('');
    setActionsRole('ALL');
    setActionsManager('ALL');
    setActionsSort('NEWEST_SUBMITTED');
    setActionsPage(1);
  };

  const PAGE_SIZE = 10;
  const totalPages = Math.ceil(rows.length / PAGE_SIZE) || 1;
  const safePage = Math.min(actionsPage, totalPages);
  const pagedRows = rows.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const startItem = rows.length === 0 ? 0 : (safePage - 1) * PAGE_SIZE + 1;
  const endItem = Math.min(safePage * PAGE_SIZE, rows.length);

  return (
    <div className="timesheets__required-actions">
      {submittedRecords.length > 0 ? (
        <div className="timesheets__actions-banner" role="status">
          <span className="timesheets__actions-banner-icon" aria-hidden="true">⚡</span>
          <div>
            <strong>Action Needed: {submittedRecords.length} timesheet{submittedRecords.length === 1 ? '' : 's'} awaiting your review</strong>
            <p>Review the submitted hours and supporting documents below. You can approve directly or inspect full details.</p>
          </div>
        </div>
      ) : (
        <div className="timesheets__empty-card">
          <div className="timesheets__empty-icon" aria-hidden="true">✓</div>
          <h3>All Caught Up!</h3>
          <p>There are no timesheet submissions requiring approval right now.</p>
        </div>
      )}

      {submittedRecords.length > 0 && (
        <div className="timesheets__apple-express-bar" role="region" aria-label="1-Tap Express Approval">
          <div className="timesheets__apple-express-left">
            <div className="timesheets__apple-express-tag">
              <span className="timesheets__apple-express-dot" aria-hidden="true" />
              <span>⚡ 1-TAP EXPRESS CLEARANCE</span>
            </div>
            <div className="timesheets__apple-express-title">
              {cleanRecords.length > 0
                ? `${cleanRecords.length} Clean Submission${cleanRecords.length === 1 ? '' : 's'} Verified & Audit-Ready`
                : 'All pending submissions require custom inspection'}
            </div>
            <div className="timesheets__apple-express-subtitle">
              {cleanRecords.length > 0
                ? 'Standard hours (≤8h/day, ≤40h/wk baseline), verified attachments, and zero policy anomalies. Approve with 1 tap.'
                : 'Pending items have overtime or special conditions that require individual manager review.'}
            </div>
          </div>
          <button
            type="button"
            className="timesheets__apple-express-btn"
            disabled={cleanRecords.length === 0 || isExpressClearing}
            onClick={handleExpressApproveClean}
          >
            {isExpressClearing ? (
              <>
                <span className="timesheets__apple-spinner" aria-hidden="true" />
                <span>Clearing {expressProgress.done}/{expressProgress.total}...</span>
              </>
            ) : (
              <>
                <span className="timesheets__apple-bolt" aria-hidden="true">⚡</span>
                <span>Express Approve Clean ({cleanRecords.length})</span>
              </>
            )}
          </button>
        </div>
      )}

      {submittedRecords.length > 0 && (
  <TimesheetFilterPopover active={hasFilters}>
      <div className="timesheets__filters">
          <DateRangePicker label="Period" fromDate={actionsFromDate} toDate={actionsToDate} onChange={(start, end) => { setActionsFromDate(start); setActionsToDate(end); }} />
          <div className="timesheets__filter-search">
            <FormLabel htmlFor="actionsSearch">Search pending submissions</FormLabel>
            <SearchInput
              id="actionsSearch"
              name="actionsSearch"
              value={actionsQuery}
              onChange={(e) => setActionsQuery(e.target.value)}
              placeholder="Search employee name or ID"
            />
          </div>
          {availableRoles.length > 1 && (
            <Dropdown
              name="actionsRole"
              label="Role"
              value={actionsRole}
              onChange={(e) => setActionsRole(e.target.value)}
              options={[
                { value: 'ALL', label: 'All roles' },
                ...availableRoles.map((r) => ({ value: r, label: r === 'EMPLOYEE' ? 'Employee' : r === 'HR' ? 'HR' : r })),
              ]}
            />
          )}
          {availableManagers.length > 1 && (
            <Dropdown
              name="actionsManager"
              label="Reporting manager"
              value={actionsManager}
              onChange={(e) => setActionsManager(e.target.value)}
              options={[
                { value: 'ALL', label: 'All managers' },
                ...availableManagers.map((m) => ({ value: m, label: m })),
              ]}
            />
          )}
          <Dropdown
            name="actionsSort"
            label="Sort by"
            value={actionsSort}
            onChange={(e) => setActionsSort(e.target.value)}
            options={[
              { value: 'NEWEST_SUBMITTED', label: 'Newest submitted' },
              { value: 'OLDEST_SUBMITTED', label: 'Oldest submitted' },
              { value: 'NAME_ASC', label: 'Employee name (A–Z)' },
              { value: 'HOURS_DESC', label: 'Highest hours' },
            ]}
          />
          <Button
            type="button"
            variant="secondary"
            className="timesheets__clear-btn"
            onClick={resetFilters}
            disabled={!hasFilters}
          >
            Clear filters
          </Button>
        </div>
</TimesheetFilterPopover>
      )}

      {submittedRecords.length > 0 && (
        <>
          <Table
            className="timesheets__team-table timesheets__actions-table"
            columns={[
              { key: 'employeeId', header: 'Employee ID', width: '120px' },
              { key: 'employee', header: 'Employee', width: '160px' },
              { key: 'role', header: 'Role', width: '110px' },
              { key: 'manager', header: 'Reporting manager', width: '160px' },
              { key: 'period', header: 'Period', width: '150px' },
              { key: 'hours', header: 'Hours', width: '95px' },
              { key: 'submittedDate', header: 'Submitted on', width: '155px' },
              { key: 'document', header: 'Supporting Doc', width: '130px' },
              { key: 'status', header: 'Status', width: '115px' },
              { key: 'action', header: 'Action', width: '255px' },
            ]}
            rows={pagedRows}
            emptyMessage="No matching pending submissions found."
            renderCell={(column, row) => {
              if (column.key === 'status') {
                return <StatusTag status={row.status} tone={toneForStatus(row.status)} />;
              }
              if (column.key === 'document') {
                const docs = row.supportingDocuments || [];
                if (docs.length) {
                  const [first] = docs;
                  return (
                    <Button
                      type="button"
                      variant="secondary"
                      className="timesheets__doc-link-btn"
                      aria-label={`View supporting document ${first.fileName || row.supportingDocumentName || ''}`.trim()}
                      title={`View ${first.fileName || row.supportingDocumentName || 'Document'}`}
                      onClick={() => onViewDoc?.({
                        id: first.id,
                        fileName: first.fileName || 'supporting-doc',
                        employeeName: row.employee,
                        period: row.period,
                        hours: row.hours,
                      })}
                    >
                      <span className="timesheets__doc-icon" aria-hidden="true">📄</span>
                      <span className="timesheets__doc-name">{first.fileName || 'Document'}</span>
                      <span className="timesheets__doc-badge">{docs.length > 1 ? `+${docs.length - 1} more` : 'View'}</span>
                    </Button>
                  );
                }
                return <span className="timesheets__muted-text">None</span>;
              }
              if (column.key !== 'action') return row[column.key];
              return (
                <div className="timesheets__entry-actions">
                  <Button type="button" variant="secondary" onClick={() => onView(row)}>
                    View details
                  </Button>
                  <Button
                    type="button"
                    variant="primary"
                    disabled={reviewingId === row.id}
                    onClick={() => onReview(row, 'APPROVED')}
                  >
                    {reviewingId === row.id ? 'Approving...' : 'Approve'}
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    className="timesheets__btn-reject"
                    disabled={reviewingId === row.id}
                    onClick={() => onReject(row)}
                  >
                    Reject
                  </Button>
                </div>
              );
            }}
          />
          {rows.length > 0 && (
            <div className="timesheets__pagination-bar">
              <span className="timesheets__pagination-info">
                Showing {startItem}–{endItem} of {rows.length} pending submission{rows.length === 1 ? '' : 's'}
              </span>
              {totalPages > 1 && (
                <MyHrPagination
                  currentPage={safePage}
                  totalPages={totalPages}
                  onPageChange={setActionsPage}
                />
              )}
            </div>
          )}
        </>
      )}

      {appleBloom && (
        <ApplePayBloom
          visible={Boolean(appleBloom)}
          title={appleBloom.title}
          subtitle={appleBloom.subtitle}
          detail={appleBloom.detail}
          onClose={() => setAppleBloom(null)}
        />
      )}
    </div>
  );
}

function EmployeeDirectoryWorkspace({ employees, teamRecords, onSelectEmployee }) {
  const [empQuery, setEmpQuery] = useState('');
  const [pendingFilter, setPendingFilter] = useState('ALL');
  const [empRoleFilter, setEmpRoleFilter] = useState('ALL');
  const [empManagerFilter, setEmpManagerFilter] = useState('ALL');
  const [sortBy, setSortBy] = useState('NAME_ASC');
  const [page, setPage] = useState(1);

  const directoryRows = useMemo(() => {
    return (employees || []).map((emp) => {
      const empRecords = (teamRecords || []).filter((rec) =>
        (rec.employeeUserId && rec.employeeUserId === emp.userId)
        || (rec.employeeId && rec.employeeId === emp.employeeId)
      );
      const pendingCount = empRecords.filter((r) => r.status === 'SUBMITTED').length;
      const approvedCount = empRecords.filter((r) => r.status === 'APPROVED').length;
      const totalRecordedHours = empRecords
        .filter((r) => r.status === 'APPROVED' || r.status === 'SUBMITTED')
        .reduce((sum, r) => sum + Number(r.totalHours || 0), 0);
      const sortedRecords = [...empRecords].sort((a, b) => (b.periodEndDate || '').localeCompare(a.periodEndDate || ''));
      const latest = sortedRecords[0];
      const recentPeriod = latest ? formatPeriod(latest.periodStartDate, latest.periodEndDate) : 'No submissions yet';

      return {
        ...emp,
        id: emp.userId || emp.employeeId,
        pendingCount,
        approvedCount,
        totalRecordedHours,
        formattedTotalHours: formatHours(totalRecordedHours),
        recentPeriod,
        recordsCount: empRecords.length,
      };
    });
  }, [employees, teamRecords]);

  const availableRoles = useMemo(() => {
    return Array.from(new Set(directoryRows.map((r) => r.designation || r.role).filter(Boolean))).sort();
  }, [directoryRows]);

  const availableManagers = useMemo(() => {
    return Array.from(new Set(directoryRows.map((r) => r.manager).filter(Boolean))).sort();
  }, [directoryRows]);

  const filteredRows = useMemo(() => {
    const q = empQuery.trim().toLowerCase();
    return directoryRows.filter((e) => {
      if (q) {
        const match = (e.name || '').toLowerCase().includes(q)
          || (e.employeeId || '').toLowerCase().includes(q)
          || (e.workEmail || '').toLowerCase().includes(q)
          || (e.designation || '').toLowerCase().includes(q)
          || (e.role || '').toLowerCase().includes(q)
          || (e.manager || '').toLowerCase().includes(q);
        if (!match) return false;
      }
      if (pendingFilter === 'WITH_PENDING' && e.pendingCount <= 0) return false;
      if (pendingFilter === 'NO_PENDING' && e.pendingCount > 0) return false;
      if (empRoleFilter !== 'ALL' && (e.designation || e.role) !== empRoleFilter) return false;
      if (empManagerFilter !== 'ALL' && e.manager !== empManagerFilter) return false;
      return true;
    });
  }, [directoryRows, empQuery, pendingFilter, empRoleFilter, empManagerFilter]);

  const sortedRows = useMemo(() => {
    const list = [...filteredRows];
    if (sortBy === 'NAME_ASC') {
      return list.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    }
    if (sortBy === 'NAME_DESC') {
      return list.sort((a, b) => (b.name || '').localeCompare(a.name || ''));
    }
    if (sortBy === 'PENDING_DESC') {
      return list.sort((a, b) => b.pendingCount - a.pendingCount || (a.name || '').localeCompare(b.name || ''));
    }
    if (sortBy === 'HOURS_DESC') {
      return list.sort((a, b) => b.totalRecordedHours - a.totalRecordedHours || (a.name || '').localeCompare(b.name || ''));
    }
    if (sortBy === 'HOURS_ASC') {
      return list.sort((a, b) => a.totalRecordedHours - b.totalRecordedHours || (a.name || '').localeCompare(b.name || ''));
    }
    return list;
  }, [filteredRows, sortBy]);

  useEffect(() => {
    setPage(1);
  }, [empQuery, pendingFilter, empRoleFilter, empManagerFilter, sortBy]);

  const hasFilters = Boolean(
    empQuery
    || pendingFilter !== 'ALL'
    || empRoleFilter !== 'ALL'
    || empManagerFilter !== 'ALL'
    || sortBy !== 'NAME_ASC'
  );

  const resetFilters = () => {
    setEmpQuery('');
    setPendingFilter('ALL');
    setEmpRoleFilter('ALL');
    setEmpManagerFilter('ALL');
    setSortBy('NAME_ASC');
    setPage(1);
  };

  const PAGE_SIZE = 10;
  const totalPages = Math.ceil(sortedRows.length / PAGE_SIZE) || 1;
  const safePage = Math.min(page, totalPages);
  const pagedRows = sortedRows.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const startItem = sortedRows.length === 0 ? 0 : (safePage - 1) * PAGE_SIZE + 1;
  const endItem = Math.min(safePage * PAGE_SIZE, sortedRows.length);

  return (
    <div className="timesheets__emp-directory">
<TimesheetFilterPopover active={hasFilters}>
      <div className="timesheets__filters">
        <div className="timesheets__filter-search">
          <FormLabel htmlFor="employeeDirSearch">Search employees</FormLabel>
          <SearchInput
            id="employeeDirSearch"
            name="employeeDirSearch"
            value={empQuery}
            onChange={(e) => setEmpQuery(e.target.value)}
            placeholder="Search employee by name, ID, title, or manager"
          />
        </div>
        <Dropdown
          name="employeeDirPending"
          label="Submissions"
          value={pendingFilter}
          onChange={(e) => setPendingFilter(e.target.value)}
          options={[
            { value: 'ALL', label: 'All employees' },
            { value: 'WITH_PENDING', label: 'With pending submissions' },
            { value: 'NO_PENDING', label: 'No pending submissions' },
          ]}
        />
        {availableRoles.length > 1 && (
          <Dropdown
            name="employeeDirRole"
            label="Role / Title"
            value={empRoleFilter}
            onChange={(e) => setEmpRoleFilter(e.target.value)}
            options={[
              { value: 'ALL', label: 'All roles' },
              ...availableRoles.map((r) => ({ value: r, label: r })),
            ]}
          />
        )}
        {availableManagers.length > 1 && (
          <Dropdown
            name="employeeDirManager"
            label="Reporting manager"
            value={empManagerFilter}
            onChange={(e) => setEmpManagerFilter(e.target.value)}
            options={[
              { value: 'ALL', label: 'All managers' },
              ...availableManagers.map((m) => ({ value: m, label: m })),
            ]}
          />
        )}
        <Dropdown
          name="employeeDirSort"
          label="Sort by"
          value={sortBy}
          onChange={(e) => setSortBy(e.target.value)}
          options={[
            { value: 'NAME_ASC', label: 'Name (A–Z)' },
            { value: 'NAME_DESC', label: 'Name (Z–A)' },
            { value: 'PENDING_DESC', label: 'Most pending submissions' },
            { value: 'HOURS_DESC', label: 'Highest total hours' },
            { value: 'HOURS_ASC', label: 'Lowest total hours' },
          ]}
        />
        <Button
          type="button"
          variant="secondary"
          className="timesheets__clear-btn"
          onClick={resetFilters}
          disabled={!hasFilters}
        >
          Clear filters
        </Button>
      </div>
</TimesheetFilterPopover>

      <Table
        className="timesheets__team-table timesheets__directory-table"
        columns={[
          { key: 'employee', header: 'Employee', width: '220px' },
          { key: 'employeeId', header: 'Employee ID', width: '130px' },
          { key: 'designation', header: 'Role / Title', width: '140px' },
          { key: 'manager', header: 'Reporting manager', width: '160px' },
          { key: 'pendingCount', header: 'Pending', width: '110px' },
          { key: 'totalHours', header: 'Total Hours', width: '120px' },
          { key: 'recentPeriod', header: 'Latest Timesheet Period', width: '170px' },
          { key: 'action', header: 'Action', width: '190px' },
        ]}
        rows={pagedRows}
        emptyMessage="No employees found."
        renderCell={(column, row) => {
          if (column.key === 'employee') {
            return (
              <div className="timesheets__employee-cell">
                <span className="timesheets__avatar">{getInitials(row.name)}</span>
                <div>
                  <strong>{row.name}</strong>
                  {row.workEmail && <span>{row.workEmail}</span>}
                </div>
              </div>
            );
          }
          if (column.key === 'designation') {
            return row.designation || row.role || 'Employee';
          }
          if (column.key === 'pendingCount') {
            return row.pendingCount > 0 ? (
              <span className="timesheets__badge timesheets__badge--alert">{row.pendingCount} Pending</span>
            ) : (
              <span className="timesheets__muted-text">—</span>
            );
          }
          if (column.key === 'totalHours') return row.formattedTotalHours;
          if (column.key !== 'action') return row[column.key];
          return (
            <div className="timesheets__entry-actions">
              <Button type="button" variant="secondary" onClick={() => onSelectEmployee(row)}>
                View Profile & Hours →
              </Button>
            </div>
          );
        }}
      />
      {sortedRows.length > 0 && (
        <div className="timesheets__pagination-bar">
          <span className="timesheets__pagination-info">
            Showing {startItem}–{endItem} of {sortedRows.length} employee{sortedRows.length === 1 ? '' : 's'}
          </span>
          {totalPages > 1 && (
            <MyHrPagination
              currentPage={safePage}
              totalPages={totalPages}
              onPageChange={setPage}
            />
          )}
        </div>
      )}
    </div>
  );
}

function EmployeeProfileWorkspace({
  employee,
  teamRecords,
  onBack,
  onView,
  onDownloadPdf,
  today,
  payrollPeriod = 'MONTHLY',
}) {
  const [preset, setPreset] = useState('CURRENT_PERIOD');
  const initialDates = useMemo(() => getPresetDates('CURRENT_PERIOD', today, payrollPeriod), [today, payrollPeriod]);
  const [rangeStart, setRangeStart] = useState(initialDates.start);
  const [rangeEnd, setRangeEnd] = useState(initialDates.end);
  const [aggTab, setAggTab] = useState('month');

  const handlePresetChange = (newPreset) => {
    setPreset(newPreset);
    if (newPreset !== 'CUSTOM') {
      const dates = getPresetDates(newPreset, today, payrollPeriod);
      setRangeStart(dates.start);
      setRangeEnd(dates.end);
    }
  };

  const empRecords = useMemo(() => {
    return (teamRecords || []).filter((rec) =>
      (rec.employeeUserId && rec.employeeUserId === employee.userId)
      || (rec.employeeId && rec.employeeId === employee.employeeId)
    );
  }, [teamRecords, employee]);

  const consolidated = useMemo(() => {
    if (!rangeStart || !rangeEnd || rangeStart > rangeEnd) {
      return { entries: [], totalHours: 0, workedDays: 0, status: 'NO_HOURS' };
    }
    return consolidatePeriodEntries(empRecords, rangeStart, rangeEnd, false);
  }, [empRecords, rangeStart, rangeEnd]);

  const breakdowns = useMemo(() => {
    return computeBreakdowns(consolidated.entries);
  }, [consolidated.entries]);

  const approvedHours = useMemo(() => {
    return (consolidated.entries || [])
      .filter((e) => e.isApproved)
      .reduce((sum, e) => sum + Number(e.hoursWorked || 0), 0);
  }, [consolidated.entries]);

  const pendingHours = useMemo(() => {
    return (consolidated.entries || [])
      .filter((e) => e.isPending)
      .reduce((sum, e) => sum + Number(e.hoursWorked || 0), 0);
  }, [consolidated.entries]);

  const recentRows = useMemo(() => {
    return empRecords.map((r) => ({
      ...r,
      period: formatPeriod(r.periodStartDate, r.periodEndDate),
      hours: formatHours(r.totalHours),
      submittedDate: formatDateTime(r.submittedAt),
    }));
  }, [empRecords]);

  const [dayPage, setDayPage] = useState(1);
  useEffect(() => { setDayPage(1); }, [rangeStart, rangeEnd, preset]);
  const totalDayPages = Math.ceil(breakdowns.dayRows.length / 15) || 1;
  const safeDayPage = Math.min(dayPage, totalDayPages);
  const pagedDays = breakdowns.dayRows.slice((safeDayPage - 1) * 15, safeDayPage * 15);
  const dayStart = breakdowns.dayRows.length === 0 ? 0 : (safeDayPage - 1) * 15 + 1;
  const dayEnd = Math.min(safeDayPage * 15, breakdowns.dayRows.length);

  const [recordsPage, setRecordsPage] = useState(1);
  const totalRecordsPages = Math.ceil(recentRows.length / 5) || 1;
  const safeRecordsPage = Math.min(recordsPage, totalRecordsPages);
  const pagedRecords = recentRows.slice((safeRecordsPage - 1) * 5, safeRecordsPage * 5);
  const recordsStart = recentRows.length === 0 ? 0 : (safeRecordsPage - 1) * 5 + 1;
  const recordsEnd = Math.min(safeRecordsPage * 5, recentRows.length);

  return (
    <div className="timesheets__emp-profile">
      <Button type="button" variant="secondary" className="timesheets__back-btn" onClick={onBack}>
        ← Back to Employee Directory
      </Button>

      <div className="timesheets__emp-profile-header">
        <div className="timesheets__emp-profile-avatar">{getInitials(employee.name)}</div>
        <div className="timesheets__emp-profile-info">
          <h3>{employee.name}</h3>
          <div className="timesheets__emp-profile-tags">
            <span><strong>Employee ID:</strong> {employee.employeeId}</span>
            <span><strong>Role:</strong> {employee.designation || employee.role || 'Employee'}</span>
            {employee.manager && <span><strong>Manager:</strong> {employee.manager}</span>}
            {employee.workEmail && <span><strong>Email:</strong> {employee.workEmail}</span>}
          </div>
        </div>
      </div>

      <div className="timesheets__profile-controls">
        <Dropdown
          name="profileRangePreset"
          label="Date range preset"
          value={preset}
          onChange={(e) => handlePresetChange(e.target.value)}
          options={[
            { value: 'CURRENT_PERIOD', label: 'Current Pay Period' },
            { value: 'PREVIOUS_PERIOD', label: 'Previous Pay Period' },
            { value: 'CURRENT_MONTH', label: 'Current Month' },
            { value: 'PREVIOUS_MONTH', label: 'Previous Month' },
            { value: 'LAST_90_DAYS', label: 'Last 90 Days' },
            { value: 'CUSTOM', label: 'Custom Date Range' },
          ]}
        />
        <div className="timesheets__date-range-fields">
          <div className="timesheets__date-range-field">
            <label htmlFor="profileStartDate">Start date</label>
            <input
              id="profileStartDate"
              type="date"
              className="timesheets__date-input"
              value={rangeStart}
              onChange={(e) => {
                setPreset('CUSTOM');
                setRangeStart(e.target.value);
              }}
            />
          </div>
          <div className="timesheets__date-range-field">
            <label htmlFor="profileEndDate">End date</label>
            <input
              id="profileEndDate"
              type="date"
              className="timesheets__date-input"
              value={rangeEnd}
              onChange={(e) => {
                setPreset('CUSTOM');
                setRangeEnd(e.target.value);
              }}
            />
          </div>
        </div>
      </div>

      <div className="timesheets__profile-stats">
        <div className="timesheets__profile-stat-card">
          <span>Total Hours</span>
          <strong>{formatHours(consolidated.totalHours)}</strong>
        </div>
        <div className="timesheets__profile-stat-card">
          <span>Days Worked</span>
          <strong>{consolidated.workedDays} day{consolidated.workedDays === 1 ? '' : 's'}</strong>
        </div>
        <div className="timesheets__profile-stat-card">
          <span>Approved Hours</span>
          <strong>{formatHours(approvedHours)}</strong>
        </div>
        <div className="timesheets__profile-stat-card">
          <span>Pending Hours</span>
          <strong>{formatHours(pendingHours)}</strong>
        </div>
      </div>

      <div className="timesheets__breakdown-section">
        <div className="timesheets__breakdown-header">
          <div>
            <h4>Hours Breakdown</h4>
            <p>Aggregated totals for {formatDate(rangeStart)} - {formatDate(rangeEnd)}</p>
          </div>
          <div className="timesheets__agg-switcher" role="tablist" aria-label="Aggregation view">
            <button
              type="button"
              role="tab"
              aria-selected={aggTab === 'month'}
              className={`timesheets__agg-btn${aggTab === 'month' ? ' is-active' : ''}`}
              onClick={() => setAggTab('month')}
            >
              Month-wise
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={aggTab === 'week'}
              className={`timesheets__agg-btn${aggTab === 'week' ? ' is-active' : ''}`}
              onClick={() => setAggTab('week')}
            >
              Week-wise
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={aggTab === 'day'}
              className={`timesheets__agg-btn${aggTab === 'day' ? ' is-active' : ''}`}
              onClick={() => setAggTab('day')}
            >
              Day-wise
            </button>
          </div>
        </div>

        {aggTab === 'month' && (
          <Table
            columns={[
              { key: 'label', header: 'Month' },
              { key: 'workingDays', header: 'Working Days' },
              { key: 'formattedHours', header: 'Total Hours' },
              { key: 'approvedHours', header: 'Approved Hours' },
              { key: 'pendingHours', header: 'Pending Hours' },
              { key: 'status', header: 'Status' },
            ]}
            rows={breakdowns.monthRows}
            emptyMessage="No recorded hours found for the selected months."
            renderCell={(column, row) => {
              if (column.key === 'approvedHours') return formatHours(row.approvedHours);
              if (column.key === 'pendingHours') return formatHours(row.pendingHours);
              if (column.key === 'status') return <StatusTag status={row.status} tone={toneForStatus(row.status)} />;
              return row[column.key];
            }}
          />
        )}

        {aggTab === 'week' && (
          <Table
            columns={[
              { key: 'label', header: 'Week Period' },
              { key: 'workingDays', header: 'Working Days' },
              { key: 'formattedHours', header: 'Total Hours' },
              { key: 'approvedHours', header: 'Approved Hours' },
              { key: 'pendingHours', header: 'Pending Hours' },
              { key: 'status', header: 'Status' },
            ]}
            rows={breakdowns.weekRows}
            emptyMessage="No recorded hours found for the selected weeks."
            renderCell={(column, row) => {
              if (column.key === 'approvedHours') return formatHours(row.approvedHours);
              if (column.key === 'pendingHours') return formatHours(row.pendingHours);
              if (column.key === 'status') return <StatusTag status={row.status} tone={toneForStatus(row.status)} />;
              return row[column.key];
            }}
          />
        )}

        {aggTab === 'day' && (
          <>
            <Table
              columns={[
                { key: 'formattedDate', header: 'Date' },
                { key: 'dayOfWeek', header: 'Day' },
                { key: 'formattedHours', header: 'Hours Worked' },
                { key: 'description', header: 'Task / Description' },
                { key: 'status', header: 'Status' },
              ]}
              rows={pagedDays}
              emptyMessage="No days found in this range."
              renderCell={(column, row) => {
                if (column.key === 'status') {
                  if (row.isPending || row.status === 'Pending' || row.status === 'SUBMITTED') {
                    return <StatusTag status="Pending" tone="warning" />;
                  }
                  if (row.isApproved || row.status === 'Approved' || row.status === 'APPROVED') {
                    return <StatusTag status="Approved" tone="success" />;
                  }
                  if (row.hours > 0) {
                    return <StatusTag status="Logged" tone="neutral" />;
                  }
                  return <span className="timesheets__muted-text">—</span>;
                }
                return row[column.key];
              }}
            />
            {breakdowns.dayRows.length > 15 && (
              <div className="timesheets__pagination-bar">
                <span className="timesheets__pagination-info">
                  Showing {dayStart}–{dayEnd} of {breakdowns.dayRows.length} days
                </span>
                {totalDayPages > 1 && (
                  <MyHrPagination
                    currentPage={safeDayPage}
                    totalPages={totalDayPages}
                    onPageChange={setDayPage}
                  />
                )}
              </div>
            )}
          </>
        )}
      </div>

      <div className="timesheets__recent-ts-section">
        <h4>Submitted Timesheet Records</h4>
        <Table
          columns={[
            { key: 'period', header: 'Period' },
            { key: 'hours', header: 'Total Hours' },
            { key: 'submittedDate', header: 'Submitted on' },
            { key: 'status', header: 'Status' },
            { key: 'action', header: 'Action' },
          ]}
          rows={pagedRecords}
          emptyMessage="No timesheet records submitted by this employee yet."
          renderCell={(column, row) => {
            if (column.key === 'status') return <StatusTag status={row.status} tone={toneForStatus(row.status)} />;
            if (column.key !== 'action') return row[column.key];
            return (
              <div className="timesheets__entry-actions">
                <Button type="button" variant="secondary" onClick={() => onView(row)}>
                  View details
                </Button>
                <Button type="button" variant="secondary" onClick={() => onDownloadPdf(row)}>
                  Download PDF
                </Button>
              </div>
            );
          }}
        />
        {recentRows.length > 5 && (
          <div className="timesheets__pagination-bar">
            <span className="timesheets__pagination-info">
              Showing {recordsStart}–{recordsEnd} of {recentRows.length} records
            </span>
            {totalRecordsPages > 1 && (
              <MyHrPagination
                currentPage={safeRecordsPage}
                totalPages={totalRecordsPages}
                onPageChange={setRecordsPage}
              />
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function AllSubmissionsWorkspace({
  role,
  teamRows,
  query,
  setQuery,
  roleFilter,
  setRoleFilter,
  statusFilter,
  setStatusFilter,
  managers,
  managerFilter,
  setManagerFilter,
  periodFilter = 'ALL',
  setPeriodFilter,
  fromDateFilter = '',
  setFromDateFilter,
  toDateFilter = '',
  setToDateFilter,
  submissionsSort = 'NEWEST_SUBMITTED',
  setSubmissionsSort,
  resetFilters,
  hasFilters,
  onView,
}) {
  const [page, setPage] = useState(1);

  const availableRoles = useMemo(() => {
    const rolesFromRows = teamRows.map((r) => r.role || r.employeeRole).filter(Boolean);
    const defaults = role === 'COMPANY_ADMIN' ? ['EMPLOYEE', 'HR'] : [];
    return Array.from(new Set([...defaults, ...rolesFromRows])).sort();
  }, [teamRows, role]);

  const sortedRows = useMemo(() => {
    const list = [...teamRows];
    if (submissionsSort === 'NEWEST_SUBMITTED') {
      return list.sort((a, b) => new Date(b.submittedAt || b.createdAt || b.periodEndDate || 0) - new Date(a.submittedAt || a.createdAt || a.periodEndDate || 0));
    }
    if (submissionsSort === 'OLDEST_SUBMITTED') {
      return list.sort((a, b) => new Date(a.submittedAt || a.createdAt || a.periodEndDate || 0) - new Date(b.submittedAt || b.createdAt || b.periodEndDate || 0));
    }
    if (submissionsSort === 'PERIOD_NEWEST') {
      return list.sort((a, b) => (b.periodEndDate || '').localeCompare(a.periodEndDate || ''));
    }
    if (submissionsSort === 'PERIOD_OLDEST') {
      return list.sort((a, b) => (a.periodStartDate || '').localeCompare(b.periodStartDate || ''));
    }
    if (submissionsSort === 'HOURS_DESC') {
      return list.sort((a, b) => Number(b.totalHours || 0) - Number(a.totalHours || 0));
    }
    if (submissionsSort === 'HOURS_ASC') {
      return list.sort((a, b) => Number(a.totalHours || 0) - Number(b.totalHours || 0));
    }
    if (submissionsSort === 'NAME_ASC') {
      return list.sort((a, b) => (a.employee || '').localeCompare(b.employee || ''));
    }
    return list;
  }, [teamRows, submissionsSort]);

  useEffect(() => {
    setPage(1);
  }, [query, roleFilter, statusFilter, managerFilter, periodFilter, fromDateFilter, toDateFilter, submissionsSort]);

  // Period preset and Custom range both drive the same filter, so only one
  // can be in effect at a time - picking one disables (and clears) the other.
  const hasCustomRange = Boolean(fromDateFilter || toDateFilter);
  const hasPeriodPreset = Boolean(periodFilter && periodFilter !== 'ALL');

  const PAGE_SIZE = 10;
  const totalPages = Math.ceil(sortedRows.length / PAGE_SIZE) || 1;
  const safePage = Math.min(page, totalPages);
  const pagedRows = sortedRows.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const startItem = sortedRows.length === 0 ? 0 : (safePage - 1) * PAGE_SIZE + 1;
  const endItem = Math.min(safePage * PAGE_SIZE, sortedRows.length);

  return (
    <div className="timesheets__all-submissions">
<TimesheetFilterPopover active={hasFilters}>
      <div className={`timesheets__filters${role === 'COMPANY_ADMIN' ? ' timesheets__filters--admin' : ' timesheets__filters--hr'}`}>
        <div className="timesheets__filter-search">
          <FormLabel htmlFor="timesheetSearch">Search</FormLabel>
          <SearchInput
            id="timesheetSearch"
            name="timesheetSearch"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search employee or ID"
          />
        </div>
        <Dropdown
          name="timesheetPeriod"
          label="Period"
          value={periodFilter}
          disabled={hasCustomRange}
          onChange={(event) => setPeriodFilter?.(event.target.value)}
          options={[
            { value: 'ALL', label: 'All periods' },
            { value: 'CURRENT_MONTH', label: 'Current month' },
            { value: 'PREVIOUS_MONTH', label: 'Previous month' },
            { value: 'LAST_90_DAYS', label: 'Last 90 days' },
            { value: 'THIS_YEAR', label: 'This year' },
          ]}
        />
        <DateRangePicker
          label="Custom range"
          fromDate={fromDateFilter}
          toDate={toDateFilter}
          disabled={hasPeriodPreset}
          onChange={(start, end) => {
            setFromDateFilter?.(start);
            setToDateFilter?.(end);
            // Picking a custom range takes over from the Period preset - reset
            // it back to "All periods" so only one filter is ever in effect.
            if (start || end) setPeriodFilter?.('ALL');
          }}
        />
        {(role === 'COMPANY_ADMIN' || availableRoles.length > 1) && (
          <Dropdown
            name="timesheetRole"
            label="Role"
            value={roleFilter}
            onChange={(event) => setRoleFilter(event.target.value)}
            options={[
              { value: 'ALL', label: 'All roles' },
              ...availableRoles.map((r) => ({ value: r, label: r === 'EMPLOYEE' ? 'Employee' : r === 'HR' ? 'HR' : r })),
            ]}
          />
        )}
        <Dropdown
          name="timesheetStatus"
          label="Status"
          value={statusFilter === 'DRAFT' ? 'ALL' : statusFilter}
          onChange={(event) => setStatusFilter(event.target.value)}
          options={[
            { value: 'ALL', label: 'All statuses' },
            { value: 'SUBMITTED', label: 'Submitted' },
            { value: 'APPROVED', label: 'Approved' },
            { value: 'REJECTED', label: 'Rejected' },
          ]}
        />
        {(role === 'COMPANY_ADMIN' || (managers && managers.length > 1)) && (
          <Dropdown
            name="timesheetManager"
            label="Reporting manager"
            value={managerFilter}
            onChange={(event) => setManagerFilter(event.target.value)}
            options={[
              { value: 'ALL', label: 'All managers' },
              ...managers.map((manager) => ({ value: manager, label: manager })),
            ]}
          />
        )}
        <Dropdown
          name="timesheetSort"
          label="Sort by"
          value={submissionsSort}
          onChange={(event) => setSubmissionsSort?.(event.target.value)}
          options={[
            { value: 'NEWEST_SUBMITTED', label: 'Newest submitted' },
            { value: 'OLDEST_SUBMITTED', label: 'Oldest submitted' },
            { value: 'PERIOD_NEWEST', label: 'Period (newest first)' },
            { value: 'PERIOD_OLDEST', label: 'Period (oldest first)' },
            { value: 'HOURS_DESC', label: 'Highest hours' },
            { value: 'HOURS_ASC', label: 'Lowest hours' },
            { value: 'NAME_ASC', label: 'Employee name (A–Z)' },
          ]}
        />
        <Button
          type="button"
          variant="secondary"
          className="timesheets__clear-btn"
          onClick={resetFilters}
          disabled={!hasFilters}
        >
          Clear filters
        </Button>
      </div>
</TimesheetFilterPopover>

      <Table
        className="timesheets__team-table timesheets__submissions-table"
        columns={[
          { key: 'employeeId', header: 'Employee ID', width: '120px' },
          { key: 'employee', header: 'Employee', width: '160px' },
          { key: 'role', header: 'Role', width: '110px' },
          { key: 'manager', header: 'Reporting manager', width: '160px' },
          { key: 'period', header: 'Period', width: '150px' },
          { key: 'hours', header: 'Hours', width: '100px' },
          { key: 'status', header: 'Status', width: '120px' },
          { key: 'action', header: 'Action', width: '140px' },
        ]}
        rows={pagedRows}
        emptyMessage={role === 'HR' ? 'No direct-report timesheets found.' : 'No organization timesheets found.'}
        renderCell={(column, row) => {
          if (column.key === 'status') return <StatusTag status={row.status} tone={toneForStatus(row.status)} />;
          if (column.key !== 'action') return row[column.key];
          return (
            <div className="timesheets__entry-actions">
              <Button type="button" variant="secondary" onClick={() => onView(row)}>
                View details
              </Button>
            </div>
          );
        }}
      />
      {sortedRows.length > 0 && (
        <div className="timesheets__pagination-bar">
          <span className="timesheets__pagination-info">
            Showing {startItem}–{endItem} of {sortedRows.length} submission{sortedRows.length === 1 ? '' : 's'}
          </span>
          {totalPages > 1 && (
            <MyHrPagination
              currentPage={safePage}
              totalPages={totalPages}
              onPageChange={setPage}
            />
          )}
        </div>
      )}
    </div>
  );
}

function EntryList({ entries }) {
  return (
    <Table
      className="timesheets__entries-table"
      columns={[
        { key: 'workDate', header: 'Date', width: '120px' },
        { key: 'dayOfWeek', header: 'Day', width: '120px' },
        { key: 'hoursWorked', header: 'Hours', width: '90px' },
        { key: 'entryType', header: 'Type', width: '110px' },
        { key: 'description', header: 'Description' },
      ]}
      rows={entries}
      emptyMessage="No entries recorded for this day."
      renderCell={(column, row) => {
        if (column.key === 'workDate') return formatWorkDate(row.workDate);
        if (column.key === 'hoursWorked') return formatHours(row.hoursWorked);
        if (column.key === 'entryType') return entryTypeLabel(row.entryType);
        return row[column.key] || 'No description';
      }}
    />
  );
}

function TimesheetDetails({ record, canReview, reviewingId, onReview, onReject, onClose, onDownload, onDownloadPeriodPdf, onResubmit }) {
  const [comment, setComment] = useState('');
  const isReviewable = canReview && record?.status === 'SUBMITTED' && Boolean(record?.id) && !record.id.startsWith('period-');
  const isReviewing = reviewingId === record?.id;

  useEffect(() => { setComment(''); }, [record?.id]);

  return <Modal
    isOpen={Boolean(record)}
    title={record ? `Timesheet details - ${formatPeriod(record.periodStartDate, record.periodEndDate)}` : ''}
    onClose={onClose}
    className="timesheets__details-modal"
    footer={<div className="timesheets__entry-actions">
      {isReviewable && <>
        <Button type="button" variant="secondary" disabled={isReviewing} onClick={() => onReject(record)}>Reject</Button>
        <Button type="button" variant="primary" disabled={isReviewing} onClick={() => onReview('APPROVED', comment)}>{isReviewing ? 'Saving...' : 'Approve'}</Button>
      </>}
      {record?.status === 'REJECTED' && onResubmit && (
        <Button type="button" variant="primary" onClick={() => onResubmit(record)}>
          Resubmit timesheet
        </Button>
      )}
      {record?.id?.startsWith('period-') && (
        <Button
          type="button"
          variant="primary"
          onClick={() => onDownloadPeriodPdf?.(record.periodStartDate, record.periodEndDate)}
        >
          Download Period PDF
        </Button>
      )}
      <Button type="button" variant="secondary" onClick={onClose}>Close</Button>
    </div>}
  >{record && <div className="timesheets__record-details">
    {record.status === 'REJECTED' && (
      <div className="timesheets__rejected-modal-notice" role="alert">
        <span className="timesheets__rejected-modal-icon" aria-hidden="true">⚠️</span>
        <div>
          <strong>Timesheet Rejected</strong>
          <p>{record.reviewComment ? `Review note: "${record.reviewComment}"` : 'Your manager requested changes before this timesheet can be approved.'}</p>
          <small>Please revise the recorded hours and resubmit for approval.</small>
        </div>
      </div>
    )}
    <dl>
      <div><dt>Employee</dt><dd>{record.employeeName || record.employeeId || 'Unknown employee'}</dd></div>
      <div><dt>Role</dt><dd>{record.employeeRole || 'Employee'}</dd></div>
      <div><dt>Reporting manager</dt><dd>{record.reportingManagerName || record.reportingManagerLoginId || 'Assigned manager'}</dd></div>
      <div><dt>Period</dt><dd>{formatPeriod(record.periodStartDate, record.periodEndDate)}</dd></div>
      <div><dt>Total hours</dt><dd>{formatHours(record.totalHours)}</dd></div>
      <div><dt>Status</dt><dd><StatusTag status={record.status} tone={toneForStatus(record.status)} /></dd></div>
      <div><dt>Supporting document{record.supportingDocuments?.length > 1 ? 's' : ''}</dt><dd>
        {record.supportingDocuments?.length ? (
          <div className="timesheets__drawer-documents">
            {record.supportingDocuments.map((doc) => (
              <Button key={doc.id} type="button" variant="secondary" title={doc.fileName} onClick={() => onDownload?.(doc.id, doc.fileName)}>
                <span className="timesheets__drawer-document-name">{doc.fileName || 'Download'}</span>
              </Button>
            ))}
          </div>
        ) : 'None attached'}
      </dd></div>
      {record.changeReason && <div><dt>Reason for change</dt><dd>{record.changeReason}</dd></div>}
      {record.reviewedAt && <div><dt>Reviewed</dt><dd>{formatDateTime(record.reviewedAt)}</dd></div>}
      {record.reviewComment && <div><dt>Review note</dt><dd>{record.reviewComment}</dd></div>}
    </dl>
    {isReviewable && <div className="timesheets__review-note"><TextInput name="review-comment" label="Review note (optional)" value={comment} onChange={(event) => setComment(event.target.value)} /></div>}
    <EntryList entries={record.entries || []} />
    <SupportingDocumentList documents={record.supportingDocuments} />
  </div>}</Modal>;
}

function ReeditConfirmationModal({ record, selectedStart, selectedEnd, onConfirm, onClose }) {
  if (!record) return null;
  const isDifferentRange = selectedStart && selectedEnd && (record.periodStartDate !== selectedStart || record.periodEndDate !== selectedEnd);
  const statusLabel = record.status === 'REJECTED'
    ? 'rejected'
    : record.status === 'APPROVED'
    ? 'approved'
    : 'submitted';

  const modalTitle = isDifferentRange
    ? "Timesheet Conflict: Period Already On File"
    : record.status === 'REJECTED'
    ? "Timesheet Conflict: Period Previously Rejected"
    : record.status === 'APPROVED'
    ? "Timesheet Conflict: Period Previously Approved"
    : "Re-enter Submitted Timesheet?";

  const entriesToShow = (record.conflictedEntries && record.conflictedEntries.length > 0)
    ? record.conflictedEntries
    : (record.entries || []);

  return <Modal
    isOpen={Boolean(record)}
    title={modalTitle}
    onClose={onClose}
    footer={<div className="timesheets__entry-actions">
      <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
      <Button type="button" variant="primary" onClick={onConfirm}>Confirm re-edit</Button>
    </div>}
  >
    <div className="timesheets__reedit-modal">
      <div className="timesheets__reedit-warning">
        <span className="timesheets__reedit-banner-icon" aria-hidden="true">⚠️</span>
        <div>
          <strong>
            {isDifferentRange
              ? `Timesheet already ${statusLabel} for overlapping period`
              : `Timesheet already ${statusLabel} for this period`}
          </strong>
          <p>
            {isDifferentRange ? (
              <>The dates you selected (<strong>{formatPeriod(selectedStart, selectedEnd)}</strong>) overlap with an existing {statusLabel} timesheet for <strong>{formatPeriod(record.periodStartDate, record.periodEndDate)}</strong> with <strong>{formatHours(record.totalHours)}</strong> total.</>
            ) : (
              <>A timesheet for <strong>{formatPeriod(record.periodStartDate, record.periodEndDate)}</strong> was previously {statusLabel} with <strong>{formatHours(record.totalHours)}</strong> total.</>
            )}
          </p>
        </div>
      </div>

      <div className="timesheets__reedit-dates-section">
        <span className="timesheets__reedit-dates-title">Dates & hours currently recorded:</span>
        <div className="timesheets__reedit-entries-table">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Day</th>
                <th>Hours</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              {entriesToShow.map((entry) => (
                <tr key={entry.workDate}>
                  <td>{formatWorkDate(entry.workDate)}</td>
                  <td>{entry.dayOfWeek || parseDateKey(entry.workDate).toLocaleDateString('en-US', { weekday: 'short' })}</td>
                  <td><strong>{formatHours(entry.hoursWorked)}</strong></td>
                  <td>{entry.description || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="timesheets__reedit-recommendation">
        <strong>Re-entry guidance:</strong>
        <p>
          Re-editing this timesheet will reopen it as a draft so you can update or replace the recorded hours.
        </p>
        <p>
          • <strong>Reason for change</strong> is required before submitting your corrections.
        </p>
        <p>
          • <strong>Mandatory document</strong>: Uploading your <strong>approved changed timesheet</strong> (e.g. manager approval email or signed timesheet) is mandatory before submitting your corrections.
        </p>
      </div>
    </div>
  </Modal>;
}

function RejectTimesheetModal({ record, isSubmitting, onConfirm, onClose }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    setReason('');
    setError('');
  }, [record?.id]);

  if (!record) return null;

  const handleSubmit = (e) => {
    e?.preventDefault();
    if (!reason.trim()) {
      setError('Please provide a reason for rejecting this timesheet.');
      return;
    }
    setError('');
    onConfirm(record, reason.trim());
  };

  return (
    <Modal
      isOpen={Boolean(record)}
      title="Reject Timesheet"
      onClose={onClose}
      footer={
        <div className="timesheets__entry-actions">
          <Button
            type="button"
            variant="secondary"
            disabled={isSubmitting}
            onClick={onClose}
          >
            Cancel
          </Button>
          <Button
            type="button"
            variant="primary"
            disabled={isSubmitting}
            onClick={handleSubmit}
          >
            {isSubmitting ? 'Rejecting...' : 'Confirm Rejection'}
          </Button>
        </div>
      }
    >
      <form className="timesheets__reject-modal" onSubmit={handleSubmit}>
        <div className="timesheets__reject-modal-header">
          <span className="timesheets__reject-modal-icon" aria-hidden="true">⚠️</span>
          <div>
            <strong>Reject timesheet for {record.employeeName || record.employeeId || 'Employee'}</strong>
            <p>
              Period: {formatPeriod(record.periodStartDate, record.periodEndDate)} ({formatHours(record.totalHours)})
            </p>
          </div>
        </div>

        <div className="timesheets__reject-field">
          <label htmlFor="rejection-reason">
            Reason for Rejection <span style={{ color: '#dc2626' }}>*</span>
          </label>
          <textarea
            id="rejection-reason"
            name="rejectionReason"
            rows={4}
            className={error ? 'is-invalid' : ''}
            placeholder="Explain why this timesheet cannot be approved and what the employee should adjust before resubmitting..."
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              if (error) setError('');
            }}
            disabled={isSubmitting}
            autoFocus
          />
          {error && <p className="timesheets__field-error" role="alert">{error}</p>}
          <small>This feedback will be sent directly to the employee so they can revise and resubmit.</small>
        </div>
      </form>
    </Modal>
  );
}



export function DocumentViewerModal({ doc, onClose, onError }) {
  const [blob, setBlob] = useState(null);
  const [originalFileName, setOriginalFileName] = useState('');
  const [preview, setPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [isExportingPdf, setIsExportingPdf] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);
  const [zoomLevel, setZoomLevel] = useState(100);
  const [pdfViewMode, setPdfViewMode] = useState('fit-width');
  const [showThumbnails, setShowThumbnails] = useState(false);

  useEffect(() => {
    if (!doc?.id) {
      setBlob(null);
      setOriginalFileName('');
      setPreview(null);
      setError('');
      setIsExpanded(false);
      setZoomLevel(100);
      setPdfViewMode('fit-width');
      setShowThumbnails(false);
      return;
    }

    let active = true;
    setLoading(true);
    setError('');

    documentApi.download(doc.id)
      .then(async (response) => {
        if (!active) return;
        const b = response.data;
        setBlob(b);
        const fileName = doc.fileName || '';
        setOriginalFileName(downloadFileName(response, fileName));
        const detected = await sniffBlobType(b, response, fileName);

        if (detected.kind === 'pdf') {
          const pdfBlob = b.type === 'application/pdf' ? b : new Blob([b], { type: 'application/pdf' });
          const url = URL.createObjectURL(pdfBlob);
          setPreview({ type: 'pdf', url });
        } else if (detected.kind === 'image') {
          const imgBlob = b.type && b.type.startsWith('image/') ? b : new Blob([b], { type: detected.type || 'image/png' });
          const url = URL.createObjectURL(imgBlob);
          setPreview({ type: 'image', url });
        } else if (detected.kind === 'text') {
          const text = typeof b.text === 'function' ? await b.text() : String(b || '');
          setPreview({ type: 'text', text });
        } else {
          setPreview({ type: 'generic' });
        }
      })
      .catch(async (err) => {
        if (!active) return;
        const msg = await extractErrorMessage(err);
        setError(msg || 'Failed to load document preview.');
        onError?.(msg);
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [doc?.id, doc?.fileName, onError]);

  useEffect(() => {
    return () => {
      if (preview?.url) {
        URL.revokeObjectURL(preview.url);
      }
    };
  }, [preview?.url]);

  const effectiveZoom = (pdfViewMode === 'fit-width' || pdfViewMode === 'fit-page') ? 100 : zoomLevel;

  const pdfViewerUrl = useMemo(() => {
    if (!preview?.url) return '';
    const navpanes = showThumbnails ? '1' : '0';
    const pagemode = showThumbnails ? 'thumbs' : 'none';
    let viewParam = 'view=FitH';
    if (pdfViewMode === 'fit-page') {
      viewParam = 'view=Fit';
    } else if (effectiveZoom !== 100) {
      viewParam = `zoom=${effectiveZoom}`;
    }
    return `${preview.url}#toolbar=1&navpanes=${navpanes}&pagemode=${pagemode}&${viewParam}`;
  }, [preview?.url, showThumbnails, pdfViewMode, effectiveZoom]);

  if (!doc) return null;

  async function handleDownloadPdf() {
    if (!blob) return;
    setIsExportingPdf(true);
    try {
      await downloadBlobAsPdf(blob, doc.fileName, {
        employeeName: doc.employeeName,
        period: doc.period,
        hours: doc.hours,
      }, (msg) => {
        setError(msg);
        onError?.(msg);
      });
    } finally {
      setIsExportingPdf(false);
    }
  }

  function handleDownloadOriginal() {
    if (!blob) return;
    saveBlob(blob, originalFileName || doc.fileName || 'supporting-document');
  }

  const modalClass = ['timesheets__doc-modal', isExpanded ? 'timesheets__doc-modal--expanded' : ''].filter(Boolean).join(' ');

  return (
    <Modal
      isOpen={Boolean(doc)}
      title={`Document Preview: ${doc.fileName || 'Supporting Document'}`}
      onClose={onClose}
      className={modalClass}
    >
      <div className="timesheets__doc-modal-content">
        {(doc.employeeName || doc.period || doc.hours) && (
          <div className="timesheets__doc-modal-meta">
            {doc.employeeName && <span><strong>Employee:</strong> {doc.employeeName}</span>}
            {doc.period && <span><strong>Period:</strong> {doc.period}</span>}
            {doc.hours && <span><strong>Hours:</strong> {doc.hours}</span>}
          </div>
        )}

        {!loading && !error && preview?.type === 'pdf' && (
          <div className="timesheets__doc-view-toolbar" role="toolbar" aria-label="PDF display adjustments">
            <div className="timesheets__doc-view-group">
              <span className="timesheets__doc-view-label">Fit / Zoom:</span>
              <button
                type="button"
                className={`timesheets__doc-view-btn ${pdfViewMode === 'fit-width' ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => { setPdfViewMode('fit-width'); setZoomLevel(100); }}
                title="Fit page width (removes horizontal scrolling and makes text legible)"
                aria-pressed={pdfViewMode === 'fit-width'}
              >
                Fit Width
              </button>
              <button
                type="button"
                className={`timesheets__doc-view-btn ${pdfViewMode === 'fit-page' ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => { setPdfViewMode('fit-page'); setZoomLevel(100); }}
                title="Fit entire page vertically"
                aria-pressed={pdfViewMode === 'fit-page'}
              >
                Fit Page
              </button>
              <button
                type="button"
                className={`timesheets__doc-view-btn ${pdfViewMode === 'custom' && zoomLevel === 100 ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => { setPdfViewMode('custom'); setZoomLevel(100); }}
                title="Standard 100% zoom"
                aria-pressed={pdfViewMode === 'custom' && zoomLevel === 100}
              >
                100%
              </button>
              <button
                type="button"
                className={`timesheets__doc-view-btn ${pdfViewMode === 'custom' && zoomLevel === 125 ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => { setPdfViewMode('custom'); setZoomLevel(125); }}
                title="Zoom in 125%"
                aria-pressed={pdfViewMode === 'custom' && zoomLevel === 125}
              >
                125%
              </button>
              <button
                type="button"
                className={`timesheets__doc-view-btn ${pdfViewMode === 'custom' && zoomLevel === 150 ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => { setPdfViewMode('custom'); setZoomLevel(150); }}
                title="Zoom in 150%"
                aria-pressed={pdfViewMode === 'custom' && zoomLevel === 150}
              >
                150%
              </button>
            </div>
            <div className="timesheets__doc-view-group">
              <button
                type="button"
                className={`timesheets__doc-view-btn ${isExpanded ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => setIsExpanded((prev) => !prev)}
                title={isExpanded ? 'Switch to standard wide view' : 'Expand modal to full window width'}
                aria-pressed={isExpanded}
              >
                {isExpanded ? '⤢ Standard Width' : '⤢ Full Width'}
              </button>
              <button
                type="button"
                className={`timesheets__doc-view-btn ${showThumbnails ? 'timesheets__doc-view-btn--active' : ''}`}
                onClick={() => setShowThumbnails((prev) => !prev)}
                title="Toggle page thumbnails sidebar"
                aria-pressed={showThumbnails}
              >
                {showThumbnails ? 'Hide Thumbnails' : 'Thumbnails'}
              </button>
              <a
                href={preview.url}
                target="_blank"
                rel="noopener noreferrer"
                className="timesheets__doc-view-btn timesheets__doc-view-btn--link"
                title="Open PDF in a new browser tab for full native browser controls"
                onClick={(event) => {
                  window.open(preview.url, '_blank');
                  event.preventDefault();
                }}
              >
                Open in new tab ↗
              </a>
            </div>
          </div>
        )}

        {loading && (
          <div className="timesheets__doc-loading" role="status">
            <span className="timesheets__spinner" aria-hidden="true" />
            <p>Loading document preview...</p>
          </div>
        )}

        {error && (
          <div className="timesheets__doc-error" role="alert">
            <p>{error}</p>
            <Button type="button" variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        )}

        {!loading && !error && (
          <div className="timesheets__doc-preview-area">
            {preview?.type === 'pdf' && (
              <div className="timesheets__doc-pdf-wrap">
                <div
                  className="timesheets__doc-zoom-stage"
                  style={{
                    width: effectiveZoom === 100 ? '100%' : `${effectiveZoom}%`,
                    minWidth: effectiveZoom === 100 ? '100%' : `${effectiveZoom}%`,
                    height: effectiveZoom === 100 ? '100%' : `${effectiveZoom}%`,
                    minHeight: effectiveZoom === 100 ? '100%' : `${Math.round(520 * (effectiveZoom / 100))}px`,
                    transition: 'width 0.15s ease, min-width 0.15s ease',
                  }}
                >
                  <iframe
                    key={`${preview?.url || ''}-${pdfViewMode}-${effectiveZoom}-${showThumbnails}`}
                    title="Document Preview"
                    src={pdfViewerUrl}
                    className="timesheets__doc-iframe"
                    style={{
                      width: '100%',
                      height: '100%',
                      minHeight: effectiveZoom === 100 ? 520 : `${Math.round(520 * (effectiveZoom / 100))}px`,
                    }}
                  />
                </div>
              </div>
            )}

            {preview?.type === 'image' && (
              <div className="timesheets__doc-img-wrap">
                <img
                  src={preview.url}
                  alt={doc.fileName || 'Supporting Document'}
                  className="timesheets__doc-img"
                  onError={() => {
                    setError('Unable to display this file as an image. Download the original file or try Download as PDF.');
                    setPreview(null);
                  }}
                />
              </div>
            )}

            {preview?.type === 'text' && (
              <div className="timesheets__doc-text-wrap">
                <pre className="timesheets__doc-text">{preview.text}</pre>
              </div>
            )}

            {preview?.type === 'generic' && (
              <div className="timesheets__doc-generic-wrap">
                <span className="timesheets__doc-generic-icon" aria-hidden="true">📄</span>
                <strong>{doc.fileName || 'Attached document'}</strong>
                <p>Preview is not directly available in-browser for this file format. You can download it as a PDF or in its original format below.</p>
              </div>
            )}
          </div>
        )}

        <div className="timesheets__doc-modal-footer">
          <Button
            type="button"
            variant="primary"
            disabled={loading || !blob || isExportingPdf}
            onClick={handleDownloadPdf}
            className="timesheets__doc-btn-pdf"
          >
            {isExportingPdf ? 'Saving as PDF...' : 'Download as PDF'}
          </Button>
          <Button
            type="button"
            variant="secondary"
            disabled={loading || !blob}
            onClick={handleDownloadOriginal}
          >
            Download original
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function SupportingDocumentList({ documents, onRemove }) {
  const list = (documents || []).filter((doc) => doc?.id);
  if (!list.length) return null;
  return (
    <div className="timesheets__supporting-documents">
      {list.map((doc) => (
        <div className="timesheets__supporting-document-row" key={doc.id}>
          <SupportingDocument document={doc} />
          {onRemove && (
            <button type="button" className="timesheets__supporting-document-remove" aria-label={`Remove ${doc.fileName || 'document'}`} onClick={() => onRemove(doc)}>
              Remove
            </button>
          )}
        </div>
      ))}
    </div>
  );
}

function SupportingDocument({ document }) {
  const [preview, setPreview] = useState(null);
  const [zoom, setZoom] = useState(1);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [isExportingPdf, setIsExportingPdf] = useState(false);

  useEffect(() => {
    setPreview(null);
    setZoom(1);
    setError('');
  }, [document?.id]);
  useEffect(() => () => { if (preview?.url) URL.revokeObjectURL(preview.url); }, [preview]);
  if (!document?.id) return null;

  async function view() {
    setLoading(true);
    setError('');
    setZoom(1);
    try {
      const response = await documentApi.download(document.id);
      const blob = response.data;
      const detected = await sniffBlobType(blob, response, document.fileName || '');

      if (detected.kind === 'pdf') {
        const pdfBlob = blob.type === 'application/pdf' ? blob : new Blob([blob], { type: 'application/pdf' });
        setPreview({ url: URL.createObjectURL(pdfBlob), type: 'pdf' });
      } else if (detected.kind === 'image') {
        const imageBlob = blob.type && blob.type.startsWith('image/') ? blob : new Blob([blob], { type: detected.type || 'image/png' });
        setPreview({ url: URL.createObjectURL(imageBlob), type: 'image' });
      } else if (detected.kind === 'text') {
        const text = typeof blob.text === 'function' ? await blob.text() : String(blob || '');
        setPreview({ text, type: 'text' });
      } else {
        saveBlob(blob, document.fileName || 'timesheet-document');
      }
    } catch (failure) {
      const message = await extractErrorMessage(failure);
      setError(message);
    } finally {
      setLoading(false);
    }
  }

  async function handleDownloadPdf() {
    setIsExportingPdf(true);
    try {
      const response = await documentApi.download(document.id);
      await downloadBlobAsPdf(response.data, document.fileName, {}, setError);
    } catch (failure) {
      const message = await extractErrorMessage(failure);
      setError(message);
    } finally {
      setIsExportingPdf(false);
    }
  }

  return <section className="timesheets__attachment" aria-label="Attached timesheet document">
    <div className="timesheets__attachment-header">
      <span className="timesheets__attachment-icon" aria-hidden="true">📎</span>
      <strong>Attached timesheet: {document.fileName || 'Supporting document'}</strong>
    </div>
    <div className="timesheets__attachment-actions">
      <Button type="button" variant="secondary" disabled={loading} onClick={view}>{loading ? 'Loading document...' : 'View document'}</Button>
      <Button type="button" variant="secondary" onClick={() => downloadSupportingDocument(document.id, document.fileName, setError)}>Download original</Button>
      <Button type="button" variant="secondary" disabled={isExportingPdf} onClick={handleDownloadPdf}>
        {isExportingPdf ? 'Saving as PDF...' : 'Download as PDF'}
      </Button>
    </div>
    {error && <p role="alert">{error}</p>}
    {preview && (
      <div className="timesheets__attachment-preview">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.5rem' }}>
          <div className="wp-inline-zoom-toolbar" role="toolbar" aria-label="Preview zoom">
            <button
              type="button"
              className="wp-inline-zoom-btn"
              onClick={() => setZoom((z) => Math.max(0.5, Math.round((z - 0.25) * 100) / 100))}
              disabled={zoom <= 0.5}
              title="Zoom out (-)"
              aria-label="Zoom out"
            >
              −
            </button>
            <button
              type="button"
              className="wp-inline-zoom-label"
              onClick={() => setZoom(1)}
              title="Reset zoom to 100%"
              aria-label="Reset zoom to 100%"
            >
              {Math.round(zoom * 100)}%
            </button>
            <button
              type="button"
              className="wp-inline-zoom-btn"
              onClick={() => setZoom((z) => Math.min(3.0, Math.round((z + 0.25) * 100) / 100))}
              disabled={zoom >= 3.0}
              title="Zoom in (+)"
              aria-label="Zoom in"
            >
              +
            </button>
          </div>
          <span style={{ fontSize: '0.75rem', color: '#94a3b8' }}>
            Tip: Double-tap or use buttons to zoom
          </span>
        </div>

        <div
          className="timesheets__preview-viewport"
          style={{
            transform: `scale(${zoom})`,
            transformOrigin: 'top center',
            transition: 'transform 0.15s ease',
            overflow: 'auto',
            maxHeight: '75vh',
            width: '100%'
          }}
        >
          {preview.type === 'pdf' && (
            <iframe
              title="Timesheet document preview"
              src={preview.url}
              className="timesheets__preview-iframe"
            />
          )}
          {preview.type === 'image' && (
            <img
              alt="Attached timesheet"
              src={preview.url}
              className="timesheets__preview-image"
              onDoubleClick={() => setZoom((z) => (z > 1 ? 1 : 1.75))}
              onError={() => {
                setError('Unable to display this file as an image. Download the original file or try Download as PDF.');
                setPreview(null);
              }}
            />
          )}
          {preview.type === 'text' && (
            <pre className="timesheets__preview-text">{preview.text}</pre>
          )}
        </div>

        <div className="timesheets__preview-toolbar">
          {preview.url && (
            <a
              href={preview.url}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn--secondary timesheets__preview-link"
            >
              Open in new tab ↗
            </a>
          )}
          <Button type="button" variant="secondary" onClick={() => setPreview(null)}>
            Close preview
          </Button>
        </div>
      </div>
    )}
  </section>;
}


const TimesheetFilterAnchor = createContext(null);

function TimesheetFilterPopover({ children, active }) {
  const anchor = useContext(TimesheetFilterAnchor);
  const [open, setOpen] = useState(false);
  const root = useRef(null);
  const trigger = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const dismiss = (event) => {
      if (event.type === 'keydown') {
        if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); }
      } else if (!root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', dismiss);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', dismiss); };
  }, [open]);
  if (!anchor) return null;
  return createPortal(<div className="timesheets__filter-popover" ref={root} onMouseEnter={() => setOpen(true)} onMouseLeave={() => { if (!root.current?.contains(document.activeElement)) setOpen(false); }}>
    <button ref={trigger} type="button" className={`timesheets__subtab-btn timesheets__filter-trigger${active ? ' has-filters' : ''}`} aria-expanded={open} aria-controls="timesheet-filter-panel" onClick={() => setOpen(true)}><svg className="timesheets__filter-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 4h18l-7 8v7l-4 2V12L3 4Z" /></svg>Filters {active && <span className="timesheets__filter-dot" aria-label="Filters applied" />}</button>
    {open && <section id="timesheet-filter-panel" className="timesheets__filter-panel" aria-label="Timesheet filters">
      <header><strong>Filter timesheets</strong><button type="button" aria-label="Close filters" onClick={() => { setOpen(false); trigger.current?.focus(); }}>Close</button></header>
      {children}
    </section>}
  </div>, anchor);
}