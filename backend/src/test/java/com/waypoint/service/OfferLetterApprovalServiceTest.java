package com.waypoint.service;

import com.waypoint.dto.myhr.OfferLetterApprovalListResponse;
import com.waypoint.dto.myhr.OfferLetterApprovalResponse;
import com.waypoint.dto.myhr.OfferLetterApprovalSubmitRequest;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.OfferLetterApprovalDetails;
import com.waypoint.entity.Organization;
import com.waypoint.entity.StoredDocument;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.DocumentRequestRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfferLetterApprovalServiceTest {
    private static final byte[] PDF = "%PDF-1.7 offer letter".getBytes(StandardCharsets.US_ASCII);
    private static final Set<UserRole> SENDERS = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);
    private static final Set<UserRole> ADMINS = Set.of(UserRole.COMPANY_ADMIN);

    @Mock DocumentRequestRepository requests;
    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    @Mock MyHrAuthenticatedUserService auth;
    @Mock DocumentService documents;
    @Mock AuditService audit;
    @Mock InAppNotificationService notifications;
    @Mock EmailService emailService;
    @Mock OrganizationRepository organizations;
    @Mock AuthorizedSignatureService signatures;

    OfferLetterApprovalService service;

    private NotificationOrchestrator orchestrator() {
        return TestOrchestrators.create(notifications, emailService, new EmailTemplateService(), users, profiles);
    }
    DocumentRequest request;

    @BeforeEach void setUp() {
        service = new OfferLetterApprovalService(requests, users, profiles, auth, documents, audit,
                orchestrator(), organizations, signatures);
        request = DocumentRequest.builder().id("request-1").organizationId("org-1").requestedByUserId("employee-1")
                .requestedForUserId("hr-1").documentType(RequestedDocumentType.OFFER_LETTER).requestTitle("Offer Letter")
                .requestDirection("TO_MANAGER").status(DocumentRequestStatus.PENDING).build();
        lenient().when(auth.require("hr-1", SENDERS, false)).thenReturn(actor("hr-1", UserRole.HR, "org-1"));
        lenient().when(auth.require("hr-2", SENDERS, false)).thenReturn(actor("hr-2", UserRole.HR, "org-1"));
        lenient().when(auth.require("admin", SENDERS, false)).thenReturn(actor("admin", UserRole.COMPANY_ADMIN, "org-1"));
        lenient().when(auth.require("admin", ADMINS, false)).thenReturn(actor("admin", UserRole.COMPANY_ADMIN, "org-1"));
        lenient().when(auth.require("other-admin", ADMINS, false)).thenReturn(actor("other-admin", UserRole.COMPANY_ADMIN, "org-2"));
        lenient().when(auth.require("hr-1", ADMINS, false)).thenThrow(new ForbiddenException("You do not have access to this resource"));
        lenient().when(auth.require("employee-1", ADMINS, false)).thenThrow(new ForbiddenException("You do not have access to this resource"));
        lenient().when(auth.require("employee-1", SENDERS, false)).thenThrow(new ForbiddenException("You do not have access to this resource"));
        lenient().when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        lenient().when(requests.findByIdAndOrganizationId("request-1", "org-2")).thenReturn(Optional.empty());
        // The atomic compare-and-set the real repository performs.
        lenient().when(requests.transitionOfferLetterStatus(anyString(), anyString(), anyCollection(), anyString(), any()))
                .thenAnswer(call -> {
                    Collection<String> from = call.getArgument(2);
                    if (!from.contains(request.getStatus().name())) return 0L;
                    request.setStatus(DocumentRequestStatus.valueOf(call.getArgument(3)));
                    return 1L;
                });
        lenient().when(requests.save(any(DocumentRequest.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(documents.store(any())).thenReturn(StoredDocument.builder().id("approval-doc-1").fileName("offer-letter-approval-v1.pdf").build());
        lenient().when(users.findByOrganizationIdAndAccountStatusAndRoleIn("org-1", AccountStatus.ACTIVE, ADMINS))
                .thenReturn(List.of(user("admin", UserRole.COMPANY_ADMIN, "admin@tardus.example")));
        lenient().when(users.findById("hr-1")).thenReturn(Optional.of(user("hr-1", UserRole.HR, "hr@tardus.example")));
        lenient().when(users.findById("admin")).thenReturn(Optional.of(user("admin", UserRole.COMPANY_ADMIN, "admin@tardus.example")));
        lenient().when(profiles.findByUserId(anyString())).thenReturn(Optional.empty());
        // HR sees the approver as "<Organization> Admin".
        lenient().when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1").organizationName("Tardus").build()));
    }

    // ---------------------------------------------------------------- submit

    @Test void hrSubmitsForApprovalAndTheLetterBecomesPendingApprovalNotSent() {
        OfferLetterApprovalResponse response = submit("hr-1", "request-1", details(), PDF);

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
        assertThat(response.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.canSend()).isFalse();
        assertThat(request.getCurrentDocumentId()).isEqualTo("approval-doc-1");
        assertThat(request.getFulfilledDocumentId()).isNull();
        assertThat(request.getOfferLetterApproval().getEmployeeName()).isEqualTo("Jane Doe");
        assertThat(request.getOfferLetterApproval().getSubmittedByUserId()).isEqualTo("hr-1");
        assertThat(request.getApprovedByUserId()).isNull();
        verify(audit).record(any(AuthenticatedMyHrUser.class), eq(AuditActionType.OFFER_SUBMITTED_FOR_APPROVAL), eq("DOCUMENT_REQUEST"),
                eq("request-1"), anyString(), anyString(), any(), any());
    }

    @Test void submittingNotifiesTheAdminInAppAndByEmailWithAReviewLink() {
        submit("hr-1", "request-1", details(), PDF);

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                eq("Offer Letter Approval Required"),
                eq("HR has submitted an offer letter for Jane Doe for the position of Software Developer. Please review and approve or reject the offer letter."),
                eq("request-1"), eq("/myhr/admin/offer-letter-approvals/request-1"));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("admin@tardus.example"), eq("Offer Letter Approval Required – Jane Doe"), html.capture());
        assertThat(html.getValue()).contains("Jane Doe", "Software Developer", "Paid", "Full-time", "2026-10-15",
                "Review Offer Letter", "http://localhost:5173/myhr/admin/offer-letter-approvals/request-1");
        // No approve action is offered inside the email.
        assertThat(html.getValue()).doesNotContainIgnoringCase("/approve");
        // The employee is not contacted at all.
        verify(emailService, never()).sendHtmlEmail(eq("employee@tardus.example"), anyString(), anyString());
    }

    @Test void duplicateSubmissionWhilePendingIsRejectedAndNeverNotifiesTwice() {
        submit("hr-1", "request-1", details(), PDF);

        assertThatThrownBy(() -> submit("hr-1", "request-1", details(), PDF))
                .isInstanceOf(BusinessException.class).hasMessage("This offer letter is already waiting for Admin approval.");
        verify(notifications, times(1)).createInAppOnlyNotification(anyString(), anyString(), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                anyString(), anyString(), anyString(), anyString());
        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(documents, times(1)).store(any());
    }

    @Test void aConcurrentSubmissionThatLosesTheRaceStoresNothingAndNotifiesNobody() {
        when(requests.transitionOfferLetterStatus(anyString(), anyString(), anyCollection(), anyString(), any())).thenReturn(0L);

        assertThatThrownBy(() -> submit("hr-1", "request-1", details(), PDF))
                .isInstanceOf(BusinessException.class).hasMessage(OfferLetterApprovalService.CONCURRENT_CHANGE);
        verify(documents).delete("approval-doc-1");
        verifyNoInteractions(notifications, emailService);
        verify(requests, never()).save(any());
    }

    @Test void onlyTheAssignedHrOrAnAdminMaySubmitAndNeverAnEmployee() {
        assertThatThrownBy(() -> submit("hr-2", "request-1", details(), PDF)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> submit("employee-1", "request-1", details(), PDF)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(notifications, emailService, documents);
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.PENDING);
    }

    @Test void aLetterThatIsAlreadyApprovedOrSentCannotBeSubmittedAgain() {
        request.setStatus(DocumentRequestStatus.APPROVED);
        assertThatThrownBy(() -> submit("hr-1", "request-1", details(), PDF)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already approved");
        request.setStatus(DocumentRequestStatus.FULFILLED);
        assertThatThrownBy(() -> submit("hr-1", "request-1", details(), PDF)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been sent");
    }

    @Test void aNonPdfIsRejectedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> submit("hr-1", "request-1", details(), "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(documents, notifications);
    }

    @Test void aCompanyAdminPreparingTheLetterIsApprovedWithoutNotifyingAnyone() {
        OfferLetterApprovalResponse response = submit("admin", "request-1", details(), PDF);

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.APPROVED);
        assertThat(request.getApprovedByUserId()).isEqualTo("admin");
        assertThat(response.canSend()).isTrue();
        verifyNoInteractions(notifications, emailService);
    }

    // ---------------------------------------------------------------- approve

    @Test void adminApprovesAndHrIsNotifiedInAppAndByEmailButTheEmployeeIsNot() {
        submitAsHr();

        OfferLetterApprovalResponse response = service.approve("admin", "request-1");

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.APPROVED);
        assertThat(request.getApprovedByUserId()).isEqualTo("admin");
        assertThat(request.getApprovedAt()).isNotNull();
        assertThat(request.getFulfilledDocumentId()).as("approval alone never sends the letter").isNull();
        assertThat(response.approvalStatus()).isEqualTo("APPROVED");
        assertThat(response.documentId()).isEqualTo("approval-doc-1");
        verify(audit).record(any(AuthenticatedMyHrUser.class), eq(AuditActionType.OFFER_APPROVED), eq("DOCUMENT_REQUEST"), eq("request-1"),
                anyString(), anyString(), any(), any());
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr-1"), eq(NotificationType.OFFER_LETTER_APPROVED), eq("Offer Letter Approved"),
                eq("The offer letter for Jane Doe has been approved by the Tardus Admin. You can now send it to the employee."),
                eq("request-1"), eq("/myhr/hr/document-requests/request-1/offer-letter"));
        verify(emailService).sendHtmlEmail(eq("hr@tardus.example"), eq("Offer Letter Approved – Jane Doe"), anyString());
    }

    @Test void onlyACompanyAdminCanApprove() {
        submitAsHr();

        assertThatThrownBy(() -> service.approve("hr-1", "request-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.approve("employee-1", "request-1")).isInstanceOf(ForbiddenException.class);
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
    }

    @Test void anAdminOfAnotherOrganizationCannotSeeOrApprove() {
        submitAsHr();

        assertThatThrownBy(() -> service.approve("other-admin", "request-1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reject("other-admin", "request-1", "No")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
    }

    @Test void anAlreadyApprovedLetterCannotBeApprovedAgainAndSendsNoSecondNotification() {
        submitAsHr();
        service.approve("admin", "request-1");

        assertThatThrownBy(() -> service.approve("admin", "request-1")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been approved");
        verify(notifications, times(1)).createInAppOnlyNotification(anyString(), eq("hr-1"), eq(NotificationType.OFFER_LETTER_APPROVED),
                anyString(), anyString(), anyString(), anyString());
        verify(emailService, times(1)).sendHtmlEmail(eq("hr@tardus.example"), anyString(), anyString());
    }

    @Test void aLetterThatWasNeverSubmittedCannotBeApproved() {
        assertThatThrownBy(() -> service.approve("admin", "request-1")).isInstanceOf(BusinessException.class);
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.PENDING);
    }

    @Test void aFailingNotificationOrAuditNeverTurnsAnApprovalIntoAFailure() {
        submitAsHr();
        doThrow(new RuntimeException("notifications down")).when(notifications).createInAppOnlyNotification(anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString());
        doThrow(new RuntimeException("smtp down")).when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
        doThrow(new RuntimeException("audit down")).when(audit).record(any(AuthenticatedMyHrUser.class), any(), anyString(), anyString(),
                any(), anyString(), any(), any());

        OfferLetterApprovalResponse response = service.approve("admin", "request-1");

        assertThat(response.approvalStatus()).isEqualTo("APPROVED");
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.APPROVED);
    }

    // ---------------------------------------------------------------- reject

    @Test void rejectionRequiresAReasonAndChangesNothingWithout() {
        submitAsHr();

        assertThatThrownBy(() -> service.reject("admin", "request-1", "  ")).isInstanceOf(BusinessException.class)
                .hasMessage("Rejection reason is required");
        assertThatThrownBy(() -> service.reject("admin", "request-1", null)).isInstanceOf(BusinessException.class);
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
    }

    @Test void adminRejectsStoresTheReasonAndNotifiesHrWithIt() {
        submitAsHr();

        OfferLetterApprovalResponse response = service.reject("admin", "request-1", "  Wrong salary  ");

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.RETURNED_FOR_CORRECTION);
        assertThat(request.getReturnReason()).isEqualTo("Wrong salary");
        assertThat(request.getApprovedByUserId()).isNull();
        assertThat(request.getFulfilledDocumentId()).isNull();
        assertThat(response.approvalStatus()).isEqualTo("REJECTED");
        assertThat(response.rejectionReason()).isEqualTo("Wrong salary");
        assertThat(response.canSend()).isFalse();
        assertThat(response.canSubmit()).as("HR can correct and resubmit").isTrue();
        verify(audit).record(any(AuthenticatedMyHrUser.class), eq(AuditActionType.OFFER_REJECTED), eq("DOCUMENT_REQUEST"), eq("request-1"),
                anyString(), anyString(), any(), any());
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr-1"), eq(NotificationType.OFFER_LETTER_REJECTED), eq("Offer Letter Rejected"),
                eq("The offer letter for Jane Doe was rejected by the Tardus Admin.\n\nReason:\nWrong salary"),
                eq("request-1"), eq("/myhr/hr/document-requests/request-1/offer-letter"));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("hr@tardus.example"), eq("Offer Letter Rejected – Jane Doe"), html.capture());
        assertThat(html.getValue()).contains("Wrong salary");
    }

    @Test void anAlreadyRejectedLetterCannotBeRejectedOrApprovedAgain() {
        submitAsHr();
        service.reject("admin", "request-1", "Wrong salary");

        assertThatThrownBy(() -> service.reject("admin", "request-1", "Again")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been rejected");
        assertThatThrownBy(() -> service.approve("admin", "request-1")).isInstanceOf(BusinessException.class);
        verify(notifications, times(1)).createInAppOnlyNotification(anyString(), eq("hr-1"), eq(NotificationType.OFFER_LETTER_REJECTED),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test void aRejectedLetterCanBeCorrectedResubmittedAndApproved() {
        submitAsHr();
        service.reject("admin", "request-1", "Wrong salary");

        OfferLetterApprovalResponse resubmitted = submit("hr-1", "request-1", details(), PDF);

        assertThat(resubmitted.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(request.getReturnReason()).isNull();
        // (the first submission's notification was cleared in submitAsHr); resubmitting asks the Admin exactly once more.
        verify(notifications, times(1)).createInAppOnlyNotification(anyString(), eq("admin"), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                anyString(), anyString(), anyString(), anyString());
        assertThat(service.approve("admin", "request-1").approvalStatus()).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- reads

    @Test void hrReadsTheStateAndAnUnrelatedHrOrAnEmployeeCannot() {
        submitAsHr();

        assertThat(service.get("hr-1", "request-1").approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(service.get("hr-1", "request-1").canApprove()).as("HR never gets the approve action").isFalse();
        assertThatThrownBy(() -> service.get("hr-2", "request-1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get("employee-1", "request-1")).isInstanceOf(ForbiddenException.class);
    }

    @Test void adminListCountsPendingApprovedAndRejected() {
        DocumentRequest pending = approvalRequest("r-pending", DocumentRequestStatus.SUBMITTED);
        DocumentRequest approved = approvalRequest("r-approved", DocumentRequestStatus.APPROVED);
        DocumentRequest sent = approvalRequest("r-sent", DocumentRequestStatus.FULFILLED);
        DocumentRequest rejected = approvalRequest("r-rejected", DocumentRequestStatus.RETURNED_FOR_CORRECTION);
        when(requests.findByOrganizationIdAndOfferLetterApprovalNotNullOrderByUpdatedAtDesc("org-1"))
                .thenReturn(List.of(pending, approved, sent, rejected));

        OfferLetterApprovalListResponse list = service.list("admin");

        assertThat(list.pending()).isEqualTo(1);
        assertThat(list.approved()).isEqualTo(2);
        assertThat(list.rejected()).isEqualTo(1);
        assertThat(list.items()).extracting(OfferLetterApprovalResponse::approvalStatus)
                .containsExactly("PENDING_APPROVAL", "APPROVED", "SENT", "REJECTED");
        assertThatThrownBy(() -> service.list("hr-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.list("employee-1")).isInstanceOf(ForbiddenException.class);
    }

    @Test void aNewOrganizationAdminSeesOnlyItsOwnApprovalQueue() {
        when(auth.require("itap-admin", ADMINS, false)).thenReturn(actor("itap-admin", UserRole.COMPANY_ADMIN, "org-itap"));
        DocumentRequest itapPending = approvalRequest("itap-request", DocumentRequestStatus.SUBMITTED);
        itapPending.setOrganizationId("org-itap");
        when(requests.findByOrganizationIdAndOfferLetterApprovalNotNullOrderByUpdatedAtDesc("org-itap"))
                .thenReturn(List.of(itapPending));

        OfferLetterApprovalListResponse list = service.list("itap-admin");

        assertThat(list.pending()).isEqualTo(1);
        assertThat(list.items()).extracting(OfferLetterApprovalResponse::requestId).containsExactly("itap-request");
        verify(requests).findByOrganizationIdAndOfferLetterApprovalNotNullOrderByUpdatedAtDesc("org-itap");
    }

    @Test void approvalStatusAndApprovedHelpersFollowTheLifecycle() {
        assertThat(OfferLetterApprovalService.approvalStatus(request)).isEqualTo("NONE");
        assertThat(OfferLetterApprovalService.isApproved(request)).isFalse();
        assertThat(OfferLetterApprovalService.isApproved(approvalRequest("a", DocumentRequestStatus.SUBMITTED))).as("pending is not approved").isFalse();
        assertThat(OfferLetterApprovalService.isApproved(approvalRequest("a", DocumentRequestStatus.APPROVED))).isTrue();
        assertThat(OfferLetterApprovalService.isApproved(approvalRequest("a", DocumentRequestStatus.FULFILLED))).isTrue();
        assertThat(OfferLetterApprovalService.isApproved(approvalRequest("a", DocumentRequestStatus.RETURNED_FOR_CORRECTION))).isFalse();
    }

    // ---------------------------------------------------------------- other documents HR delivers

    private DocumentRequest document(RequestedDocumentType type, String direction) {
        return DocumentRequest.builder().id("request-1").organizationId("org-1").requestedByUserId("employee-1").requestedForUserId("hr-1")
                .documentType(type).requestTitle("Employment Verification").requestDirection(direction).status(DocumentRequestStatus.PENDING).build();
    }

    private OfferLetterApprovalSubmitRequest documentDetails() {
        return new OfferLetterApprovalSubmitRequest("Jane Doe", null, null, null, null, "2026-10-01", null, null, null, "{\"employeeName\":\"Jane Doe\"}", null);
    }

    @Test void anyDocumentHrDeliversGoesThroughAdminApprovalWithItsOwnFileAndWording() {
        request.setDocumentType(RequestedDocumentType.EMPLOYMENT_VERIFICATION);
        request.setRequestTitle("Employment Verification");
        byte[] docx = "PK-docx-bytes".getBytes(StandardCharsets.US_ASCII);
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("approval-doc-1").fileName("approval-v1-letter.docx").build());

        OfferLetterApprovalResponse response = service.submit("hr-1", "request-1", documentDetails(), "../letter.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
        assertThat(response.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.canSend()).isFalse();
        assertThat(response.documentTitle()).isEqualTo("Employment Verification");
        assertThat(response.documentType()).isEqualTo("EMPLOYMENT_VERIFICATION");
        assertThat(response.fileName()).isEqualTo("letter.docx");
        ArgumentCaptor<DocumentUploadRequest> stored = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documents).store(stored.capture());
        assertThat(stored.getValue().fileName()).isEqualTo("approval-v1-letter.docx");
        assertThat(stored.getValue().contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(stored.getValue().content()).isEqualTo(docx);
        // The Admin is asked in-app and by email, using the document's own name - never the employee.
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                eq("Document Approval Required"),
                eq("HR has submitted the Employment Verification for Jane Doe. Please review and approve or reject the document."),
                eq("request-1"), eq("/myhr/admin/offer-letter-approvals/request-1"));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("admin@tardus.example"), eq("Document Approval Required – Jane Doe – Employment Verification"), html.capture());
        assertThat(html.getValue()).contains("Employment Verification", "Review Document", "http://localhost:5173/myhr/admin/offer-letter-approvals/request-1");
        verify(emailService, never()).sendHtmlEmail(eq("employee@tardus.example"), anyString(), anyString());
    }

    @Test void approvingAndRejectingAnyDocumentNotifiesHrWithTheDocumentName() {
        request.setDocumentType(RequestedDocumentType.OTHER);
        request.setRequestTitle("Salary Verification Letter");
        submit("hr-1", "request-1", documentDetails(), PDF);
        org.mockito.Mockito.clearInvocations(notifications, emailService);

        service.reject("admin", "request-1", "Wrong amount");

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr-1"), eq(NotificationType.OFFER_LETTER_REJECTED), eq("Document Rejected"),
                eq("The Salary Verification Letter for Jane Doe was rejected by the Tardus Admin.\n\nReason:\nWrong amount"),
                eq("request-1"), eq("/myhr/hr/document-requests/request-1"));
        verify(emailService).sendHtmlEmail(eq("hr@tardus.example"), eq("Document Rejected – Jane Doe"), anyString());

        submit("hr-1", "request-1", documentDetails(), PDF);
        org.mockito.Mockito.clearInvocations(notifications, emailService);
        service.approve("admin", "request-1");
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr-1"), eq(NotificationType.OFFER_LETTER_APPROVED), eq("Document Approved"),
                eq("The Salary Verification Letter for Jane Doe has been approved by the Tardus Admin. You can now send it to the employee."),
                eq("request-1"), eq("/myhr/hr/document-requests/request-1"));
    }

    @Test void theSignedI983AndDocumentsHrRequestedFromAnEmployeeNeedNoApproval() {
        request.setDocumentType(RequestedDocumentType.I983);
        assertThatThrownBy(() -> submit("hr-1", "request-1", documentDetails(), PDF)).isInstanceOf(BusinessException.class)
                .hasMessage("This document does not need Admin approval");

        request.setDocumentType(RequestedDocumentType.OTHER);
        request.setRequestDirection("TO_EMPLOYEE");
        assertThatThrownBy(() -> submit("hr-1", "request-1", documentDetails(), PDF)).isInstanceOf(BusinessException.class);
        assertThat(OfferLetterApprovalService.requiresApproval(document(RequestedDocumentType.EMPLOYMENT_LETTER, "TO_MANAGER"))).isTrue();
        assertThat(OfferLetterApprovalService.requiresApproval(document(RequestedDocumentType.OFFER_LETTER, "TO_MANAGER"))).isTrue();
        assertThat(OfferLetterApprovalService.requiresApproval(document(RequestedDocumentType.I983, "TO_MANAGER"))).isFalse();
        assertThat(OfferLetterApprovalService.requiresApproval(document(RequestedDocumentType.OTHER, "TO_EMPLOYEE"))).isFalse();
        verifyNoInteractions(documents, notifications);
    }

    @Test void aDocumentFileIsRequiredButOnlyOfferLettersMustBePdfs() {
        request.setDocumentType(RequestedDocumentType.OTHER);

        assertThatThrownBy(() -> service.submit("hr-1", "request-1", documentDetails(), "a.docx", "application/msword", new byte[0]))
                .isInstanceOf(BusinessException.class).hasMessage("The document file is required");
        // A non-PDF is fine for an ordinary document...
        assertThat(service.submit("hr-1", "request-1", documentDetails(), "a.docx", "application/msword", new byte[]{1, 2, 3}).approvalStatus())
                .isEqualTo("PENDING_APPROVAL");
    }

    // ---------------------------------------------------------------- helpers

    private OfferLetterApprovalResponse submit(String actorId, String requestId, OfferLetterApprovalSubmitRequest input, byte[] content) {
        return service.submit(actorId, requestId, input, "offer-letter.pdf", "application/pdf", content);
    }

    // ---------------------------------------------------------------- company admin signatory lookup

    private OfferLetterApprovalSubmitRequest signedDetails() {
        return new OfferLetterApprovalSubmitRequest("Jane Doe", "Software Developer", "Paid", "Full-time", "2026-10-15", "2026-10-01",
                "$95,000", "Austin, TX", "Build things", "{}", new com.waypoint.dto.myhr.I983SignaturePlacement(1, 0.1, 0.1, 0.2, 0.05));
    }

    private void givenOrganizationAdminEmail(String adminEmail) {
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1").organizationName("Tardus")
                .adminName("Jane Admin").adminEmail(adminEmail).build()));
        lenient().when(signatures.findProfileSignature(anyString(), anyString(), any())).thenReturn(Optional.of(
                new AuthorizedSignatureService.ActiveSignature("Jane Admin", null, null, new byte[]{1}, "image/png", "PROFILE")));
    }

    @Test void theSignatoryIsTheOrganizationsAdminEvenWhenTheAdminEmailDiffersFromTheLoginEmail() {
        givenOrganizationAdminEmail("tardusinc27@gmail.com");

        service.submit("hr-1", "request-1", signedDetails(), "letter.pdf", "application/pdf", PDF);

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
        // Notification goes to the admin account; the contact email is resolved separately by the notification service.
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                anyString(), anyString(), eq("request-1"), anyString());
    }

    @Test void aMissingAdminEmailStillResolvesTheOnlyActiveCompanyAdmin() {
        givenOrganizationAdminEmail(null);

        service.submit("hr-1", "request-1", signedDetails(), "letter.pdf", "application/pdf", PDF);

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
    }

    @Test void anInactiveCompanyAdminIsNeverTheSignatory() {
        givenOrganizationAdminEmail("tardusinc27@gmail.com");
        when(users.findByOrganizationIdAndAccountStatusAndRoleIn("org-1", AccountStatus.ACTIVE, ADMINS)).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit("hr-1", "request-1", signedDetails(), "letter.pdf", "application/pdf", PDF))
                .isInstanceOf(BusinessException.class).hasMessageContaining("company admin account could not be found");
    }

    @Test void anAdminOfAnotherOrganizationIsNeverTheSignatory() {
        givenOrganizationAdminEmail("admin@tardus.example");
        User foreign = user("admin", UserRole.COMPANY_ADMIN, "admin@tardus.example");
        foreign.setOrganizationId("org-2");
        when(users.findByOrganizationIdAndAccountStatusAndRoleIn("org-1", AccountStatus.ACTIVE, ADMINS)).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.submit("hr-1", "request-1", signedDetails(), "letter.pdf", "application/pdf", PDF))
                .isInstanceOf(BusinessException.class).hasMessageContaining("company admin account could not be found");
    }

    @Test void severalAdminsAreDisambiguatedByLoginEmailMatchingAdminEmailAndNeverPickedArbitrarily() {
        User second = user("admin-2", UserRole.COMPANY_ADMIN, "second@tardus.example");
        when(users.findByOrganizationIdAndAccountStatusAndRoleIn("org-1", AccountStatus.ACTIVE, ADMINS))
                .thenReturn(List.of(user("admin", UserRole.COMPANY_ADMIN, "admin@tardus.example"), second));

        givenOrganizationAdminEmail("tardusinc27@gmail.com");
        assertThatThrownBy(() -> service.submit("hr-1", "request-1", signedDetails(), "letter.pdf", "application/pdf", PDF))
                .isInstanceOf(BusinessException.class).hasMessageContaining("company admin account could not be found");

        givenOrganizationAdminEmail(" Second@Tardus.example ");
        service.submit("hr-1", "request-1", signedDetails(), "letter.pdf", "application/pdf", PDF);
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin-2"), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                anyString(), anyString(), eq("request-1"), anyString());
        verify(notifications, never()).createInAppOnlyNotification(eq("org-1"), eq("admin"), any(), anyString(), anyString(), anyString(), anyString());
    }

    private void submitAsHr() {
        submit("hr-1", "request-1", details(), PDF);
        org.mockito.Mockito.clearInvocations(notifications, emailService, audit, documents);
    }

    private static OfferLetterApprovalSubmitRequest details() {
        return new OfferLetterApprovalSubmitRequest("Jane Doe", "Software Developer", "Paid", "Full-time", "2026-10-15", "2026-10-01",
                "$95,000", "Austin, TX", "Build things", "{\"employeeName\":\"Jane Doe\"}", null);
    }

    private static DocumentRequest approvalRequest(String id, DocumentRequestStatus status) {
        DocumentRequest item = DocumentRequest.builder().id(id).organizationId("org-1").requestedByUserId("employee-1").requestedForUserId("hr-1")
                .documentType(RequestedDocumentType.OFFER_LETTER).requestTitle("Offer Letter").status(status)
                .offerLetterApproval(OfferLetterApprovalDetails.builder().employeeName("Jane Doe").designation("Dev").submittedByUserId("hr-1").build())
                .build();
        if (status == DocumentRequestStatus.APPROVED || status == DocumentRequestStatus.FULFILLED) {
            item.setApprovedByUserId("admin");
            item.setApprovedAt(LocalDateTime.now());
        }
        return item;
    }

    private static AuthenticatedMyHrUser actor(String id, UserRole role, String organizationId) {
        return new AuthenticatedMyHrUser(id, id, id + "@example.com", role, organizationId, AccountStatus.ACTIVE, false);
    }

    private static User user(String id, UserRole role, String email) {
        return User.builder().id(id).loginId(id).email(email).role(role).organizationId("org-1").accountStatus(AccountStatus.ACTIVE).build();
    }

    // ---------------------------------------------------------------- centralized recipient resolution

    @Test void approvalEmailsGoToTheSameUsersWithoutUsingWorkEmail() {
        lenient().when(profiles.findByUserId("admin")).thenReturn(Optional.of(
                com.waypoint.entity.EmployeeProfile.builder().userId("admin").organizationId("org-1")
                        .personalEmail("admin-personal@example.com").workEmail("admin-work@example.com").build()));
        lenient().when(profiles.findByUserId("hr-1")).thenReturn(Optional.of(
                com.waypoint.entity.EmployeeProfile.builder().userId("hr-1").organizationId("org-1")
                        .personalEmail("hr-personal@example.com").workEmail("hr-work@example.com").build()));

        submit("hr-1", "request-1", details(), PDF);
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED),
                anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("admin@tardus.example"), eq("Offer Letter Approval Required – Jane Doe"), anyString());
        org.mockito.Mockito.clearInvocations(notifications, emailService);

        service.approve("admin", "request-1");
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr-1"), eq(NotificationType.OFFER_LETTER_APPROVED),
                anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("hr-personal@example.com"), eq("Offer Letter Approved – Jane Doe"), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("admin-work@example.com"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("hr-work@example.com"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("admin-personal@example.com"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("hr@tardus.example"), anyString(), anyString());
    }
}
