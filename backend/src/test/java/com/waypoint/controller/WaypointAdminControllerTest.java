package com.waypoint.controller;

import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.security.WaypointAdminAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaypointAdminController.class)
class WaypointAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean WaypointAdminAuthenticatedUserService authenticatedUserService;
    @MockitoBean JwtService jwtService;

    @Test
    void returnsPlatformIdentityWithoutOrganizationData() throws Exception {
        when(jwtService.extractUserId("token")).thenReturn("platform-1");
        when(jwtService.extractRole("token")).thenReturn("WAYPOINT_ADMIN");
        when(currentUserResolver.resolveUserId(any())).thenReturn("platform-1");
        when(authenticatedUserService.require("platform-1")).thenReturn(
                new WaypointAdminAuthenticatedUserService.AuthenticatedWaypointAdmin(
                        "platform-1", "WPADMIN", "admin@waypoint.test", UserRole.WAYPOINT_ADMIN, false));

        mockMvc.perform(get("/api/waypoint-admin/dashboard").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("platform-1"))
                .andExpect(jsonPath("$.data.role").value("WAYPOINT_ADMIN"))
                .andExpect(jsonPath("$.data.organizationId").doesNotExist());
    }

    @Test
    void interceptorRejectsCompanyAdminTokenBeforeController() throws Exception {
        when(jwtService.extractUserId("token")).thenReturn("company-1");
        when(jwtService.extractRole("token")).thenReturn("COMPANY_ADMIN");

        mockMvc.perform(get("/api/waypoint-admin/dashboard").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }
}
