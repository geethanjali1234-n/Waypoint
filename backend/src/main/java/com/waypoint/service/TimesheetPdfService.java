package com.waypoint.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a My HR timesheet to a downloadable PDF. Layout mirrors the on-screen
 * review: a header with period and metadata, then the daily-entry table.
 */
@Service
public class TimesheetPdfService {

    private static final Color INK = new Color(0x1F, 0x2D, 0x3D);
    private static final Color MUTED = new Color(0x55, 0x5F, 0x6D);
    private static final Color HEADER_FILL = new Color(0x2F, 0x6F, 0x57);
    private static final Color BORDER = new Color(0xD0, 0xD5, 0xDD);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    public byte[] render(TimesheetResponse timesheet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 54, 54);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(title("Timesheet"));
            document.add(subtitle(formatDate(timesheet.periodStartDate())
                    + "  –  " + formatDate(timesheet.periodEndDate())));

            document.add(metadataTable(timesheet));
            document.add(entriesTable(timesheet));
            document.add(footer());

            document.close();
        } catch (RuntimeException exception) {
            throw new BusinessException("The timesheet PDF could not be generated");
        }
        return out.toByteArray();
    }

    public String fileName(TimesheetResponse timesheet) {
        String start = timesheet.periodStartDate() == null ? "period" : timesheet.periodStartDate().toString();
        String end = timesheet.periodEndDate() == null ? "" : "_" + timesheet.periodEndDate();
        return "timesheet_" + start + end + ".pdf";
    }

    private Paragraph title(String text) {
        Paragraph paragraph = new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK));
        paragraph.setSpacingAfter(4f);
        return paragraph;
    }

    private Paragraph subtitle(String text) {
        Paragraph paragraph = new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA, 11, MUTED));
        paragraph.setSpacingAfter(16f);
        return paragraph;
    }

    private PdfPTable metadataTable(TimesheetResponse timesheet) {
        Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, MUTED);
        Font value = FontFactory.getFont(FontFactory.HELVETICA, 11, INK);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingAfter(18f);
        addMetadata(table, label, value, "Employee", orDash(timesheet.employeeName()));
        addMetadata(table, label, value, "Employee ID", orDash(timesheet.employeeId()));
        String managerDisplay = timesheet.reportingManagerName() != null && !timesheet.reportingManagerName().isBlank()
                ? timesheet.reportingManagerName()
                : timesheet.reportingManagerLoginId();
        addMetadata(table, label, value, "Reporting manager", orDash(managerDisplay));
        addMetadata(table, label, value, "Status",
                timesheet.status() == null ? "-" : timesheet.status().name());
        addMetadata(table, label, value, "Total hours", formatHours(timesheet.totalHours()));
        addMetadata(table, label, value, "Submitted",
                timesheet.submittedAt() == null ? "Not submitted" : timesheet.submittedAt().format(DATE_TIME));
        return table;
    }

    private void addMetadata(PdfPTable table, Font labelFont, Font valueFont, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setPaddingBottom(8f);
        cell.addElement(new Paragraph(label.toUpperCase(), labelFont));
        cell.addElement(new Paragraph(value, valueFont));
        table.addCell(cell);
    }

    private PdfPTable entriesTable(TimesheetResponse timesheet) {
        Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);

        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.6f, 1.1f, 4f});
        table.setWidthPercentage(100);

        for (String header : List.of("Date", "Day", "Hours", "Description")) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headFont));
            cell.setBackgroundColor(HEADER_FILL);
            cell.setBorderColor(HEADER_FILL);
            cell.setPadding(6f);
            table.addCell(cell);
        }

        List<TimesheetEntryResponse> entries =
                timesheet.entries() == null ? List.of() : timesheet.entries();
        if (entries.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No entries recorded.", cellFont));
            empty.setColspan(4);
            empty.setPadding(6f);
            empty.setBorderColor(BORDER);
            table.addCell(empty);
            return table;
        }

        for (TimesheetEntryResponse entry : entries) {
            table.addCell(bodyCell(formatDate(entry.workDate()), cellFont));
            table.addCell(bodyCell(orDash(entry.dayOfWeek()), cellFont));
            table.addCell(bodyCell(formatHours(entry.hoursWorked()), cellFont));
            table.addCell(bodyCell(orDash(entry.description()), cellFont));
        }
        return table;
    }

    private PdfPCell bodyCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(6f);
        cell.setBorderColor(BORDER);
        return cell;
    }

    private Paragraph footer() {
        Paragraph paragraph = new Paragraph(
                "Generated " + LocalDate.now().format(DATE) + " from Waypoint My HR.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, MUTED));
        paragraph.setSpacingBefore(18f);
        return paragraph;
    }

    private String formatDate(LocalDate date) {
        return date == null ? "-" : date.format(DATE);
    }

    private String formatHours(BigDecimal hours) {
        BigDecimal value = hours == null ? BigDecimal.ZERO : hours;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " h";
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
