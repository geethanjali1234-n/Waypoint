package com.waypoint.controller;

import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.TimesheetPdfService;
import com.waypoint.service.TimesheetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyHrTimesheetController.class)
class MyHrTimesheetControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CurrentUserResolver currentUserResolver;

    @MockitoBean
    TimesheetService timesheetService;

    @MockitoBean
    TimesheetPdfService timesheetPdfService;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    com.waypoint.service.TimesheetDocumentParserService timesheetDocumentParserService;

    @MockitoBean
    com.waypoint.service.MyHrDocumentService myHrDocumentService;

    @Test
    void parsesUploadedTimesheetDocumentThroughHttp() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("employee-1");
        com.waypoint.dto.myhr.ParsedTimesheetResponse mockParsed = new com.waypoint.dto.myhr.ParsedTimesheetResponse(
                "doc-1", "company.csv", LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 2),
                new BigDecimal("8.00"), List.of(), "SUCCESS", "Extracted 1 days totaling 8.00 hours");
        when(myHrDocumentService.upload(eq("employee-1"), any(), any())).thenReturn(
                new com.waypoint.dto.myhr.DocumentUploadResponse("doc-1", "company.csv", "text/csv", 24L,
                        "TIMESHEET_SUPPORTING_DOCUMENT"));
        when(timesheetDocumentParserService.parse(any(), any(), any(), any(), any())).thenReturn(mockParsed);

        org.springframework.mock.web.MockMultipartFile mockFile = new org.springframework.mock.web.MockMultipartFile(
                "file", "company.csv", "text/csv", "Date,Hours\n2025-01-02,8".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/myhr/timesheets/parse-document")
                        .file(mockFile)
                        .param("dateOrder", "DMY")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.supportingDocumentId").value("doc-1"))
                .andExpect(jsonPath("$.data.periodStartDate").value("2025-01-02"))
                .andExpect(jsonPath("$.data.totalHours").value(8.00));
    }

    @Test
    void storageFailureDoesNotReturnSuccessfulExtraction() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("employee-1");
        when(myHrDocumentService.upload(eq("employee-1"), any(), any()))
                .thenThrow(new com.waypoint.exception.BusinessException("Document upload failed"));
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "company.csv", "text/csv", "Date,Hours\n2025-01-02,8".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/myhr/timesheets/parse-document").file(file)
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        org.mockito.Mockito.verifyNoInteractions(timesheetDocumentParserService);
    }

    @Test
    void listsOrganizationTimesheetsForAnAuthenticatedMyHrUser() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(timesheetService.listForUser("admin-1")).thenReturn(List.of(response("timesheet-1", TimesheetStatus.SUBMITTED)));

        mockMvc.perform(get("/api/myhr/timesheets").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("timesheet-1"))
                .andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
    }

    @Test
    void reviewsATimesheetThroughTheAdminApiContract() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(timesheetService.review(eq("admin-1"), eq("timesheet-1"), any()))
                .thenReturn(response("timesheet-1", TimesheetStatus.APPROVED));

        mockMvc.perform(patch("/api/myhr/timesheets/timesheet-1/review")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"comment\":\"Verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("timesheet-1"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    private TimesheetResponse response(String id, TimesheetStatus status) {
        return new TimesheetResponse(
                id,
                "employee-1",
                "TD001",
                "Employee One",
                null,
                "hr-1",
                "TD002",
                "Manager Two",
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 6),
                List.of(),
                BigDecimal.ZERO,
                List.of(),
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
