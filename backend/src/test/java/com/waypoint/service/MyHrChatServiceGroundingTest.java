package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.MyHrChatCitation;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.ReportingManagerRef;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Mock-based tests of chat knowledge grounding: no Claude, Ollama, Voyage or MongoDB call is made. */
class MyHrChatServiceGroundingTest {

    private static final String ORG = "org-acme-77";
    private static final String QUESTION = "How much vacation do new employees get?";
    private static final String POLICY_TEXT = "New employees accrue 1.5 vacation days per month.";
    /** Not about company policy, so without excerpts it still reaches the model as general guidance. */
    private static final String GENERAL = "Write a polite email to my team";
    private static final String EMPLOYEE_CARRY_FORWARD = "How many unused vacation days can I carry forward?";
    private static final String EMPLOYEE_WIDE_CARRY_FORWARD = "How many unused vacation days can employees carry forward?";

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-71", ORG, UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-71", ORG);

    private final Logger logger = (Logger) LoggerFactory.getLogger(MyHrChatService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
        // Default for these tests; the routing tests below override it explicitly.
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
    }

    @AfterEach
    void stopCapturingLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    // --- Switches and privacy -------------------------------------------------------------------

    @Test
    void groundingOffDoesNotLetAModelInventACompanyPolicy() {
        when(ollama.chat(QUESTION, List.of())).thenReturn("General answer.");

        MyHrChatResponse response = service("ollama", false, false).chat(employee, QUESTION, List.of());

        assertThat(response).isEqualTo(new MyHrChatResponse(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER,
                MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE, List.of()));
        verifyNoInteractions(retrieval, claude);
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
    }

    @Test
    void claudePrimaryWithoutExternalApprovalGroundsPolicyAnswersOnLocalOllamaOnly() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("They accrue 1.5 days per month [S1].");

        MyHrChatResponse response = service("claude", true, false).chat(employee, QUESTION, List.of());

        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama).chat(prompt.capture());
        assertThat(prompt.getValue().containsPolicyContext()).isTrue();
        assertThat(prompt.getValue().userMessage()).contains(POLICY_TEXT);
        verifyNoInteractions(claude);
    }

    @Test
    void withoutApprovalOrALocalOllamaNothingIsRetrievedAndClaudeIsToldPoliciesCannotBeChecked() {
        when(ollama.isLocalEndpoint()).thenReturn(false);
        when(claude.chat(any(MyHrGroundedPrompt.class))).thenReturn("I can't check company policies right now.");

        MyHrChatResponse response = service("claude", true, false).chat(employee, GENERAL, List.of());

        verifyNoInteractions(retrieval);
        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(claude).chat(prompt.capture());
        assertThat(prompt.getValue().containsPolicyContext()).isFalse();
        assertThat(prompt.getValue().userMessage()).doesNotContain("<policy_context>");
        assertThat(prompt.getValue().systemPrompt()).contains(MyHrKnowledgeGrounding.CONTEXT_UNAVAILABLE);
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE);
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        MyHrChatResponse policy = service("ollama", true, false).chat(employee, QUESTION, List.of());
        assertThat(policy.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE);
        assertThat(policy.message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);
        verify(claude, times(1)).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verifyNoInteractions(retrieval);
    }

    @Test
    void approvedClaudeFallsBackWithPolicyTextOnlyToALocalOllama() {
        when(ollama.isLocalEndpoint()).thenReturn(false);
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeUnavailableException());

        assertThatThrownBy(() -> service("claude", true, true).chat(employee, QUESTION, List.of()))
                .isInstanceOf(OllamaClient.PolicyContextNotLocalException.class);
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
    }

    @Test
    void uncitedAnswersToSuppliedPolicyAreWithheldIdenticallyForClaudeAndOllama() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(
                List.of(new MyHrCitedSegment("Employees get 99 vacation days [4].", List.of())));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Employees get 99 vacation days [4].");

        for (MyHrChatService service : List.of(service("claude", true, true), service("ollama", true, false))) {
            MyHrChatResponse response = service.chat(employee, QUESTION, List.of());
            assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
            assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
            assertThat(response.citations()).isEmpty();
        }

        // QUESTION is about company policy: a [GENERAL] marker does not exempt the uncited answer.
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("[GENERAL] Here is a draft email.");
        MyHrChatResponse markedPolicyAnswer = service("ollama", true, false).chat(employee, QUESTION, List.of());
        assertThat(markedPolicyAnswer.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
        assertThat(markedPolicyAnswer.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(markedPolicyAnswer.citations()).isEmpty();

        // A general question that happens to match a policy excerpt still gets its general answer.
        when(retrieval.retrieve(employee, GENERAL)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        MyHrChatResponse general = service("ollama", true, false).chat(employee, GENERAL, List.of());
        assertThat(general.message()).isEqualTo("Here is a draft email.");
        assertThat(general.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
        assertThat(logText()).contains("status=UNVERIFIED reason=NO_VALID_CITATION")
                .doesNotContain(QUESTION, POLICY_TEXT);
    }

    @Test
    void disabledRetrievalDoesNotLetAModelInventACompanyPolicy() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(new RetrievalResult(RetrievalStatus.DISABLED, List.of(), 0));
        when(ollama.chat(QUESTION, List.of())).thenReturn("General answer.");

        MyHrChatResponse response = service("ollama", true, false).chat(employee, QUESTION, List.of());

        assertThat(response).isEqualTo(new MyHrChatResponse(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER,
                MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE, List.of()));
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
    }

    // --- Grounded answers -----------------------------------------------------------------------

    @Test
    void aCitedLocalAnswerStatingAnAmountNoSuppliedExcerptContainsIsWithheld() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class)))
                .thenReturn("New employees accrue 1.5 days per month [S1]. Bonuses are 50% [S1].");

        MyHrChatResponse response = service("ollama", true, false).chat(employee, QUESTION, List.of());

        // The same rule as for provider-reported citations: a citation does not make an invented amount true.
        assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER).doesNotContain("50");
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void localPrimaryGroundsTheAnswerAndReturnsOnlyTheSourcesItCites() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT),
                chunk("travel", "Travel Policy", ChunkAccessLevel.COMPANY_WIDE, "Book economy class.")));
        when(ollama.chat(any(MyHrGroundedPrompt.class)))
                .thenReturn("New employees accrue 1.5 days per month [S1]. Ask your manager first [S7].");

        MyHrChatResponse response = service("ollama", true, false).chat(employee, QUESTION, List.of());

        assertThat(response.message()).isEqualTo("“" + POLICY_TEXT + "” [1]").doesNotContain("Ask your manager");
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.citations()).containsExactly(new MyHrChatCitation(1, "Leave Policy", "leave.pdf", false));

        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama).chat(prompt.capture());
        assertThat(prompt.getValue().systemPrompt()).startsWith(MyHrKnowledgeGrounding.GROUNDED_SYSTEM_PROMPT);
        assertThat(prompt.getValue().userMessage()).contains(POLICY_TEXT, "Book economy class.", QUESTION);
        verify(retrieval, times(1)).retrieve(employee, QUESTION);
        verify(ollama, never()).chat(anyString(), anyList());
        verifyNoInteractions(claude);
    }

    @Test
    void approvedClaudeFallbackReceivesTheSameSourcesHistoryAndQuestionWithoutASecondRetrieval() {
        when(retrieval.retrieve(hr, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class)))
                .thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Yes [S1].");

        // A server-stamped, permitted earlier turn: the only history an external provider may receive.
        StampedHistory stamps = new StampedHistory();
        MyHrChatResponse response = stamps.service(claude, ollama, "claude", retrieval, true, true, false,
                        (organizationId, chunks) -> true)
                .chat(hr, QUESTION, stamps.turns(hr, MyHrChatHistoryProvenance.ExternalUse.PERMITTED, "Hello", "Hi!"),
                        StampedHistory.SESSION);

        ArgumentCaptor<MyHrGroundedDocumentPrompt> toClaude = ArgumentCaptor.forClass(MyHrGroundedDocumentPrompt.class);
        ArgumentCaptor<MyHrGroundedPrompt> toOllama = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(claude).chatWithDocuments(toClaude.capture());
        verify(ollama).chat(toOllama.capture());
        // The same authorized source, history and escaped question; only the form differs per provider.
        assertThat(toOllama.getValue().history()).hasSize(2).isEqualTo(toClaude.getValue().history());
        assertThat(toClaude.getValue().documents()).singleElement()
                .extracting(MyHrGroundedDocumentPrompt.Document::text).isEqualTo(POLICY_TEXT);
        assertThat(toOllama.getValue().userMessage()).contains(POLICY_TEXT).endsWith(toClaude.getValue().question());
        verify(retrieval, times(1)).retrieve(any(), anyString());
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(logText()).contains("provider=ollama fallback=true grounded=true sources=1 cited=1 status=GROUNDED");
    }

    @Test
    void approvedClaudeReceivesTheAuthorizedSourcesAsCitedDocumentsAsPrimary() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(List.of(new MyHrCitedSegment(
                "They accrue 1.5 days per month.", List.of(new MyHrCitedSegment.Citation("char_location", POLICY_TEXT, 0,
                        0, POLICY_TEXT.length())))));

        MyHrChatResponse response = service("claude", true, true).chat(employee, QUESTION, List.of());

        assertThat(response.message()).isEqualTo("“" + POLICY_TEXT + "” [1]");
        assertThat(response.citations()).containsExactly(new MyHrChatCitation(1, "Leave Policy", "leave.pdf", false));
        verify(claude, never()).chat(anyString(), anyList());
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        verifyNoInteractions(ollama);
        assertThat(logText()).contains(
                "provider=claude citations=native grounded=true sources=1 cited=1 status=GROUNDED reason=none");
    }

    @Test
    void knowledgeIsRetrievedFreshForEveryMessage() {
        when(retrieval.retrieve(any(), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("General answer.");
        MyHrChatService service = service("ollama", true, false);

        service.chat(employee, QUESTION, List.of());
        service.chat(employee, QUESTION, List.of());
        service.chat(employee, "What is the travel policy?", List.of());

        verify(retrieval, times(2)).retrieve(employee, QUESTION);
        verify(retrieval).retrieve(employee, "What is the travel policy?");
    }

    @Test
    void noMatchingPolicyKeepsGeneralAnswersAndShowsNoInventedCitations() {
        when(retrieval.retrieve(employee, "Write a polite email to my team"))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 3));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Here is a draft [S1].");

        MyHrChatResponse response = service("ollama", true, false).chat(employee, "Write a polite email to my team", List.of());

        assertThat(response.message()).isEqualTo("Here is a draft.");
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
        assertThat(response.citations()).isEmpty();
        ArgumentCaptor<MyHrGroundedPrompt> prompt = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama).chat(prompt.capture());
        assertThat(prompt.getValue().systemPrompt()).contains(MyHrKnowledgeGrounding.CONTEXT_NONE_FOUND);
    }

    // --- Unsupported policy claims --------------------------------------------------------------

    /** Policy questions with obvious policy keywords. */
    private static final List<String> KEYWORD_POLICY_QUESTIONS = List.of(
            "What is the offer letter policy for contractors?", "Are interns eligible for health insurance?",
            "What is the bonus structure?", "How much is the travel reimbursement?",
            "Can I take some time off next month?", "What is our parental leave?");
    /** Policy questions without the word "policy" or similar obvious rule terms. */
    private static final List<String> IMPLICIT_POLICY_QUESTIONS = List.of(
            "Do contractors receive an offer letter?", "Does everyone get a laptop?", "Do we get Juneteenth off?",
            "Do I need to give two weeks notice before quitting?", "Can I expense my home internet?");

    @Test
    void policyQuestionsWithoutApprovedExcerptsGetAFixedReplyAndNeverReachAModel() {
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);

        for (MyHrChatService service : List.of(service("ollama", true, false), service("claude", true, true))) {
            for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
                for (String question : concat(KEYWORD_POLICY_QUESTIONS, IMPLICIT_POLICY_QUESTIONS)) {
                    assertThat(service.chat(actor, question, List.of())).as(question).isEqualTo(new MyHrChatResponse(
                            MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER, MyHrKnowledgeStatus.NO_MATCHING_POLICY, List.of()));
                    verify(retrieval, org.mockito.Mockito.atLeastOnce()).retrieve(actor, question);
                }
            }
        }

        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
        verifyNoInteractions(claude);
        assertThat(logText()).contains("provider=none grounded=true sources=0 cited=0 status=NO_MATCHING_POLICY "
                + "policy-question=true").doesNotContain("contractors", "laptop", "Juneteenth");
    }

    @Test
    void policyQuestionsWhilePoliciesCannotBeCheckedGetAFixedUnavailableReply() {
        when(retrieval.retrieve(employee, "Do contractors receive an offer letter?"))
                .thenReturn(new RetrievalResult(RetrievalStatus.UNAVAILABLE, List.of(), 0));
        when(retrieval.retrieve(employee, "What is the bonus structure?")).thenThrow(new IllegalStateException("down"));
        MyHrChatService service = service("ollama", true, false);

        for (String question : List.of("Do contractors receive an offer letter?", "What is the bonus structure?")) {
            assertThat(service.chat(employee, question, List.of())).as(question).isEqualTo(new MyHrChatResponse(
                    MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER, MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE, List.of()));
        }

        // No approved destination for policy text: nothing is retrieved and no model is asked either.
        when(ollama.isLocalEndpoint()).thenReturn(false);
        assertThat(service("claude", true, false).chat(employee, "Are interns eligible for health insurance?", List.of())
                .message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);

        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verifyNoInteractions(claude);
        verify(retrieval, never()).retrieve(employee, "Are interns eligible for health insurance?");
    }

    @Test
    void anEligibilityQuestionAboutOfferLettersIsAPolicyQuestionNotWorkflowGuidance() {
        String question = "Do contractors receive an offer letter?";
        when(retrieval.retrieve(any(), anyString())).thenReturn(retrieved(chunk("contractors", "Contractor Policy",
                ChunkAccessLevel.COMPANY_WIDE, "Contractors sign an Independent Contractor Agreement.")));
        when(ollama.chat(any(MyHrGroundedPrompt.class)))
                .thenReturn("Contractors sign an Independent Contractor Agreement [S1].", "Contractors get a letter.");
        MyHrChatService service = service("ollama", true, false);

        MyHrChatResponse cited = service.chat(employee, question, List.of());
        assertThat(cited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(cited.citations()).extracting(MyHrChatCitation::title).containsExactly("Contractor Policy");

        MyHrChatResponse uncited = service.chat(employee, question, List.of());
        assertThat(uncited.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
        assertThat(uncited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(logText()).doesNotContain("workflow=");
    }

    @Test
    void generalConversationGreetingsAndWorkflowsAreUnchanged() {
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("General answer.");
        MyHrChatService service = service("ollama", true, false);

        for (String general : List.of(GENERAL, "Can you help me plan my week?", "Explain what a W-2 form is")) {
            assertThat(service.chat(employee, general, List.of())).as(general).isEqualTo(new MyHrChatResponse(
                    "General answer.", MyHrKnowledgeStatus.NO_MATCHING_POLICY, List.of()));
        }
        verify(ollama, times(3)).chat(any(MyHrGroundedPrompt.class));
        // General knowledge is outside Wavi's company scope: refused before retrieval or any model.
        assertThat(service.chat(employee, "What is the capital of France?", List.of()).message())
                .isEqualTo(MyHrScopeGuard.OUT_OF_SCOPE_REPLY);
        verify(retrieval, never()).retrieve(employee, "What is the capital of France?");

        assertThat(service.chat(employee, "hello", List.of()).message()).isEqualTo("Hello! How can I help you today?");
        assertThat(service.chat(employee, "I'm a new hire and want my official offer letter", List.of()).message())
                .contains("choose Request a document", "Offer Letter");
        verify(retrieval, never()).retrieve(employee, "hello");
        verify(retrieval, never()).retrieve(employee, "I'm a new hire and want my official offer letter");
    }

    @Test
    void policyQuestionDetectionIsConservativeButLeavesGeneralQuestionsToTheModel() {
        for (String question : concat(KEYWORD_POLICY_QUESTIONS, IMPLICIT_POLICY_QUESTIONS)) {
            assertThat(MyHrKnowledgeGrounding.isPolicyQuestion(question)).as(question).isTrue();
        }
        for (String general : List.of(GENERAL, "Can you help me plan my week?", "What is the capital of France?",
                "Can I ask you something?", "How do I write a resignation letter?")) {
            assertThat(MyHrKnowledgeGrounding.isPolicyQuestion(general)).as(general).isFalse();
        }
        assertThat(MyHrKnowledgeGrounding.isGroupEligibilityQuestion("Do contractors receive an offer letter?")).isTrue();
        assertThat(MyHrKnowledgeGrounding.isGroupEligibilityQuestion("I'm a new hire and want my official offer letter"))
                .isFalse();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }

    // --- Fail closed ----------------------------------------------------------------------------

    @Test
    void retrievalFailuresTellTheModelPoliciesCannotBeCheckedAndFlagTheResponse() {
        when(retrieval.retrieve(employee, GENERAL))
                .thenReturn(new RetrievalResult(RetrievalStatus.UNAVAILABLE, List.of(), 0))
                .thenThrow(new IllegalStateException("mongodb+srv://user:s3cret@cluster.example " + POLICY_TEXT));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("I can't check company policies right now.");
        MyHrChatService service = service("ollama", true, false);

        for (int attempt = 0; attempt < 2; attempt++) {
            MyHrChatResponse response = service.chat(employee, GENERAL, List.of());
            assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE);
            assertThat(response.citations()).isEmpty();
        }

        ArgumentCaptor<MyHrGroundedPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama, times(2)).chat(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> {
            assertThat(prompt.systemPrompt()).contains(MyHrKnowledgeGrounding.CONTEXT_UNAVAILABLE);
            assertThat(prompt.userMessage()).doesNotContain("<policy_context>");
        });
        assertThat(logText()).doesNotContain("mongodb", "s3cret", POLICY_TEXT, GENERAL);
    }

    @Test
    void authorizationFailuresFromRetrievalAreNotSwallowed() {
        when(retrieval.retrieve(employee, QUESTION)).thenThrow(new ForbiddenException("denied"));

        assertThatThrownBy(() -> service("ollama", true, false).chat(employee, QUESTION, List.of()))
                .isInstanceOf(ForbiddenException.class);
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
        verifyNoInteractions(claude);
    }

    @Test
    void aMissingActorIsRejectedAndTheUserIdPathIsNeverGrounded() {
        MyHrChatService service = service("ollama", true, false);
        assertThatThrownBy(() -> service.chat((VerifiedMyHrActor) null, QUESTION, List.of()))
                .isInstanceOf(UnauthorizedException.class);

        when(ollama.chat(QUESTION, List.of())).thenReturn("General answer.");
        assertThat(service.chat(QUESTION, List.of(), "employee-71").knowledgeStatus()).isNull();
        verifyNoInteractions(retrieval);
    }

    @Test
    void personalLookupsUseTheVerifiedUserAndNeverRetrieveKnowledge() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("TD002");
        when(employees.getOwnDetail("employee-71")).thenReturn(profile);

        MyHrChatResponse response = service("ollama", true, false).chat(employee, "What is my employee ID?", List.of());

        assertThat(response.message()).isEqualTo("Your employee ID is TD002.");
        verifyNoInteractions(retrieval, ollama, claude);
    }

    @Test
    void whoAmIUsesOnlyEachVerifiedActorsOwnProfileWithoutAnyProvider() {
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        MyHrOwnProfileResponse employeeProfile = mock(MyHrOwnProfileResponse.class);
        MyHrOwnProfileResponse hrProfile = mock(MyHrOwnProfileResponse.class);
        MyHrOwnProfileResponse adminProfile = mock(MyHrOwnProfileResponse.class);
        when(employeeProfile.name()).thenReturn("Employee One");
        when(hrProfile.name()).thenReturn("HR One");
        when(adminProfile.name()).thenReturn("Admin One");
        when(employees.getOwnDetail("employee-71")).thenReturn(employeeProfile);
        when(employees.getOwnDetail("hr-71")).thenReturn(hrProfile);
        when(employees.getOwnDetail("admin-71")).thenReturn(adminProfile);
        MyHrChatService service = service("claude", true, false);

        for (String question : List.of("Who am I?", "Who am I", "who am i")) {
            assertThat(service.chat(employee, question, List.of()).message()).as(question)
                    .contains("Your profile:", "Name: Employee One").doesNotContain("HR One", "Admin One");
        }
        assertThat(service.chat(hr, "Who am I?", List.of()).message()).contains("Name: HR One")
                .doesNotContain("Employee One", "Admin One");
        assertThat(service.chat(admin, "who am i", List.of()).message()).contains("Name: Admin One")
                .doesNotContain("Employee One", "HR One");

        verify(employees, times(3)).getOwnDetail("employee-71");
        verify(employees).getOwnDetail("hr-71");
        verify(employees).getOwnDetail("admin-71");
        verifyNoInteractions(retrieval, ollama, claude);
    }

    // --- Small talk -----------------------------------------------------------------------------

    @Test
    void standaloneGreetingsGetAFixedReplyWithoutRetrievalOrAnyProvider() {
        Map<String, String> replies = Map.ofEntries(
                Map.entry("hello", "Hello! How can I help you today?"),
                Map.entry("  Hello there!  ", "Hello! How can I help you today?"),
                Map.entry("HI", "Hi there! How can I help you today?"),
                Map.entry("hey", "Hi there! How can I help you today?"),
                Map.entry("Hi there.", "Hi there! How can I help you today?"),
                Map.entry("hey there?", "Hi there! How can I help you today?"),
                Map.entry("good morning", "Good morning! How can I help you today?"),
                Map.entry("Good  Afternoon!", "Good afternoon! How can I help you today?"),
                Map.entry("good evening.", "Good evening! How can I help you today?"),
                Map.entry("thanks", "You're welcome!"),
                Map.entry("Thank you!", "You're welcome!"),
                Map.entry("thank you very much", "You're welcome!"),
                Map.entry("Thanks a lot!!", "You're welcome!"),
                Map.entry("bye", "Goodbye! Have a great day."),
                Map.entry("Goodbye.", "Goodbye! Have a great day."),
                Map.entry("see you!", "Goodbye! Have a great day."));

        for (MyHrChatService service : List.of(service("ollama", true, false), service("claude", true, true),
                service("ollama", false, false))) {
            for (VerifiedMyHrActor actor : List.of(employee, hr)) {
                replies.forEach((message, reply) -> assertThat(service.chat(actor, message, List.of()))
                        .as(message).isEqualTo(new MyHrChatResponse(reply)));
            }
        }

        verifyNoInteractions(retrieval, ollama, claude);
        assertThat(logText()).contains("MYHR_CHAT_PROVIDER_USED provider=none small-talk=true")
                .doesNotContainIgnoringCase("hello").doesNotContainIgnoringCase("thank");
    }

    @Test
    void greetingsCombinedWithAQuestionStillRequireGroundingAndCitations() {
        String withGreeting = "hello, how many vacation days can I carry forward?";
        String employeeWideQuery = "hello, how many vacation days can employees carry forward?";
        when(retrieval.retrieve(employee, employeeWideQuery)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("You accrue 1.5 vacation days per month [S1].",
                "Up to 30 days.");
        MyHrChatService service = service("ollama", true, false);

        MyHrChatResponse cited = service.chat(employee, withGreeting, List.of());
        assertThat(cited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(cited.citations()).extracting(MyHrChatCitation::title).containsExactly("Leave Policy");

        MyHrChatResponse uncited = service.chat(employee, withGreeting, List.of());
        assertThat(uncited.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
        assertThat(uncited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        verify(retrieval, times(2)).retrieve(employee, employeeWideQuery);

        for (String notSmallTalk : List.of("hi team", "thanks for the leave summary", "good morning, what is the travel policy?",
                "bye for now, see you tomorrow", "hello hello")) {
            when(retrieval.retrieve(employee, notSmallTalk))
                    .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
            service.chat(employee, notSmallTalk, List.of());
            verify(retrieval).retrieve(employee, notSmallTalk);
        }
    }

    @Test
    void employeeAndHrCarryForwardQuestionsUseTheSameCompanyWidePolicyQuery() {
        RetrievedChunk leavePolicy = chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE,
                "Employees can carry forward up to 5 unused annual-leave days into the next year.");
        when(retrieval.retrieve(hr, EMPLOYEE_WIDE_CARRY_FORWARD)).thenReturn(retrieved(leavePolicy));
        when(retrieval.retrieve(employee, EMPLOYEE_WIDE_CARRY_FORWARD)).thenReturn(retrieved(leavePolicy));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Employees can carry forward up to 5 unused annual-leave days into the next year [S1].");
        MyHrChatService service = service("ollama", true, false);

        MyHrChatResponse hrResponse = service.chat(hr, EMPLOYEE_WIDE_CARRY_FORWARD, List.of());
        MyHrChatResponse employeeResponse = service.chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());

        assertThat(hrResponse.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(employeeResponse.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(employeeResponse.citations()).containsExactly(
                new MyHrChatCitation(1, "Employee Leave Policy", "leave.pdf", false));
        verify(retrieval).retrieve(employee, EMPLOYEE_WIDE_CARRY_FORWARD);
        verifyNoInteractions(claude);
    }

    @Test
    void aPolicySectionNumberIsNeverReadAsTheSourceItCites() {
        RetrievedChunk sections = new RetrievedChunk("leave-ingestion-00000", "leave", "Employee Leave Policy",
                "stored-leave", "leave.pdf", "leave-ingestion", 0, ChunkAccessLevel.COMPANY_WIDE,
                LocalDateTime.of(2026, 10, 1, 9, 0), 0.82, "1. Accrual\nFull-time employees accrue 15 vacation days.");
        RetrievedChunk carryForward = new RetrievedChunk("leave-ingestion-00001", "leave", "Employee Leave Policy",
                "stored-leave", "leave.pdf", "leave-ingestion", 1, ChunkAccessLevel.COMPANY_WIDE,
                LocalDateTime.of(2026, 10, 1, 9, 0), 0.78, "2. Carry forward\nEmployees can carry forward up to 5 unused days.");
        when(retrieval.retrieve(employee, EMPLOYEE_WIDE_CARRY_FORWARD)).thenReturn(retrieved(sections, carryForward));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "You can carry forward up to 5 unused vacation days [S1].",
                "You can carry forward up to 5 unused vacation days [2].",
                "You can carry forward up to 5 unused vacation days [2]."); // the one bounded local retry, still uncited
        MyHrChatService service = service("ollama", true, false);

        MyHrChatResponse cited = service.chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());
        MyHrChatResponse sectionNumbered = service.chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());

        ArgumentCaptor<MyHrGroundedPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama, times(3)).chat(prompts.capture()); // cited once; the section-numbered answer plus its single retry
        // One document is one source, labelled S1 however many of its excerpts were retrieved.
        String user = prompts.getValue().userMessage();
        assertThat(user).contains("<source id=\"S1\" title=\"Employee Leave Policy\"").doesNotContain("<source id=\"1\"");
        assertThat(user.split("<source ", -1)).hasSize(2);
        assertThat(cited.message()).isEqualTo("You can carry forward up to 5 unused vacation days [1]."
                );
        assertThat(cited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(cited.citations()).containsExactly(
                new MyHrChatCitation(1, "Employee Leave Policy", "leave.pdf", false));
        assertThat(sectionNumbered.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(sectionNumbered.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
        assertThat(sectionNumbered.citations()).isEmpty();
        assertThat(logText())
                .contains("provider=ollama local-policy=true grounded=true sources=1 cited=1 status=GROUNDED reason=none")
                .contains("provider=ollama local-policy=true citation-retry=true grounded=true sources=1 cited=0 "
                        + "status=UNVERIFIED reason=NO_VALID_CITATION")
                .doesNotContain("carry forward up to 5", "15 vacation days", EMPLOYEE_CARRY_FORWARD);
        verifyNoInteractions(claude);
    }

    @Test
    void withTwoSourcesASectionNumberIsNotAttributedToTheSecondSource() {
        RetrievedChunk leave = chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE,
                "2. Carry forward\nEmployees can carry forward up to 5 unused days.");
        RetrievedChunk travel = chunk("travel", "Travel Policy", ChunkAccessLevel.COMPANY_WIDE, "Book economy class.");
        when(retrieval.retrieve(employee, EMPLOYEE_WIDE_CARRY_FORWARD)).thenReturn(retrieved(leave, travel));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                // Section 2 of the leave policy: before S labels this was accepted as a citation of the Travel Policy.
                "You can carry forward up to 5 unused days [2].",
                "You can carry forward up to 5 unused days [2].", // the one bounded local retry, still uncited
                "You can carry forward up to 5 unused days [S1]. Book economy class [S2].",
                "You can carry forward up to 5 unused days [S1, 2, S3].");
        MyHrChatService service = service("ollama", true, false);

        MyHrChatResponse sectionNumbered = service.chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());
        MyHrChatResponse both = service.chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());
        MyHrChatResponse mixed = service.chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());

        assertThat(sectionNumbered.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(sectionNumbered.citations()).isEmpty();
        assertThat(both.message()).isEqualTo("“Employees can carry forward up to 5 unused days.” [1]")
                .doesNotContain("economy class");
        assertThat(both.citations()).extracting(MyHrChatCitation::title)
                .containsExactly("Employee Leave Policy");
        // Only the supplied source id survives; the bare number and the unsupplied id are dropped, not converted.
        assertThat(mixed.message()).isEqualTo("You can carry forward up to 5 unused days [1].");
        assertThat(mixed.citations()).extracting(MyHrChatCitation::title).containsExactly("Employee Leave Policy");
    }

    @Test
    void stripsAnEchoedGroundedPromptForEmployeeHrAndCompanyAdminBeforeValidatingCitations() {
        String question = "How many unused vacation days can employees carry forward?";
        String answer = "According to the approved demo leave policy, employees can carry forward up to 5 unused "
                + "annual-leave days into the next year. [1]";
        // The excerpt states the answer's fact, so only the echo handling decides the result.
        String rawExcerpt = "INTERNAL RETRIEVAL EXCERPT: do not display this source text. Up to 5 unused days carry forward.";
        String echoedPrompt = "I'm Wavi, Waypoint's HR and employee-support assistant.\n"
                + "<policy_context>\n<source id=\"S1\"><excerpt>\n" + rawExcerpt
                + "\n</excerpt></source>\n</policy_context>\n\n<employee_question>\n" + question
                + "\n</employee_question>\n" + answer.replace("[1]", "[S1]");
        RetrievedChunk leavePolicy = chunk("demo-leave", "SAMPLE Employee Leave Policy — DEMO ONLY",
                ChunkAccessLevel.COMPANY_WIDE, rawExcerpt);
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            when(retrieval.retrieve(actor, question)).thenReturn(retrieved(leavePolicy));
        }
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(echoedPrompt);
        MyHrChatService service = service("ollama", true, false);

        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            MyHrChatResponse response = service.chat(actor, question, List.of());
            assertThat(response.message()).isEqualTo("“Up to 5 unused days carry forward.” [1]" + "\n\nNote: this document is marked as a demo or fictional sample, so treat it as sample information, not as an official employee entitlement.")
                    .doesNotContain("I'm Wavi", "policy_context", "<source", "<excerpt>", rawExcerpt);
            assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
            assertThat(response.citations()).containsExactly(new MyHrChatCitation(1,
                    "SAMPLE Employee Leave Policy — DEMO ONLY", "demo-leave.pdf", false));
        }
        verifyNoInteractions(claude);
    }

    @Test
    void employeePersonalCarryForwardQuestionRetainsACitedAnswerAfterACompletePromptEcho() {
        String answer = "According to the approved demo leave policy, employees can carry forward up to 5 unused "
                + "annual-leave days into the next year. [1]";
        String echoedPrompt = "I'm Wavi, Waypoint's HR and employee-support assistant.\n"
                + "<policy_context><source id=\"S1\"><excerpt>Internal source text.</excerpt></source></policy_context>\n"
                + "<employee_question>\n" + EMPLOYEE_CARRY_FORWARD + "\n</employee_question>\n" + answer.replace("[1]", "[S1]");
        when(retrieval.retrieve(employee, EMPLOYEE_WIDE_CARRY_FORWARD)).thenReturn(retrieved(
                chunk("demo-leave", "SAMPLE Employee Leave Policy — DEMO ONLY", ChunkAccessLevel.COMPANY_WIDE,
                        "Employees can carry forward up to 5 unused annual-leave days into the next year.")));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(echoedPrompt);

        MyHrChatResponse response = service("ollama", true, false).chat(employee, EMPLOYEE_CARRY_FORWARD, List.of());

        assertThat(response.message()).isEqualTo("According to the approved demo leave policy, employees can carry forward up to 5 unused annual-leave days into the next year. [1]" + "\n\nNote: this document is marked as a demo or fictional sample, so treat it as sample information, not as an official employee entitlement.")
                .doesNotContain("policy_context", "Internal source text.");
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.citations()).containsExactly(new MyHrChatCitation(1,
                "SAMPLE Employee Leave Policy — DEMO ONLY", "demo-leave.pdf", false));
    }

    @Test
    void removesAProviderScopeIntroductionForEmployeeHrAndCompanyAdminButKeepsTheValidatedPolicyAnswer() {
        String answer = "According to the approved demo leave policy, employees can carry forward up to 5 unused "
                + "annual-leave days into the next year. [1]";
        String providerResponse = "I'm Wavi, Waypoint's HR and employee-support assistant, so I can only help with "
                + "Waypoint and company topics: timesheets, payroll, your profile, document requests, onboarding, "
                + "company policies, and your work on the company. Is there something work-related I can help you with?"
                + "\n\n" + answer.replace("[1]", "[S1]");
        RetrievedChunk leavePolicy = chunk("demo-leave", "SAMPLE Employee Leave Policy — DEMO ONLY",
                ChunkAccessLevel.COMPANY_WIDE,
                "Employees can carry forward up to 5 unused annual-leave days into the next year.");
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            when(retrieval.retrieve(actor, EMPLOYEE_WIDE_CARRY_FORWARD)).thenReturn(retrieved(leavePolicy));
        }
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(providerResponse);
        MyHrChatService service = service("ollama", true, false);

        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            MyHrChatResponse response = service.chat(actor, EMPLOYEE_CARRY_FORWARD, List.of());

            assertThat(response.message()).isEqualTo("According to the approved demo leave policy, employees can carry forward up to 5 unused annual-leave days into the next year. [1]" + "\n\nNote: this document is marked as a demo or fictional sample, so treat it as sample information, not as an official employee entitlement.")
                    .doesNotContain("I'm Wavi", "work-related");
            assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
            assertThat(response.citations()).containsExactly(new MyHrChatCitation(1,
                    "SAMPLE Employee Leave Policy — DEMO ONLY", "demo-leave.pdf", false));
        }
        verifyNoInteractions(claude);
    }

    @Test
    void withholdsAPartialGroundedPromptEchoRatherThanExposingItsContext() {
        // The excerpt states the answer's fact, so only the echo handling decides the result.
        String rawExcerpt = "INTERNAL RETRIEVAL EXCERPT: do not display this source text. Up to 5 unused days carry forward.";
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, rawExcerpt)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("I'm Wavi. <policy_context><source id=\"S1\">"
                + rawExcerpt + "</source></policy_context> [S1]");

        MyHrChatResponse response = service("ollama", true, false).chat(employee, QUESTION, List.of());

        assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER)
                .doesNotContain(rawExcerpt, "policy_context", "<source");
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(response.citations()).isEmpty();
        assertThat(logText()).contains("status=UNVERIFIED reason=PROMPT_ECHO_REJECTED")
                .doesNotContain(rawExcerpt);
    }

    @Test
    void greetingsStillRequireAVerifiedActor() {
        assertThatThrownBy(() -> service("ollama", true, false).chat((VerifiedMyHrActor) null, "hello", List.of()))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(retrieval, ollama, claude);
    }

    // --- Workflow guidance ----------------------------------------------------------------------

    private static final String TIMESHEET_HOW_TO = "how to update timesheets";
    private static final String PHONE_CHANGE = "i want to change my phone number in my profile";
    private static final String OFFER_LETTER_REQUEST = "I'm a new hire and want my official offer letter";
    private static final String DOCUMENT_APPROVAL_HOW_TO = "How do I approve an employee's document request?";

    @Test
    void workflowQuestionsGetGuidanceForTheVerifiedRoleWithoutRetrievalOrAnyProvider() {
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        for (MyHrChatService service : List.of(service("ollama", true, false), service("claude", true, true))) {
            assertThat(guidance(service, employee, TIMESHEET_HOW_TO))
                    .contains("Enter Timesheet", "reason for change", "resubmit")
                    .doesNotContain("Team Timesheets", "My Timesheets");
            assertThat(guidance(service, hr, TIMESHEET_HOW_TO)).contains("My Timesheets tab", "reason for change");
            assertThat(guidance(service, admin, TIMESHEET_HOW_TO))
                    .contains("don't correct timesheets themselves", "reject the submitted timesheet with a reason")
                    .doesNotContain("Enter Timesheet");

            assertThat(guidance(service, employee, PHONE_CHANGE))
                    .contains("Open Profile Change Requests and submit a request", "phone number")
                    .doesNotContain("You also review");
            assertThat(guidance(service, hr, PHONE_CHANGE)).contains("submit a request", "You also review");
            assertThat(guidance(service, admin, PHONE_CHANGE))
                    .contains("don't have an employee profile", "approve or reject")
                    .doesNotContain("submit a request");

            assertThat(guidance(service, employee, OFFER_LETTER_REQUEST))
                    .contains("choose Request a document", "Offer Letter", "SSN Copy", "sign it and return it")
                    .doesNotContain("Request for myself", "To prepare an offer letter");
            assertThat(guidance(service, hr, OFFER_LETTER_REQUEST))
                    .contains("choose Request for myself", "Offer Letter", "To prepare an offer letter");
            assertThat(guidance(service, admin, OFFER_LETTER_REQUEST))
                    .contains("don't request offer letters for themselves", "Document Approvals")
                    .doesNotContain("Request a document", "Request for myself");
        }

        verifyNoInteractions(retrieval, ollama, claude);
        assertThat(logText()).contains("provider=none workflow=TIMESHEET_CORRECTION", "provider=none workflow=PROFILE_CHANGE",
                "provider=none workflow=OFFER_LETTER").doesNotContain("phone number", "new hire", "update timesheets");
    }

    @Test
    void otherWorkflowPhrasingsAreRecognised() {
        MyHrChatService service = service("ollama", true, false);
        for (String timesheet : List.of("How do I enter my timesheet?", "how do I resubmit a rejected timesheet",
                "I need to correct my hours", "Fill out my timesheet")) {
            assertThat(guidance(service, employee, timesheet)).as(timesheet).contains("Enter Timesheet");
        }
        for (String profile : List.of("update my address", "How can I change my personal email?",
                "I want to change my legal name", "edit my contact details")) {
            assertThat(guidance(service, employee, profile)).as(profile).contains("Open Profile Change Requests");
        }
        for (String document : List.of("how do I request an employment verification letter",
                "I need an experience letter", "I want to request a document")) {
            assertThat(guidance(service, employee, document)).as(document)
                    .contains("Request a document", "Select the document type").doesNotContain("Offer Letter");
        }
        verifyNoInteractions(retrieval, ollama, claude);
    }

    @Test
    void documentApprovalHowToUsesTheActualRoleRestrictedWorkflowInsteadOfPolicyRetrieval() {
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        MyHrChatService service = service("ollama", true, false);

        assertThat(guidance(service, admin, DOCUMENT_APPROVAL_HOW_TO))
                .contains("Open Document Approvals", "Approve Document", "requires a reason")
                .doesNotContain("company policy");
        assertThat(guidance(service, hr, DOCUMENT_APPROVAL_HOW_TO))
                .contains("Submit it for Company Admin approval")
                .doesNotContain("Open Document Approvals");
        assertThat(guidance(service, employee, DOCUMENT_APPROVAL_HOW_TO))
                .contains("Employees cannot approve document requests")
                .doesNotContain("Open Document Approvals");

        verifyNoInteractions(retrieval, ollama, claude);
    }

    @Test
    void policyTimingStatusAndAmbiguousQuestionsStillUseRetrievalAndCitationRules() {
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("General answer.");
        MyHrChatService service = service("ollama", true, false);

        for (String question : List.of("What is the timesheet deadline?", "When should I submit my timesheet?",
                // "why was my timesheet rejected?" and "What is the status of my address change?" are own-record
                // lookups now (MyHrChatServiceIntentRoutingMatrixTest), so they never reach retrieval or a model.
                "How many hours can I log per week?", "Can I edit overtime hours?", "What is the profile update policy?",
                "How long does offer letter approval take?", "How do I update my address and my timesheet hours?",
                "What is the leave policy?")) {
            MyHrChatResponse response = service.chat(employee, question, List.of());
            assertThat(response.knowledgeStatus()).as(question).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
            verify(retrieval).retrieve(employee, question);
        }

        String offerPolicy = "What is the offer letter policy?";
        when(retrieval.retrieve(employee, offerPolicy)).thenReturn(retrieved(
                chunk("offers", "Offer Policy", ChunkAccessLevel.COMPANY_WIDE, "Offers are valid for 10 days.")));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Offers are valid for 10 days [S1].", "Offers last 30 days.");
        MyHrChatResponse cited = service.chat(employee, offerPolicy, List.of());
        assertThat(cited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(cited.citations()).extracting(MyHrChatCitation::title).containsExactly("Offer Policy");
        MyHrChatResponse uncited = service.chat(employee, offerPolicy, List.of());
        assertThat(uncited.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
        assertThat(uncited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(logText()).doesNotContain("workflow=");
    }

    @Test
    void workflowGuidanceRequiresAVerifiedActor() {
        MyHrChatService service = service("ollama", true, false);
        assertThatThrownBy(() -> service.chat((VerifiedMyHrActor) null, TIMESHEET_HOW_TO, List.of()))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(retrieval, ollama, claude);

        when(ollama.chat(TIMESHEET_HOW_TO, List.of())).thenReturn("General answer.");
        assertThat(service.chat(TIMESHEET_HOW_TO, List.of(), "employee-71").message()).isEqualTo("General answer.");
        verifyNoInteractions(retrieval);
    }

    @Test
    void timesheetEntryCorrectionAndReviewGetDistinctAnswersForEachRole() {
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        MyHrChatService service = service("ollama", true, false);
        String entry = "How do I submit my timesheet?";
        String correction = "How do I fix a rejected timesheet?";
        List<String> reviews = List.of("How can I review my team's timesheets?", "How do I review and reject a timesheet?");

        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            String entered = guidance(service, actor, entry);
            String corrected = guidance(service, actor, correction);
            String reviewed = guidance(service, actor, reviews.get(1));
            assertThat(List.of(entered, corrected, reviewed)).as(actor.role().name()).doesNotHaveDuplicates();
            assertThat(guidance(service, actor, reviews.get(0))).isEqualTo(reviewed);
        }

        assertThat(guidance(service, employee, entry)).contains("Enter Timesheet", "Submitted Timesheets")
                .doesNotContain("Team Timesheets", "My Timesheets");
        assertThat(guidance(service, employee, correction)).contains("resubmit", "reason for change");
        assertThat(guidance(service, employee, reviews.get(0))).contains("Employees don't review timesheets")
                .doesNotContain("Team Timesheets", "approved or rejected");

        assertThat(guidance(service, hr, entry)).contains("My Timesheets tab", "Enter Timesheet");
        assertThat(guidance(service, hr, correction)).contains("My Timesheets tab", "resubmit");
        for (String review : reviews) {
            assertThat(guidance(service, hr, review)).contains("Team Timesheets", "active direct reports",
                    "Only SUBMITTED timesheets can be approved or rejected", "requires a reason");
        }

        for (String question : List.of(entry, correction, reviews.get(0), reviews.get(1))) {
            assertThat(guidance(service, admin, question)).as(question)
                    .doesNotContain("Enter Timesheet", "submit it to your reporting manager", "Team Timesheets");
        }
        assertThat(guidance(service, admin, entry)).contains("can't submit one");
        assertThat(guidance(service, admin, correction)).contains("reject the submitted timesheet with a reason");
        assertThat(guidance(service, admin, reviews.get(1))).contains("across the organization",
                "Only SUBMITTED timesheets can be approved or rejected", "requires a reason");

        verifyNoInteractions(retrieval, ollama, claude);
        assertThat(logText()).contains("workflow=TIMESHEET_ENTRY", "workflow=TIMESHEET_CORRECTION",
                "workflow=TIMESHEET_REVIEW");
    }

    @Test
    void actionRequestsSayTheAssistantCannotActAndOfferOnlyTheRolesOwnWorkflow() {
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        MyHrChatService service = service("ollama", true, false);
        String reply = MyHrChatService.ACTION_REQUEST_REPLY;

        String offer = "Can you approve my offer letter right now?";
        assertThat(guidance(service, employee, offer)).startsWith(reply).contains("choose Request a document");
        assertThat(guidance(service, hr, offer)).startsWith(reply).contains("choose Request for myself");
        assertThat(guidance(service, admin, offer)).startsWith(reply).contains("Document Approvals")
                .doesNotContain("Request a document", "Request for myself");

        String reject = "Can you reject John's timesheet?";
        assertThat(guidance(service, employee, reject)).startsWith(reply).contains("Employees don't review timesheets");
        assertThat(guidance(service, hr, reject)).startsWith(reply).contains("Team Timesheets");

        String phone = "Please change my phone number to 5551234567";
        assertThat(guidance(service, employee, phone)).startsWith(reply).contains("Open Profile Change Requests");
        assertThat(guidance(service, admin, phone)).startsWith(reply).contains("don't have an employee profile");

        // A command spanning two workflows gets the plain reply, not guidance for either one.
        for (String withoutWorkflow : List.of("Could you delete my draft timesheet?", "Can you approve my leave request?",
                "Can you sign the I-983 for me?", "Submit it", "update my address and my timesheet hours")) {
            assertThat(guidance(service, employee, withoutWorkflow)).as(withoutWorkflow).isEqualTo(reply);
        }
        verifyNoInteractions(retrieval, ollama, claude);
        assertThat(logText()).contains("provider=none action-request=true workflow=OFFER_LETTER",
                "provider=none action-request=true workflow=none").doesNotContain("5551234567", "John");
    }

    @Test
    void questionsThatOnlyMentionAnActionAreNotActionRequests() {
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("General answer.");
        MyHrChatService service = service("ollama", true, false);

        assertThat(service.chat(employee, "Could you update me on the leave policy?", List.of()).message())
                .isEqualTo(MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER);
        assertThat(service.chat(employee, "Sign in is not working for me", List.of()).message()).isEqualTo("General answer.");
        assertThat(service.chat(employee, "Can you help me submit my timesheet?", List.of()).message())
                .contains("Enter Timesheet").doesNotContain(MyHrChatService.ACTION_REQUEST_REPLY);
        verify(retrieval).retrieve(employee, "Could you update me on the leave policy?");
        assertThat(logText()).doesNotContain("action-request=true");
    }

    @Test
    void reportingManagerQuestionsReturnOnlyTheVerifiedUsersOwnManager() {
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        MyHrOwnProfileResponse withManager = mock(MyHrOwnProfileResponse.class);
        when(withManager.reportingManager()).thenReturn(
                new ReportingManagerRef("manager-9", "WRAGT009", "Jane Manager", "HR", null, "Admin User"));
        MyHrOwnProfileResponse withoutManager = mock(MyHrOwnProfileResponse.class);
        when(employees.getOwnDetail("employee-71")).thenReturn(withManager);
        when(employees.getOwnDetail("hr-71")).thenReturn(withoutManager);
        when(employees.getOwnDetail("admin-71")).thenReturn(withoutManager);
        MyHrChatService service = service("ollama", true, false);

        for (String question : List.of("Who is my reporting manager?", "who's my manager", "Who do I report to?",
                "What is my supervisor's name?", "  WHO IS MY BOSS?  ")) {
            assertThat(service.chat(employee, question, List.of())).as(question)
                    .isEqualTo(new MyHrChatResponse("Your reporting manager is Jane Manager."));
        }
        String notAssigned = "No reporting manager is assigned to you in Waypoint. If that looks wrong, please contact HR.";
        assertThat(service.chat(hr, "Who is my reporting manager?", List.of()).message()).isEqualTo(notAssigned);
        assertThat(service.chat(admin, "Who is my reporting manager?", List.of()).message()).isEqualTo(notAssigned);

        verify(employees, never()).getOwnDetail("manager-9");
        verifyNoInteractions(retrieval, ollama, claude);
        assertThatThrownBy(() -> service.chat((VerifiedMyHrActor) null, "Who is my reporting manager?", List.of()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.chat("Who is my reporting manager?", List.of(), null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void managerQuestionsAboutOthersAreNotLookedUpAndManagerDetailsNeverReachLaterPrompts() {
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("General answer.");
        StampedHistory stamps = new StampedHistory();
        MyHrChatService service = stamps.service(claude, ollama, "ollama", retrieval, true, false, false,
                ExternalPolicyProcessingAuthorization.DENY_ALL);

        service.chat(employee, "Who is the manager of the finance team?", List.of());
        verify(employees, never()).getOwnDetail(anyString());

        // Session-verified history, as the browser returns it: the personal turn is stamped restricted.
        List<MyHrChatHistoryMessage> history = new java.util.ArrayList<>(stamps.turns(employee,
                MyHrChatHistoryProvenance.ExternalUse.RESTRICTED,
                "Who is my reporting manager?", "Your reporting manager is Jane Manager."));
        history.addAll(stamps.turns(employee, MyHrChatHistoryProvenance.ExternalUse.PERMITTED,
                "Write a haiku about Mondays", "Mondays arrive slow."));
        service.chat(employee, GENERAL, history, StampedHistory.SESSION);

        ArgumentCaptor<MyHrGroundedPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama, times(2)).chat(prompts.capture());
        MyHrGroundedPrompt last = prompts.getAllValues().get(1);
        assertThat(last.history()).extracting(MyHrChatHistoryMessage::content)
                .containsExactly("Write a haiku about Mondays", "Mondays arrive slow.");
        assertThat(last.userMessage()).doesNotContain("Jane Manager");
    }

    private static String guidance(MyHrChatService service, VerifiedMyHrActor actor, String message) {
        MyHrChatResponse response = service.chat(actor, message, List.of());
        assertThat(response).as(message).isEqualTo(new MyHrChatResponse(response.message()));
        return response.message();
    }

    // --- Access levels --------------------------------------------------------------------------

    @Test
    void hrOnlySourcesAreFlaggedForManagersAndNeverReachEmployees() {
        String question = "What is the investigation policy?";
        RetrievedChunk hrOnly = chunk("investigations", "Investigation Procedure", ChunkAccessLevel.HR_ONLY,
                "HR-only investigation steps.");
        when(retrieval.retrieve(hr, question)).thenReturn(retrieved(hrOnly));
        when(retrieval.retrieve(employee, question)).thenReturn(retrieved(hrOnly));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Follow the documented steps [S1].");
        MyHrChatService service = service("ollama", true, false);

        MyHrChatResponse forHr = service.chat(hr, question, List.of());
        MyHrChatResponse forEmployee = service.chat(employee, question, List.of());

        assertThat(forHr.citations()).containsExactly(
                new MyHrChatCitation(1, "Investigation Procedure", "investigations.pdf", true));
        assertThat(forEmployee.citations()).isEmpty();
        assertThat(forEmployee.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
        // The employee's policy question has no excerpt it may see, so no model is asked at all.
        assertThat(forEmployee.message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER);
        ArgumentCaptor<MyHrGroundedPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama, times(1)).chat(prompts.capture());
        assertThat(prompts.getValue().userMessage()).contains("restricted=\"hr-only\"", "HR-only investigation steps.");
        assertThat(forEmployee.message()).doesNotContain("HR-only investigation steps.", "Investigation Procedure");
    }

    @Test
    void hrOnlyPolicyGetsOneLocalCitationRetryForHrAndCompanyAdminButNeverForAnEmployee() {
        String question = "What does the HR confidential recruitment policy say?";
        RetrievedChunk recruitment = chunk("recruitment", "HR Confidential Recruitment Policy", ChunkAccessLevel.HR_ONLY,
                "Recruitment candidate records are confidential and must remain within HR.");
        VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
        when(retrieval.retrieve(hr, question)).thenReturn(retrieved(recruitment));
        when(retrieval.retrieve(admin, question)).thenReturn(retrieved(recruitment));
        when(retrieval.retrieve(employee, question)).thenReturn(retrieved(recruitment));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Recruitment candidate records are confidential.",
                "Recruitment candidate records are confidential and remain within HR [S1].",
                "Recruitment candidate records are confidential.",
                "Recruitment candidate records are confidential and remain within HR [S1].");
        MyHrChatService service = service("claude", true, false);

        for (VerifiedMyHrActor manager : List.of(hr, admin)) {
            MyHrChatResponse response = service.chat(manager, question, List.of());
            assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
            assertThat(response.citations()).containsExactly(new MyHrChatCitation(1,
                    "HR Confidential Recruitment Policy", "recruitment.pdf", true));
        }

        MyHrChatResponse employeeResponse = service.chat(employee, question, List.of());
        assertThat(employeeResponse.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
        assertThat(employeeResponse.message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER)
                .doesNotContain("Recruitment candidate records", "HR Confidential Recruitment Policy");
        verify(ollama, times(4)).chat(any(MyHrGroundedPrompt.class));
        verifyNoInteractions(claude);
    }

    @Test
    void groundedLogsCarryCountsButNoPolicyTextOrQuestion() {
        when(retrieval.retrieve(employee, QUESTION)).thenReturn(retrieved(
                chunk("leave", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE, POLICY_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Yes [S1].");

        service("ollama", true, false).chat(employee, QUESTION, List.of());

        assertThat(logText()).contains(
                        "MYHR_CHAT_PROVIDER_USED provider=ollama local-policy=true grounded=true sources=1 cited=1 status=GROUNDED")
                .doesNotContain(POLICY_TEXT, QUESTION, "Leave Policy", ORG);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private MyHrChatService service(String provider, boolean groundingEnabled, boolean externalApproved) {
        // externalApproved: the switch is on and, for this test only, the organization authorized external processing.
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                employees, null, provider, retrieval, groundingEnabled, externalApproved, false,
                externalApproved ? (organizationId, chunks) -> true : ExternalPolicyProcessingAuthorization.DENY_ALL);
    }

    private String logText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
    }

    private static RetrievalResult retrieved(RetrievedChunk... chunks) {
        return new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(chunks), chunks.length);
    }

    private static RetrievedChunk chunk(String document, String title, ChunkAccessLevel level, String text) {
        return new RetrievedChunk(document + "-ingestion-00000", document, title, "stored-" + document,
                document + ".pdf", document + "-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }
}
