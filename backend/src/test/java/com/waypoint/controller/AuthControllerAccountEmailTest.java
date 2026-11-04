package com.waypoint.controller;

import com.waypoint.dto.auth.AccountEmailChangeRequestedResponse;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.security.AuthRateLimitingFilter;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.AccountEmailChangeService;
import com.waypoint.service.AuthService;
import com.waypoint.service.OrganizationRegistrationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerAccountEmailTest {

    private final AuthService authService = mock(AuthService.class);
    private final AccountEmailChangeService accountEmailChangeService = mock(AccountEmailChangeService.class);
    private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(authService, mock(OrganizationRegistrationService.class), resolver, accountEmailChangeService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(resolver.resolveUserId(any())).thenReturn("tardus-admin");
    }

    @Test
    void theOldEndpointIsGoneAndCanNeverChangeAnEmail() throws Exception {
        mvc.perform(patch("/api/auth/change-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"attacker@example.com\",\"currentPassword\":\"Secret123\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(accountEmailChangeService);
        verifyNoInteractions(authService);
    }

    @Test
    void noRouteAcceptsAnEmailChangeInOneStep() {
        // The only handlers that mention the change are the retired stub and the two verified steps.
        long mappingsToChangeEmail = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.getName().toLowerCase().contains("changeemail")
                        || method.getName().toLowerCase().contains("emailchange")).count();
        assertThat(mappingsToChangeEmail).isEqualTo(3);
        assertThat(Arrays.stream(AuthService.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("changeCompanyAdminEmail");
    }

    @Test
    void requestingAChangeUsesTheAuthenticatedUserAndNeverSendsAnythingElse() throws Exception {
        when(accountEmailChangeService.requestChange(eq("tardus-admin"), any()))
                .thenReturn(new AccountEmailChangeRequestedResponse("tardus.admin.example@example.com", 600, 60));

        mvc.perform(post("/api/auth/change-email/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"tardus.admin.example@example.com\",\"currentPassword\":\"Secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newEmail").value("tardus.admin.example@example.com"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));

        verify(accountEmailChangeService).requestChange(eq("tardus-admin"), any());
    }

    @Test
    void confirmingTakesOnlyTheCodeAndReturnsTheFreshSession() throws Exception {
        LoginResponse session = LoginResponse.builder().userId("tardus-admin").email("tardus.admin.example@example.com").accessToken("fresh").tokenType("Bearer").build();
        when(accountEmailChangeService.confirmChange(eq("tardus-admin"), any())).thenReturn(session);

        mvc.perform(post("/api/auth/change-email/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"123456\",\"newEmail\":\"attacker@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("tardus.admin.example@example.com"))
                .andExpect(jsonPath("$.data.accessToken").value("fresh"));
    }

    @Test
    void aMalformedCodeNeverReachesTheService() throws Exception {
        for (String body : new String[] {"{\"otp\":\"12345\"}", "{\"otp\":\"abcdef\"}", "{\"otp\":\"\"}", "{}"}) {
            mvc.perform(post("/api/auth/change-email/confirm").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(accountEmailChangeService, never()).confirmChange(any(), any());
    }

    @Test
    void bothNewStepsArePerClientRateLimitedLikeTheOtherAuthEndpoints() throws Exception {
        for (String uri : new String[] {"/api/auth/change-email/request", "/api/auth/change-email/confirm"}) {
            AuthRateLimitingFilter filter = new AuthRateLimitingFilter();
            FilterChain chain = mock(FilterChain.class);
            int blocked = 0;
            for (int i = 0; i < 35; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
                request.setRemoteAddr("203.0.113.9");
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request, response, chain);
                if (response.getStatus() == 429) blocked++;
            }
            assertThat(blocked).as(uri).isGreaterThan(0);
        }
    }
}
