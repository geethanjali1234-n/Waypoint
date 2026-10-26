package com.waypoint.service;

import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TimesheetDocumentParserServiceTest {

    private TimesheetDocumentParserService parserService;
    private TimesheetPdfService pdfService;

    @BeforeEach
    void setUp() {
        parserService = new TimesheetDocumentParserService();
        pdfService = new TimesheetPdfService();
    }

    @Test
    void extractsUploadedScannedWeeklyDocumentAndChecksItsTotals() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(new TimesheetOcrService().isAvailable(), "Tesseract OCR binary is not installed on this system");
        try (var source = getClass().getResourceAsStream("/timesheets/scanned-weekly-september-2026.pdf")) {
            assertThat(source).isNotNull();
            var parsed = parserService.parse(source.readAllBytes(), "weekly.pdf", "uploaded-doc");
            assertThat(parsed.extractionStatus()).isEqualTo("PARTIAL");
            assertThat(parsed.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 8));
            assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2026, 9, 12));
            assertThat(parsed.totalHours()).isEqualByComparingTo("45.00");
            assertThat(parsed.entries()).hasSize(5);
            for (int index = 0; index < 5; index++) {
                assertThat(parsed.entries().get(index).workDate()).isEqualTo(LocalDate.of(2026, 9, 8).plusDays(index));
                assertThat(parsed.entries().get(index).hoursWorked()).isEqualByComparingTo("9.00");
            }
            assertThat(parsed.message()).contains("weekday labels conflict", "40 regular + 5 overtime");
        }
    }

    @Test
    void parsesDirectOcrTextPassedFromClient() {
        String ocrResult = """
                Timesheet Report
                Date         Hours   Activity
                2026-09-08   8.0     Design
                2026-09-09   8.0     Implementation
                2026-09-10   7.5     Testing
                """;
        var parsed = parserService.parse(new byte[0], "timesheet_scan.png", "doc-ocr", "MDY", ocrResult);
        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(parsed.totalHours()).isEqualByComparingTo("23.50");
        assertThat(parsed.entries()).hasSize(3);
    }

    @Test
    void supportsHeaderlessTsvAndRejectsNegativeTextHours() {
        assertThat(parse("2025-01-02\t7:30\tSupport", "MDY").totalHours()).isEqualByComparingTo("7.5");
        assertThat(parse("2025-01-02 -8 hours", "MDY").entries()).isEmpty();
    }

    @Test
    void autoDetectsUnambiguousDayFirstDatesEvenWhenDateOrderDropdownIsLeftAtDefault() {
        // Document is written DD/MM/YYYY (21 can only be a day), but the caller left the
        // "Document date format" selector at its default MM/DD/YYYY (MDY). The day is
        // unambiguous, so it must still convert to the correct calendar date and submit
        // correctly instead of misreading day 21 as a month.
        var result = parse("21/09/2026,8,Support", "MDY");
        assertThat(result.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 21));

        // Same date written MM/DD/YYYY converts to the identical calendar date, so the
        // outcome doesn't depend on which of the two the source document actually used.
        assertThat(parse("09/21/2026,8,Support", "DMY").periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void honorsSelectedDateOrderOnlyWhenBothDatePartsAreAmbiguous() {
        // 03/04/2025 is a genuinely ambiguous date - both readings are valid calendar
        // dates, so the user's selected order is the only signal available.
        assertThat(parse("03/04/2025,8,Support", "DMY").periodStartDate()).isEqualTo(LocalDate.of(2025, 4, 3));
        assertThat(parse("03/04/2025,8,Support", "MDY").periodStartDate()).isEqualTo(LocalDate.of(2025, 3, 4));
    }

    @Test
    void parsesCsvDocumentAndCalculatesTotalsProperly() {
        String csv = """
                Date,Hours,Description
                2026-09-01,8.0,Feature development
                2026-09-02,7.5,Code review and testing
                2026-09-03,8.0,Sprint planning
                """;
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        ParsedTimesheetResponse response = parserService.parse(bytes, "timesheet.csv", "doc-123");

        assertThat(response.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(response.supportingDocumentId()).isEqualTo("doc-123");
        assertThat(response.supportingDocumentName()).isEqualTo("timesheet.csv");
        assertThat(response.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.periodEndDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(response.totalHours()).isEqualByComparingTo("23.50");
        assertThat(response.entries()).hasSize(3);

        TimesheetEntryResponse first = response.entries().get(0);
        assertThat(first.workDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(first.hoursWorked()).isEqualByComparingTo("8.00");
        assertThat(first.description()).isEqualTo("Feature development");
    }

    @Test
    void parsesTextDocumentWithVariedDateAndHourFormats() {
        String text = """
                Timesheet summary:
                Sep 1, 2026 - 8 hrs - Client kickoff
                Sep 2, 2026 - 8.00 h - Development
                Sep 3, 2026: 4:30 - Half day morning
                """;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        ParsedTimesheetResponse response = parserService.parse(bytes, "timesheet_notes.txt", "doc-456");

        assertThat(response.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(response.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.periodEndDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        // 8 + 8 + 4.5 = 20.50
        assertThat(response.totalHours()).isEqualByComparingTo("20.50");
    }

    @Test
    void parsesPdfTimesheetGeneratedBySystem() throws Exception {
        TimesheetResponse timesheet = new TimesheetResponse(
                "ts-1", "user-1", "EMP-001", "John Doe", com.waypoint.enums.UserRole.EMPLOYEE,
                "mgr-1", "MGR-001", "Jane Manager",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                List.of(
                        new TimesheetEntryResponse(LocalDate.of(2026, 9, 1), "Tue", BigDecimal.valueOf(8.0), "Project tasks"),
                        new TimesheetEntryResponse(LocalDate.of(2026, 9, 2), "Wed", BigDecimal.valueOf(8.0), "Documentation")
                ),
                BigDecimal.valueOf(16.0),
                List.of(new com.waypoint.dto.myhr.TimesheetSupportingDocumentResponse("doc-orig", "orig.pdf")),
                TimesheetStatus.SUBMITTED, LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(),
                null, null, null, null
        );

        byte[] pdfBytes = pdfService.render(timesheet);

        ParsedTimesheetResponse parsed = parserService.parse(pdfBytes, "timesheet_export.pdf", "doc-new");

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.supportingDocumentId()).isEqualTo("doc-new");
        assertThat(parsed.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(parsed.totalHours()).isEqualByComparingTo("16.00");
    }

    @Test
    void handlesUnparseableDocumentWithFallbackStatus() {
        String randomText = "This is a random document without dates and hours.";
        byte[] bytes = randomText.getBytes(StandardCharsets.UTF_8);

        ParsedTimesheetResponse response = parserService.parse(bytes, "notes.txt", "doc-789");

        assertThat(response.extractionStatus()).isEqualTo("FALLBACK");
        assertThat(response.supportingDocumentId()).isEqualTo("doc-789");
        assertThat(response.totalHours()).isEqualByComparingTo("0.00");
        assertThat(response.message()).contains("could not be automatically detected");
    }

    private ParsedTimesheetResponse parse(String text, String order) {
        return parserService.parse(text.getBytes(StandardCharsets.UTF_8), "hours.csv", "doc-1", order);
    }

    @Test
    void mapsReorderedColumnsAndQuotedDescriptionsInsteadOfProjectNumbers() {
        var result = parse("Project,Description,Hours,Work Date\n123,\"Review, project 2\",7.5,2025-01-02", "MDY");
        assertThat(result.totalHours()).isEqualByComparingTo("7.5");
        assertThat(result.entries().getFirst().description()).isEqualTo("Review, project 2");
    }

    @Test
    void sumsDailyRowsAndRegularOvertimeWithoutDoubleCountingTotal() {
        var result = parse("Date;Regular Hours;Overtime Hours;Total Hours\n02/01/2025;6;1;7\n02/01/2025;1;0;1", "DMY");
        assertThat(result.periodStartDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(result.totalHours()).isEqualByComparingTo("8");
        assertThat(parse("Date;Regular;Overtime\n2025-01-02;7;1", "MDY").totalHours()).isEqualByComparingTo("8");
    }

    @Test
    void reportsInvalidRowsAndDoesNotInterpretClockInAsHours() {
        var result = parse("Date,Start,End,Hours\n2025-01-02,09:00,17:00,8\n2025-01-03,09:00,17:00,-8", "MDY");
        assertThat(result.extractionStatus()).isEqualTo("PARTIAL");
        assertThat(result.totalHours()).isEqualByComparingTo("8");
        assertThat(parse("2025-01-02 09:00 17:00", "MDY").entries()).isEmpty();
        assertThat(parse("Date,Hours\n2025-02-30,8", "MDY").entries()).isEmpty();
    }

    @Test
    void neverTruncatesLongPeriodsOrInventsFallbackDates() {
        var result = parse("Date,Hours\n2025-01-01,8\n2025-02-01,8", "MDY");
        assertThat(result.extractionStatus()).isEqualTo("FALLBACK");
        assertThat(result.entries()).isEmpty();
        assertThat(result.periodStartDate()).isNull();
        assertThat(parse("Date,Hours\n2025-01-01,16\n2025-01-01,16", "MDY").entries()).isEmpty();
    }

    @Test
    void readsExcelDatesAndDurationCells() throws Exception {
        for (org.apache.poi.ss.usermodel.Workbook workbook : List.of(
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(), new org.apache.poi.hssf.usermodel.HSSFWorkbook())) {
            try (workbook; var output = new java.io.ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("Hours");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("Date");
                header.createCell(1).setCellValue("Hours");
                var row = sheet.createRow(1);
                var date = row.createCell(0);
                date.setCellValue(LocalDate.of(2025, 1, 2));
                var dateStyle = workbook.createCellStyle();
                dateStyle.setDataFormat(workbook.createDataFormat().getFormat("m/d/yyyy"));
                date.setCellStyle(dateStyle);
                row.createCell(1).setCellValue("7:30");
                workbook.write(output);
                String name = workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook ? "hours.xlsx" : "hours.xls";
                var result = parserService.parse(output.toByteArray(), name, "doc-1");
                assertThat(result.totalHours()).isEqualByComparingTo("7.5");
                assertThat(result.periodStartDate()).isEqualTo(LocalDate.of(2025, 1, 2));
            }
        }
    }

    @Test
    void readsWordTablesAndSafelyFallsBackForImagesOrCorruptFiles() throws Exception {
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var output = new java.io.ByteArrayOutputStream()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Date");
            table.getRow(0).getCell(1).setText("Hours");
            table.getRow(1).getCell(0).setText("2 January 2025");
            table.getRow(1).getCell(1).setText("8");
            document.write(output);
            assertThat(parserService.parse(output.toByteArray(), "hours.docx", "doc-1").totalHours()).isEqualByComparingTo("8");
        }
        for (String name : List.of("scan.png", "corrupt.pdf", "corrupt.xlsx")) {
            assertThat(parserService.parse(new byte[]{1, 2, 3}, name, "doc-1").extractionStatus()).isEqualTo("FALLBACK");
        }
    }

    @Test
    void extractsCalendarFormatFromOcrTextSimulatingPortalScreenshot() {
        String portalOcr = """
                timesheet.ultimatix.net/timesheetEntry/#/Home
                TCS Ultimatix Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                Supervisor Name : Pritiranjan Behera
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                40
                40
                40
                40
                40
                Assigned Task 0 hour
                SAP Security Projects
                Claim Task
                Download this month's Timesheet
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "timesheet_calendar.png", "doc-cal", "MDY", portalOcr);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(parsed.totalHours()).isEqualByComparingTo("168.00");
        assertThat(parsed.entries()).hasSize(31);

        // August 1, 2026 is Saturday (weekend -> 0h)
        assertThat(parsed.entries().get(0).workDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(parsed.entries().get(0).hoursWorked()).isEqualByComparingTo("0.00");
        assertThat(parsed.entries().get(0).dayOfWeek()).isEqualTo("Sat");

        // August 3, 2026 is Monday (weekday -> 8h)
        assertThat(parsed.entries().get(2).workDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(parsed.entries().get(2).hoursWorked()).isEqualByComparingTo("8.00");
        assertThat(parsed.entries().get(2).dayOfWeek()).isEqualTo("Mon");
        assertThat(parsed.entries().get(2).description()).isEqualTo("SAP Security Projects");

        assertThat(parsed.message()).contains("Extracted 21 working days", "168.00 hours", "August 2026", "SAP Security Projects");
    }

    @Test
    void extractsAugustEvenWhenDeadlineBannerMentionsSeptember() {
        String portalWithSeptemberBanner = """
                timesheet.ultimatix.net/timesheetEntry/#/Home
                Completion of Information Security Awareness courses is a mandatory requirement for accessing all modules in Ultimatix, including Timesheet.
                Timesheet entry enabled till 9th September 2026
                Geetha Gurram
                Employee ID : 3301908
                Project Name : TCS Apple SAP Security Projects (20911106)
                Supervisor Name : Pritiranjan Behera(713966)
                Employee IOU : IS-USWC-1.1
                Last Login: Sep 04,2026 11:59:22 PM IST
                < August 2026 >
                Timesheet Saved Successfully
                9th August 2026
                Current Allocation
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                8 8 8 8 8 0 0
                03 04 05 06 07 08 09 40
                8 8 8 8 8 0 0
                10 11 12 13 14 15 16 40
                8 8 8 8 8 0 0
                17 18 19 20 21 22 23 40
                8 8 8 8 8 0 0
                24 25 26 27 28 29 30 40
                8 8 8 8 8 0 0
                31 01 02 03 04 05 06 40
                8 8 8 8 8 0 0
                Assigned Task 0 0 hour
                SAP Security Projects
                (Apple_1.1_SAP_VG_ESU_AUS T&M - 20911106)
                0 0
                Claim Task
                Unassigned Task 0 0 hour
                Total : 0 hour
                Download this month's Timesheet
                Copy this day's efforts to other day/days
                Copy this day's efforts to the current week (07 Sep 2026 to 13 Sep 2026)
                Copy this day's efforts to the current month (till 9th September 2026)
                Build No : 1.17.0-18-8e9698f | Copyright 2026 Tata Consultancy Services
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "ultimatix.png", "doc-banner", "MDY", portalWithSeptemberBanner);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(parsed.totalHours()).isEqualByComparingTo("168.00");
        assertThat(parsed.entries()).hasSize(31);
        assertThat(parsed.message()).contains("Extracted 21 working days", "168.00 hours", "August 2026", "SAP Security Projects");
    }

    @Test
    void readsWeekdayHolidayAsZeroFromCalendarGridInsteadOfAssumingEightHours() {
        String portal = """
                Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                8 8 8 8 8 0 0
                03 04 05 06 07 08 09 40
                8 8 8 8 8 0 0
                10 11 12 13 14 15 16 40
                8 8 8 0 8 0 0
                17 18 19 20 21 22 23 40
                8 8 8 8 8 0 0
                24 25 26 27 28 29 30 40
                8 8 8 8 8 0 0
                31 01 02 03 04 05 06 40
                8 8 8 8 8 0 0
                Assigned Task
                SAP Security Projects
                Claim Task
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "holiday.png", "doc-holiday", "MDY", portal);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("160.00");
        TimesheetEntryResponse holiday = parsed.entries().get(12);
        assertThat(holiday.workDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(holiday.hoursWorked()).isEqualByComparingTo("0.00");
        assertThat(holiday.description()).isEmpty();
        assertThat(parsed.message()).contains("Extracted 20 working days", "160.00 hours");
    }

    @Test
    void readsHalfDayLeaveHoursFromCalendarGrid() {
        String portal = """
                Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                8 8 8 8 8 0 0
                03 04 05 06 07 08 09 40
                8 8 8 8 8 0 0
                10 11 12 13 14 15 16 40
                8 8 8 8 8 0 0
                17 18 19 20 21 22 23 40
                8 8 4 8 8 0 0
                24 25 26 27 28 29 30 40
                8 8 8 8 8 0 0
                31 01 02 03 04 05 06 40
                8 8 8 8 8 0 0
                Assigned Task
                SAP Security Projects
                Claim Task
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "halfday.png", "doc-halfday", "MDY", portal);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("164.00");
        TimesheetEntryResponse halfDay = parsed.entries().get(18);
        assertThat(halfDay.workDate()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(halfDay.hoursWorked()).isEqualByComparingTo("4.00");
        assertThat(halfDay.description()).isEqualTo("SAP Security Projects");
    }

    @Test
    void readsOvertimeHoursExceedingEightFromCalendarGrid() {
        String portal = """
                Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                8 8 8 8 8 0 0
                03 04 05 06 07 08 09 40
                8 10 8 8 8 0 0
                10 11 12 13 14 15 16 40
                8 8 8 8 8 0 0
                17 18 19 20 21 22 23 40
                8 8 8 8 8 0 0
                24 25 26 27 28 29 30 40
                8 8 8 8 8 0 0
                31 01 02 03 04 05 06 40
                8 8 8 8 8 0 0
                Assigned Task
                SAP Security Projects
                Claim Task
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "overtime.png", "doc-overtime", "MDY", portal);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("170.00");
        TimesheetEntryResponse overtime = parsed.entries().get(3);
        assertThat(overtime.workDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(overtime.hoursWorked()).isEqualByComparingTo("10.00");
    }

    @Test
    void readsSixDayWorkWeekWhereSaturdayHasHoursFromCalendarGrid() {
        String portal = """
                Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                8 8 8 8 8 0 0
                03 04 05 06 07 08 09 40
                8 8 8 8 8 0 0
                10 11 12 13 14 15 16 40
                8 8 8 8 8 0 0
                17 18 19 20 21 22 23 40
                8 8 8 8 8 0 0
                24 25 26 27 28 29 30 40
                8 8 8 8 8 4 0
                31 01 02 03 04 05 06 40
                8 8 8 8 8 0 0
                Assigned Task
                SAP Security Projects
                Claim Task
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "sixday.png", "doc-sixday", "MDY", portal);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("172.00");
        TimesheetEntryResponse saturday = parsed.entries().get(28);
        assertThat(saturday.workDate()).isEqualTo(LocalDate.of(2026, 8, 29));
        assertThat(saturday.dayOfWeek()).isEqualTo("Sat");
        assertThat(saturday.hoursWorked()).isEqualByComparingTo("4.00");
        assertThat(parsed.message()).contains("Extracted 22 working days");
    }

    @Test
    void ignoresPrintedWeeklyColumnAndUsesActualPartTimeGridHours() {
        String portal = """
                Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                6 6 6 6 6 0 0
                03 04 05 06 07 08 09 40
                6 6 6 6 6 0 0
                10 11 12 13 14 15 16 40
                6 6 6 6 6 0 0
                17 18 19 20 21 22 23 40
                6 6 6 6 6 0 0
                24 25 26 27 28 29 30 40
                6 6 6 6 6 0 0
                31 01 02 03 04 05 06 40
                6 6 6 6 6 0 0
                Assigned Task
                SAP Security Projects
                Claim Task
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "parttime.png", "doc-parttime", "MDY", portal);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("126.00");
        assertThat(parsed.message()).contains("Extracted 21 working days", "126.00 hours");
    }

    @Test
    void fallsBackToUniformWeekdayAssumptionWhenCalendarGridRowIsUnreadable() {
        String portal = """
                Timesheet
                Project Name : TCS Apple SAP Security Projects (20911106)
                August 2026
                Mo Tu We Th Fr Sa Su Weekly
                27 28 29 30 31 01 02 40
                8 8 8 8 8 0 0
                03 04 05 06 07 08 09 40
                8 8 8 8 8 0 0
                10 11 12 13 14 15 16 40
                8 8 8 O 8 0 0
                17 18 19 20 21 22 23 40
                8 8 8 8 8 0 0
                24 25 26 27 28 29 30 40
                8 8 8 8 8 0 0
                31 01 02 03 04 05 06 40
                8 8 8 8 8 0 0
                Assigned Task
                SAP Security Projects
                Claim Task
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "garbled.png", "doc-garbled", "MDY", portal);

        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("168.00");
        assertThat(parsed.entries()).hasSize(31);
        assertThat(parsed.message()).contains("Extracted 21 working days", "168.00 hours");
    }

    @Test
    void rejectsFutureMonthCalendarFormat() {
        String futurePortal = """
                Timesheet Entry
                October 2099
                Mo Tu We Th Fr Sa Su Weekly
                40 40 40 40
                Assigned Task
                Security Projects
                """;

        ParsedTimesheetResponse parsed = parserService.parse(new byte[0], "future.png", "doc-future", "MDY", futurePortal);
        assertThat(parsed.extractionStatus()).isEqualTo("FALLBACK");
        assertThat(parsed.message()).contains("in the future");
    }

    @Test
    void extractsCalendarTimesheetFromUserUploadedScreenshotIfPresent() throws Exception {
        java.io.File imageFile = new java.io.File("C:/Users/ultim/.gemini/antigravity-ide/brain/e8c9cd9e-09a1-479c-b94e-68d4836e150c/.user_uploaded/media_1789669526633.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(imageFile.exists() && new TimesheetOcrService().isAvailable(),
                "User uploaded screenshot or Tesseract OCR is not available");

        byte[] bytes = java.nio.file.Files.readAllBytes(imageFile.toPath());
        ParsedTimesheetResponse response = parserService.parse(bytes, "calendar_screenshot.png", "doc-screenshot");

        assertThat(response.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(response.periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(response.periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(response.totalHours()).isEqualByComparingTo("168.00");
        assertThat(response.entries()).hasSize(31);
        assertThat(response.message()).contains("21 working days", "168.00 hours", "August 2026");
    }

    @Test
    void fallsBackToClaudeVisionWhenLocalParsingCannotReadTheDocument() {
        ClaudeDocumentExtractionService claude = mock(ClaudeDocumentExtractionService.class);
        ParsedTimesheetResponse claudeResult = new ParsedTimesheetResponse(
                "doc-1", "scan.pdf", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
                new BigDecimal("8.00"),
                List.of(new TimesheetEntryResponse(LocalDate.of(2026, 9, 1), "Tue", new BigDecimal("8.00"), "")),
                "SUCCESS", "Extracted 1 day(s) using AI document reading.");
        when(claude.extract(any(), eq("scan.pdf"), eq("doc-1"), eq("MDY"))).thenReturn(Optional.of(claudeResult));
        TimesheetDocumentParserService service = new TimesheetDocumentParserService(claude);

        // An empty byte array trips the local "document could not be read" fallback immediately.
        ParsedTimesheetResponse response = service.parse(new byte[0], "scan.pdf", "doc-1", "MDY", null);

        assertThat(response).isEqualTo(claudeResult);
    }

    @Test
    void neverCallsClaudeWhenLocalParsingAlreadySucceeded() {
        ClaudeDocumentExtractionService claude = mock(ClaudeDocumentExtractionService.class);
        TimesheetDocumentParserService service = new TimesheetDocumentParserService(claude);
        String text = "Timesheet summary:\nSep 1, 2026 - 8 hrs - Client kickoff\n";

        ParsedTimesheetResponse response = service.parse(text.getBytes(StandardCharsets.UTF_8), "notes.txt", "doc-2", "MDY", null);

        assertThat(response.extractionStatus()).isEqualTo("SUCCESS");
        verifyNoInteractions(claude);
    }

    @Test
    void keepsTheLocalFallbackMessageWhenClaudeAlsoCannotReadIt() {
        ClaudeDocumentExtractionService claude = mock(ClaudeDocumentExtractionService.class);
        when(claude.extract(any(), any(), any(), any())).thenReturn(Optional.empty());
        TimesheetDocumentParserService service = new TimesheetDocumentParserService(claude);

        ParsedTimesheetResponse response = service.parse(new byte[0], "scan.pdf", "doc-3", "MDY", null);

        assertThat(response.extractionStatus()).isEqualTo("FALLBACK");
        assertThat(response.message()).contains("could not be automatically detected");
    }
}
