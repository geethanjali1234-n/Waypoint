package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatCitation;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import com.waypoint.setup.KnowledgeChunkVectorSearch;
import com.waypoint.setup.KnowledgeChunkVectorSearch.CandidateChunk;
import com.waypoint.setup.KnowledgeChunkVectorSearch.SearchRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
 * The 36 company-policy questions from the Step 7 RAG readiness audit, end to end through
 * {@code chat(VerifiedMyHrActor, ...)}. All of them reach knowledge retrieval for every role, none is answered with
 * invented policy details when no approved policy matches, general questions on the same subjects stay refused
 * before any retrieval or model call, and retrieval still applies the actor's organization and role.
 * No Claude, Ollama, Voyage or MongoDB call is made.
 */
class MyHrPolicyScopeRegressionTest {

    private static final String ORG = "org-acme-77";
    private static final String OTHER_ORG = "org-other-12";
    private static final String MODEL = "voyage-4-lite";

    /** Reached retrieval before the fix. */
    static final List<String> PREVIOUSLY_ACCEPTED = List.of(
            "What is the leave policy?", "How many leave days can I carry forward?",
            "How many sick days do I get per year?", "What is the maternity leave policy?",
            "Is Juneteenth a paid holiday?", "What is the dress code?", "What is the remote work policy?",
            "Can I expense my home internet?", "What's the 401k match?", "What is the notice period?",
            "Do I need approval for overtime?", "Can I accept gifts from clients?",
            "Is there a policy on using ChatGPT?", "Can contractors work from home?",
            "Are interns eligible for health insurance?", "What is the bereavement leave?",
            "Can I have a second job?", "Is there a gym membership benefit?");

    private static final String PAY_DATE_PREFIX = "Waypoint doesn't record pay dates, so I can't tell you when you'll be paid";
    private static final String PAY_DATE_SUFFIX = " For your exact pay date, check your pay documents or ask HR.";
    static final String PAY_DATE_NOT_FOUND = PAY_DATE_PREFIX
            + ", and I couldn't find a pay date in the approved company policies." + PAY_DATE_SUFFIX;
    static final String PAY_DATE_UNAVAILABLE = PAY_DATE_PREFIX
            + ", and I can't check the approved company policies right now." + PAY_DATE_SUFFIX;
    static final String PAY_DATE_NOT_CHECKED = PAY_DATE_PREFIX + "." + PAY_DATE_SUFFIX;

    /** Refused by the scope guard before the fix. */
    static final List<String> PREVIOUSLY_REFUSED = List.of(
            "How many days off do I get?", "When is payday?", "Am I eligible for a stipend?",
            "How much is the internet stipend?", "What is the severance package?",
            "What are the guidelines for social media?", "Am I allowed to bring my dog?",
            "Is smoking permitted on the premises?", "What is the carry-over limit?",
            "What are the rules for gifts from vendors?", "Do we get Election Day off?",
            "Can I take vacation during the World Cup?", "What is the per diem for restaurants on business travel?",
            "Am I allowed to use ChatGPT for my tasks?", "Can I use GitHub Copilot?",
            "What happens if there's bad weather?", "Is there a confidentiality agreement I must follow?",
            "What is the commission rate for sales?");

    /** The same subjects asked as general knowledge: never company policy, so never retrieved or answered. */
    static final List<String> GENERAL_COUNTERPARTS = List.of(
            "What's the weather in Dallas today?", "Will there be bad weather tomorrow?", "What's the weather forecast?",
            "What is ChatGPT?", "Explain how GitHub Copilot works", "Can I use git to revert a commit?",
            "Am I allowed to use React hooks inside loops?", "Who won the World Cup in 2022?",
            "When is the next World Cup?", "What are the rules of cricket?", "What's the latest news?",
            "When is Election Day?", "What's the news about the election?", "Recommend restaurants in Dallas",
            "What are the best vacation destinations in Europe?", "Tell me a joke about vacation",
            "Write a Python script to calculate severance");

