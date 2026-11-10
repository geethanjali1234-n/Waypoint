package com.waypoint.controller;

import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.dto.myhr.GoogleConnectionStatusResponse;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.google.GoogleConnectResult;
import com.waypoint.service.google.GoogleConnectionService;
import com.waypoint.service.google.GoogleRateLimits;
import com.waypoint.service.google.GoogleTestEmailService;
import com.waypoint.service.google.PlatformGoogleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({OrganizationGoogleConnectionController.class, GoogleOAuthCallbackController.class})
@TestPropertySource(properties = "waypoint.app.frontend-base-url=https://app.example.test/")
class GoogleConnectionControllersTest {

    private static final String BASE = "/api/myhr/organization-settings/email/google";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CurrentUserResolver currentUserResolver;

    @MockitoBean
    GoogleConnectionService googleConnectionService;

    @MockitoBean
    PlatformGoogleAuthorizationService platformGoogleAuthorizationService;

    @MockitoBean
    GoogleTestEmailService googleTestEmailService;

    @MockitoBean
    GoogleRateLimits googleRateLimits;

    @MockitoBean
    JwtService jwtService;

    @org.junit.jupiter.api.BeforeEach
    void allowCallbacks() {
        when(googleRateLimits.allowCallback(any())).thenReturn(true);
    }

    // ------------------------------------------------------------------ authenticated admin endpoints

