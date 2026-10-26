package com.waypoint.controller;

import com.waypoint.dto.platform.WaypointAdminOrganizationDetail;
import com.waypoint.dto.platform.WaypointAdminOrganizationPageResponse;
import com.waypoint.dto.platform.WaypointAdminOrganizationSummary;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationStatus;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.WaypointAdminOrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaypointAdminOrganizationController.class)
class WaypointAdminOrganizationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean WaypointAdminOrganizationService organizationService;
    @MockitoBean JwtService jwtService;

    @Test
    void waypointAdminCanSearchAndPageOrganizations() throws Exception {
        authorize("WAYPOINT_ADMIN");
        var summary = new WaypointAdminOrganizationSummary(
                "org-1", "ACME", "Acme Health", OrganizationStatus.INACTIVE, "people@acme.example",
                "Chicago", "IL", "Healthcare", CompanySize.MEDIUM, LocalDateTime.of(2026, 1, 2, 3, 4));
        when(organizationService.list("platform-1", 1, 5, "Acme"))
                .thenReturn(new WaypointAdminOrganizationPageResponse(List.of(summary), 1, 5, 6, 2));

        mockMvc.perform(get("/api/waypoint-admin/organizations")
                        .param("page", "1").param("size", "5").param("search", "Acme")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].organizationName").value("Acme Health"))
                .andExpect(jsonPath("$.data.items[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(6));
        verify(organizationService).list("platform-1", 1, 5, "Acme");
    }

    @Test
    void detailContainsOnlyApprovedBusinessInformation() throws Exception {
        authorize("WAYPOINT_ADMIN");
        when(organizationService.get("platform-1", "org-1")).thenReturn(new WaypointAdminOrganizationDetail(
                "org-1", "ACME", "Acme Health", OrganizationStatus.ACTIVE, "people@acme.example", "555-0100",
                "IL", "Chicago", "100 Main St", "60601", CompanySize.MEDIUM,
                "Regional provider", "Healthcare", "https://acme.example",
                LocalDateTime.of(2026, 1, 2, 3, 4), LocalDateTime.of(2026, 2, 3, 4, 5)));

        mockMvc.perform(get("/api/waypoint-admin/organizations/org-1")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationName").value("Acme Health"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.credentials").doesNotExist())
                .andExpect(jsonPath("$.data.storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.organizationLogoDocumentId").doesNotExist())
                .andExpect(jsonPath("$.data.nextEmployeeSequence").doesNotExist())
                .andExpect(jsonPath("$.data.templates").doesNotExist())
                .andExpect(jsonPath("$.data.signatures").doesNotExist())
                .andExpect(jsonPath("$.data.employees").doesNotExist())
                .andExpect(jsonPath("$.data.offerLetters").doesNotExist());
    }

    @Test
    void missingOrganizationReturnsNotFound() throws Exception {
        authorize("WAYPOINT_ADMIN");
        when(organizationService.get("platform-1", "missing"))
                .thenThrow(new ResourceNotFoundException("Organization not found"));

        mockMvc.perform(get("/api/waypoint-admin/organizations/missing")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Organization not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "HR", "EMPLOYEE", "JOB_SEEKER"})
    void rejectsEveryNonPlatformRole(String role) throws Exception {
        authorize(role);

        mockMvc.perform(get("/api/waypoint-admin/organizations")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    private void authorize(String role) {
        when(jwtService.extractUserId("token")).thenReturn("platform-1");
        when(jwtService.extractRole("token")).thenReturn(role);
        when(currentUserResolver.resolveUserId(any())).thenReturn("platform-1");
    }
}
