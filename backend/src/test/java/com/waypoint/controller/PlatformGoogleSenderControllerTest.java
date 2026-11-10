package com.waypoint.controller;

import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.google.PlatformGoogleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformGoogleSenderController.class)
class PlatformGoogleSenderControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean PlatformGoogleAuthorizationService authorizationService;
    @MockitoBean JwtService jwtService;

    @Test void platformGoogleEndpointsRejectMissingCredentials() throws Exception {
        mockMvc.perform(get("/api/waypoint-admin/email/google")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/waypoint-admin/email/google/connect")).andExpect(status().isUnauthorized());
        verifyNoInteractions(authorizationService);
    }

    @Test void platformGoogleEndpointsRejectAnyNonWaypointAdminJwtRole() throws Exception {
        org.mockito.Mockito.when(jwtService.extractUserId(any())).thenReturn("company-admin");
        org.mockito.Mockito.when(jwtService.extractRole(any())).thenReturn("COMPANY_ADMIN");
        mockMvc.perform(get("/api/waypoint-admin/email/google").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authorizationService);
    }
}
