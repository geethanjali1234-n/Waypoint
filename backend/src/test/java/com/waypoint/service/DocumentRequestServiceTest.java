package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.dto.myhr.*;
import com.waypoint.entity.*;
import com.waypoint.enums.*;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.*;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentRequestServiceTest {
    @Mock DocumentRequestRepository requests;
    @Mock ReportingAssignmentRepository assignments;
    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    @Mock MyHrAuthenticatedUserService authenticated;
    @Mock ObjectProvider<DocumentService> documentProvider;
    @Mock DocumentService documents;
    @Mock AuditService audit;
    @Mock InAppNotificationService notifications;
    @Mock EmailService emailService;
    @Mock MyHrDocumentFulfillmentEmailService fulfillmentEmails;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    DocumentRequestService service;

    @BeforeEach void setUp() {
        service = new DocumentRequestService(requests, assignments, users, profiles, authenticated, documentProvider, audit,
                TestOrchestrators.create(notifications, emailService, new EmailTemplateService(), users, profiles), fulfillmentEmails, mongoTemplate);
        lenient().when(profiles.findByOrganizationId(anyString())).thenReturn(List.of());
        lenient().when(users.findByIdIn(any())).thenReturn(List.of());
        lenient().when(documentProvider.getIfAvailable()).thenReturn(documents);
        lenient().when(requests.save(any())).thenAnswer(invocation -> {
            DocumentRequest request = invocation.getArgument(0);
            if (request.getId() == null) request.setId("request-1");
            return request;
        });
    }

    private void actor(String id, UserRole role) {
        actor(id, role, "org-1");
    }
    private void actor(String id, UserRole role, String organizationId) {
        when(authenticated.require(eq(id), anySet(), eq(false))).thenReturn(
                new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(id, id, id + "@test", role,
                        organizationId, AccountStatus.ACTIVE, false));
    }
    private User user(String id, UserRole role) {
        return User.builder().id(id).loginId(id).organizationId("org-1").role(role)
                .accountStatus(AccountStatus.ACTIVE).build();
    }
    private ReportingAssignment assignment(String employee, String manager) {
        return ReportingAssignment.builder().organizationId("org-1").employeeUserId(employee)
                .reportingManagerUserId(manager).relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true).build();
    }
    private DocumentRequest pending(String from, String to) {
        return DocumentRequest.builder().id("request-1").organizationId("org-1").requestedByUserId(from)
                .requestedForUserId(to).requestTitle("Letter").requestDirection("TO_MANAGER")
                .status(DocumentRequestStatus.PENDING).build();
    }
    private DocumentRequestCreateRequest managerInput(String target) {
        return new DocumentRequestCreateRequest(target, "PRE_EMPLOYMENT_ONBOARDING", "NORMAL", null,
                "Please provide", List.of("Signed offer letter / employment agreement"));
    }
    private EmployeeDocumentRequestCreateRequest selfInput() {
        return new EmployeeDocumentRequestCreateRequest("EMPLOYMENT_LETTER", "Employment letter", "Needed", null);
    }

    @Test void companyAdminCreatesPendingRequestForActiveOrganizationUser() {
        actor("admin", UserRole.COMPANY_ADMIN);
        User admin = user("admin", UserRole.COMPANY_ADMIN);
        User employee = user("employee", UserRole.EMPLOYEE);
        when(users.findById("employee")).thenReturn(Optional.of(employee));
        when(users.findByIdIn(Set.of("admin", "employee"))).thenReturn(List.of(admin, employee));
        when(profiles.findByOrganizationId("org-1")).thenReturn(List.of(EmployeeProfile.builder()
                .userId("employee").organizationId("org-1").employeeId("EMP-001")
                .firstName("John").lastName("Doe").build()));
        DocumentRequestResponse response = service.createManagerRequest("admin", managerInput("employee"));
        assertThat(response.status()).isEqualTo(DocumentRequestStatus.PENDING);
        verify(audit).record(argThat(event -> {
            if (event.getActionType() != AuditActionType.DOCUMENT_REQUEST_CREATED
                    || !(event.getUpdatedValue() instanceof Map<?, ?> metadata)) return false;
            return "admin".equals(metadata.get("requestedBy"))
                    && "John Doe (EMP-001)".equals(metadata.get("requestedFor"));
        }));
    }

    @Test void companyAdminSeesAllRequestsInAuthenticatedOrganization() {
        actor("admin", UserRole.COMPANY_ADMIN);
        when(requests.findByOrganizationIdOrderByRequestedAtDesc("org-1"))
                .thenReturn(List.of(pending("employee-1", "hr-1"), pending("employee-2", "hr-2")));
        assertThat(service.list("admin")).hasSize(2);
        verify(requests).findByOrganizationIdOrderByRequestedAtDesc("org-1");
    }

    @Test void companyAdminWorklistUsesOnlyItsOrganizationAndPrioritizesLatestApprovalActivity() {
        actor("itap-admin", UserRole.COMPANY_ADMIN, "org-itap");
        DocumentRequest itapRequest = pending("itap-employee", "itap-hr");
        itapRequest.setOrganizationId("org-itap");
        when(mongoTemplate.count(any(Query.class), eq(DocumentRequest.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(DocumentRequest.class))).thenReturn(List.of(itapRequest));

        var page = service.list("itap-admin", 0, 10, "ALL", "ALL", "ALL", null, null);

        assertThat(page.items()).hasSize(1);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(DocumentRequest.class));
        assertThat(query.getValue().getQueryObject().getString("organizationId")).isEqualTo("org-itap");
        assertThat(query.getValue().getSortObject()).containsEntry("updatedAt", -1).containsEntry("requestedAt", -1);
    }

    @Test void listResolvesProfilesAndAttachmentNamesInOneBatchEachNotPerRequest() {
        actor("admin", UserRole.COMPANY_ADMIN);
        DocumentRequest first = pending("employee-1", "hr-1"); first.setId("r1"); first.setAttachmentDocumentIds(List.of("doc-1", "doc-2"));
        DocumentRequest second = pending("employee-2", "hr-1"); second.setId("r2"); second.setAttachmentDocumentIds(List.of("doc-3"));
        DocumentRequest third = pending("employee-1", "hr-1"); third.setId("r3");
        when(requests.findByOrganizationIdOrderByRequestedAtDesc("org-1")).thenReturn(List.of(first, second, third));
        when(users.findByIdIn(any())).thenReturn(List.of(user("employee-1", UserRole.EMPLOYEE), user("employee-2", UserRole.EMPLOYEE), user("hr-1", UserRole.HR)));
        when(profiles.findByUserIdIn(any())).thenReturn(List.of(
                EmployeeProfile.builder().userId("employee-1").organizationId("org-1").firstName("Jane").lastName("Doe").build(),
                EmployeeProfile.builder().userId("employee-2").organizationId("org-other").firstName("Other").lastName("Org").build()));
        when(documents.getMetadataByIds(any())).thenReturn(Map.of(
                "doc-1", StoredDocument.builder().id("doc-1").fileName("a.pdf").build(),
                "doc-3", StoredDocument.builder().id("doc-3").fileName("c.pdf").build()));

        List<DocumentRequestResponse> result = service.list("admin");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).attachmentFileNames()).containsExactly("a.pdf", null);
        assertThat(result.get(1).attachmentFileNames()).containsExactly("c.pdf");
        assertThat(result.get(0).requestedByName()).isEqualTo("Jane Doe");
        // A profile from another organization is never used.
        assertThat(result.get(1).requestedByName()).isEqualTo("employee-2");
        verify(profiles, times(1)).findByUserIdIn(any());
        verify(profiles, never()).findByOrganizationId(anyString());
        verify(documents, times(1)).getMetadataByIds(any());
        verify(documents, never()).getMetadata(anyString());
        verify(users, times(1)).findByIdIn(any());
    }

    private DocumentRequest offerLetter(DocumentRequestStatus status) {
        DocumentRequest item = pending("employee", "hr");
        item.setDocumentType(RequestedDocumentType.OFFER_LETTER);
        item.setStatus(status);
        return item;
    }
    private DocumentRequest approvedOfferLetter() {
        DocumentRequest item = offerLetter(DocumentRequestStatus.APPROVED);
        item.setOfferLetterApproval(OfferLetterApprovalDetails.builder().employeeName("Jane Doe").designation("Dev").submittedByUserId("hr").build());
        item.setApprovedByUserId("admin"); item.setApprovedAt(java.time.LocalDateTime.now());
        item.setCurrentDocumentId("approval-doc");
        return item;
    }

    @Test void anOfferLetterCanNeverBeFulfilledDirectlyBypassingAdminApproval() {
        actor("hr", UserRole.HR);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(offerLetter(DocumentRequestStatus.PENDING)));

        assertThatThrownBy(() -> service.fulfill("hr", "request-1", "offer.pdf", "application/pdf", new byte[]{'%', 'P', 'D', 'F', '-'}))
                .isInstanceOf(BusinessException.class).hasMessage(DocumentRequestService.OFFER_LETTER_NEEDS_APPROVAL);
        verify(documents, never()).store(any());
    }

    @Test void hrCannotDeliverAnOfferLetterThatIsNotApproved() {
        actor("hr", UserRole.HR);
        for (DocumentRequestStatus status : List.of(DocumentRequestStatus.PENDING, DocumentRequestStatus.SUBMITTED,
                DocumentRequestStatus.RETURNED_FOR_CORRECTION)) {
            DocumentRequest item = offerLetter(status);
            when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
            assertThatThrownBy(() -> service.deliverApprovedOfferLetter("hr", "request-1", null))
                    .as(status.name()).isInstanceOf(BusinessException.class).hasMessage(DocumentRequestService.OFFER_LETTER_NEEDS_APPROVAL);
            assertThat(item.getStatus()).isEqualTo(status);
            assertThat(item.getFulfilledDocumentId()).isNull();
        }
        verify(documents, never()).store(any());
        verify(notifications, never()).createInAppOnlyNotification(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void anApprovedOfferLetterIsDeliveredOnceAsACopyOfTheExactApprovedPdf() {
        actor("hr", UserRole.HR);
        DocumentRequest item = approvedOfferLetter();
        byte[] approvedBytes = "%PDF-1.7 approved".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        StoredDocument approvedMeta = StoredDocument.builder().id("approval-doc").organizationId("org-1").relatedEntityType("DOCUMENT_REQUEST")
                .relatedEntityId("request-1").documentType(DocumentType.REQUESTED_DOCUMENT).fileName("offer-letter-approval-v1.pdf").build();
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
        when(documents.getStoredContent("approval-doc")).thenReturn(new DocumentService.DocumentContent(approvedMeta, approvedBytes, "application/pdf", approvedBytes.length));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("delivered-doc").build());
        when(requests.transitionOfferLetterStatus(anyString(), anyString(), anyCollection(), anyString(), any())).thenReturn(1L);

        assertThat(service.deliverApprovedOfferLetter("hr", "request-1", null).emailDelivery().status()).isEqualTo("SKIPPED");
        verifyNoInteractions(fulfillmentEmails);

        assertThat(item.getStatus()).isEqualTo(DocumentRequestStatus.FULFILLED);
        assertThat(item.getFulfilledDocumentId()).isEqualTo("delivered-doc");
        assertThat(item.getFulfilledByUserId()).isEqualTo("hr");
        ArgumentCaptor<DocumentUploadRequest> stored = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documents, times(1)).store(stored.capture());
        assertThat(stored.getValue().content()).isEqualTo(approvedBytes);

        // A retry after the letter was delivered neither stores a second copy nor changes anything.
        service.deliverApprovedOfferLetter("hr", "request-1", null);
        verify(documents, times(1)).store(any());
    }

    @Test void aLostRaceWhileDeliveringRemovesTheCopyAndReportsAClearError() {
        actor("hr", UserRole.HR);
        DocumentRequest item = approvedOfferLetter();
        StoredDocument approvedMeta = StoredDocument.builder().id("approval-doc").organizationId("org-1").relatedEntityType("DOCUMENT_REQUEST")
                .relatedEntityId("request-1").documentType(DocumentType.REQUESTED_DOCUMENT).build();
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
        when(documents.getStoredContent("approval-doc")).thenReturn(new DocumentService.DocumentContent(approvedMeta, new byte[]{1}, "application/pdf", 1));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("delivered-doc").build());
        when(requests.transitionOfferLetterStatus(anyString(), anyString(), anyCollection(), anyString(), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.deliverApprovedOfferLetter("hr", "request-1", null)).isInstanceOf(BusinessException.class);
        verify(documents).delete("delivered-doc");
        assertThat(item.getStatus()).isEqualTo(DocumentRequestStatus.APPROVED);
    }

    @Test void anEmployeeAndAnUnassignedHrCannotDeliverAnOfferLetter() {
        actor("employee", UserRole.EMPLOYEE); actor("other-hr", UserRole.HR);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(approvedOfferLetter()));

        assertThatThrownBy(() -> service.deliverApprovedOfferLetter("employee", "request-1", null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.deliverApprovedOfferLetter("other-hr", "request-1", null)).isInstanceOf(ForbiddenException.class);
        verify(documents, never()).store(any());
    }

    @Test void hrCannotDeliverAnyDocumentDirectlyButACompanyAdminStillCan() {
        actor("hr", UserRole.HR); actor("admin", UserRole.COMPANY_ADMIN);
        DocumentRequest item = pending("employee", "hr");
        item.setDocumentType(RequestedDocumentType.EMPLOYMENT_VERIFICATION);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());

        assertThatThrownBy(() -> service.fulfill("hr", "request-1", "letter.pdf", "application/pdf", new byte[]{1}))
                .isInstanceOf(BusinessException.class).hasMessage(DocumentRequestService.OFFER_LETTER_NEEDS_APPROVAL);
        verify(documents, never()).store(any());

        service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1});
        assertThat(item.getStatus()).isEqualTo(DocumentRequestStatus.FULFILLED);
    }

    @Test void anEmployeeStillFulfillsADocumentHrRequestedFromThemDirectly() {
        actor("employee", UserRole.EMPLOYEE);
        DocumentRequest item = pending("hr", "employee");
        item.setRequestDirection("TO_EMPLOYEE");
        item.setDocumentType(RequestedDocumentType.OTHER);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());

        service.fulfill("employee", "request-1", "id-card.pdf", "application/pdf", new byte[]{1});

        assertThat(item.getStatus()).isEqualTo(DocumentRequestStatus.FULFILLED);
    }

    @Test void anApprovedDocumentIsDeliveredWithItsOriginalFileNameAndType() {
        actor("hr", UserRole.HR);
        DocumentRequest item = approvedOfferLetter();
        item.setDocumentType(RequestedDocumentType.EMPLOYMENT_VERIFICATION);
        item.getOfferLetterApproval().setFileName("verification.docx");
        item.getOfferLetterApproval().setContentType("application/msword");
        byte[] approvedBytes = {9, 8, 7};
        StoredDocument approvedMeta = StoredDocument.builder().id("approval-doc").organizationId("org-1").relatedEntityType("DOCUMENT_REQUEST")
                .relatedEntityId("request-1").documentType(DocumentType.REQUESTED_DOCUMENT).build();
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
        when(documents.getStoredContent("approval-doc")).thenReturn(new DocumentService.DocumentContent(approvedMeta, approvedBytes, "application/msword", 3));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("delivered-doc").build());
        when(requests.transitionOfferLetterStatus(anyString(), anyString(), anyCollection(), anyString(), any())).thenReturn(1L);

        when(fulfillmentEmails.send(any(), any(), eq("john@example.com")))
                .thenReturn(new DocumentEmailDeliveryResponse("DELIVERED", "john@example.com", "Email delivered to john@example.com."));

        DocumentFulfillmentResponse result = service.deliverApprovedOfferLetter("hr", "request-1", "john@example.com");

        // Like a direct fulfillment, the approved document is emailed to the employee, exactly as approved.
        assertThat(result.emailDelivery().status()).isEqualTo("DELIVERED");
        ArgumentCaptor<EmailAttachment> mail = ArgumentCaptor.forClass(EmailAttachment.class);
        verify(fulfillmentEmails).send(any(), mail.capture(), eq("john@example.com"));
        assertThat(mail.getValue().fileName()).isEqualTo("verification.docx");
        assertThat(mail.getValue().contentType()).isEqualTo("application/msword");
        assertThat(mail.getValue().content()).isEqualTo(approvedBytes);

        ArgumentCaptor<DocumentUploadRequest> stored = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documents).store(stored.capture());
        assertThat(stored.getValue().fileName()).isEqualTo("verification.docx");
        assertThat(stored.getValue().contentType()).isEqualTo("application/msword");
        assertThat(stored.getValue().content()).isEqualTo(approvedBytes);
        assertThat(item.getStatus()).isEqualTo(DocumentRequestStatus.FULFILLED);
    }

    @Test void anApprovedDocumentUsesTheHrReviewedEmailDraft() {
        actor("hr", UserRole.HR);
        DocumentRequest item = approvedOfferLetter();
        item.setDocumentType(RequestedDocumentType.EXPERIENCE_LETTER);
        byte[] approvedBytes = {9, 8, 7};
        StoredDocument approvedMeta = StoredDocument.builder().id("approval-doc").organizationId("org-1")
                .relatedEntityType("DOCUMENT_REQUEST").relatedEntityId("request-1").documentType(DocumentType.REQUESTED_DOCUMENT).build();
        DocumentFulfillmentEmailRequest email = new DocumentFulfillmentEmailRequest(
                "john@example.com", "Your Experience and Relieving Letter", "Hi John,\n\nPlease find your letter attached.");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));
        when(documents.getStoredContent("approval-doc")).thenReturn(new DocumentService.DocumentContent(approvedMeta, approvedBytes, "application/pdf", 3));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("delivered-doc").build());
        when(requests.transitionOfferLetterStatus(anyString(), anyString(), anyCollection(), anyString(), any())).thenReturn(1L);
        when(fulfillmentEmails.sendWithMessage(any(), any(), same(email)))
                .thenReturn(new DocumentEmailDeliveryResponse("DELIVERED", "john@example.com", "Email delivered to john@example.com."));

        service.deliverApprovedDocument("hr", "request-1", email);

        verify(fulfillmentEmails).sendWithMessage(same(item), argThat(attachment ->
                attachment.fileName().equals("document")
                        && Arrays.equals(attachment.content(), approvedBytes)), same(email));
        verify(fulfillmentEmails, never()).send(any(), any(), any());
    }

    @Test void aDocumentThatNeedsNoApprovalCannotBeDeliveredThroughTheApprovalEndpoint() {
        actor("hr", UserRole.HR);
        DocumentRequest item = approvedOfferLetter();
        item.setDocumentType(RequestedDocumentType.I983);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.deliverApprovedOfferLetter("hr", "request-1", null)).isInstanceOf(BusinessException.class);
        verify(documents, never()).store(any());
    }

    @Test void managerRequestRejectsUnsupportedDocumentCatalogEntry() {
        actor("admin", UserRole.COMPANY_ADMIN); when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        var input = new DocumentRequestCreateRequest("employee", "PRE_EMPLOYMENT_ONBOARDING", "NORMAL", null,
                "Please provide", List.of("Paystub Copy"));
        assertThatThrownBy(() -> service.createManagerRequest("admin", input)).isInstanceOf(BusinessException.class);
        verify(requests, never()).save(any());
    }

    @Test void hrMayRequestOnlyFromDirectReport() {
        actor("hr", UserRole.HR); when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        when(assignments.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                "org-1", "hr", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of());
        assertThatThrownBy(() -> service.createManagerRequest("hr", managerInput("employee"))).isInstanceOf(ForbiddenException.class);
    }

    @Test void hrSelfServiceRoutesToPrimaryManagerInsteadOfSelf() {
        actor("hr", UserRole.HR); when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "hr", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment("hr", "admin")));
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", UserRole.COMPANY_ADMIN)));
        DocumentRequestResponse result = service.createSelfServiceRequest("hr", selfInput(), List.of());
        assertThat(result.requestedForUserId()).isEqualTo("admin");
    }

    @Test void employeeSelfServiceRequiresActiveAuthoritativeManager() {
        actor("employee", UserRole.EMPLOYEE); when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createSelfServiceRequest("employee", selfInput(), List.of())).isInstanceOf(BusinessException.class);
    }

    @Test void employeeListIsLimitedToRequestsInvolvingThem() {
        actor("employee", UserRole.EMPLOYEE);
        when(requests.findByOrganizationIdOrderByRequestedAtDesc("org-1")).thenReturn(List.of(pending("employee", "hr"), pending("other", "hr")));
        assertThat(service.list("employee")).hasSize(1);
    }

    @Test void crossOrganizationRequestUsesNotFoundSemantics() {
        actor("admin", UserRole.COMPANY_ADMIN); when(requests.findByIdAndOrganizationId("foreign", "org-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("admin", "foreign")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void companyAdminCanGetAnyRequestInOwnOrganization() {
        actor("admin", UserRole.COMPANY_ADMIN);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(pending("employee", "hr")));
        assertThat(service.get("admin", "request-1").id()).isEqualTo("request-1");
    }

    @Test void hrCanGetRequestsCreatedByOrAssignedToSelf() {
        actor("hr", UserRole.HR);
        when(requests.findByIdAndOrganizationId(eq("request-1"), eq("org-1")))
                .thenReturn(Optional.of(pending("hr", "admin")))
                .thenReturn(Optional.of(pending("employee", "hr")));
        assertThat(service.get("hr", "request-1").requestedByUserId()).isEqualTo("hr");
        assertThat(service.get("hr", "request-1").requestedForUserId()).isEqualTo("hr");
    }

    @Test void hrCanGetDirectReportRequestButNotUnrelatedRequest() {
        actor("hr", UserRole.HR);
        when(assignments.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                "org-1", "hr", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("employee", "hr")));
        when(requests.findByIdAndOrganizationId("direct", "org-1")).thenReturn(Optional.of(pending("employee", "admin")));
        when(requests.findByIdAndOrganizationId("unrelated", "org-1")).thenReturn(Optional.of(pending("other", "admin")));
        assertThat(service.get("hr", "direct").requestedByUserId()).isEqualTo("employee");
        assertThatThrownBy(() -> service.get("hr", "unrelated")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void employeeCanGetOwnOrAssignedRequestsButNotUnrelatedRequest() {
        actor("employee", UserRole.EMPLOYEE);
        when(requests.findByIdAndOrganizationId(eq("request-1"), eq("org-1")))
                .thenReturn(Optional.of(pending("employee", "hr")))
                .thenReturn(Optional.of(pending("admin", "employee")))
                .thenReturn(Optional.of(pending("other", "hr")));
        assertThat(service.get("employee", "request-1").requestedByUserId()).isEqualTo("employee");
        assertThat(service.get("employee", "request-1").requestedForUserId()).isEqualTo("employee");
        assertThatThrownBy(() -> service.get("employee", "request-1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void onlyAssignedRecipientCanFulfill() {
        actor("other", UserRole.EMPLOYEE); when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(pending("employee", "hr")));
        assertThatThrownBy(() -> service.fulfill("other", "request-1", "letter.pdf", "application/pdf", new byte[]{1}))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(documents);
    }

    @Test void successfulFulfillmentStoresCanonicalDocumentAndAudits() {
        actor("admin", UserRole.COMPANY_ADMIN); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());
        DocumentFulfillmentResponse result = service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1});
        assertThat(result.request().status()).isEqualTo(DocumentRequestStatus.FULFILLED);
        ArgumentCaptor<DocumentUploadRequest> upload = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documents).store(upload.capture());
        assertThat(upload.getValue().relatedEntityId()).isEqualTo("request-1");
        assertThat(upload.getValue().documentType()).isEqualTo(DocumentType.REQUESTED_DOCUMENT);
        verify(audit).record(argThat(event -> event.getActionType() == AuditActionType.DOCUMENT_REQUEST_FULFILLED));
    }

    @Test void companyAdminCanFulfillRequestAssignedToHr() {
        actor("admin", UserRole.COMPANY_ADMIN); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());
        DocumentFulfillmentResponse result = service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1});
        assertThat(result.request().status()).isEqualTo(DocumentRequestStatus.FULFILLED);
        assertThat(request.getFulfilledByUserId()).isEqualTo("admin");
    }

    @Test void employeeOriginatedFulfillmentEmailsTheCompletedDocument() {
        // A Company Admin delivers directly; HR's documents go through Admin approval first (see deliverApprovedOfferLetter).
        actor("admin", UserRole.COMPANY_ADMIN); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").fileName("employment-letter.pdf").build());

        service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1, 2});

        verify(fulfillmentEmails).sendWithMessage(same(request), argThat(attachment -> attachment.fileName().equals("employment-letter.pdf")
                && attachment.contentType().equals("application/pdf") && Arrays.equals(attachment.content(), new byte[]{1, 2})), isNull());
    }

    @Test void managerOriginatedFulfillmentDoesNotEmailTheHrRequester() {
        actor("employee", UserRole.EMPLOYEE); DocumentRequest request = pending("hr", "employee");
        request.setRequestDirection("TO_EMPLOYEE");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").fileName("document.pdf").build());

        service.fulfill("employee", "request-1", "document.pdf", "application/pdf", new byte[]{1});

        verifyNoInteractions(fulfillmentEmails);
    }

    @Test void fulfillmentPersistenceFailureDeletesStoredDocument() {
        actor("admin", UserRole.COMPANY_ADMIN); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());
        when(requests.save(request)).thenThrow(new RuntimeException("save failed"));
        assertThatThrownBy(() -> service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1})).hasMessage("save failed");
        verify(documents).delete("doc-1");
    }

    @Test void assignedManagerCanRejectWithTrimmedReason() {
        actor("hr", UserRole.HR); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        DocumentRequestResponse result = service.reject("hr", "request-1", "  unavailable  ");
        assertThat(result.status()).isEqualTo(DocumentRequestStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("unavailable");
        verify(audit).record(argThat(event -> event.getActionType() == AuditActionType.DOCUMENT_REQUEST_REJECTED));
    }

    @Test void unauthorizedManagerCannotRejectRequestAssignedToSomeoneElse() {
        actor("other-hr", UserRole.HR);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(pending("employee", "hr")));
        assertThatThrownBy(() -> service.reject("other-hr", "request-1", "Unavailable")).isInstanceOf(ForbiddenException.class);
    }

    @Test void rejectionRequiresReasonAndHonorsMaximum() {
        actor("hr", UserRole.HR); when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(pending("employee", "hr")));
        assertThatThrownBy(() -> service.reject("hr", "request-1", " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reject("hr", "request-1", "x".repeat(1001))).isInstanceOf(BusinessException.class);
    }

    @Test void fulfilledOrRejectedRequestsCannotTransitionAgain() {
        actor("hr", UserRole.HR); DocumentRequest request = pending("employee", "hr"); request.setStatus(DocumentRequestStatus.REJECTED);
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.fulfill("hr", "request-1", "x.pdf", "application/pdf", new byte[]{1})).isInstanceOf(BusinessException.class);
        request.setStatus(DocumentRequestStatus.FULFILLED);
        assertThatThrownBy(() -> service.reject("hr", "request-1", "Unavailable")).isInstanceOf(BusinessException.class);
    }

    @Test void successfulAttachmentsAreLinkedToRequestMetadata() {
        actor("employee", UserRole.EMPLOYEE); when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment("employee", "hr")));
        when(users.findById("hr")).thenReturn(Optional.of(user("hr", UserRole.HR)));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());
        var file = new MockMultipartFile("files", "support.pdf", "application/pdf", new byte[]{1});
        DocumentRequestResponse result = service.createSelfServiceRequest("employee", selfInput(), List.of(file));
        assertThat(result.attachmentDocumentIds()).containsExactly("doc-1");
        verify(requests, times(2)).save(any(DocumentRequest.class));
    }

    @Test void attachmentFailureCompensatesStoredDocumentsAndRequest() {
        actor("employee", UserRole.EMPLOYEE); when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment("employee", "hr")));
        when(users.findById("hr")).thenReturn(Optional.of(user("hr", UserRole.HR)));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build()).thenThrow(new RuntimeException("storage failed"));
        var one = new MockMultipartFile("files", "one.pdf", "application/pdf", new byte[]{1});
        var two = new MockMultipartFile("files", "two.pdf", "application/pdf", new byte[]{2});
        assertThatThrownBy(() -> service.createSelfServiceRequest("employee", selfInput(), List.of(one, two)))
                .hasMessage("Supporting attachment could not be stored. Please try again or choose a different file.");
        verify(documents).delete("doc-1"); verify(requests).deleteById("request-1");
    }

    @Test void downloadRejectsArbitraryOrMismatchedDocument() {
        actor("employee", UserRole.EMPLOYEE); DocumentRequest request = pending("employee", "hr"); request.setAttachmentDocumentIds(List.of("doc-1"));
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.getDocumentContent("employee", "request-1", "other")).isInstanceOf(ResourceNotFoundException.class);
        StoredDocument metadata = StoredDocument.builder().id("doc-1").organizationId("other-org")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST").documentType(DocumentType.REQUESTED_DOCUMENT).build();
        when(documents.getContent("doc-1")).thenReturn(new DocumentService.DocumentContent(metadata, new byte[]{1}, "application/pdf", 1));
        assertThatThrownBy(() -> service.getDocumentContent("employee", "request-1", "doc-1")).isInstanceOf(ResourceNotFoundException.class);
        metadata.setOrganizationId("org-1"); metadata.setRelatedEntityId("another-request");
        assertThatThrownBy(() -> service.getDocumentContent("employee", "request-1", "doc-1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void hrManagerRequestNotifiesTheTargetEmployee() {
        actor("hr", UserRole.HR);
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        when(assignments.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                "org-1", "hr", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("employee", "hr")));

        service.createManagerRequest("hr", managerInput("employee"));

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"),
                eq(NotificationType.DOCUMENT_REQUEST_CREATED), eq("New document request"),
                contains("Signed offer letter / employment agreement"), eq("request-1"), anyString());
    }

    @Test void companyAdminRequestNotifiesTheTargetEmployee() {
        actor("admin", UserRole.COMPANY_ADMIN);
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));

        service.createManagerRequest("admin", managerInput("employee"));

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"),
                eq(NotificationType.DOCUMENT_REQUEST_CREATED), eq("New document request"),
                anyString(), eq("request-1"), contains("/document-requests/request-1"));
    }

    @Test void employeeSelfServiceRequestNotifiesTheCurrentReportingManagerOnly() {
        actor("employee", UserRole.EMPLOYEE);
        when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(assignment("employee", "hr")));
        when(users.findById("hr")).thenReturn(Optional.of(user("hr", UserRole.HR)));

        service.createSelfServiceRequest("employee", selfInput(), List.of());

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr"),
                eq(NotificationType.DOCUMENT_REQUEST_CREATED), eq("New employee document request"),
                contains("Employment letter"), eq("request-1"), anyString());
        // Only the actual assigned recipient is notified - never a broadcast to other HR users.
        verify(notifications, times(1)).createInAppOnlyNotification(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void hrOriginatedRequestFulfilledByEmployeeNotifiesTheRequestingHr() {
        actor("employee", UserRole.EMPLOYEE);
        DocumentRequest request = pending("hr", "employee");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(users.findById("hr")).thenReturn(Optional.of(user("hr", UserRole.HR)));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());

        service.fulfill("employee", "request-1", "letter.pdf", "application/pdf", new byte[]{1});

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr"),
                eq(NotificationType.DOCUMENT_REQUEST_FULFILLED), eq("Document request fulfilled"),
                contains("Letter"), eq("request-1"), anyString());
    }

    @Test void employeeOriginatedRequestFulfilledByAdminOnBehalfOfHrNotifiesTheEmployeeNotTheActor() {
        // Company Admin can fulfill on behalf of the assigned HR recipient; the
        // notification must still go to the original requester (the employee), never
        // to the acting Company Admin.
        actor("admin", UserRole.COMPANY_ADMIN);
        DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());

        service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1});

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"),
                eq(NotificationType.DOCUMENT_REQUEST_FULFILLED), any(), contains("admin"), eq("request-1"), anyString());
        verify(notifications, never()).createInAppOnlyNotification(any(), eq("admin"), any(), any(), any(), any(), any());
    }

    @Test void fulfillmentFailureNeverSendsAFulfilledNotification() {
        actor("admin", UserRole.COMPANY_ADMIN); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());
        when(requests.save(request)).thenThrow(new RuntimeException("save failed"));

        assertThatThrownBy(() -> service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1}));

        verifyNoInteractions(notifications);
    }

    @Test void rejectionNotifiesTheOriginalRequesterWithTheRequiredReason() {
        actor("hr", UserRole.HR); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));

        service.reject("hr", "request-1", "Document is illegible");

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"),
                eq(NotificationType.DOCUMENT_REQUEST_REJECTED), eq("Document request rejected"),
                contains("Reason: Document is illegible"), eq("request-1"), anyString());
    }

    @Test void aNotificationFailureNeverBreaksAnOtherwiseSuccessfulRequestCreation() {
        // Regression test: notification creation is secondary to the business
        // action - a transient failure there (validation, a Mongo hiccup) must
        // never surface as a failure of the document request itself.
        actor("hr", UserRole.HR);
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        when(assignments.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                "org-1", "hr", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("employee", "hr")));
        doThrow(new IllegalArgumentException("boom")).when(notifications)
                .createInAppOnlyNotification(any(), any(), any(), any(), any(), any(), any());

        DocumentRequestResponse response = service.createManagerRequest("hr", managerInput("employee"));

        assertThat(response.status()).isEqualTo(DocumentRequestStatus.PENDING);
    }


    private byte[] samplePdf() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        var pdf = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new com.lowagie.text.Paragraph("Full offer terms and HR signature"));
        pdf.close();
        return out.toByteArray();
    }

    private byte[] samplePdfWithSignatureAnchor() throws Exception {
        byte[] original = samplePdf();
        var out = new java.io.ByteArrayOutputStream();
        var reader = new com.lowagie.text.pdf.PdfReader(original);
        try {
            var stamper = new com.lowagie.text.pdf.PdfStamper(reader, out);
            var field = new com.lowagie.text.pdf.TextField(stamper.getWriter(),
                    new com.lowagie.text.Rectangle(72, 96, 252, 138), "waypoint_employee_signature");
            stamper.addAnnotation(field.getTextField(), 1);
            stamper.close();
        } finally { reader.close(); }
        return out.toByteArray();
    }

    private byte[] sampleSignature() throws Exception {
        var image = new java.awt.image.BufferedImage(80, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private DocumentRequest sentOffer() {
        var request = pending("employee", "hr");
        request.setDocumentType(RequestedDocumentType.OFFER_LETTER);
        request.setStatus(DocumentRequestStatus.FULFILLED);
        request.setFulfilledDocumentId("original");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        return request;
    }

    @Test void signedOfferStampsTheEmployeeSignatureInTheOriginalAcceptanceArea() throws Exception {
        actor("employee", UserRole.EMPLOYEE);
        sentOffer();
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        when(users.findById("hr")).thenReturn(Optional.of(userWithEmail("hr", UserRole.HR, "hr-login@example.com")));
        var metadata = StoredDocument.builder().id("original").organizationId("org-1")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST")
                .documentType(DocumentType.REQUESTED_DOCUMENT).fileName("offer.pdf").contentType("application/pdf").build();
        when(documents.getContent("original")).thenReturn(new DocumentService.DocumentContent(metadata, samplePdfWithSignatureAnchor(), "application/pdf", 0));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("signed").build());
        when(requests.recordSignedResponse(eq("request-1"), eq("org-1"), eq("original"), eq("signed"), any())).thenReturn(1L);
        var response = service.submitSignedResponse("employee", "request-1", "sig.png", "image/png", sampleSignature(), null, null, null);
        assertThat(response.signedResponseDocumentId()).isEqualTo("signed");
        assertThat(response.employeeSignatureDocumentId()).isNull();
        var upload = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documents).store(upload.capture());
        var reader = new com.lowagie.text.pdf.PdfReader(upload.getValue().content());
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            assertThat(extractor.getTextFromPage(1)).contains("Full offer terms and HR signature");
            assertThat(reader.getAcroFields().getFieldItem("waypoint_employee_signature")).isNull();
            assertThat(reader.getPageN(1).getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES)
                    .getAsDict(com.lowagie.text.pdf.PdfName.XOBJECT).getKeys()).isNotEmpty();
        } finally { reader.close(); }
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr"), any(), eq("Signed document returned"), anyString(), eq("request-1"), eq("/myhr/hr/document-requests/request-1"));
        verify(emailService).sendHtmlEmail(eq("hr-login@example.com"), eq("Signed document returned"), anyString());
        var signedMetadata = StoredDocument.builder().id("signed").organizationId("org-1")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST")
                .documentType(DocumentType.REQUESTED_DOCUMENT).fileName("signed-offer.pdf").contentType("application/pdf").build();
        when(documents.getContent("signed")).thenReturn(new DocumentService.DocumentContent(signedMetadata, upload.getValue().content(), "application/pdf", 0));
        assertThat(service.getDocumentContent("employee", "request-1", "signed").content()).isEqualTo(upload.getValue().content());
        actor("hr", UserRole.HR);
        assertThat(service.getDocumentContent("hr", "request-1", "signed").content()).isEqualTo(upload.getValue().content());
        assertThatThrownBy(() -> service.submitSignedResponse("employee", "request-1", "sig.png", "image/png", sampleSignature(), null, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("already");
    }

    @Test void hrRequesterCanSignTheirOwnOfferLetter() throws Exception {
        actor("hr", UserRole.HR);
        var request = sentOffer();
        request.setRequestedByUserId("hr");
        request.setRequestedForUserId("admin");
        when(users.findById("hr")).thenReturn(Optional.of(user("hr", UserRole.HR)));
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", UserRole.COMPANY_ADMIN)));
        var metadata = StoredDocument.builder().id("original").organizationId("org-1")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST")
                .documentType(DocumentType.REQUESTED_DOCUMENT).fileName("offer.pdf").contentType("application/pdf").build();
        when(documents.getContent("original")).thenReturn(new DocumentService.DocumentContent(metadata, samplePdfWithSignatureAnchor(), "application/pdf", 0));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("signed").build());
        when(requests.recordSignedResponse(eq("request-1"), eq("org-1"), eq("original"), eq("signed"), any())).thenReturn(1L);

        var response = service.submitSignedResponse("hr", "request-1", "sig.png", "image/png", sampleSignature(), null, null, null);

        assertThat(response.signedResponseDocumentId()).isEqualTo("signed");
        verify(requests).recordSignedResponse(eq("request-1"), eq("org-1"), eq("original"), eq("signed"), any());
    }

    @Test void companyAdminRequesterCanSignTheirOwnOfferLetter() throws Exception {
        actor("admin", UserRole.COMPANY_ADMIN);
        var request = sentOffer();
        request.setRequestedByUserId("admin");
        request.setRequestedForUserId("hr");
        when(users.findById("admin")).thenReturn(Optional.of(user("admin", UserRole.COMPANY_ADMIN)));
        when(users.findById("hr")).thenReturn(Optional.of(user("hr", UserRole.HR)));
        var metadata = StoredDocument.builder().id("original").organizationId("org-1")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST")
                .documentType(DocumentType.REQUESTED_DOCUMENT).fileName("offer.pdf").contentType("application/pdf").build();
        when(documents.getContent("original")).thenReturn(new DocumentService.DocumentContent(metadata, samplePdfWithSignatureAnchor(), "application/pdf", 0));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("signed").build());
        when(requests.recordSignedResponse(eq("request-1"), eq("org-1"), eq("original"), eq("signed"), any())).thenReturn(1L);

        var response = service.submitSignedResponse("admin", "request-1", "sig.png", "image/png", sampleSignature(), null, null, null);

        assertThat(response.signedResponseDocumentId()).isEqualTo("signed");
        verify(requests).recordSignedResponse(eq("request-1"), eq("org-1"), eq("original"), eq("signed"), any());
    }

    @Test void anotherHrUserCannotSignSomeoneElsesOfferLetter() {
        actor("other-hr", UserRole.HR);
        sentOffer();

        assertThatThrownBy(() -> service.submitSignedResponse("other-hr", "request-1", "sig.png", "image/png", sampleSignature(), null, null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(documents, never()).store(any());
    }

    @Test void offerCannotBeReplacedByAnUploadedSignatureOrUnrelatedDocument() {
        actor("employee", UserRole.EMPLOYEE); sentOffer();
        assertThatThrownBy(() -> service.submitSignedResponse("employee", "request-1", null, null, null, "other.pdf", "application/pdf", new byte[]{1}))
                .isInstanceOf(BusinessException.class).hasMessageContaining("original offer");
        verify(documents, never()).store(any());
    }

    @Test void concurrentSignedReturnDeletesOnlyTheLosingUpload() {
        actor("employee", UserRole.EMPLOYEE);
        var request = sentOffer(); request.setDocumentType(RequestedDocumentType.OTHER);
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("losing-upload").build());
        assertThatThrownBy(() -> service.submitSignedResponse("employee", "request-1", null, null, null, "signed.pdf", "application/pdf", new byte[]{1}))
                .isInstanceOf(BusinessException.class).hasMessageContaining("already");
        verify(documents).delete("losing-upload");
        verifyNoInteractions(notifications);
    }

    @Test void anotherEmployeeCannotSignTheOffer() {
        actor("other", UserRole.EMPLOYEE); sentOffer();
        assertThatThrownBy(() -> service.submitSignedResponse("other", "request-1", "sig.png", "image/png", new byte[]{1}, null, null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(documents, never()).store(any());
    }

    @Test void legacyDetachedSignatureOpensAsACompleteOfferLetter() throws Exception {
        actor("employee", UserRole.EMPLOYEE);
        var request = sentOffer(); request.setEmployeeSignatureDocumentId("legacy-signature");
        when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        var original = StoredDocument.builder().id("original").organizationId("org-1")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST")
                .documentType(DocumentType.REQUESTED_DOCUMENT).fileName("offer.pdf").contentType("application/pdf").build();
        var signature = StoredDocument.builder().id("legacy-signature").organizationId("org-1")
                .relatedEntityId("request-1").relatedEntityType("DOCUMENT_REQUEST")
                .documentType(DocumentType.REQUESTED_DOCUMENT).fileName("signature.png").contentType("image/png").build();
        when(documents.getContent("original")).thenReturn(new DocumentService.DocumentContent(original, samplePdf(), "application/pdf", 0));
        when(documents.getContent("legacy-signature")).thenReturn(new DocumentService.DocumentContent(signature, sampleSignature(), "image/png", 0));
        assertThat(service.get("employee", "request-1").signedResponseDocumentId()).isEqualTo("legacy-signature");
        var content = service.getDocumentContent("employee", "request-1", "legacy-signature");
        assertThat(content.contentType()).isEqualTo("application/pdf");
        var reader = new com.lowagie.text.pdf.PdfReader(content.content());
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(2);
            assertThat(new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1)).contains("Full offer terms");
        } finally { reader.close(); }
    }

    // ---- email notifications (recipient: personalEmail, then account email; dedicated document emails are not duplicated) ----

    private User userWithEmail(String id, UserRole role, String accountEmail) {
        return User.builder().id(id).loginId(id).organizationId("org-1").role(role).email(accountEmail)
                .accountStatus(AccountStatus.ACTIVE).build();
    }

    private void personalEmail(String userId, String email) {
        lenient().when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                EmployeeProfile.builder().userId(userId).organizationId("org-1").personalEmail(email).workEmail("work@example.com").build()));
    }

    private void stubManagerRequestTarget(User target) {
        actor("hr", UserRole.HR);
        when(users.findById("employee")).thenReturn(Optional.of(target));
        when(assignments.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                "org-1", "hr", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("employee", "hr")));
    }

    @Test void createdRequestEmailsTheEmployeeAtPersonalEmailNeverTheWorkEmail() {
        stubManagerRequestTarget(userWithEmail("employee", UserRole.EMPLOYEE, "login@example.com"));
        personalEmail("employee", "personal@example.com");

        service.createManagerRequest("hr", managerInput("employee"));

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_CREATED),
                eq("New document request"), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("personal@example.com"), eq("New document request"), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("work@example.com"), anyString(), anyString());
    }

    @Test void createdRequestFallsBackToTheAccountEmail() {
        stubManagerRequestTarget(userWithEmail("employee", UserRole.EMPLOYEE, "login@example.com"));

        service.createManagerRequest("hr", managerInput("employee"));

        verify(emailService).sendHtmlEmail(eq("login@example.com"), eq("New document request"), anyString());
    }

    @Test void createdRequestWithNoEmailAddressKeepsTheInAppNotificationOnly() {
        stubManagerRequestTarget(userWithEmail("employee", UserRole.EMPLOYEE, null));

        service.createManagerRequest("hr", managerInput("employee"));

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_CREATED),
                anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test void emailFailureNeverFailsTheRequestCreation() {
        stubManagerRequestTarget(userWithEmail("employee", UserRole.EMPLOYEE, "login@example.com"));
        doThrow(new IllegalStateException("smtp down")).when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        DocumentRequestResponse response = service.createManagerRequest("hr", managerInput("employee"));

        assertThat(response.status()).isEqualTo(DocumentRequestStatus.PENDING);
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_CREATED),
                anyString(), anyString(), eq("request-1"), anyString());
    }

    @Test void employeeSelfServiceRequestEmailsTheAssignedRecipient() {
        actor("employee", UserRole.EMPLOYEE);
        when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(assignment("employee", "hr")));
        when(users.findById("hr")).thenReturn(Optional.of(userWithEmail("hr", UserRole.HR, "hr-login@example.com")));
        personalEmail("hr", "hr-personal@example.com");

        service.createSelfServiceRequest("employee", selfInput(), List.of());

        verify(emailService).sendHtmlEmail(eq("hr-personal@example.com"), eq("New employee document request"), anyString());
    }

    @Test void rejectionEmailsTheRequesterWithTheReason() {
        actor("hr", UserRole.HR); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(users.findById("employee")).thenReturn(Optional.of(userWithEmail("employee", UserRole.EMPLOYEE, "login@example.com")));

        service.reject("hr", "request-1", "Document is illegible");

        verify(emailService).sendHtmlEmail(eq("login@example.com"), eq("Document request rejected"), anyString());
    }

    @Test void fulfillmentOfAManagerRequestEmailsTheRequestingHr() {
        actor("employee", UserRole.EMPLOYEE);
        DocumentRequest request = pending("hr", "employee");
        request.setRequestDirection("TO_EMPLOYEE");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(users.findById("hr")).thenReturn(Optional.of(userWithEmail("hr", UserRole.HR, "hr-login@example.com")));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").build());

        service.fulfill("employee", "request-1", "letter.pdf", "application/pdf", new byte[]{1});

        verify(emailService).sendHtmlEmail(eq("hr-login@example.com"), eq("Document request fulfilled"), anyString());
        verifyNoInteractions(fulfillmentEmails);
    }

    @Test void fulfillmentWithTheDedicatedDocumentEmailDoesNotSendASecondNotificationEmail() {
        actor("admin", UserRole.COMPANY_ADMIN); DocumentRequest request = pending("employee", "hr");
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(users.findById("employee")).thenReturn(Optional.of(userWithEmail("employee", UserRole.EMPLOYEE, "login@example.com")));
        when(documents.store(any())).thenReturn(StoredDocument.builder().id("doc-1").fileName("employment-letter.pdf").build());

        service.fulfill("admin", "request-1", "letter.pdf", "application/pdf", new byte[]{1, 2});

        verify(fulfillmentEmails).sendWithMessage(same(request), any(), isNull());
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_FULFILLED),
                anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }
}
