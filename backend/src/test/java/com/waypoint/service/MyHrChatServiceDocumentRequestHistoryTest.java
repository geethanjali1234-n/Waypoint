package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrOwnDocumentRequestHistory;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyHrChatServiceDocumentRequestHistoryTest {
    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE);

    @Test
    void answersLatestAndOfferLetterStatusesWithoutCallingAi() {
        DocumentRequestService documents = mock(DocumentRequestService.class);
        when(documents.listOwnHistory("employee-1")).thenReturn(List.of(
                request(RequestedDocumentType.EMPLOYMENT_VERIFICATION, "Employment verification letter",
                        true, DocumentRequestStatus.APPROVED, 10, null, false),
                request(RequestedDocumentType.OFFER_LETTER, "Offer Letter", true,
                        DocumentRequestStatus.FULFILLED, 9, null, true)));
        Clients clients = new Clients(documents);

        assertThat(ask(clients.service, "What is the status of my latest document request?"))
                .isEqualTo("Your latest document request was approved on 2026-10-11. It was requested on 2026-10-10.");
        assertThat(ask(clients.service, "Can you tell me my latest document request status?"))
                .isEqualTo("Your latest document request was approved on 2026-10-11. It was requested on 2026-10-10.");
        assertThat(ask(clients.service, "Has my document request been approved?"))
                .isEqualTo("Your latest document request was approved on 2026-10-11. It was requested on 2026-10-10.");
        assertThat(ask(clients.service, "What is the status of my offer letter?"))
                .isEqualTo("Your latest offer letter request was completed on 2026-10-10. It was requested on 2026-10-09.");
        assertThat(ask(clients.service, "Where can I download my completed offer letter?"))
                .isEqualTo("Your completed offer letter is available in Document Requests. Open your Offer Letter request and use Preview or Download.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void answersPendingAndOnboardingQuestionsFromAuthorizedHistory() {
        DocumentRequestService documents = mock(DocumentRequestService.class);
        when(documents.listOwnHistory("employee-1")).thenReturn(List.of(
                request(RequestedDocumentType.OTHER, "Onboarding documents", false,
                        DocumentRequestStatus.PENDING, 12, "PRE_EMPLOYMENT_ONBOARDING", false)));
        Clients clients = new Clients(documents);

        assertThat(ask(clients.service, "Is my onboarding document request still pending?"))
                .isEqualTo("Your latest onboarding document request is pending, requested on 2026-10-12.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void distinguishesARecordedSignedDocumentWithoutExposingItsIdentifier() {
        DocumentRequestService documents = mock(DocumentRequestService.class);
        when(documents.listOwnHistory("employee-1")).thenReturn(List.of(
                new MyHrOwnDocumentRequestHistory(RequestedDocumentType.OFFER_LETTER, "Offer Letter", List.of(), "EMPLOYMENT",
                        true, DocumentRequestStatus.FULFILLED, date(9), null, null, date(10), null,
                        date(10), null, true, true)));
        Clients clients = new Clients(documents);

        assertThat(ask(clients.service, "What is the status of my offer letter?"))
                .isEqualTo("Your latest offer letter request was completed on 2026-10-10. A signed document was recorded on 2026-10-10. It was requested on 2026-10-09.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void reportsRejectionReasonsOnlyWhenTheyAreRecorded() {
        DocumentRequestService documents = mock(DocumentRequestService.class);
        when(documents.listOwnHistory("employee-1")).thenReturn(List.of(
                new MyHrOwnDocumentRequestHistory(RequestedDocumentType.OTHER, "Employment letter", List.of(), "EMPLOYMENT",
                        true, DocumentRequestStatus.REJECTED, date(8), null, null, null, date(9), null,
                        "Missing manager approval", false, false)));
        Clients clients = new Clients(documents);

        assertThat(ask(clients.service, "Why was my document request rejected?"))
                .isEqualTo("Your most recent rejected document request was rejected on 2026-10-09. Recorded reason: Missing manager approval");

        when(documents.listOwnHistory("employee-1")).thenReturn(List.of(
                new MyHrOwnDocumentRequestHistory(RequestedDocumentType.OTHER, "Employment letter", List.of(), "EMPLOYMENT",
                        true, DocumentRequestStatus.REJECTED, date(8), null, null, null, null, null,
                        null, false, false)));
        assertThat(ask(clients.service, "Why was my document request rejected?"))
                .isEqualTo("Your most recent rejected document request, but no rejection date was recorded. Waypoint has no rejection reason recorded.");
    }

    @Test
    void reportsSubmittedEmploymentVerificationAndMultipleOwnRequestsWithoutAttachments() {
        DocumentRequestService documents = mock(DocumentRequestService.class);
        when(documents.listOwnHistory("employee-1")).thenReturn(List.of(
                request(RequestedDocumentType.EMPLOYMENT_VERIFICATION, "Employment verification letter", true,
                        DocumentRequestStatus.PENDING, 11, "EMPLOYMENT", false),
                request(RequestedDocumentType.OFFER_LETTER, "Offer Letter", true,
                        DocumentRequestStatus.FULFILLED, 10, "EMPLOYMENT", true),
                request(RequestedDocumentType.OTHER, "I-9 documents", false,
                        DocumentRequestStatus.PENDING, 9, "PRE_EMPLOYMENT_ONBOARDING", false)));
        Clients clients = new Clients(documents);

        assertThat(ask(clients.service, "When did I submit my employment verification letter request?"))
                .isEqualTo("You submitted your employment verification letter request on 2026-10-11.");
        assertThat(ask(clients.service, "What documents have I requested?"))
                .isEqualTo("You requested: Employment verification letter (pending); Offer Letter (completed).");
    }

    @Test
    void reportsMissingHistoryAndDoesNotUseEmployeeHistoryForHr() {
        DocumentRequestService documents = mock(DocumentRequestService.class);
        when(documents.listOwnHistory("employee-1")).thenReturn(List.of());
        Clients clients = new Clients(documents);

        assertThat(ask(clients.service, "What is the status of my latest document request?"))
                .isEqualTo("I couldn't find a document request of yours in Waypoint.");

        // HR's own requests come from the existing "My requests" worklist, never the employee-only history,
        // and the question no longer reaches the model.
        VerifiedMyHrActor hr = TestActors.hr("hr-1", "org-1");
        assertThat(clients.service.chat(hr, "What is the status of my latest document request?", List.of()).message())
                .isEqualTo("I couldn't find a document request you made for yourself in Waypoint. Requests you sent "
                        + "to employees are under Request Documents, My requests.");
        verify(documents).list("hr-1", 0, 1, null, null, null, null, "mine");
        verify(documents).listOwnHistory("employee-1");
        verify(documents, org.mockito.Mockito.never()).listOwnHistory("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    private String ask(MyHrChatService service, String question) {
        return service.chat(EMPLOYEE, question, List.of()).message();
    }

    private MyHrOwnDocumentRequestHistory request(RequestedDocumentType type, String title, boolean requestedByMe,
                                                   DocumentRequestStatus status, int day, String category, boolean downloadable) {
        return new MyHrOwnDocumentRequestHistory(type, title, List.of(), category, requestedByMe, status,
                date(day), null, status == DocumentRequestStatus.APPROVED ? date(day + 1) : null,
                status == DocumentRequestStatus.FULFILLED ? date(day + 1) : null, null, null, null, false, downloadable);
    }

    private LocalDateTime date(int day) {
        return LocalDateTime.of(2026, 10, day, 9, 0);
    }

    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final MyHrChatService service;

        private Clients(DocumentRequestService documents) {
            service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                    mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class), documents,
                    "claude", null, false, false);
        }
    }
}
