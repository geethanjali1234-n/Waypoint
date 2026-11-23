package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatCitation;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrOwnDocumentRequestHistory;
import com.waypoint.dto.myhr.ProfileChangeRequestResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OFFLINE regression tests for two answer-quality fixes found in manual testing, over synthetic records and mocked
 * providers (no database, no API call):
 * (1) the Employee "pending work" overview reads ALL the employee's records and separates what needs the employee from
 *     what waits for HR or a manager;
 * (2) a local Ollama policy answer that cites nothing gets exactly one local-only retry (company-wide as well as
 *     HR-only), with every citation and access check unchanged.
 */
class WaviEmployeePendingAndLocalCitationRetryTest {

    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);

    // ---------------------------------------------------------------------------------------------------------------------
    // (1) Employee pending-work overview
    // ---------------------------------------------------------------------------------------------------------------------

    private final TimesheetService timesheets = mock(TimesheetService.class);
    private final ProfileChangeRequestService profileChanges = mock(ProfileChangeRequestService.class);
    private final DocumentRequestService documents = mock(DocumentRequestService.class);
    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);

    private MyHrChatService overviewService() {
        return new MyHrChatService(claude, ollama, timesheets, profileChanges, mock(EmployeeManagementService.class),
                documents, "ollama", retrieval, false, false);
    }

    private static TimesheetResponse timesheet(String owner, String start, String end, TimesheetStatus status, String stamp) {
        LocalDateTime at = LocalDateTime.parse(stamp);
        return new TimesheetResponse("ts-" + start + status, owner, "EMP", "Name", UserRole.EMPLOYEE, null, null, null,
                LocalDate.parse(start), LocalDate.parse(end), List.of(), BigDecimal.ZERO, List.of(), status,
                status == TimesheetStatus.DRAFT ? null : at, at, at, null, null, null, null);
    }

    private static ProfileChangeRequestResponse change(ProfileChangeStatus status) {
        ProfileChangeRequestResponse request = mock(ProfileChangeRequestResponse.class);
        when(request.status()).thenReturn(status);
        return request;
    }

    private static MyHrOwnDocumentRequestHistory document(String title, DocumentRequestStatus status, boolean requestedByMe) {
        return new MyHrOwnDocumentRequestHistory(RequestedDocumentType.OTHER, title, List.of(), "TAX_PAYROLL", requestedByMe,
                status, LocalDateTime.parse("2026-11-01T09:00:00"), null, null, null, null, null, null, false, false);
    }

    private String overview() {
        MyHrChatResponse response = overviewService().pendingWorkResponse(EMPLOYEE, WaviTools.WorkArea.ALL, null, null, null);
        return response.message();
    }

    @Test
    void itReadsEveryTimesheetNotOnlyTheLatestAndSeparatesWhatNeedsTheEmployeeFromWhatWaitsForReview() {
        when(timesheets.listForUser(EMPLOYEE.userId())).thenReturn(List.of(
                timesheet("employee-9", "2026-11-16", "2026-11-22", TimesheetStatus.SUBMITTED, "2026-11-18T22:00:00"), // latest: waiting
                timesheet("employee-9", "2026-11-09", "2026-11-15", TimesheetStatus.DRAFT, "2026-11-13T10:00:00"),     // older draft: needs the employee
                timesheet("employee-9", "2026-11-02", "2026-11-08", TimesheetStatus.REJECTED, "2026-11-06T10:00:00"),  // rejected, never fixed
                timesheet("employee-9", "2026-10-26", "2026-11-01", TimesheetStatus.APPROVED, "2026-10-30T10:00:00")));

        String reply = overview();

        assertThat(reply).startsWith("Employees don't approve anything in Waypoint")
                .contains("Needs your action:", "2 timesheets to submit or correct",
                        "Mon Nov 9, 2026 to Sun Nov 15, 2026 (draft)", "Mon Nov 2, 2026 to Sun Nov 8, 2026 (rejected)")
                .contains("Waiting for HR or your manager (nothing for you to do):", "1 submitted timesheet awaiting review");
        // the latest, submitted timesheet is NOT presented as something the employee must do
        assertThat(reply.substring(0, reply.indexOf("Waiting for HR"))).doesNotContain("Nov 16");
    }

    @Test
    void aRejectedTimesheetThatWasResubmittedOrApprovedForTheSamePeriodNeedsNothingMore() {
        when(timesheets.listForUser(EMPLOYEE.userId())).thenReturn(List.of(
                timesheet("employee-9", "2026-11-02", "2026-11-08", TimesheetStatus.REJECTED, "2026-11-06T10:00:00"),
                timesheet("employee-9", "2026-11-02", "2026-11-08", TimesheetStatus.APPROVED, "2026-11-09T10:00:00"),   // the fix
                timesheet("employee-9", "2026-11-09", "2026-11-15", TimesheetStatus.REJECTED, "2026-11-13T10:00:00"),
                timesheet("employee-9", "2026-11-09", "2026-11-15", TimesheetStatus.SUBMITTED, "2026-11-14T10:00:00"))); // resubmitted

        String reply = overview();

        assertThat(reply).contains("Needs your action: nothing right now.")
                .contains("1 submitted timesheet awaiting review");
    }

    @Test
    void anOlderRejectionIsStillOpenWhenTheLaterRecordOfThatPeriodIsOnlyEarlierThanIt() {
        when(timesheets.listForUser(EMPLOYEE.userId())).thenReturn(List.of(
                timesheet("employee-9", "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-05T10:00:00"),
                timesheet("employee-9", "2026-11-02", "2026-11-08", TimesheetStatus.REJECTED, "2026-11-07T10:00:00"))); // rejected AFTER submitting

        assertThat(overview()).contains("1 timesheet to submit or correct", "Mon Nov 2, 2026 to Sun Nov 8, 2026 (rejected)");
    }

    @Test
    void profileChangesWaitForHrNeverForTheEmployeeAndEveryPendingOneIsCounted() {
        List<ProfileChangeRequestResponse> requests = List.of(
                change(ProfileChangeStatus.APPROVED),   // the most recent, decided
                change(ProfileChangeStatus.PENDING),
                change(ProfileChangeStatus.PENDING),
                change(ProfileChangeStatus.REJECTED));
        when(profileChanges.listMine(EMPLOYEE.userId())).thenReturn(requests);

        String reply = overview();

        assertThat(reply).contains("2 pending profile change requests awaiting HR");
        assertThat(reply.substring(0, reply.indexOf("Waiting for HR"))).doesNotContain("profile change");
    }

    @Test
    void documentRequestsAreSplitByWhoMustActAndFinishedOnesAreIgnored() {
        when(documents.listOwnHistory(EMPLOYEE.userId())).thenReturn(List.of(
                document("Form W-4", DocumentRequestStatus.PENDING, false),                       // asked of the employee: theirs
                document("Direct deposit form", DocumentRequestStatus.RETURNED_FOR_CORRECTION, false), // theirs
                document("Signed I-983", DocumentRequestStatus.SUBMITTED, false),                 // submitted: waiting for HR
                document("Employment letter", DocumentRequestStatus.PENDING, true),               // the employee asked HR: waiting
                document("Old request", DocumentRequestStatus.APPROVED, false),
                document("Another", DocumentRequestStatus.FULFILLED, true),
                document("Declined", DocumentRequestStatus.REJECTED, false),
                document("Cancelled", DocumentRequestStatus.CANCELLED, false)));

        String reply = overview();
        String action = reply.substring(0, reply.indexOf("Waiting for HR"));
        String waiting = reply.substring(reply.indexOf("Waiting for HR"));

        assertThat(action).contains("2 open document requests for you to complete", "Form W-4",
                "Direct deposit form (returned for correction)")
                .doesNotContain("Signed I-983", "Employment letter", "Old request", "Declined", "Cancelled");
        assertThat(waiting).contains("2 document requests awaiting HR");
    }

    @Test
    void whenNothingIsOpenItSaysSoInBothGroups() {
        String reply = overview();
        assertThat(reply).contains("Needs your action: nothing right now.", "no submitted timesheets awaiting review.",
                "no pending profile change requests.", "no document requests awaiting HR.");
    }

    @Test
    void onlyTheEmployeesOwnRecordsAreUsedAndNoModelIsAsked() {
        when(timesheets.listForUser(EMPLOYEE.userId())).thenReturn(List.of(
                timesheet("someone-else", "2026-11-09", "2026-11-15", TimesheetStatus.DRAFT, "2026-11-13T10:00:00")));
        String reply = overview();
        assertThat(reply).contains("Needs your action: nothing right now.");
        verifyNoInteractions(claude, ollama);
    }

    @Test
    void theQuestionAsTypedReachesTheNewOverviewThroughTheNormalChatPath() {
        when(timesheets.listForUser(EMPLOYEE.userId())).thenReturn(List.of(
                timesheet("employee-9", "2026-11-09", "2026-11-15", TimesheetStatus.DRAFT, "2026-11-13T10:00:00")));
        MyHrChatResponse response = overviewService().chat(EMPLOYEE, "do I have anything pending?", List.of());
        assertThat(response.message()).contains("Needs your action:", "1 timesheet to submit or correct");
        verifyNoInteractions(claude, ollama);
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // (2) One bounded, local-only citation retry
    // ---------------------------------------------------------------------------------------------------------------------

    private static final String QUESTION = "How many unused vacation days can employees carry forward?";
    private static final String LEAVE_TEXT = "2. Carry forward\nEmployees may carry forward up to 5 unused days.";
    private static final String HR_TEXT = "HR may approve up to 8 extra carry-forward days.";

    private MyHrChatService policyService(boolean externalApproved) {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                mock(EmployeeManagementService.class), mock(DocumentRequestService.class), "claude", retrieval, true,
                externalApproved, false,
                externalApproved ? (organizationId, chunks) -> true : ExternalPolicyProcessingAuthorization.DENY_ALL);
    }

    private static RetrievalResult retrieved(RetrievedChunk... chunks) {
        return new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(chunks), chunks.length);
    }

    private static RetrievedChunk chunk(String document, String title, ChunkAccessLevel level, String text) {
        return new RetrievedChunk(document + "-ingestion-00000", document, title, "stored-" + document,
                document + ".pdf", document + "-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }

    private void companyWide() {
        when(retrieval.retrieve(eq(EMPLOYEE), anyString())).thenReturn(retrieved(
                chunk("leave", "Synthetic Leave Policy", ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
    }

    @Test
    void aCompanyWideLocalAnswerWithoutACitationGetsOneRetryAndThenAnswersWithVerifiedCitations() {
        companyWide();
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "You can carry forward up to 5 unused days.",            // correct, but no source marker
                "You can carry forward up to 5 unused days [S1].");       // the retry cites

        MyHrChatResponse response = policyService(false).chat(EMPLOYEE, QUESTION, List.of());

        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(response.citations()).containsExactly(new MyHrChatCitation(1, "Synthetic Leave Policy", "leave.pdf", false));
        ArgumentCaptor<MyHrGroundedPrompt> prompts = ArgumentCaptor.forClass(MyHrGroundedPrompt.class);
        verify(ollama, times(2)).chat(prompts.capture());
        assertThat(prompts.getAllValues().get(1).systemPrompt()).contains("Include at least one valid source id citation");
        // local-only: Claude never saw the policy text, before or during the retry
        verify(claude, never()).chatWithDocuments(any(MyHrGroundedDocumentPrompt.class));
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));
    }

    @Test
    void theRetryIsBoundedAndAnAnswerThatStillCitesNothingIsNeverShown() {
        companyWide();
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Leave is generous and you can take as much as you like.",
                "Leave is generous and you can take as much as you like, really.");

        MyHrChatResponse response = policyService(false).chat(EMPLOYEE, QUESTION, List.of());

        verify(ollama, times(2)).chat(any(MyHrGroundedPrompt.class)); // exactly one retry, no more
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(response.citations()).isEmpty();
        assertThat(response.message()).doesNotContain("generous", "as much as you like");
    }

    @Test
    void aCitedAnswerIsNotRetriedAndAnInvalidSourceIdStillCountsAsUncited() {
        companyWide();
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("You can carry forward up to 5 unused days [S1].");
        assertThat(policyService(false).chat(EMPLOYEE, QUESTION, List.of()).knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        verify(ollama, times(1)).chat(any(MyHrGroundedPrompt.class));

        org.mockito.Mockito.reset(ollama);
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Up to 99 days [S9].", "You can carry forward up to 5 unused days [S1].");
        MyHrChatResponse corrected = policyService(false).chat(EMPLOYEE, QUESTION, List.of());
        assertThat(corrected.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(corrected.message()).doesNotContain("99");
    }

    @Test
    void anApprovedClaudeAnswerNeverTriggersALocalRetryAndHrOnlyContentStillStaysLocal() {
        // Claude-approved company-wide content: answered by Claude with native citations; the local retry never runs.
        companyWide();
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(List.of(new MyHrCitedSegment(
                "You can carry forward up to 5 unused days.", List.of(new MyHrCitedSegment.Citation("char_location",
                        LEAVE_TEXT, 0, 0, LEAVE_TEXT.length())))));
        policyService(true).chat(EMPLOYEE, QUESTION, List.of());
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));

        // HR-only content: local, retried once if uncited, and never sent to Claude.
        VerifiedMyHrActor hr = TestActors.hr("hr-9", "org-9");
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(retrieved(
                chunk("hr", "Synthetic HR Guidance", ChunkAccessLevel.HR_ONLY, HR_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("HR may approve 8 extra days.", "HR may approve up to 8 extra days [S1].");
        MyHrChatResponse forHr = policyService(true).chat(hr, "What is the HR carry forward guidance?", List.of());
        assertThat(forHr.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        verify(ollama, times(2)).chat(any(MyHrGroundedPrompt.class));
        ArgumentCaptor<MyHrGroundedDocumentPrompt> sentToClaude = ArgumentCaptor.forClass(MyHrGroundedDocumentPrompt.class);
        verify(claude, times(1)).chatWithDocuments(sentToClaude.capture()); // only the earlier company-wide call
        assertThat(sentToClaude.getAllValues()).flatExtracting(MyHrGroundedDocumentPrompt::documents)
                .extracting(MyHrGroundedDocumentPrompt.Document::text).noneMatch(text -> text.contains(HR_TEXT));
    }

    @Test
    void aRemoteOllamaNeverReceivesPolicyTextAndIsNeverRetried() {
        companyWide();
        when(ollama.isLocalEndpoint()).thenReturn(false);
        MyHrChatService service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class),
                mock(DocumentRequestService.class), "claude", retrieval, true, false, false,
                ExternalPolicyProcessingAuthorization.DENY_ALL);
        try {
            service.chat(EMPLOYEE, QUESTION, List.of());
        } catch (RuntimeException expected) {
            // reported as unavailable: policy text is not sent to a remote Ollama
        }
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, never()).chat(anyString(), anyList());
    }
}
