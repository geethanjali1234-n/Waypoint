package com.waypoint.service;

import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimesheetPdfServiceTest {

    private final TimesheetPdfService service = new TimesheetPdfService();

    private TimesheetResponse timesheet(List<TimesheetEntryResponse> entries, BigDecimal totalHours) {
        return new TimesheetResponse(
                "ts-1", "employee-1", "TD003", "Jordan Davis", UserRole.EMPLOYEE,
                "hr-1", "TD002", "HR Manager",
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13),
                entries, totalHours, List.of(),
                TimesheetStatus.SUBMITTED,
                LocalDateTime.of(2026, 9, 14, 9, 30), LocalDateTime.of(2026, 9, 7, 8, 0),
                LocalDateTime.of(2026, 9, 14, 9, 30), null, null, null, null);
    }

    @Test
    void rendersAPdfDocumentWithEntries() {
        byte[] pdf = service.render(timesheet(List.of(
                new TimesheetEntryResponse(LocalDate.of(2026, 9, 7), "MONDAY", new BigDecimal("8.00"), "Feature work"),
                new TimesheetEntryResponse(LocalDate.of(2026, 9, 8), "TUESDAY", new BigDecimal("7.50"), "Testing")),
                new BigDecimal("15.50")));

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(800);
    }

    @Test
    void rendersWhenThereAreNoEntries() {
        byte[] pdf = service.render(timesheet(List.of(), BigDecimal.ZERO));

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void fileNameIncludesThePeriod() {
        assertThat(service.fileName(timesheet(List.of(), BigDecimal.ZERO)))
                .isEqualTo("timesheet_2026-09-07_2026-09-13.pdf");
    }
}
