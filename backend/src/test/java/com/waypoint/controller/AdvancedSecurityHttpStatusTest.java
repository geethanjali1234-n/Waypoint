package com.waypoint.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.GlobalExceptionHandler;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.ClaudeChatClient;
import com.waypoint.service.MyHrChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Step 9.3 G7 through the application's real MVC wiring: the JWT interceptor, static-resource
 * lookup and GlobalExceptionHandler, with every service mocked. No database or AI provider is used;
 * all "secrets" are canaries.
 */
@WebMvcTest(value = MyHrChatController.class, properties = "waypoint.myhr.chat.enabled=true")
class AdvancedSecurityHttpStatusTest {

    private static final String CANARY_TOKEN = "canary-query-token-7d6e5f";
    private static final String CANARY_SEGMENT = "canary-path-segment-1a2b3c";
    private static final String NOT_FOUND = "{\"success\":false,\"message\":\"The requested endpoint was not found.\",\"data\":null}";
    private static final String METHOD_NOT_ALLOWED = "{\"success\":false,\"message\":\"Method not allowed\",\"data\":null}";

    @Autowired MockMvc mockMvc;
    @MockitoBean MyHrChatService chatService;
    @MockitoBean ClaudeChatClient claudeChatClient;
    @MockitoBean JwtService jwtService;
    @MockitoBean MyHrActorResolver actorResolver;

    private final VerifiedMyHrActor employee = TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE);
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        handlerLogger.addAppender(logs);
        when(jwtService.extractUserId("token")).thenReturn("employee-1");
        when(jwtService.extractRole("token")).thenReturn("EMPLOYEE");
    }

    @AfterEach
    void stopCapturingLogs() {
        handlerLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void unsupportedMethodsOnTheChatEndpointGet405WithAllowPostAndNeverReachTheService() throws Exception {
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[] {
                get("/api/myhr/chat").param("token", CANARY_TOKEN).header("Authorization", "Bearer token"),
                put("/api/myhr/chat").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/myhr/chat"),
                get("/api/myhr/chat")}) {
            MvcResult result = mockMvc.perform(request).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(405);
            assertThat(result.getResponse().getHeader("Allow")).isEqualTo("POST");
            assertThat(result.getResponse().getContentAsString()).isEqualTo(METHOD_NOT_ALLOWED);
        }
        verifyNoInteractions(chatService, actorResolver);
        assertNoWarnOrErrorAndNoRequestDetail();
    }

    @Test
    void anUnknownApiPathIs404ForAnAuthenticatedCallerAndDoesNotEchoThePath() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/myhr/does-not-exist/" + CANARY_SEGMENT)
                .param("token", CANARY_TOKEN).header("Authorization", "Bearer token")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(NOT_FOUND);
        assertNoWarnOrErrorAndNoRequestDetail();
    }

    @Test
    void anUnknownPathOutsideTheProtectedApiIs404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/no-such-area/" + CANARY_SEGMENT)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(NOT_FOUND);
        assertNoWarnOrErrorAndNoRequestDetail();
    }

    @Test
    void authenticationStillComesFirstForUnknownPathsUnderTheProtectedApi() throws Exception {
        // Unchanged behaviour: the JWT interceptor answers before any route lookup result is revealed.
        MvcResult result = mockMvc.perform(get("/api/myhr/does-not-exist")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("Missing or invalid authentication token");
    }

    @Test
    void theValidChatEndpointStillAnswersNormally() throws Exception {
        when(actorResolver.resolve(any(), eq(MyHrChatController.CHAT_ROLES))).thenReturn(employee);
        when(chatService.chat(eq(employee), eq("hello"), isNull(), any())).thenReturn(new MyHrChatResponse("Hello! How can I help you today?"));

        MvcResult result = mockMvc.perform(post("/api/myhr/chat").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("Hello! How can I help you today?");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void anUnexpectedFailureInsideAValidRouteIsStillTheGenericSafe500() throws Exception {
        when(actorResolver.resolve(any(), eq(MyHrChatController.CHAT_ROLES))).thenReturn(employee);
        when(chatService.chat(eq(employee), eq("hello"), isNull(), any()))
                .thenThrow(new IllegalStateException("failure carrying " + CANARY_TOKEN));

        MvcResult result = mockMvc.perform(post("/api/myhr/chat").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"success\":false,\"message\":\"An unexpected error occurred\",\"data\":null}");
        assertThat(logText()).doesNotContain(CANARY_TOKEN);
    }

    private void assertNoWarnOrErrorAndNoRequestDetail() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getLevel().isGreaterOrEqual(Level.WARN)).as("level " + event.getLevel()).isFalse();
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(logText()).doesNotContain(CANARY_TOKEN).doesNotContain(CANARY_SEGMENT)
                .doesNotContain("/api/").doesNotContain("does-not-exist");
    }

    private String logText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}
