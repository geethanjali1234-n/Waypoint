package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
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
 * Claude-only mode, which is off unless explicitly enabled: Ollama is never called, a Claude failure is reported
 * instead of answered elsewhere, and policy text reaches Claude only with approval. Both providers are mocks, so
 * no API call is made and no policy text leaves the test.
 */
class MyHrChatServiceClaudeOnlyModeTest {

    private static final String GENERAL = "Write a polite email to my team";
    private static final String POLICY_QUESTION = "How many unused vacation days can employees carry forward?";
    private static final String POLICY_TEXT = "Synthetic test policy: up to 5 unused days carry forward.";

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MyHrChatService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        // A local Ollama is available, so any Ollama call below would be a Claude-only violation, not a missing endpoint.
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void isOffUnlessEnabledSoTheExistingClaudeFallbackIsUnchanged() {
        when(claude.chat(eq(GENERAL), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(eq(GENERAL), anyList())).thenReturn("Hi team!");

        MyHrChatService existing = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), employees, mock(DocumentRequestService.class), "claude",
                retrieval, false, false);

        assertThat(existing.chat(employee, GENERAL, List.of()).message()).isEqualTo("Hi team!");
        assertThat(logged()).contains("provider=ollama fallback=true").doesNotContain("Claude-only");
    }

    @Test
    void claudeAnswersGeneralQuestionsAndOllamaIsNeverCalled() {
        when(claude.chat(eq(GENERAL), anyList())).thenReturn("Hi team, ...");

        assertThat(service(false, false).chat(employee, GENERAL, List.of()).message()).isEqualTo("Hi team, ...");

        verifyNoInteractions(ollama);
        assertThat(logged()).contains("Claude-only mode is enabled", "provider=claude");
    }

    @Test
    void unavailableOrIncompleteClaudeIsReportedAndNeverAnsweredByOllama() {
        MyHrChatService service = service(false, false);
        for (ClaudeChatClient.ClaudeUnavailableException failure : List.of(
                new ClaudeChatClient.ClaudeUnavailableException(),
                new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.TRUNCATED),
                new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.NO_TEXT))) {
            org.mockito.Mockito.reset(claude);
            when(claude.chat(eq(GENERAL), anyList())).thenThrow(failure);

            assertThatThrownBy(() -> service.chat(employee, GENERAL, List.of())).isSameAs(failure);
        }
        verify(ollama, never()).chat(anyString(), anyList());
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        assertThat(logged()).doesNotContain("fallback=true");
    }

    @Test
    void historyIsStillFilteredAndTheLegacyNoActorPathAlsoNeverFallsBack() {
        StampedHistory stamps = new StampedHistory();
        List<MyHrChatHistoryMessage> history = stamps.turns(employee, MyHrChatHistoryProvenance.ExternalUse.PERMITTED,
                "What is my employee ID?", "Your employee ID is WRAGT002.",
                "Write a short welcome note for my new teammate", "Welcome to the team!");
        List<MyHrChatHistoryMessage> safe = StampedHistory.unstamped(history.subList(2, 4));
        when(claude.chat(eq("Make it longer"), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        MyHrChatService service = stamps.service(claude, ollama, "claude", retrieval, false, false, true,
                ExternalPolicyProcessingAuthorization.DENY_ALL);

        assertThatThrownBy(() -> service.chat(employee, "Make it longer", history, StampedHistory.SESSION))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        // The legacy path has no login session: no stamp verifies, so the follow-up has no context and no
        // provider receives it (and nothing falls back).
        assertThat(service.chat("Make it longer", history, "employee-9").message()).doesNotContain("Welcome");

        verify(claude).chat("Make it longer", safe);
        verify(claude, org.mockito.Mockito.times(1)).chat(eq("Make it longer"), anyList());
        verifyNoInteractions(ollama);
    }

    @Test
    void withoutApprovalPolicyTextGoesNowhereAndPolicyQuestionsGetTheFixedReply() {
        MyHrChatService service = service(true, false);

        MyHrChatResponse response = service.chat(employee, POLICY_QUESTION, List.of());

        // Nothing is retrieved (no query is embedded) and no provider is asked; the actual policy is never sent.
        assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE);
        verifyNoInteractions(retrieval, ollama, claude);
        assertThat(logged()).contains("Claude-only mode excludes Ollama").doesNotContain(POLICY_QUESTION);
    }

    @Test
    void withoutApprovalAGeneralQuestionStillReachesClaudeWithoutPolicyText() {
        when(claude.chat(any(MyHrGroundedPrompt.class))).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        MyHrChatService service = service(true, false);

        assertThatThrownBy(() -> service.chat(employee, GENERAL, List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);

        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(claude).chat(prompt.capture());
        assertThat(prompt.getValue().containsPolicyContext()).isFalse();
        assertThat(prompt.getValue().userMessage()).doesNotContain("<policy_context>");
        verifyNoInteractions(retrieval, ollama);
    }

    @Test
    void withApprovalAuthorizedExcerptsGoToClaudeAndAFailureIsNeverRetriedOnOllama() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.RETRIEVED,
                List.of(chunk(ChunkAccessLevel.COMPANY_WIDE)), 1));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.TRUNCATED));
        MyHrChatService service = service(true, true);

        assertThatThrownBy(() -> service.chat(employee, POLICY_QUESTION, List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeIncompleteResponseException.class);

        ArgumentCaptor<MyHrGroundedDocumentPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedDocumentPrompt.class);
        verify(claude).chatWithDocuments(prompt.capture());
        assertThat(prompt.getValue().documents()).singleElement()
                .extracting(MyHrGroundedDocumentPrompt.Document::text).isEqualTo(POLICY_TEXT);
        verifyNoInteractions(ollama);
        assertThat(logged()).doesNotContain(POLICY_TEXT, POLICY_QUESTION);
    }

    @Test
    void anHrOnlyExcerptReachesNeitherClaudeNorOllamaAndTheQuestionGetsTheFixedReply() {
        VerifiedMyHrActor hr = TestActors.hr("hr-9", "org-9");
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.RETRIEVED,
                List.of(chunk(ChunkAccessLevel.HR_ONLY)), 1));

        MyHrChatResponse response = service(true, true).chat(hr, POLICY_QUESTION, List.of());

        // HR-only text may not leave Waypoint, and Claude-only mode excludes Ollama: it is discarded.
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE);
        assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);
        verify(claude, never()).chatWithDocuments(any());
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        verifyNoInteractions(ollama);
        assertThat(logged()).contains("external-policy=RESTRICTED_CONTENT").doesNotContain(POLICY_TEXT);
    }

    @Test
    void deterministicAnswersStillWorkWhileClaudeIsDown() {
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("WRAGT002");
        when(employees.getOwnDetail("employee-9")).thenReturn(profile);
        MyHrChatService service = service(true, false);

        assertThat(service.chat(employee, "Can you tell me my employee ID?", List.of()).message())
                .isEqualTo("Your employee ID is WRAGT002.");
        assertThat(service.chat(employee, "hello", List.of()).message()).contains("How can I help");
        assertThat(service.chat(employee, "Can you approve my timesheet?", List.of()).message())
                .startsWith(MyHrChatService.ACTION_REQUEST_REPLY);
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void aContradictoryConfigurationFailsAtStartupInsteadOfUsingOllama() {
        for (String provider : List.of("ollama", "", "other")) {
            assertThatThrownBy(() -> new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                    mock(ProfileChangeRequestService.class), employees, mock(DocumentRequestService.class), provider,
                    retrieval, false, false, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Claude-only mode requires waypoint.myhr.ai.primary-provider=claude");
        }
        verifyNoInteractions(claude, ollama);
    }

    private MyHrChatService service(boolean groundingEnabled, boolean externalApproved) {
        // externalApproved: the switch is on and, for this test only, the organization authorized external processing.
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                employees, mock(DocumentRequestService.class), "claude", retrieval, groundingEnabled, externalApproved, true,
                externalApproved ? (organizationId, chunks) -> true : ExternalPolicyProcessingAuthorization.DENY_ALL);
    }

    private static RetrievedChunk chunk(ChunkAccessLevel level) {
        return new RetrievedChunk("leave-ingestion-00000", "leave", "Synthetic Leave Policy", "stored-leave",
                "leave.pdf", "leave-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, POLICY_TEXT);
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}
