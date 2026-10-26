package com.waypoint.controller;

import com.waypoint.dto.myhr.EligibleReportingManagerResponse;
import com.waypoint.dto.myhr.EmployeeDetailResponse;
import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.EmployeeManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmployeeManagementController.class)
class EmployeeManagementControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean EmployeeManagementService employeeManagementService;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean JwtService jwtService;

    @Test
    void listsEmployeesForAuthenticatedManager() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(employeeManagementService.list("hr-1")).thenReturn(List.of(new EmployeeSummaryResponse(
                "emp-1", "TD003", "John Doe", "EMPLOYEE", "john@work.test", "john@home.test",
                "Software Engineer", EmploymentType.FULL_TIME, null, "Pragna", AccountStatus.ACTIVE)));

        mockMvc.perform(get("/api/myhr/employees").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("emp-1"))
                .andExpect(jsonPath("$.data[0].personType").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data[0].reportingManagerName").value("Pragna"));
    }

    @Test
    void updatesAccountStatus() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(employeeManagementService.updateAccountStatus(
                eq("hr-1"), eq("emp-1"), eq(AccountStatus.INACTIVE), isNull()))
                .thenReturn(new EmployeeDetailResponse(
                        "emp-1", "TD003", "John Doe", "John", "Doe", "EMPLOYEE",
                        "john@work.test", "john@home.test", "555-0100", "Software Engineer",
                        EmploymentType.FULL_TIME, null, "1 Main St", "Austin", "Texas", "78701",
                        AccountStatus.INACTIVE, null, null, List.of(), List.of()));

        mockMvc.perform(patch("/api/myhr/employees/emp-1/status")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("INACTIVE"));
    }

    @Test
    void updatesAccountStatusWithAReassignmentTargetForADeactivatedHr() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(employeeManagementService.updateAccountStatus(
                eq("admin-1"), eq("hr-1"), eq(AccountStatus.INACTIVE), eq("admin-1")))
                .thenReturn(new EmployeeDetailResponse(
                        "hr-1", "TD002", "Sarah Jones", "Sarah", "Jones", "HR",
                        "sarah@work.test", "sarah@home.test", "555-0100", "HR Manager",
                        EmploymentType.FULL_TIME, null, "1 Main St", "Austin", "Texas", "78701",
                        AccountStatus.INACTIVE, null, null, List.of(), List.of()));

        mockMvc.perform(patch("/api/myhr/employees/hr-1/status")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"reassignToUserId\":\"admin-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("INACTIVE"));
    }

    @Test
    void returnsEmployeeDetail() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(employeeManagementService.getDetail("hr-1", "emp-1")).thenReturn(new EmployeeDetailResponse(
                "emp-1", "TD003", "John Doe", "John", "Doe", "EMPLOYEE",
                "john@work.test", "john@home.test", "555-0100", "Software Engineer",
                EmploymentType.FULL_TIME, null, "1 Main St", "Austin", "Texas", "78701",
                AccountStatus.ACTIVE, null, null, List.of(), List.of()));

        mockMvc.perform(get("/api/myhr/employees/emp-1").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("emp-1"))
                .andExpect(jsonPath("$.data.name").value("John Doe"));
    }

    @Test
    void returnsTheAuthenticatedUsersOwnProfile() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("emp-1");
        when(employeeManagementService.getOwnDetail("emp-1")).thenReturn(new MyHrOwnProfileResponse(
                "emp-1", "TD003", "TD003", UserRole.EMPLOYEE, AccountStatus.ACTIVE,
                "John", "Doe", "John Doe", "john@account.test", "john@home.test", "555-0100",
                "Software Engineer", EmploymentType.FULL_TIME, null, "1 Main St", "Austin", "Texas", "78701",
                "org-1", "Tardus", "TD", "hr@tardus.example.com", "200 Lake Street, Austin, TX, 78701", "+15551234567",
                "https://tardus.example.com", "84-3596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com", "+19726936993", null,
                "/uploads/company-logos/org-1/logo.png", "/uploads/company-signatures/org-1/signature.png", null, null));

        mockMvc.perform(get("/api/myhr/employees/me").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("emp-1"))
                .andExpect(jsonPath("$.data.name").value("John Doe"))
                .andExpect(jsonPath("$.data.organizationCode").value("TD"))
                .andExpect(jsonPath("$.data.organizationAddress").value("200 Lake Street, Austin, TX, 78701"))
                .andExpect(jsonPath("$.data.organizationEinNumber").value("84-3596475"))
                .andExpect(jsonPath("$.data.organizationEVerifyNumber").value("1533740"))
                .andExpect(jsonPath("$.data.organizationLogoUrl").value("/uploads/company-logos/org-1/logo.png"));
    }

    @Test
    void returnsEligibleReportingManagersScopedToTheGivenEmployee() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(employeeManagementService.eligibleReportingManagers("admin-1", "emp-1"))
                .thenReturn(List.of(new EligibleReportingManagerResponse(
                        "hr-2", "TD002", "Sarah Jones", "HR", null)));

        mockMvc.perform(get("/api/myhr/employees/reporting-managers")
                        .header("Authorization", "Bearer token")
                        .param("employeeUserId", "emp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("hr-2"))
                .andExpect(jsonPath("$.data[0].roleLabel").value("HR"));
    }

    @Test
    void returnsEligibleReportingManagersWhenNoEmployeeUserIdGiven() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(employeeManagementService.eligibleReportingManagers(eq("admin-1"), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/myhr/employees/reporting-managers").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void changesReportingManager() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(employeeManagementService.changeReportingManager(eq("hr-1"), eq("emp-1"), any()))
                .thenReturn(new EmployeeDetailResponse(
                        "emp-1", "TD003", "John Doe", "John", "Doe", "EMPLOYEE",
                        "john@work.test", "john@home.test", "555-0100", "Software Engineer",
                        EmploymentType.FULL_TIME, null, "1 Main St", "Austin", "Texas", "78701",
                        AccountStatus.ACTIVE, null, null, List.of(), List.of()));

        mockMvc.perform(patch("/api/myhr/employees/emp-1/reporting-manager")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newReportingManagerUserId\":\"hr-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("emp-1"));
    }

    private static MyHrOwnProfileResponse ownProfile(String userId, String signatureUrl) {
        return new MyHrOwnProfileResponse(userId, userId, null, UserRole.HR, AccountStatus.ACTIVE, null, null, null,
                userId + "@tardus.test", null, null, null, null, null, null, null, null, null,
                "org-1", "Tardus", "TD", null, "", null, null, null, null, null, null, null, null, null, null, null, signatureUrl);
    }

    @Test
    void uploadsTheSignatureForTheAuthenticatedUserResolvedFromTheToken() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(employeeManagementService.uploadOwnSignature(eq("hr-1"), any()))
                .thenReturn(ownProfile("hr-1", "/uploads/gridfs/abc/organization-signature.png"));

        mockMvc.perform(multipart("/api/myhr/employees/me/signature")
                        .file(new MockMultipartFile("file", "organization-signature.png", "image/png", new byte[]{1, 2}))
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("hr-1"))
                .andExpect(jsonPath("$.data.signatureUrl").value("/uploads/gridfs/abc/organization-signature.png"));
        verify(employeeManagementService).uploadOwnSignature(eq("hr-1"), any());
    }

    @Test
    void removesTheAuthenticatedUsersOwnSignature() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("emp-1");
        when(employeeManagementService.removeOwnSignature("emp-1")).thenReturn(ownProfile("emp-1", null));

        mockMvc.perform(delete("/api/myhr/employees/me/signature").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signatureUrl").doesNotExist());
        verify(employeeManagementService).removeOwnSignature("emp-1");
    }
}
