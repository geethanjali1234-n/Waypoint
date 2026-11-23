package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.MyHrChatCitation;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
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
 * Claude as the primary provider with local Ollama as the fallback: Ollama answers only when Claude is temporarily
 * unavailable, never after a refusal or in place of an answer that failed the citation rules, and when neither
 * provider can answer the failure is reported. Providers are mocks and documents are synthetic: no API call is
 * made and no real policy is used.
 */
class MyHrChatServiceClaudePrimaryTest {

    private static final String GENERAL = "Write a polite email to my team";
    private static final String POLICY_QUESTION = "How many unused vacation days can employees carry forward?";
    private static final String LEAVE_TEXT = "2. Carry forward\nEmployees may carry forward up to 5 unused days.";
    private static final String HR_TEXT = "HR may approve up to 8 extra carry-forward days.";

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-9", "org-9");
    private final Logger logger = (Logger) LoggerFactory.getLogger(MyHrChatService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        // a mocked Ollama stands for the verified local model the fallback requires
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        logs.start();
        logger.addAppender(logs);
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    // --- General chat: which failures fall back --------------------------------------------------------------

    @Test
    void temporaryUnavailabilityAndIncompleteAnswersFallBackToLocalOllama() {
        MyHrChatService service = service(false, false);
        for (ClaudeChatClient.ClaudeUnavailableException failure : List.of(
                new ClaudeChatClient.ClaudeUnavailableException(),
                new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.TRUNCATED),
                new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.NO_TEXT),
                new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.UNEXPECTED_STOP))) {
            org.mockito.Mockito.reset(claude);
            when(claude.chat(eq(GENERAL), anyList())).thenThrow(failure);
            when(ollama.chat(eq(GENERAL), anyList())).thenReturn("Hi team!");

            assertThat(service.chat(employee, GENERAL, List.of()).message()).isEqualTo("Hi team!");
        }
        assertThat(logged()).contains("provider=ollama fallback=true");
    }

    @Test
    void aRefusalIsAnsweredSafelyAndNeverSentToOllama() {
        when(claude.chat(eq(GENERAL), anyList())).thenThrow(new ClaudeChatClient.ClaudeSafetyRefusalException());

        MyHrChatResponse response = service(false, false).chat(employee, GENERAL, List.of());

        assertThat(response.message()).isEqualTo(MyHrChatService.REFUSAL_REPLY);
        assertThat(response.knowledgeStatus()).isNull();
        assertThat(response.actions()).isEmpty();
        verifyNoInteractions(ollama);
        assertThat(logged()).contains("provider=claude refusal=true").doesNotContain(GENERAL);
    }

    @Test
    void configurationAndRejectedRequestsAreReportedAndNeverSentToOllama() {
        when(claude.chat(eq(GENERAL), anyList())).thenThrow(new ClaudeChatClient.ClaudeConfigurationException());
        assertThatThrownBy(() -> service(false, false).chat(employee, GENERAL, List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);

        org.mockito.Mockito.reset(claude);
        when(claude.chat(eq(GENERAL), anyList())).thenThrow(new ClaudeChatClient.ClaudeRequestRejectedException());
        assertThatThrownBy(() -> service(false, false).chat(employee, GENERAL, List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeRequestRejectedException.class);

        verify(ollama, never()).chat(anyString(), anyList());
    }

    @Test
    void whenNeitherProviderCanAnswerTheAssistantIsReportedUnavailable() {
        when(claude.chat(eq(GENERAL), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(eq(GENERAL), anyList())).thenThrow(new OllamaClient.OllamaUnavailableException());

        assertThatThrownBy(() -> service(false, false).chat(employee, GENERAL, List.of()))
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class);
    }

    @Test
    void aGroundedGeneralQuestionFollowsTheSameRefusalAndFallbackRules() {
        when(retrieval.retrieve(eq(employee), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(claude.chat(any(MyHrGroundedPrompt.class))).thenThrow(new ClaudeChatClient.ClaudeSafetyRefusalException());

        assertThat(service(true, false).chat(employee, GENERAL, List.of()).message()).isEqualTo(MyHrChatService.REFUSAL_REPLY);
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));

        org.mockito.Mockito.reset(claude);
        when(claude.chat(any(MyHrGroundedPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.TRUNCATED));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("[GENERAL] Hi team!");
        MyHrChatResponse fallback = service(true, false).chat(employee, GENERAL, List.of());
        assertThat(fallback.message()).isEqualTo("Hi team!");
        assertThat(fallback.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
    }

    // --- Company policy without approval for Claude (the current shared-readonly configuration) ---------------

    @Test
    void withoutApprovalPolicyTextGoesOnlyToLocalOllamaWithSourceIdsAndThePlainReplyRule() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("You can carry forward up to 5 unused days [S1].");

        MyHrChatResponse response = service(true, false).chat(employee, POLICY_QUESTION, List.of());

        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama).chat(prompt.capture());
        assertThat(prompt.getValue().userMessage()).contains("<source id=\"S1\"", LEAVE_TEXT);
        assertThat(prompt.getValue().systemPrompt()).contains("for example [S1]")
                .endsWith(MyHrKnowledgeGrounding.PLAIN_REPLY_RULE);
        assertThat(response.message()).isEqualTo("You can carry forward up to 5 unused days [1].");
        assertThat(response.citations()).containsExactly(
                new MyHrChatCitation(1, "Synthetic Leave Policy", "leave.pdf", false));
        verify(claude, never()).chatWithDocuments(any());
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        assertThat(logged()).contains("provider=ollama local-policy=true grounded=true sources=1 cited=1 status=GROUNDED");
    }

    // --- Company policy with approval for Claude (prepared; not active in shared-readonly) --------------------

    @Test
    void approvedClaudeAnswersFromCitedDocumentsInTheExistingApiFormat() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(List.of(
                new MyHrCitedSegment("You can carry forward up to 5 unused days.",
                        List.of(new MyHrCitedSegment.Citation("char_location", LEAVE_TEXT, 0, 0, LEAVE_TEXT.length())))));

        MyHrChatResponse response = service(true, true).chat(employee, POLICY_QUESTION, List.of());

        assertThat(response.message()).isEqualTo("You can carry forward up to 5 unused days. [1]");
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.citations()).containsExactly(
                new MyHrChatCitation(1, "Synthetic Leave Policy", "leave.pdf", false));
        verifyNoInteractions(ollama);
        assertThat(logged()).contains("provider=claude citations=native grounded=true sources=1 cited=1 status=GROUNDED")
                .doesNotContain(LEAVE_TEXT, POLICY_QUESTION);
    }

    @Test
    void anApprovedClaudeAnswerThatFailsTheCitationRulesIsWithheldAndNeverReplacedByOllama() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        MyHrChatService service = service(true, true);
        for (List<MyHrCitedSegment> rejected : List.of(
                List.of(new MyHrCitedSegment("You can carry forward up to 5 unused days.", List.of())),
                List.of(new MyHrCitedSegment("You can carry forward up to 30 days.", List.of(
                        new MyHrCitedSegment.Citation("char_location", "Employees may carry forward up to 30 days.", 0, 0, 10)))),
                List.of(new MyHrCitedSegment("You can carry forward up to 30 days.", List.of(
                        new MyHrCitedSegment.Citation("char_location", LEAVE_TEXT, 0, 0, LEAVE_TEXT.length())))))) {
            org.mockito.Mockito.reset(claude);
            when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(rejected);

            MyHrChatResponse response = service.chat(employee, POLICY_QUESTION, List.of());

            assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
            assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
            assertThat(response.citations()).isEmpty();
        }
        verifyNoInteractions(ollama);
        assertThat(logged()).contains("reason=NO_VALID_CITATION", "reason=UNSUPPORTED_CITATION", "reason=UNSUPPORTED_FACT");
    }

    @Test
    void anApprovedClaudeRefusalIsAnsweredSafelyAndPolicyTextNeverReachesOllama() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeSafetyRefusalException());

        MyHrChatResponse response = service(true, true).chat(employee, POLICY_QUESTION, List.of());

        assertThat(response.message()).isEqualTo(MyHrChatService.REFUSAL_REPLY).doesNotContain(LEAVE_TEXT);
        assertThat(response.citations()).isNullOrEmpty();
        verifyNoInteractions(ollama);
        assertThat(logged()).contains("provider=claude refusal=true grounded=true sources=1 cited=0");
    }

    @Test
    void whenApprovedClaudeIsUnavailableLocalOllamaAnswersFromTheSameAuthorizedSources() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeIncompleteResponseException(ClaudeChatClient.IncompleteReason.TRUNCATED));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("You can carry forward up to 5 unused days [S1].");

        MyHrChatResponse response = service(true, true).chat(employee, POLICY_QUESTION, List.of());

        assertThat(response.message()).isEqualTo("You can carry forward up to 5 unused days [1].");
        assertThat(response.citations()).extracting(MyHrChatCitation::title).containsExactly("Synthetic Leave Policy");
        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama).chat(prompt.capture());
        assertThat(prompt.getValue().userMessage()).contains("<source id=\"S1\"", LEAVE_TEXT);
        assertThat(logged()).contains("provider=ollama fallback=true grounded=true sources=1 cited=1 status=GROUNDED");
    }

    @Test
    void whenNeitherApprovedClaudeNorLocalOllamaCanAnswerThePolicyQuestionIsReportedUnavailable() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenThrow(new OllamaClient.OllamaUnavailableException());

        assertThatThrownBy(() -> service(true, true).chat(employee, POLICY_QUESTION, List.of()))
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class);

        // A remote Ollama never receives policy text; that is reported as unavailable too.
        org.mockito.Mockito.reset(ollama);
        when(ollama.isLocalEndpoint()).thenReturn(false);
        assertThatThrownBy(() -> service(true, true).chat(employee, POLICY_QUESTION, List.of()))
                .isInstanceOf(OllamaClient.PolicyContextNotLocalException.class);
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
    }

    @Test
    void hrOnlyPolicyStaysOnLocalOllamaEvenWithApprovalAndOnlyCompanyWidePolicyReachesClaude() {
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(retrieved(
                chunk("leave", "Synthetic Leave Policy", ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT),
                chunk("hr", "Synthetic HR Guidance", ChunkAccessLevel.HR_ONLY, HR_TEXT)));
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(
                chunk("leave", "Synthetic Leave Policy", ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("HR may approve up to 8 extra days [S2].");
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(List.of(new MyHrCitedSegment(
                "You can carry forward up to 5 unused days.", List.of(new MyHrCitedSegment.Citation("char_location",
                        LEAVE_TEXT, 0, 0, LEAVE_TEXT.length())))));
        MyHrChatService service = service(true, true);

        MyHrChatResponse forHr = service.chat(hr, POLICY_QUESTION, List.of());
        MyHrChatResponse forEmployee = service.chat(employee, POLICY_QUESTION, List.of());

        // Internal read permission is not external disclosure permission: the HR request never reaches Claude.
        ArgumentCaptor<MyHrGroundedDocumentPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedDocumentPrompt.class);
        verify(claude, org.mockito.Mockito.times(1)).chatWithDocuments(prompts.capture());
        assertThat(prompts.getValue().documents()).extracting(MyHrGroundedDocumentPrompt.Document::title)
                .containsExactly("Synthetic Leave Policy");
        assertThat(prompts.getValue().documents()).extracting(MyHrGroundedDocumentPrompt.Document::text)
                .noneMatch(text -> text.contains(HR_TEXT));
        ArgumentCaptor<MyHrGroundedPrompt> local = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama).chat(local.capture());
        assertThat(local.getValue().userMessage()).contains(LEAVE_TEXT, HR_TEXT);
        assertThat(forHr.citations()).containsExactly(
                new MyHrChatCitation(1, "Synthetic Leave Policy", "leave.pdf", false),
                new MyHrChatCitation(2, "Synthetic HR Guidance", "hr.pdf", true));
        assertThat(forHr.message()).contains("5 unused", "8 extra");
        assertThat(forEmployee.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(forEmployee.citations()).containsExactly(
                new MyHrChatCitation(1, "Synthetic Leave Policy", "leave.pdf", false));
        assertThat(logged()).contains("provider=ollama local-policy=true grounded=true sources=2 cited=2",
                "external-policy=RESTRICTED_CONTENT", "external-policy=PERMITTED");
    }

    @Test
    void onPolicyPathsOnlyAvailabilityFailuresReachOllamaNeverConfigurationRejectionOrSecurityFailures() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(chunk("leave", "Synthetic Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        for (RuntimeException notEligible : List.of(new ClaudeChatClient.ClaudeConfigurationException(),
                new ClaudeChatClient.ClaudeRequestRejectedException())) {
            org.mockito.Mockito.reset(claude);
            when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenThrow(notEligible);
            when(claude.chat(any(MyHrGroundedPrompt.class))).thenThrow(notEligible);

            // Approved Claude with policy documents, and a grounded general question without policy text.
            assertThatThrownBy(() -> service(true, true).chat(employee, POLICY_QUESTION, List.of())).isSameAs(notEligible);
            when(retrieval.retrieve(eq(employee), eq(GENERAL)))
                    .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
            assertThatThrownBy(() -> service(true, true).chat(employee, GENERAL, List.of())).isSameAs(notEligible);
        }

        // Authorization failures from retrieval are never answered by any provider.
        for (RuntimeException denied : List.of(new com.waypoint.exception.UnauthorizedException("Account is not active"),
                new com.waypoint.exception.ForbiddenException("You do not have access to this resource"))) {
            org.mockito.Mockito.reset(retrieval, claude);
            when(retrieval.retrieve(any(), anyString())).thenThrow(denied);
            assertThatThrownBy(() -> service(true, true).chat(employee, POLICY_QUESTION, List.of())).isSameAs(denied);
            verifyNoInteractions(claude);
        }
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
    }

    /**
     * externalApproved: the deployment switch is on and, for this test only, the organization has explicitly
     * authorized external processing of its company-wide policy. Waypoint itself records no such authorization.
     */
    private MyHrChatService service(boolean groundingEnabled, boolean externalApproved) {
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                mock(EmployeeManagementService.class), mock(DocumentRequestService.class), "claude", retrieval,
                groundingEnabled, externalApproved, false,
                externalApproved ? (organizationId, chunks) -> true : ExternalPolicyProcessingAuthorization.DENY_ALL);
    }

    private static RetrievalResult retrieved(RetrievedChunk... chunks) {
        return new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(chunks), chunks.length);
    }

    private static RetrievedChunk chunk(String document, String title, ChunkAccessLevel level, String text) {
        return new RetrievedChunk(document + "-ingestion-00000", document, title, "stored-" + document,
                document + ".pdf", document + "-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}