    @Test
    void adminEndpointsRequireAnAuthorizationHeader() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/connect")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(googleConnectionService);
    }

    @Test
    void statusUsesTheAuthenticatedUserAndReturnsNoSecrets() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(googleConnectionService.status("admin-1")).thenReturn(new GoogleConnectionStatusResponse(
                true, "CONNECTED", "admin@itap.example", "itap.example", null, null, null));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONNECTED"))
                .andExpect(jsonPath("$.data.googleAccountEmail").value("admin@itap.example"))
                .andExpect(jsonPath("$.data.organizationEmailDomain").value("itap.example"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.googleSubject").doesNotExist());
    }

    @Test
    void connectReturnsTheAuthorizationUrlForTheAuthenticatedAdminOnly() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(googleConnectionService.start("admin-1")).thenReturn(new GoogleConnectStartResponse("https://google.test/auth?state=abc"));

        mockMvc.perform(post(BASE + "/connect").param("organizationId", "someone-elses-org")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl").value("https://google.test/auth?state=abc"));
        verify(googleConnectionService).start("admin-1");
    }

    @Test
    void disconnectUsesTheAuthenticatedUser() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(googleConnectionService.disconnect("admin-1")).thenReturn(new GoogleConnectionStatusResponse(
                true, "DISCONNECTED", null, "itap.example", null, null, null));

        mockMvc.perform(delete(BASE).header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCONNECTED"));
    }

    @Test
    void aNonAdminIsRefusedByTheService() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("employee-1");
        when(googleConnectionService.start("employee-1")).thenThrow(new ForbiddenException("You do not have access to this resource"));

        mockMvc.perform(post(BASE + "/connect").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ------------------------------------------------------------------ public callback

    @Test
    void callbackIsPublicAndRedirectsToTheSettingsPageOnSuccess() throws Exception {
        when(googleConnectionService.handleCallback("the-code", "the-state", null))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.CONNECTED, null));

        mockMvc.perform(get("/api/oauth/google/callback").param("code", "the-code").param("state", "the-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/myhr/admin/organization?google=connected"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verifyNoInteractions(currentUserResolver);
    }

    @Test
    void aPlatformStateRoutesToThePlatformCallbackFlowAndNeverToAnOrganization() throws Exception {
        when(platformGoogleAuthorizationService.ownsState("platform-state")).thenReturn(true);
        when(platformGoogleAuthorizationService.handleCallback("the-code", "platform-state", null))
                .thenReturn(GoogleConnectResult.CONNECTED);

        mockMvc.perform(get("/api/oauth/google/callback").param("code", "the-code").param("state", "platform-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/waypoint-admin/dashboard?platformGoogle=connected"));
        verifyNoInteractions(googleConnectionService);
    }

    @Test
    void failuresRedirectWithAReasonCodeAndNeverEchoTheCodeOrState() throws Exception {
        when(googleConnectionService.handleCallback(any(), any(), any()))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.STATE_EXPIRED, null));

        mockMvc.perform(get("/api/oauth/google/callback").param("code", "secret-code").param("state", "secret-state")
                        .param("organizationId", "org-x"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/myhr/admin/organization?google=error&reason=STATE_EXPIRED"));
    }

    @Test
    void aDomainMismatchAlsoCarriesTheDomainOfTheChosenAccount() throws Exception {
        when(googleConnectionService.handleCallback(any(), any(), any()))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.DOMAIN_MISMATCH, "gmail.com"));

        mockMvc.perform(get("/api/oauth/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://app.example.test/myhr/admin/organization?google=error&reason=DOMAIN_MISMATCH&accountDomain=gmail.com"));
    }

    @Test
    void anAuthorizationDenialIsPassedToTheService() throws Exception {
        when(googleConnectionService.handleCallback(null, "s", "access_denied"))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.ACCESS_DENIED, null));

        mockMvc.perform(get("/api/oauth/google/callback").param("state", "s").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/myhr/admin/organization?google=error&reason=ACCESS_DENIED"));
    }

    @Test
    void aCallbackWithNoParametersStillRedirectsInsteadOfFailing() throws Exception {
        when(googleConnectionService.handleCallback(null, null, null))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.INVALID_STATE, null));

        mockMvc.perform(get("/api/oauth/google/callback"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/myhr/admin/organization?google=error&reason=INVALID_STATE"));
        verify(googleConnectionService, never()).start(any());
    }

    @Test
    void theCallbackAcceptsOnlyCodeStateAndErrorNeverAnOrganizationId() throws Exception {
        Method callback = Arrays.stream(GoogleOAuthCallbackController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("callback")).findFirst().orElseThrow();

        // The only request values it reads are code, state and error; the fourth parameter is the servlet request, used
        // for the client address of the rate limit. There is no organization id (or anything like it) to bind.
        var bound = Arrays.stream(callback.getParameters()).filter(parameter -> parameter.isAnnotationPresent(RequestParam.class)).toList();
        assertThat(bound).extracting(parameter -> parameter.getName()).containsExactly("code", "state", "error");
        assertThat(callback.getParameters()).hasSize(4);
        assertThat(callback.getParameterTypes()[3]).isEqualTo(jakarta.servlet.http.HttpServletRequest.class);
    }

    // ------------------------------------------------------------------ Phase 5: test email and callback hardening

    @Test
    void testEmailRequiresAuthenticationAndTakesNoInput() throws Exception {
        mockMvc.perform(post(BASE + "/test")).andExpect(status().isUnauthorized());
        verifyNoInteractions(googleTestEmailService);

        Method test = Arrays.stream(OrganizationGoogleConnectionController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("test")).findFirst().orElseThrow();
        assertThat(test.getParameterTypes()).containsExactly(jakarta.servlet.http.HttpServletRequest.class);
    }

    @Test
    void testEmailUsesTheAuthenticatedUserAndIgnoresAnySuppliedRecipient() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(googleTestEmailService.sendTestEmail("admin-1")).thenReturn(
                new com.waypoint.dto.myhr.GoogleTestEmailResponse(true, "SENT", "hr@itap.example", "ops@itap.example"));

        mockMvc.perform(post(BASE + "/test").param("to", "victim@elsewhere.example").param("recipient", "victim@elsewhere.example")
                        .contentType("application/json").content("{\"to\":\"victim@elsewhere.example\"}")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true))
                .andExpect(jsonPath("$.data.recipient").value("hr@itap.example"))
                .andExpect(jsonPath("$.data.sender").value("ops@itap.example"));
        verify(googleTestEmailService).sendTestEmail("admin-1");
    }

    @Test
    void aNonAdminCannotSendATestEmail() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("employee-1");
        when(googleTestEmailService.sendTestEmail("employee-1")).thenThrow(new ForbiddenException("You do not have access to this resource"));

        mockMvc.perform(post(BASE + "/test").header("Authorization", "Bearer token")).andExpect(status().isForbidden());
    }

    @Test
    void aBlockedClientIsRedirectedWithoutTheServiceBeingCalled() throws Exception {
        when(googleRateLimits.allowCallback(any())).thenReturn(false);

        mockMvc.perform(get("/api/oauth/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/myhr/admin/organization?google=error&reason=TOO_MANY_ATTEMPTS"));
        verifyNoInteractions(googleConnectionService);
    }

    @Test
    void anUnexpectedFailureBecomesAGenericRedirectAndNeverALeakedError() throws Exception {
        when(googleConnectionService.handleCallback(any(), any(), any())).thenThrow(new IllegalStateException("db password=hunter2"));

        mockMvc.perform(get("/api/oauth/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.example.test/myhr/admin/organization?google=error&reason=CONNECT_FAILED"));
    }

    @Test
    void theRateLimitKeyIsTheClientAddressAndTheForwardedAddressWinsWhenPresent() throws Exception {
        when(googleConnectionService.handleCallback(any(), any(), any()))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.INVALID_STATE, null));

        mockMvc.perform(get("/api/oauth/google/callback").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"));

        verify(googleRateLimits).allowCallback("203.0.113.9");
    }

    @Test
    void aForwardedValueThatIsNotAnIpAddressIsNeverUsedAsTheRateLimitKey() throws Exception {
        when(googleConnectionService.handleCallback(any(), any(), any()))
                .thenReturn(new GoogleConnectionService.CallbackOutcome(GoogleConnectResult.INVALID_STATE, null));

        mockMvc.perform(get("/api/oauth/google/callback").header("X-Forwarded-For", "attacker-chosen-text-" + "x".repeat(500)));

        verify(googleRateLimits).allowCallback("127.0.0.1"); // MockMvc's remote address
    }
}