    private static final List<VerifiedMyHrActor> ACTORS = List.of(
            TestActors.actor("employee-1", ORG, UserRole.EMPLOYEE), TestActors.hr("hr-1", ORG),
            TestActors.companyAdmin("admin-1", ORG));

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final TimesheetService timesheets = mock(TimesheetService.class);
    private final ProfileChangeRequestService profiles = mock(ProfileChangeRequestService.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final DocumentRequestService documents = mock(DocumentRequestService.class);

    @Test
    void allThirtySixAuditPolicyQuestionsPassTheScopeGuardAndAreClassifiedAsPolicy() {
        List<String> all = Stream.concat(PREVIOUSLY_ACCEPTED.stream(), PREVIOUSLY_REFUSED.stream()).toList();
        assertThat(all).hasSize(36).doesNotHaveDuplicates();
        for (String question : all) {
            assertThat(MyHrScopeGuard.decide(question, List.of())).as(question).isEqualTo(MyHrScopeGuard.Decision.IN_SCOPE);
            // A policy question is never answered without an approved excerpt (no invented details).
            assertThat(MyHrKnowledgeGrounding.isPolicyQuestion(question)).as(question).isTrue();
        }
    }

    @Test
    void allThirtySixReachRetrievalAndAnUnrelatedExcerptIsNotPresentedAsAnAnswer() {
        when(retrieval.retrieve(any(), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.RETRIEVED,
                List.of(chunk(ChunkAccessLevel.COMPANY_WIDE, "Approved policy text.")), 1));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("According to the policy [S1].");
        MyHrChatService service = service("claude", true);

        for (VerifiedMyHrActor actor : ACTORS) {
            for (String question : Stream.concat(PREVIOUSLY_ACCEPTED.stream(), PREVIOUSLY_REFUSED.stream()).toList()) {
                MyHrChatResponse response = service.chat(actor, question, List.of());
                assertThat(response.knowledgeStatus()).as(actor.role() + ": " + question)
                        .isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
                assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
                assertThat(response.citations()).isEmpty();
                assertThat(response.message()).isNotEqualTo(MyHrScopeGuard.OUT_OF_SCOPE_REPLY);
                verify(retrieval).retrieve(eq(actor), eq(MyHrKnowledgeGrounding.retrievalQuery(question, List.of())));
            }
        }
        // Policy text never went to Claude, which is not approved for it here.
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        verify(claude, never()).chat(anyString(), anyList());
    }

    @Test
    void previouslyRefusedQuestionsWithoutAMatchingPolicyGetTheFixedReplyAndNoModelCall() {
        MyHrChatService service = service("claude", true);
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        for (VerifiedMyHrActor actor : ACTORS) {
            for (String question : PREVIOUSLY_REFUSED) {
                MyHrChatResponse response = service.chat(actor, question, List.of());
                // A pay-date question gets the same "not in the approved policies" answer, plus what Waypoint records.
                assertThat(response.message()).as(question).isEqualTo(MyHrQuestionInterpreter.isPayDateQuestion(question)
                        ? PAY_DATE_NOT_FOUND : MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER);
                assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
                assertThat(response.citations()).isEmpty();
            }
        }
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.UNAVAILABLE, List.of(), 0));
        for (String question : PREVIOUSLY_REFUSED) {
            assertThat(service.chat(ACTORS.getFirst(), question, List.of()).message()).as(question)
                    .isEqualTo(MyHrQuestionInterpreter.isPayDateQuestion(question)
                            ? PAY_DATE_UNAVAILABLE : MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);
        }
        verifyNoModelCall();
    }

    @Test
    void generalQuestionsOnTheSameSubjectsNeverReachRetrievalOrAModel() {
        for (MyHrChatService service : List.of(service("claude", true), service("ollama", true),
                service("claude", false), service("ollama", false))) {
            for (VerifiedMyHrActor actor : ACTORS) {
                for (String question : GENERAL_COUNTERPARTS) {
                    assertThat(service.chat(actor, question, List.of()).message()).as(question)
                            .isEqualTo(MyHrScopeGuard.OUT_OF_SCOPE_REPLY);
                }
            }
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void withoutGroundingPolicyQuestionsDoNotReachAnUngroundedModel() {
        MyHrChatService service = service("claude", false);
        for (String question : PREVIOUSLY_REFUSED) {
            // Waypoint records no pay dates, so a pay-date question never reaches a model that could only guess one.
            assertThat(service.chat(ACTORS.getFirst(), question, List.of()).message()).as(question)
                    .isEqualTo(MyHrQuestionInterpreter.isPayDateQuestion(question) ? PAY_DATE_NOT_CHECKED
                            : MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);
        }
        verifyNoInteractions(retrieval, ollama, claude);
    }

    @Test
    void deterministicAnswersAreUnchangedAndComeFirst() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("TARDU004");
        when(employees.getOwnDetail("employee-1")).thenReturn(profile);
        MyHrChatService service = service("claude", true);

        assertThat(service.chat(ACTORS.get(0), "What is my employee ID?", List.of()).message())
                .isEqualTo("Your employee ID is TARDU004.");
        assertThat(service.chat(ACTORS.get(0), "hello", List.of()).message()).isEqualTo("Hello! How can I help you today?");
        assertThat(service.chat(ACTORS.get(0), "How do I submit my timesheet?", List.of()).message())
                .startsWith("Open Timesheets and choose Enter Timesheet");
        assertThat(service.chat(ACTORS.get(0), "What can you help me with?", List.of()).message())
                .contains("I only help with Waypoint and company topics");
        verifyNoModelCall();
        verifyNoInteractions(retrieval);
    }

    // --- Real retrieval: organization and role restrictions -----------------------------------------------

    @Test
    void anEmployeeNeverReceivesHrOnlyOrOtherOrganizationPolicyEvenIfTheSearchReturnedIt() {
        RealRetrieval real = new RealRetrieval();
        VerifiedMyHrActor employee = ACTORS.getFirst();

        MyHrChatResponse response = real.chat(employee, "What is the carry-over limit?");

        SearchRequest request = real.searches.getValue();
        assertThat(request.organizationId()).isEqualTo(ORG);
        assertThat(request.accessLevels()).containsExactly(ChunkAccessLevel.COMPANY_WIDE);
        String prompt = real.prompts.getValue().userMessage();
        assertThat(prompt).contains(RealRetrieval.COMPANY_TEXT)
                .doesNotContain(RealRetrieval.HR_TEXT, RealRetrieval.OTHER_ORG_TEXT);
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.citations()).extracting(MyHrChatCitation::hrOnly).containsOnly(false);
    }

    @Test
    void hrAndCompanyAdminsAlsoSeeHrOnlyPolicyButNeverAnotherOrganizations() {
        for (VerifiedMyHrActor manager : List.of(ACTORS.get(1), ACTORS.get(2))) {
            RealRetrieval real = new RealRetrieval();

            MyHrChatResponse response = real.chat(manager, "What is the carry-over limit?");

            assertThat(real.searches.getValue().accessLevels())
                    .containsExactlyInAnyOrder(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
            String prompt = real.prompts.getValue().userMessage();
            assertThat(prompt).contains(RealRetrieval.COMPANY_TEXT, RealRetrieval.HR_TEXT, "restricted=\"hr-only\"")
                    .doesNotContain(RealRetrieval.OTHER_ORG_TEXT);
            assertThat(response.citations()).extracting(MyHrChatCitation::hrOnly).contains(true);
        }
    }

    @Test
    void aCitationCanNameOnlyASourceThisActorWasGivenInThisRequest() {
        // An Employee is given only the company source, S1. Whatever the model writes, the HR-only document (S2 for a
        // manager) and the other organization's document cannot be attached, by id or by bare number.
        for (String modelAnswer : List.of("Up to ten days [S2].", "Up to ten days [2].", "Up to ten days [S1, S2][2].",
                "Up to ten days [S3].")) {
            RealRetrieval real = new RealRetrieval();
            real.answer(modelAnswer);

            MyHrChatResponse response = real.chat(ACTORS.getFirst(), "What is the carry-over limit?");

            assertThat(real.prompts.getValue().userMessage()).contains("<source id=\"S1\"")
                    .doesNotContain("<source id=\"S2\"", RealRetrieval.HR_TEXT, RealRetrieval.OTHER_ORG_TEXT);
            assertThat(response.citations()).as(modelAnswer).allSatisfy(citation -> {
                assertThat(citation.sourceNumber()).isEqualTo(1);
                assertThat(citation.hrOnly()).isFalse();
                assertThat(citation.title()).isEqualTo("Leave Policy");
            });
            assertThat(response.message()).as(modelAnswer).doesNotContain("[2]", "S2", "S3");
            if (!modelAnswer.contains("S1")) {
                assertThat(response.knowledgeStatus()).as(modelAnswer).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
                assertThat(response.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
            }
        }

        // An HR user is given both sources; a bare section-style number still cites neither.
        RealRetrieval real = new RealRetrieval();
        real.answer("Up to ten days [2].");
        MyHrChatResponse hrResponse = real.chat(ACTORS.get(1), "What is the carry-over limit?");
        assertThat(real.prompts.getValue().userMessage()).contains("<source id=\"S1\"", "<source id=\"S2\"");
        assertThat(hrResponse.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(hrResponse.citations()).isEmpty();
    }

    @Test
    void approvedClaudeReceivesOnlyThisOrganizationsCompanyWideDocumentsEvenIfTheSearchReturnedMore() {
        ClaudeChatClient approvedClaude = mock(ClaudeChatClient.class);
        ArgumentCaptor<MyHrGroundedDocumentPrompt> requests = ArgumentCaptor.forClass(MyHrGroundedDocumentPrompt.class);
        when(approvedClaude.chatWithDocuments(requests.capture())).thenReturn(List.of(new MyHrCitedSegment(
                "Up to five unused days carry over.", List.of(new MyHrCitedSegment.Citation("char_location",
                        RealRetrieval.COMPANY_TEXT, 0, 0, RealRetrieval.COMPANY_TEXT.length())))));
        RealRetrieval real = new RealRetrieval();
        MyHrChatService service = real.approvedClaude(approvedClaude);

        MyHrChatResponse forEmployee = service.chat(ACTORS.getFirst(), "What is the carry-over limit?", List.of());
        MyHrChatResponse forHr = service.chat(ACTORS.get(1), "What is the carry-over limit?", List.of());

        // Only the Employee request reaches Claude; the HR request retrieved an HR-only excerpt and stays local.
        verify(approvedClaude, org.mockito.Mockito.times(1)).chatWithDocuments(any());
        assertThat(requests.getValue().documents()).extracting(MyHrGroundedDocumentPrompt.Document::text)
                .containsExactly(RealRetrieval.COMPANY_TEXT);
        assertThat(real.prompts.getValue().userMessage()).contains(RealRetrieval.COMPANY_TEXT, RealRetrieval.HR_TEXT)
                .doesNotContain(RealRetrieval.OTHER_ORG_TEXT);
        assertThat(forEmployee.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(forEmployee.citations()).containsExactly(
                new MyHrChatCitation(1, "Leave Policy", "knowledge-company.pdf", false));
        assertThat(forHr.citations()).extracting(MyHrChatCitation::hrOnly).contains(true);
        assertThat(real.searches.getAllValues()).extracting(SearchRequest::organizationId).containsOnly(ORG);
        verify(approvedClaude, never()).chat(any(MyHrGroundedPrompt.class));
        verify(real.localOllama, org.mockito.Mockito.times(1)).chat(any(MyHrGroundedPrompt.class));
    }

    /** The real KnowledgeRetrievalService over a vector search that ignores its own filter. */
    private final class RealRetrieval {
        static final String COMPANY_TEXT = "Up to five unused leave days carry over each year.";
        static final String HR_TEXT = "Confidential: HR may approve up to ten carry-over days.";
        static final String OTHER_ORG_TEXT = "Other company: unlimited carry-over.";
        static final String COMPANY_DOC = "knowledge-company";
        static final String HR_DOC = "knowledge-hr";

        final ArgumentCaptor<SearchRequest> searches = ArgumentCaptor.forClass(SearchRequest.class);
        final ArgumentCaptor<MyHrGroundedPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        private final KnowledgeChunkVectorSearch vectorSearch = mock(KnowledgeChunkVectorSearch.class);
        private final OllamaClient localOllama = mock(OllamaClient.class);
        private final KnowledgeRetrievalService realRetrieval;
        private final MyHrChatService service;

        RealRetrieval() {
            EmbeddingService embedding = mock(EmbeddingService.class);
            MongoOperations mongo = mock(MongoOperations.class);
            when(embedding.model()).thenReturn(MODEL);
            when(embedding.dimensions()).thenReturn(1024);
            when(embedding.embed(anyString(), eq(EmbeddingService.InputType.QUERY)))
                    .thenReturn(new ArrayList<>(Collections.nCopies(1024, 0.031)));
            // Worst case: the Atlas pre-filter failed and every row came back.
            when(vectorSearch.search(searches.capture())).thenReturn(List.of(
                    candidate(ORG, COMPANY_DOC, "ing-company", ChunkAccessLevel.COMPANY_WIDE, COMPANY_TEXT, 0.91),
                    candidate(ORG, HR_DOC, "ing-hr", ChunkAccessLevel.HR_ONLY, HR_TEXT, 0.90),
                    candidate(OTHER_ORG, COMPANY_DOC, "ing-company", ChunkAccessLevel.COMPANY_WIDE, OTHER_ORG_TEXT, 0.95)));
            when(mongo.find(any(Query.class), eq(KnowledgeDocument.class))).thenReturn(List.of(
                    entry(COMPANY_DOC, "Leave Policy", "ing-company", ChunkAccessLevel.COMPANY_WIDE),
                    entry(HR_DOC, "HR Leave Guidance", "ing-hr", ChunkAccessLevel.HR_ONLY)));
            realRetrieval = new KnowledgeRetrievalService(embedding, vectorSearch, mongo, true, 5, 8000, 0.70);
            when(localOllama.isLocalEndpoint()).thenReturn(true);
            org.mockito.Mockito.lenient().when(localOllama.verifiedLocalModel()).thenReturn(true);
            answer("Per policy [S1][S2].");
            service = new MyHrChatService(mock(ClaudeChatClient.class), localOllama, timesheets, profiles, employees,
                    documents, "ollama", realRetrieval, true, false);
        }

        void answer(String modelAnswer) {
            when(localOllama.chat(prompts.capture())).thenReturn(modelAnswer);
        }

        MyHrChatResponse chat(VerifiedMyHrActor actor, String question) {
            return service.chat(actor, question, List.of());
        }

        /**
         * The same real retrieval behind Claude primary with the external switch on and, for this test only, an
         * organization authorization for ORG (a mock Claude).
         */
        MyHrChatService approvedClaude(ClaudeChatClient approvedClaude) {
            return new MyHrChatService(approvedClaude, localOllama, timesheets, profiles, employees, documents, "claude",
                    realRetrieval, true, true, false, (organizationId, chunks) -> ORG.equals(organizationId));
        }

        private static CandidateChunk candidate(String org, String document, String ingestion, ChunkAccessLevel level,
                                                String text, double score) {
            return new CandidateChunk(KnowledgeIngestionService.chunkId(ingestion, 0), org, document, ingestion,
                    "source-" + document, DocumentType.COMPANY_POLICY, level, 0, text, MODEL, score);
        }

        private static KnowledgeDocument entry(String id, String title, String ingestion, ChunkAccessLevel level) {
            KnowledgeDocumentVersion active = KnowledgeDocumentVersion.builder()
                    .sourceDocumentId("source-" + id).sourceDocumentName(id + ".pdf")
                    .documentType(DocumentType.COMPANY_POLICY).accessLevel(level)
                    .stage(KnowledgeIngestionStage.INDEXED).ingestionId(ingestion).chunkCount(1)
                    .embeddingModel(MODEL).embeddingDimensions(1024)
                    .activatedAt(LocalDateTime.of(2026, 10, 1, 9, 0)).activatedByUserId("hr-earlier")
                    .build();
            return KnowledgeDocument.builder().id(id).organizationId(ORG).title(title)
                    .status(KnowledgeDocumentStatus.LIVE).activeVersion(active)
                    .sourceDocumentIds(List.of("source-" + id)).build();
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------------------

    /** Routing may ask whether Ollama is local; no question or policy text may reach either provider. */
    private void verifyNoModelCall() {
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
        verify(claude, never()).chat(anyString(), anyList());
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
    }

    private MyHrChatService service(String provider, boolean grounding) {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        return new MyHrChatService(claude, ollama, timesheets, profiles, employees, documents, provider,
                retrieval, grounding, false);
    }

    private static RetrievedChunk chunk(ChunkAccessLevel level, String text) {
        return new RetrievedChunk("policy-ingestion-00000", "policy", "Company Policy", "stored-policy", "policy.pdf",
                "policy-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }
}
