package com.waypoint.service;

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
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * OFFLINE regression tests for policy answer QUALITY, over a synthetic markdown-style leave policy (the shape a PDF or text
 * export gives): a plain-language, cited explanation instead of pasted fragments; clean, non-repeating quotes when the
 * explanation cannot be fully verified; demo/fictional documents labelled as sample information; and every
 * existing rule (citations, access, which provider sees what) unchanged. Providers are mocks; nothing is called.
 */
class WaviPolicyExplanationQualityTest {

    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);
    private static final String QUESTION = "Explain the company leave policy in simple words";

    private static final String DEMO_POLICY = """
            # Waypoint RAG Test — Employee Leave Policy

            **Organization:** Waypoint RAG Test Org
            **Policy ID:** LEAVE-001
            **Status:** Approved

            This is fictional test data only.

            ## 1. Annual leave
            Full-time employees receive 15 days of paid annual leave each year.

            ## 2. Carry forward
            Employees may carry forward up to 5 unused leave days into the next year.

            ## 3. Requests
            Employees should request leave at least two weeks in advance through their manager.
            """;

    private static final String REAL_POLICY = DEMO_POLICY
            .replace("# Waypoint RAG Test — Employee Leave Policy", "# Employee Leave Policy")
            .replace("**Organization:** Waypoint RAG Test Org", "**Organization:** Acme Inc")
            .replace("This is fictional test data only.", "This policy applies to all full-time staff.");

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);

    private MyHrChatService service(boolean claudeApproved) {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                mock(EmployeeManagementService.class), mock(DocumentRequestService.class), "claude", retrieval, true,
                claudeApproved, false,
                claudeApproved ? (organizationId, chunks) -> true : ExternalPolicyProcessingAuthorization.DENY_ALL);
    }

    private static RetrievedChunk chunk(String document, String title, ChunkAccessLevel level, int index, String text) {
        return new RetrievedChunk(document + "-ingestion-0000" + index, document, title, "stored-" + document,
                document + ".pdf", document + "-ingestion", index, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }

    private void retrieved(RetrievedChunk... chunks) {
        when(retrieval.retrieve(eq(EMPLOYEE), anyString())).thenReturn(
                new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(chunks), chunks.length));
    }

    private static final String EXPLANATION = "Full-time employees get 15 days of paid annual leave each year [S1]. "
            + "They may carry forward up to 5 unused leave days into the next year [S1]. "
            + "Leave should be requested at least two weeks in advance through the manager [S1].";

    // --- A plain-language answer, clean citation, sample labelling -------------------------------------------------------

    @Test
    void aSupportedPlainLanguageExplanationIsShownInsteadOfPastedFragmentsAndTheDemoPolicyIsLabelledSample() {
        retrieved(chunk("leave", "Test Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, DEMO_POLICY));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(EXPLANATION);

        MyHrChatResponse response = service(false).chat(EMPLOYEE, QUESTION, List.of());

        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.message()).contains("Full-time employees get 15 days of paid annual leave each year [1].",
                        "They may carry forward up to 5 unused leave days into the next year [1].")
                .doesNotContain("“", "#", "**", "Organization:", "Policy ID", "Status:", "[S1]")
                .contains("Note: this document is marked as a demo or fictional sample, so treat it as sample information, "
                        + "not as an official employee entitlement.");
        assertThat(response.citations()).containsExactly(
                new MyHrChatCitation(1, "Test Employee Leave Policy", "leave.pdf", false));
        // local policy path: Claude saw nothing
        verifyNoInteractions(claude);
    }

    @Test
    void anOrdinaryPolicyIsNotLabelledSample() {
        retrieved(chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, REAL_POLICY));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(EXPLANATION);

        String message = service(false).chat(EMPLOYEE, QUESTION, List.of()).message();

        assertThat(message).contains("15 days of paid annual leave").doesNotContain("Note:", "sample", "fictional", "demo");
    }

    // --- The explanation is shown only when fully supported; otherwise clean quoted evidence --------------------------------

    @Test
    void anExplanationWithAnUnsupportedClaimFallsBackToCleanQuotedEvidenceNeverTheClaim() {
        retrieved(chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, REAL_POLICY));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Employees receive unlimited sick leave and free vacation travel every year [S1].");

        MyHrChatResponse response = service(false).chat(EMPLOYEE, QUESTION, List.of());

        assertThat(response.message()).doesNotContain("unlimited", "sick", "travel");
        assertThat(response.message()).contains("“Full-time employees receive 15 days of paid annual leave each year.” [1]")
                .doesNotContain("#", "**", "Organization:", "Policy ID");
        assertThat(response.citations()).hasSize(1);
    }

    @Test
    void aMeaningChangingWordTheSourceDoesNotContainSendsTheAnswerBackToTheSourceWording() {
        retrieved(chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, REAL_POLICY));
        // "must" turns an option ("may carry forward") into a duty; the source never says it
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Employees must carry forward up to 5 unused leave days into the next year [S1].");

        String message = service(false).chat(EMPLOYEE, "How many unused leave days can employees carry forward?", List.of()).message();

        assertThat(message).doesNotContain("must").contains("“Employees may carry forward up to 5 unused leave days into the next year.” [1]");
    }

    @Test
    void quotedEvidenceNeverMergesHeadingsOrRepeatsASentenceFromOverlappingExcerpts() {
        retrieved(
                chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, REAL_POLICY),
                chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 1,
                        "## 2. Carry forward\nEmployees may carry forward up to 5 unused leave days into the next year.\n"
                                + "Unused leave above that limit expires on 31 December."));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Leave is nice [S1].");

        String message = service(false).chat(EMPLOYEE, "How many unused leave days can employees carry forward?", List.of()).message();

        assertThat(message.split("Employees may carry forward up to 5 unused leave days", -1)).hasSize(2); // exactly once
        assertThat(message).doesNotContain("Carry forward Employees", "2. Carry", "##");
    }

    @Test
    void theSameExplanationOnTwoCitationMarkersCollapsesAndNeverShowsSourceMarkupOrBareDuplicates() {
        retrieved(chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, REAL_POLICY));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "**Full-time employees** receive 15 days of paid annual leave each year [S1] [S1].");

        String message = service(false).chat(EMPLOYEE, QUESTION, List.of()).message();

        assertThat(message).contains("Full-time employees receive 15 days of paid annual leave each year [1].")
                .doesNotContain("[1] [1]", "**", "[S1]");
    }

    // --- Pure checks ---------------------------------------------------------------------------------------------------------

    private static MyHrKnowledgeGrounding.Source source(String title, String text) {
        return new MyHrKnowledgeGrounding.Source(1, title, "doc.pdf", ChunkAccessLevel.COMPANY_WIDE, List.of(text));
    }

    @Test
    void readableSentencesDropHeadingsMetadataMarkupAndBareLabelsAndSplitByLine() {
        assertThat(WaviPolicyEvidence.readableSentences(DEMO_POLICY)).containsExactly(
                "This is fictional test data only.",
                "Full-time employees receive 15 days of paid annual leave each year.",
                "Employees may carry forward up to 5 unused leave days into the next year.",
                "Employees should request leave at least two weeks in advance through their manager.");
        assertThat(WaviPolicyEvidence.readableSentences("- Up to 5 days carry over.\n* Two weeks notice is required.\n1. Managers approve all requests."))
                .containsExactly("Up to 5 days carry over.", "Two weeks notice is required.", "Managers approve all requests.");
    }

    @Test
    void anExplanationIsAcceptedOnlyWhenCitedBriefAndFullySupported() {
        List<MyHrKnowledgeGrounding.Source> cited = List.of(source("Leave", REAL_POLICY));
        assertThat(WaviPolicyEvidence.supportedExplanation(
                "Full-time staff get 15 days of paid annual leave each year [1].", cited))
                .isEqualTo("Full-time staff get 15 days of paid annual leave each year [1].");
        // no marker, an invented number, an invented duty, or too long: not accepted
        assertThat(WaviPolicyEvidence.supportedExplanation("Full-time staff get 15 days of leave.", cited)).isNull();
        assertThat(WaviPolicyEvidence.supportedExplanation("Full-time staff get 20 days of paid annual leave [1].", cited)).isNull();
        assertThat(WaviPolicyEvidence.supportedExplanation("Staff must take 15 days of paid annual leave [1].", cited)).isNull();
        assertThat(WaviPolicyEvidence.supportedExplanation(("Staff get 15 days of paid annual leave [1]. ").repeat(80), cited)).isNull();
        assertThat(WaviPolicyEvidence.supportedExplanation(null, cited)).isNull();
        assertThat(WaviPolicyEvidence.supportedExplanation("Anything [1].", List.of())).isNull();
    }

    @Test
    void sampleDocumentsAreRecognisedFromTheirTitleOrText() {
        assertThat(WaviPolicyEvidence.isSample(source("Test Employee Leave Policy", "Normal text about leave days."))).isFalse(); // "Test Employee" alone is not a marker
        assertThat(WaviPolicyEvidence.isSample(source("Leave", "This is fictional test data only."))).isTrue();
        assertThat(WaviPolicyEvidence.isSample(source("Demo Leave Policy", "Text."))).isTrue();
        assertThat(WaviPolicyEvidence.isSample(source("Waypoint RAG Test — Leave", "Text."))).isTrue();
        assertThat(WaviPolicyEvidence.isSample(source("Employee Leave Policy", REAL_POLICY))).isFalse();
    }

    // --- Provider and access rules are unchanged ------------------------------------------------------------------------------

    @Test
    void anApprovedClaudeExplanationIsShownWithItsVerifiedCitationAndOllamaStaysOutOfIt() {
        retrieved(chunk("leave", "Employee Leave Policy", ChunkAccessLevel.COMPANY_WIDE, 0, REAL_POLICY));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(List.of(new MyHrCitedSegment(
                "Full-time employees get 15 days of paid annual leave each year.",
                List.of(new MyHrCitedSegment.Citation("char_location", REAL_POLICY, 0, 0, REAL_POLICY.length())))));

        MyHrChatResponse response = service(true).chat(EMPLOYEE, QUESTION, List.of());

        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.message()).contains("Full-time employees get 15 days of paid annual leave each year.").doesNotContain("“", "#", "**");
        assertThat(response.citations()).extracting(MyHrChatCitation::title).containsExactly("Employee Leave Policy");
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
    }

    @Test
    void hrOnlyPolicyStaysLocalIsExplainedInPlainLanguageAndKeepsItsHrOnlyLabel() {
        VerifiedMyHrActor hr = TestActors.hr("hr-9", "org-9");
        String hrText = "# HR Confidential Recruitment Policy\n\nThis is fictional test data only.\n\n"
                + "Recruiters must complete a background check before any offer is made.";
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.RETRIEVED,
                List.of(chunk("hr", "HR Confidential Recruitment Policy", ChunkAccessLevel.HR_ONLY, 0, hrText)), 1));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Recruiters must complete a background check before making any offer [S1].");

        MyHrChatResponse response = service(true).chat(hr, "What must recruiters do before making an offer?", List.of());

        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.message()).contains("Recruiters must complete a background check before making any offer [1].",
                "Note: this document is marked as a demo or fictional sample");
        assertThat(response.citations()).containsExactly(
                new MyHrChatCitation(1, "HR Confidential Recruitment Policy", "hr.pdf", true));
        verify(claude, never()).chatWithDocuments(any(MyHrGroundedDocumentPrompt.class));
    }
}
