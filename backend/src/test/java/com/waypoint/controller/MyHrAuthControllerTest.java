package com.waypoint.controller;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyHrAuthController.class)
class MyHrAuthControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean MyHrAuthenticatedUserService authenticatedUserService;
    @MockitoBean JwtService jwtService;

    @Test
    void returnsCurrentDatabaseBackedMyHrIdentityIncludingPasswordState() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(authenticatedUserService.require(eq("hr-1"), any(), eq(true)))
                .thenReturn(new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                        "hr-1", "TD001", "hr@tardus.test", UserRole.HR,
                        "org-1", AccountStatus.ACTIVE, true));

        mockMvc.perform(get("/api/myhr/auth/me").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("hr-1"))
                .andExpect(jsonPath("$.data.loginId").value("TD001"))
                .andExpect(jsonPath("$.data.role").value("HR"))
                .andExpect(jsonPath("$.data.organizationId").value("org-1"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
    }
}
