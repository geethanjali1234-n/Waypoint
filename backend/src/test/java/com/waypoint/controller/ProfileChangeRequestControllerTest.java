package com.waypoint.controller;

import com.waypoint.dto.myhr.CreateProfileChangeRequestRequest;
import com.waypoint.dto.myhr.ProfileChangeRequestDetailResponse;
import com.waypoint.dto.myhr.ProfileChangeRequestResponse;
import com.waypoint.dto.myhr.ReportingManagerSummary;
import com.waypoint.entity.ProfileChangeRequest;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.ProfileChangeRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProfileChangeRequestController.class)
class ProfileChangeRequestControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean ProfileChangeRequestService profileChangeRequestService;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean JwtService jwtService;

    @Test
    void returnsOrganizationRequestsWithRealEmployeeAndManagerDisplayInformation() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        ReportingManagerSummary manager = new ReportingManagerSummary("hr-1", "TD001", "Alex Morgan", null, null);
        ProfileChangeRequestResponse response = new ProfileChangeRequestResponse(
                "request-1", "employee-1", "profile-1", "hr-1",
                ProfileChangeField.PHONE_NUMBER, "(555) 010-1000", "(555) 010-9999",
                "Number changed", ProfileChangeStatus.PENDING, null, null,
                LocalDateTime.of(2026, 8, 25, 13, 10), null,
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 13, 10),
                "TD002", "Jordan Lee", manager, "EMPLOYEE");
        when(profileChangeRequestService.listForAdmin("admin-1")).thenReturn(List.of(response));

        mockMvc.perform(get("/api/myhr/profile-change-requests")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value("request-1"))
                .andExpect(jsonPath("$.data[0].requesterUserId").value("employee-1"))
                .andExpect(jsonPath("$.data[0].employeeId").value("TD002"))
                .andExpect(jsonPath("$.data[0].employeeName").value("Jordan Lee"))
                .andExpect(jsonPath("$.data[0].reportingManager.employeeId").value("TD001"))
                .andExpect(jsonPath("$.data[0].reportingManager.name").value("Alex Morgan"))
                .andExpect(jsonPath("$.data[0].requesterRole").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data[0].fieldName").value("PHONE_NUMBER"))
                .andExpect(jsonPath("$.data[0].requestedValue").value("(555) 010-9999"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        verify(profileChangeRequestService).listForAdmin(eq("admin-1"));
    }

    @Test
    void returnsRequestDetailIncludingRequesterAndReportingManager() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        ProfileChangeRequestDetailResponse.RequesterSummary requester = new ProfileChangeRequestDetailResponse.RequesterSummary(
                "employee-1", "TD002", "Jordan Lee", "EMPLOYEE");
        ReportingManagerSummary manager = new ReportingManagerSummary("hr-1", "TD001", "Alex Morgan", null, null);
        ProfileChangeRequestDetailResponse detail = new ProfileChangeRequestDetailResponse(
                "request-1", ProfileChangeField.PHONE_NUMBER, "(555) 010-1000", "(555) 010-9999",
                "Number changed", ProfileChangeStatus.PENDING, null, null,
                LocalDateTime.of(2026, 8, 25, 13, 10), null,
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 13, 10),
                requester, manager);
        when(profileChangeRequestService.getDetailForAdmin("admin-1", "request-1")).thenReturn(detail);

        mockMvc.perform(get("/api/myhr/profile-change-requests/request-1")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("request-1"))
                .andExpect(jsonPath("$.data.requester.employeeId").value("TD002"))
                .andExpect(jsonPath("$.data.requester.name").value("Jordan Lee"))
                .andExpect(jsonPath("$.data.reportingManager.employeeId").value("TD001"))
                .andExpect(jsonPath("$.data.reportingManager.name").value("Alex Morgan"));

        verify(profileChangeRequestService).getDetailForAdmin(eq("admin-1"), eq("request-1"));
    }

    @Test
    void createsAProfileChangeRequestForTheAuthenticatedUser() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("employee-1");
        ProfileChangeRequestResponse response = new ProfileChangeRequestResponse(
                "request-9", "employee-1", "profile-1", "hr-1",
                ProfileChangeField.PERSONAL_EMAIL, "old@example.com", "new@example.com",
                "Changed banks.", ProfileChangeStatus.PENDING, null, null,
                LocalDateTime.of(2026, 8, 25, 13, 10), null,
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 13, 10),
                null, null, null, null);
        when(profileChangeRequestService.create(eq("employee-1"), any(CreateProfileChangeRequestRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/myhr/profile-change-requests")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldName\":\"PERSONAL_EMAIL\",\"requestedValue\":\"new@example.com\",\"requestReason\":\"Changed banks.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("request-9"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(profileChangeRequestService).create(eq("employee-1"), any(CreateProfileChangeRequestRequest.class));
    }

    @Test
    void returnsTheAuthenticatedUsersOwnRequestHistory() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("employee-1");
        ProfileChangeRequestResponse response = new ProfileChangeRequestResponse(
                "request-1", "employee-1", "profile-1", "hr-1",
                ProfileChangeField.PHONE_NUMBER, "(555) 010-1000", "(555) 010-9999",
                "Number changed", ProfileChangeStatus.PENDING, null, null,
                LocalDateTime.of(2026, 8, 25, 13, 10), null,
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 13, 10),
                "TD002", "Jordan Lee", null, null);
        when(profileChangeRequestService.listMine("employee-1")).thenReturn(List.of(response));

        mockMvc.perform(get("/api/myhr/profile-change-requests/mine")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("request-1"))
                .andExpect(jsonPath("$.data[0].employeeId").value("TD002"));

        verify(profileChangeRequestService).listMine(eq("employee-1"));
    }

    @Test
    void approvesRequestAndReturnsUpdatedResponse() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        ProfileChangeRequest updated = ProfileChangeRequest.builder()
                .id("request-1").status(ProfileChangeStatus.APPROVED).processedByUserId("admin-1")
                .processingComment("Verified with employee.").build();
        ProfileChangeRequestResponse response = new ProfileChangeRequestResponse(
                "request-1", "employee-1", "profile-1", "hr-1",
                ProfileChangeField.PHONE_NUMBER, "(555) 010-1000", "(555) 010-9999",
                "Number changed", ProfileChangeStatus.APPROVED, "admin-1", "Verified with employee.",
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 14, 0),
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 14, 0),
                null, null, null, null);
        when(profileChangeRequestService.approve("admin-1", "request-1", "Verified with employee."))
                .thenReturn(updated);
        when(profileChangeRequestService.toResponse(updated)).thenReturn(response);

        mockMvc.perform(post("/api/myhr/profile-change-requests/request-1/approve")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processingComment\":\"Verified with employee.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.processedByUserId").value("admin-1"));

        verify(profileChangeRequestService).approve(eq("admin-1"), eq("request-1"), eq("Verified with employee."));
    }

    @Test
    void rejectsRequestAndReturnsUpdatedResponse() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        ProfileChangeRequest updated = ProfileChangeRequest.builder()
                .id("request-1").status(ProfileChangeStatus.REJECTED).processedByUserId("admin-1")
                .processingComment("Needs verification.").build();
        ProfileChangeRequestResponse response = new ProfileChangeRequestResponse(
                "request-1", "employee-1", "profile-1", "hr-1",
                ProfileChangeField.PHONE_NUMBER, "(555) 010-1000", "(555) 010-9999",
                "Number changed", ProfileChangeStatus.REJECTED, "admin-1", "Needs verification.",
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 14, 0),
                LocalDateTime.of(2026, 8, 25, 13, 10), LocalDateTime.of(2026, 8, 25, 14, 0),
                null, null, null, null);
        when(profileChangeRequestService.reject("admin-1", "request-1", "Needs verification."))
                .thenReturn(updated);
        when(profileChangeRequestService.toResponse(updated)).thenReturn(response);

        mockMvc.perform(post("/api/myhr/profile-change-requests/request-1/reject")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processingComment\":\"Needs verification.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.processedByUserId").value("admin-1"));

        verify(profileChangeRequestService).reject(eq("admin-1"), eq("request-1"), eq("Needs verification."));
    }
}
