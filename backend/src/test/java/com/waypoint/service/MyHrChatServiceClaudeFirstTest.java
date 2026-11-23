package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Claude primary with the local Ollama fallback, configured as the shared-readonly backend runs:
 * knowledge grounding and external policy context off, a verified actor on every request. Both
 * providers are mocks, so no paid API call is made.
 */
class MyHrChatServiceClaudeFirstTest {

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MyHrChatService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MyHrChatService service;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        // grounding disabled and external provider not approved, exactly as in the shared-readonly profile
        service = new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                employees, "claude", retrieval, false, false);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void claudeAnswersGeneralQuestionsFirstAndOllamaIsNotCalled() {
        when(claude.chat(eq("Write a polite email to my team"), anyList())).thenReturn("Hi team, ...");

        assertThat(service.chat(employee, "Write a polite email to my team", List.of()).message()).isEqualTo("Hi team, ...");

        verify(ollama, never()).chat(anyString(), anyList());
        verifyNoInteractions(retrieval);
        assertThat(logged()).contains("provider=claude").doesNotContain("fallback=true");
    }

    @Test
    void temporaryClaudeFailuresFallBackToLocalOllamaWithTheSameFilteredHistory() {
        // Both turns are server-stamped as permitted; the own-record turn is still removed by the history filter.
        StampedHistory stamps = new StampedHistory();
        List<MyHrChatHistoryMessage> history = stamps.turns(employee, MyHrChatHistoryProvenance.ExternalUse.PERMITTED,
                "What is my employee ID?", "Your employee ID is WRAGT002.",
                "Write a short welcome note for my new teammate", "Welcome to the team!");
        List<MyHrChatHistoryMessage> safe = StampedHistory.unstamped(history.subList(2, 4));
        when(claude.chat(eq("Make it longer"), eq(safe))).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat("Make it longer", safe)).thenReturn("Welcome to the team! We are glad you are here.");
        MyHrChatService service = stamps.service(claude, ollama, "claude", retrieval, false, false, false,
                ExternalPolicyProcessingAuthorization.DENY_ALL);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        when(ollama.verifiedLocalModel()).thenReturn(true);

        assertThat(service.chat(employee, "Make it longer", history, StampedHistory.SESSION).message())
                .isEqualTo("Welcome to the team! We are glad you are here.");

        // Own-record turns never reach either provider.
        verify(claude).chat("Make it longer", safe);
        verify(ollama).chat("Make it longer", safe);
        assertThat(logged()).contains("provider=ollama fallback=true");
    }

    @Test
    void configurationAndRejectedRequestErrorsNeverFallBackSilently() {
        when(claude.chat(eq("Can you help me plan my week?"), anyList())).thenThrow(new ClaudeChatClient.ClaudeConfigurationException());
        when(claude.chat(eq("rejected"), anyList())).thenThrow(new ClaudeChatClient.ClaudeRequestRejectedException());

        assertThatThrownBy(() -> service.chat(employee, "Can you help me plan my week?", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);
        assertThatThrownBy(() -> service.chat(employee, "rejected", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeRequestRejectedException.class);
        verify(ollama, never()).chat(anyString(), anyList());
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
    }

    @Test
    void bothProvidersDownReportsTheAssistantAsUnavailable() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        when(ollama.verifiedLocalModel()).thenReturn(true);
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(anyString(), anyList())).thenThrow(new OllamaClient.OllamaUnavailableException());

        assertThatThrownBy(() -> service.chat(employee, "Write a polite email to my team", List.of()))
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class);
    }

    @Test
    void waviWorkflowsAndOwnRecordLookupsNeverReachEitherProvider() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("WRAGT002");
        when(employees.getOwnDetail("employee-9")).thenReturn(profile);

        assertThat(service.chat(employee, "Can you tell me my employee ID?", List.of()).message())
                .isEqualTo("Your employee ID is WRAGT002.");
        assertThat(service.chat(employee, "Where can I check my timesheet status?", List.of()).message())
                .contains("Submitted Timesheets");
        assertThat(service.chat(employee, "hello", List.of()).message()).contains("How can I help");
        assertThat(service.chat(employee, "Can you approve my timesheet?", List.of()).message())
                .startsWith(MyHrChatService.ACTION_REQUEST_REPLY);

        verifyNoInteractions(claude, retrieval);
        verify(ollama, never()).chat(anyString(), anyList());
    }
}
