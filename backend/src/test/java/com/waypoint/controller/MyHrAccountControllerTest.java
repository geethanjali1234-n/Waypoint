package com.waypoint.controller;

import com.waypoint.dto.myhr.CreatedAccountResult;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.MyHrAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyHrAccountController.class)
class MyHrAccountControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean MyHrAccountService accountService;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean JwtService jwtService;

    @Test
    void createsHrThroughProtectedBackendEndpoint() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(accountService.createHr(any(), any())).thenReturn(new CreatedAccountResult(
                "hr-1", "profile-1", "LOGIN-001", "EMP-001", "hr@tardus.test", UserRole.HR,
                "org-1", true, true));

        mockMvc.perform(post("/api/myhr/accounts/hr")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profile":{"firstName":"HR","lastName":"User",
                                "personalEmail":"hr@tardus.test","phoneNumber":"+15551234567",
                                "designation":"HR Manager","employmentType":"FULL_TIME",
                                "startDate":"2026-09-01","streetAddress":"1 Main Street",
                                "city":"Austin","state":"Texas","zipCode":"78701"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loginId").value("LOGIN-001"))
                .andExpect(jsonPath("$.data.employeeId").value("EMP-001"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andExpect(jsonPath("$.data.emailDelivered").value(true))
                .andExpect(jsonPath("$.data.temporaryPassword").doesNotExist());
    }

    @Test
    void rejectsMissingMandatoryProfileFieldsBeforeCreatingHr() throws Exception {
        mockMvc.perform(post("/api/myhr/accounts/hr")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profile":{"firstName":" ","lastName":"",
                                "personalEmail":"invalid","designation":" "}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
        verifyNoInteractions(accountService);
    }

    @Test
    void rejectsMissingMandatoryProfileAndManagerBeforeCreatingEmployee() throws Exception {
        mockMvc.perform(post("/api/myhr/accounts/employees")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
        verifyNoInteractions(accountService);
    }

    @Test
    void returnsEligibleReportingManagersFromAuthenticatedOrganization() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(accountService.getEligibleReportingManagers("hr-1")).thenReturn(java.util.List.of(
                new com.waypoint.dto.myhr.ReportingManagerOption(
                        "hr-2", "TD002", "Sarah", "Jones")));

        mockMvc.perform(get("/api/myhr/accounts/reporting-managers")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("hr-2"))
                .andExpect(jsonPath("$.data[0].loginId").value("TD002"))
                .andExpect(jsonPath("$.data[0].firstName").value("Sarah"))
                .andExpect(jsonPath("$.data[0].lastName").value("Jones"));
    }
}
