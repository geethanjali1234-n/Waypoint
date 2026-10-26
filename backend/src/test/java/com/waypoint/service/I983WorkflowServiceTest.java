package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class I983WorkflowServiceTest {
    @Mock DocumentRequestRepository requests;
    @Mock ReportingAssignmentRepository assignments;
    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    @Mock MyHrAuthenticatedUserService authenticated;
    @Mock ObjectProvider<DocumentService> documentProvider;
    @Mock DocumentService documents;
    @Mock DocumentRequestService documentRequests;
    @Mock AuthorizedSignatureService authorizedSignatures;
    @Mock AuditService audit;
    @Mock InAppNotificationService notifications;
    @Mock EmailService emailService;
    @Mock FileStorageService fileStorage;
    @Mock MyHrDocumentFulfillmentEmailService fulfillmentEmails;
    I983WorkflowService service;

    final Map<String, byte[]> storedContent = new HashMap<>();
    final Map<String, StoredDocument> storedMetadata = new HashMap<>();
    final AtomicInteger documentSequence = new AtomicInteger();

    @BeforeEach void setUp() {
        service = new I983WorkflowService(requests, assignments, users, profiles, authenticated, documentProvider,
                documentRequests, authorizedSignatures, audit,
                TestOrchestrators.create(notifications, emailService, new EmailTemplateService(), users, profiles), fileStorage, fulfillmentEmails);
        lenient().when(profiles.findByOrganizationId(anyString())).thenReturn(List.of());
        lenient().when(profiles.findByUserId(anyString())).thenReturn(Optional.empty());
        lenient().when(users.findByIdIn(any())).thenReturn(List.of());
        lenient().when(documentProvider.getIfAvailable()).thenReturn(documents);
        lenient().when(requests.save(any())).thenAnswer(invocation -> {
            DocumentRequest request = invocation.getArgument(0);
            if (request.getId() == null) request.setId("request-1");
            return request;
        });
        lenient().when(documents.store(any())).thenAnswer(invocation -> {
            DocumentUploadRequest upload = invocation.getArgument(0);
            String id = "doc-" + documentSequence.incrementAndGet();
            StoredDocument metadata = StoredDocument.builder().id(id).organizationId(upload.organizationId())
                    .fileName(upload.fileName()).version(upload.version()).contentType(upload.contentType())
                    .relatedEntityId(upload.relatedEntityId()).build();
            storedContent.put(id, upload.content());
            storedMetadata.put(id, metadata);
            return metadata;
        });
        lenient().when(documents.getMetadata(anyString())).thenAnswer(invocation -> storedMetadata.get(invocation.<String>getArgument(0)));
        lenient().when(documents.getContent(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            byte[] content = storedContent.get(id);
            return new DocumentService.DocumentContent(storedMetadata.get(id), content, "application/pdf", content.length);
        });
    }

    private void actor(String id, UserRole role) {
        lenient().when(authenticated.require(eq(id), anySet(), eq(false))).thenReturn(
                new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(id, id, id + "@test", role,
                        "org-1", AccountStatus.ACTIVE, false));
    }

    private User user(String id, UserRole role) {
        return User.builder().id(id).loginId(id).organizationId("org-1").role(role).accountStatus(AccountStatus.ACTIVE).build();
    }

    private void employeeReportsTo(String employee, String manager, UserRole managerRole) {
        when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(employee, RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("org-1").employeeUserId(employee)
                        .reportingManagerUserId(manager).relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER).isActive(true).build()));
        lenient().when(users.findById(manager)).thenReturn(Optional.of(user(manager, managerRole)));
    }

    private static byte[] pdf(Rectangle pageSize, int rotation) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(rotation == 0 ? pageSize : pageSize.rotate());
        PdfWriter.getInstance(document, output);
        document.open();
        document.add(new Paragraph("Form I-983 Training Plan"));
        document.close();
        return output.toByteArray();
    }

    private static byte[] signaturePng() throws Exception {
        BufferedImage image = new BufferedImage(200, 60, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.BLUE);
        graphics.drawLine(5, 50, 195, 10);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private MockMultipartFile pdfFile(String name) throws Exception {
        return new MockMultipartFile("file", name, "application/pdf", pdf(PageSize.LETTER, 0));
    }

    private EmployeeDocumentRequestCreateRequest input() {
        return new EmployeeDocumentRequestCreateRequest("I983", "Signed I-983", "STEM OPT extension", LocalDate.now().plusDays(7));
    }

    private AuthorizedSignatureService.ActiveSignature activeSignature() throws Exception {
        return new AuthorizedSignatureService.ActiveSignature("Jane Admin", "President", LocalDate.now().minusDays(1),
                signaturePng(), "image/png", AuthorizedSignatureService.SIGNATURE_SOURCE);
    }

    /** Submits as employee -> HR and makes the saved request what the repository returns afterwards. */
    private DocumentRequest submitted() throws Exception {
        actor("employee", UserRole.EMPLOYEE);
        actor("hr", UserRole.HR);
        actor("admin", UserRole.COMPANY_ADMIN);
        employeeReportsTo("employee", "hr", UserRole.HR);
        ArgumentCaptor<DocumentRequest> saved = ArgumentCaptor.forClass(DocumentRequest.class);
        service.submit("employee", input(), pdfFile("i983.pdf"), null);
        verify(requests, atLeastOnce()).save(saved.capture());
        DocumentRequest request = saved.getValue();
        lenient().when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        return request;
    }

    @Test void employeeSubmissionStoresTheOriginalAndAwaitsHrAdminReview() throws Exception {
        when(users.findByOrganizationIdAndAccountStatusAndRoleIn("org-1", AccountStatus.ACTIVE, Set.of(UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(user("admin", UserRole.COMPANY_ADMIN)));
        DocumentRequest request = submitted();

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
        assertThat(request.getDocumentType()).isEqualTo(RequestedDocumentType.I983);
        assertThat(request.getRequestedForUserId()).isEqualTo("hr");
        assertThat(request.getDocumentVersions()).singleElement().satisfies(version -> {
            assertThat(version.getKind()).isEqualTo(DocumentRequestVersion.ORIGINAL);
            assertThat(version.getFileName()).isEqualTo("i983.pdf");
            assertThat(version.getUploadedByUserId()).isEqualTo("employee");
        });
        assertThat(request.getOriginalDocumentId()).isEqualTo(request.getCurrentDocumentId());
        assertThat(request.getHistory()).extracting(DocumentRequestHistoryEntry::getAction).containsExactly("SUBMITTED");
        verify(audit).record(argThat(event -> event.getActionType() == AuditActionType.I983_SUBMITTED));
        // Both the assigned HR and the Company Admin are told there is an I-983 to review.
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr"), eq(NotificationType.DOCUMENT_REQUEST_CREATED), anyString(), anyString(), eq("request-1"), eq("/myhr/hr/document-requests/request-1"));
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.DOCUMENT_REQUEST_CREATED), anyString(), anyString(), eq("request-1"), eq("/myhr/admin/document-requests/request-1"));
    }

    private void employeeHasProfileSignature() throws Exception {
        when(fileStorage.findStoredPath("user-signatures", "employee")).thenReturn(Optional.of("gridfs/sig/signature.png"));
        when(fileStorage.load("gridfs/sig/signature.png")).thenReturn(new FileStorageService.StoredFile(
                signaturePng(), "signature.png", org.springframework.http.MediaType.IMAGE_PNG));
    }

    @Test void employeeCanSignWhereTheyChoseAndTheUnsignedUploadIsKept() throws Exception {
        actor("employee", UserRole.EMPLOYEE);
        actor("hr", UserRole.HR);
        employeeReportsTo("employee", "hr", UserRole.HR);
        employeeHasProfileSignature();
        ArgumentCaptor<DocumentRequest> saved = ArgumentCaptor.forClass(DocumentRequest.class);

        service.submit("employee", input(), pdfFile("i983.pdf"), List.of(new I983SignaturePlacement(1, 0.1, 0.8, 0.3, 0.06)));

        verify(requests, atLeastOnce()).save(saved.capture());
        DocumentRequest request = saved.getValue();
        assertThat(request.getDocumentVersions()).extracting(DocumentRequestVersion::getKind)
                .containsExactly(DocumentRequestVersion.ORIGINAL, DocumentRequestVersion.EMPLOYEE_SIGNED);
        DocumentRequestVersion employeeSigned = request.getDocumentVersions().getLast();
        assertThat(employeeSigned.getBasedOnDocumentId()).isEqualTo(request.getOriginalDocumentId());
        assertThat(employeeSigned.getSignaturePages()).containsExactly(1);
        assertThat(employeeSigned.getSignatureSource()).isEqualTo(I983WorkflowService.EMPLOYEE_SIGNATURE_SOURCE);
        // HR/Admin work on (and counter-sign) the employee-signed copy; the plain upload stays the original.
        assertThat(request.getCurrentDocumentId()).isEqualTo(employeeSigned.getDocumentId());
        assertThat(request.getOriginalDocumentId()).isNotEqualTo(employeeSigned.getDocumentId());
        PdfReader reader = new PdfReader(storedContent.get(employeeSigned.getDocumentId()));
        assertThat(new String(reader.getPageContent(1))).contains(" Do");
        reader.close();
        assertThat(request.getHistory()).extracting(DocumentRequestHistoryEntry::getAction).containsExactly("SUBMITTED", "EMPLOYEE_SIGNED");

        lenient().when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(authorizedSignatures.findActive("org-1")).thenReturn(Optional.of(activeSignature()));
        service.sign("hr", "request-1", new I983SignRequest(employeeSigned.getDocumentId(), List.of(new I983SignaturePlacement(1, 0.6, 0.8, 0.3, 0.06))));
        assertThat(request.getDocumentVersions().getLast().getBasedOnDocumentId()).isEqualTo(employeeSigned.getDocumentId());
    }

    @Test void employeeSigningNeedsAProfileSignatureAndCreatesNothingWithoutOne() throws Exception {
        actor("employee", UserRole.EMPLOYEE);
        when(fileStorage.findStoredPath("user-signatures", "employee")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit("employee", input(), pdfFile("i983.pdf"), List.of(new I983SignaturePlacement(1, 0.1, 0.1, 0.2, 0.05))))
                .isInstanceOf(BusinessException.class).hasMessage(I983WorkflowService.NO_EMPLOYEE_SIGNATURE_MESSAGE);
        verify(requests, never()).save(any());
        verify(documents, never()).store(any());
    }

    @Test void employeeCanSignTheirExistingUploadWhenResubmitting() throws Exception {
        DocumentRequest request = submitted();
        String original = request.getOriginalDocumentId();
        service.returnForCorrection("hr", "request-1", "Please sign section 7");
        employeeHasProfileSignature();

        service.resubmit("employee", "request-1", null, List.of(new I983SignaturePlacement(1, 0.1, 0.85, 0.3, 0.06)));

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
        assertThat(request.getOriginalDocumentId()).isEqualTo(original);
        DocumentRequestVersion signed = request.getDocumentVersions().getLast();
        assertThat(signed.getKind()).isEqualTo(DocumentRequestVersion.EMPLOYEE_SIGNED);
        assertThat(signed.getBasedOnDocumentId()).isEqualTo(original);
        assertThat(request.getCurrentDocumentId()).isEqualTo(signed.getDocumentId());
    }

    @Test void submissionRejectsFilesThatAreNotPdfs() {
        actor("employee", UserRole.EMPLOYEE);
        var notPdf = new MockMultipartFile("file", "i983.pdf", "application/pdf", "hello".getBytes());
        assertThatThrownBy(() -> service.submit("employee", input(), notPdf, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("PDF");
        verify(requests, never()).save(any());
    }

    @Test void approvingRequiresASignedCopyOfTheCurrentVersion() throws Exception {
        submitted();
        assertThatThrownBy(() -> service.approve("hr", "request-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signature");
    }

    @Test void signingAppliesTheActiveOrganizationSignatureAsANewVersionAndKeepsTheOriginal() throws Exception {
        DocumentRequest request = submitted();
        String originalId = request.getOriginalDocumentId();
        byte[] originalBytes = storedContent.get(originalId).clone();
        when(authorizedSignatures.findActive("org-1")).thenReturn(Optional.of(activeSignature()));

        service.sign("hr", "request-1", new I983SignRequest(originalId, List.of(new I983SignaturePlacement(1, 0.55, 0.8, 0.3, 0.08))));

        assertThat(request.getOriginalDocumentId()).isEqualTo(originalId);
        assertThat(storedContent.get(originalId)).isEqualTo(originalBytes);
        DocumentRequestVersion signed = request.getDocumentVersions().getLast();
        assertThat(signed.getKind()).isEqualTo(DocumentRequestVersion.SIGNED);
        assertThat(signed.getBasedOnDocumentId()).isEqualTo(originalId);
        assertThat(signed.getSignatoryName()).isEqualTo("Jane Admin");
        assertThat(signed.getSignatureSource()).isEqualTo(AuthorizedSignatureService.SIGNATURE_SOURCE);
        assertThat(request.getOrganizationSignedDocumentId()).isEqualTo(signed.getDocumentId());
        byte[] signedPdf = storedContent.get(signed.getDocumentId());
        PdfReader reader = new PdfReader(signedPdf);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        assertThat(new String(reader.getPageContent(1))).contains(" Do");
        reader.close();

        service.approve("hr", "request-1");
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.APPROVED);
        assertThat(request.getFulfilledDocumentId()).isEqualTo(signed.getDocumentId());
        assertThat(request.getApprovedByUserId()).isEqualTo("hr");
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_FULFILLED), anyString(), anyString(), eq("request-1"), anyString());
        verify(fulfillmentEmails).send(same(request), argThat(attachment -> attachment.fileName().endsWith(".pdf")
                && attachment.contentType().equals("application/pdf") && attachment.content().length > 0), isNull());
        // The dedicated signed-document email is the only email for an approval: no second notification email.
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test void signingIsBlockedWithoutAnActiveOrganizationSignature() throws Exception {
        DocumentRequest request = submitted();
        when(authorizedSignatures.findActive("org-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sign("admin", "request-1", new I983SignRequest(request.getCurrentDocumentId(), List.of(new I983SignaturePlacement(1, 0.1, 0.1, 0.2, 0.05)))))
                .isInstanceOf(BusinessException.class).hasMessage(I983WorkflowService.NO_SIGNATURE_MESSAGE);
        assertThat(request.getOrganizationSignedDocumentId()).isNull();
    }

    @Test void signingAStaleVersionIsRefused() throws Exception {
        submitted();
        assertThatThrownBy(() -> service.sign("hr", "request-1", new I983SignRequest("old-doc", List.of(new I983SignaturePlacement(1, 0.1, 0.1, 0.2, 0.05)))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("replaced");
    }

    @Test void replacementKeepsEveryPreviousVersionAndInvalidatesAnEarlierSignature() throws Exception {
        DocumentRequest request = submitted();
        String originalId = request.getOriginalDocumentId();
        when(authorizedSignatures.findActive("org-1")).thenReturn(Optional.of(activeSignature()));
        service.sign("hr", "request-1", new I983SignRequest(originalId, List.of(new I983SignaturePlacement(1, 0.1, 0.1, 0.2, 0.05))));

        service.replace("admin", "request-1", pdfFile("i983-fixed.pdf"));

        assertThat(request.getDocumentVersions()).extracting(DocumentRequestVersion::getKind)
                .containsExactly(DocumentRequestVersion.ORIGINAL, DocumentRequestVersion.SIGNED, DocumentRequestVersion.REPLACEMENT);
        assertThat(request.getDocumentVersions()).extracting(DocumentRequestVersion::getVersion).containsExactly(1, 2, 3);
        assertThat(request.getOriginalDocumentId()).isEqualTo(originalId);
        assertThat(request.getCurrentDocumentId()).isNotEqualTo(originalId);
        assertThat(request.getOrganizationSignedDocumentId()).isNull();
        verify(documents, never()).delete(anyString());
        assertThatThrownBy(() -> service.approve("admin", "request-1")).isInstanceOf(BusinessException.class);
    }

    @Test void returnForCorrectionLetsTheEmployeeResubmitACorrectedDocument() throws Exception {
        DocumentRequest request = submitted();
        String firstOriginal = request.getOriginalDocumentId();
        assertThatThrownBy(() -> service.resubmit("employee", "request-1", pdfFile("early.pdf"), null))
                .isInstanceOf(BusinessException.class);

        service.returnForCorrection("hr", "request-1", "Section 5 is incomplete");
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.RETURNED_FOR_CORRECTION);
        assertThat(request.getReturnReason()).isEqualTo("Section 5 is incomplete");
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_RETURNED), anyString(), contains("Section 5"), eq("request-1"), anyString());

        service.resubmit("employee", "request-1", pdfFile("i983-v2.pdf"), null);
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.SUBMITTED);
        assertThat(request.getReturnReason()).isNull();
        assertThat(request.getOriginalDocumentId()).isNotEqualTo(firstOriginal);
        assertThat(request.getDocumentVersions()).extracting(DocumentRequestVersion::getDocumentId).contains(firstOriginal);
        assertThat(request.getHistory()).extracting(DocumentRequestHistoryEntry::getAction)
                .containsExactly("SUBMITTED", "RETURNED_FOR_CORRECTION", "RESUBMITTED");
    }

    @Test void rejectionRecordsTheReasonAndReviewer() throws Exception {
        DocumentRequest request = submitted();
        service.reject("admin", "request-1", "Not eligible for STEM OPT");
        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.REJECTED);
        assertThat(request.getRejectionReason()).isEqualTo("Not eligible for STEM OPT");
        assertThat(request.getReviewedByUserId()).isEqualTo("admin");
        assertThatThrownBy(() -> service.approve("admin", "request-1")).isInstanceOf(BusinessException.class);
    }

    @Test void onlyTheAssignedHrOrACompanyAdminCanReview() throws Exception {
        submitted();
        actor("other-hr", UserRole.HR);
        assertThatThrownBy(() -> service.returnForCorrection("other-hr", "request-1", "Fix it"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.approve("employee", "request-1")).isInstanceOf(RuntimeException.class);
    }

    @Test void employeeSeesTheSignedCopyOnlyAfterApproval() {
        DocumentRequestVersion signed = DocumentRequestVersion.builder().documentId("signed").kind(DocumentRequestVersion.SIGNED).build();
        DocumentRequestVersion replacement = DocumentRequestVersion.builder().documentId("hr-copy").kind(DocumentRequestVersion.REPLACEMENT).build();
        DocumentRequestVersion original = DocumentRequestVersion.builder().documentId("mine").kind(DocumentRequestVersion.ORIGINAL).build();
        DocumentRequest request = DocumentRequest.builder().status(DocumentRequestStatus.SUBMITTED).organizationSignedDocumentId("signed").build();

        assertThat(DocumentRequestService.requesterMaySee(request, original)).isTrue();
        assertThat(DocumentRequestService.requesterMaySee(request, signed)).isFalse();
        assertThat(DocumentRequestService.requesterMaySee(request, replacement)).isFalse();
        request.setStatus(DocumentRequestStatus.APPROVED);
        assertThat(DocumentRequestService.requesterMaySee(request, signed)).isTrue();
    }

    @Test void placementIsMappedIntoPdfSpaceForEveryPageRotation() {
        Rectangle page = new Rectangle(0, 0, 600, 800);
        // Top-left of the displayed page for each /Rotate value.
        assertThat(I983WorkflowService.toUserSpace(page, 0, 0, 0)).containsExactly(0f, 800f);
        assertThat(I983WorkflowService.toUserSpace(page, 90, 0, 0)).containsExactly(0f, 0f);
        assertThat(I983WorkflowService.toUserSpace(page, 180, 0, 0)).containsExactly(600f, 0f);
        assertThat(I983WorkflowService.toUserSpace(page, 270, 0, 0)).containsExactly(600f, 800f);
        // Bottom-right of an unrotated page.
        assertThat(I983WorkflowService.toUserSpace(page, 0, 1, 1)).containsExactly(600f, 0f);
    }

    @Test void stampingARotatedPageKeepsTheDocumentReadable() throws Exception {
        byte[] rotated = pdf(PageSize.LETTER, 90);
        byte[] signed = I983WorkflowService.stampSignature(rotated, signaturePng(), List.of(new I983SignaturePlacement(1, 0.6, 0.7, 0.25, 0.1)));
        PdfReader reader = new PdfReader(signed);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        reader.close();
        assertThatThrownBy(() -> I983WorkflowService.stampSignature(rotated, signaturePng(), List.of(new I983SignaturePlacement(2, 0.1, 0.1, 0.1, 0.1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("page");
    }

    @Test void aDocumentCanBeSignedInSeveralPlacesAcrossPages() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.LETTER);
        PdfWriter.getInstance(document, output);
        document.open();
        document.add(new Paragraph("Page one"));
        document.newPage();
        document.add(new Paragraph("Page two"));
        document.close();

        byte[] signed = I983WorkflowService.stampSignature(output.toByteArray(), signaturePng(), List.of(
                new I983SignaturePlacement(1, 0.1, 0.8, 0.25, 0.06),
                new I983SignaturePlacement(1, 0.6, 0.8, 0.25, 0.06),
                new I983SignaturePlacement(2, 0.1, 0.5, 0.25, 0.06)));

        PdfReader reader = new PdfReader(signed);
        assertThat(reader.getNumberOfPages()).isEqualTo(2);
        String page1 = new String(reader.getPageContent(1));
        assertThat(page1.split(" Do", -1).length - 1).isEqualTo(2);
        assertThat(new String(reader.getPageContent(2))).contains(" Do");
        reader.close();
    }

    @Test void placementsMustBeInsideThePageAndLimitedInNumber() {
        assertThatThrownBy(() -> I983WorkflowService.requirePlacements(List.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> I983WorkflowService.requirePlacements(List.of(new I983SignaturePlacement(1, 0.9, 0.1, 0.2, 0.05))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inside the page");
        List<I983SignaturePlacement> tooMany = java.util.Collections.nCopies(I983WorkflowService.MAX_SIGNATURES + 1,
                new I983SignaturePlacement(1, 0.1, 0.1, 0.1, 0.05));
        assertThatThrownBy(() -> I983WorkflowService.requirePlacements(tooMany))
                .isInstanceOf(BusinessException.class).hasMessageContaining("at most");
    }

    @Test void anotherDocumentTypeIsNotAnI983() throws Exception {
        actor("hr", UserRole.HR);
        when(requests.findByIdAndOrganizationId("request-9", "org-1")).thenReturn(Optional.of(DocumentRequest.builder()
                .id("request-9").organizationId("org-1").documentType(RequestedDocumentType.OTHER).build()));
        assertThatThrownBy(() -> service.get("hr", "request-9")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- email notifications: same user as the in-app notification; personalEmail, then account email ----

    private void personalEmail(String userId, String email) {
        lenient().when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                EmployeeProfile.builder().userId(userId).organizationId("org-1").personalEmail(email).workEmail("work-" + userId + "@x.com").build()));
    }

    private void stubEmailRecipients() {
        personalEmail("hr", "hr-personal@x.com");
        personalEmail("employee", "employee-personal@x.com");
        lenient().when(users.findById("employee")).thenReturn(Optional.of(user("employee", UserRole.EMPLOYEE)));
        User admin = User.builder().id("admin").loginId("admin").organizationId("org-1").role(UserRole.COMPANY_ADMIN)
                .email("admin-login@x.com").accountStatus(AccountStatus.ACTIVE).build();
        lenient().when(users.findById("admin")).thenReturn(Optional.of(admin));
        lenient().when(users.findByOrganizationIdAndAccountStatusAndRoleIn("org-1", AccountStatus.ACTIVE, Set.of(UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(admin));
    }

    @Test void submissionEmailsTheSameHrAndAdminWhoGetTheInAppNotification() throws Exception {
        stubEmailRecipients();
        submitted();

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr"), eq(NotificationType.DOCUMENT_REQUEST_CREATED), anyString(), anyString(), eq("request-1"), anyString());
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.DOCUMENT_REQUEST_CREATED), anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("hr-personal@x.com"), eq("New Signed I-983 for review"), anyString());
        verify(emailService).sendHtmlEmail(eq("admin-login@x.com"), eq("New Signed I-983 for review"), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("work-hr@x.com"), anyString(), anyString());
    }

    @Test void returnAndResubmitEmailTheRequesterThenTheReviewers() throws Exception {
        stubEmailRecipients();
        submitted();
        org.mockito.Mockito.clearInvocations(notifications, emailService);

        service.returnForCorrection("hr", "request-1", "Section 5 is incomplete");

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_RETURNED), anyString(), contains("Section 5"), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("employee-personal@x.com"), eq("Signed I-983 returned for correction"), anyString());

        org.mockito.Mockito.clearInvocations(notifications, emailService);
        service.resubmit("employee", "request-1", pdfFile("i983-v2.pdf"), null);

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("hr"), eq(NotificationType.DOCUMENT_REQUEST_CREATED), eq("Signed I-983 resubmitted"), anyString(), eq("request-1"), anyString());
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("admin"), eq(NotificationType.DOCUMENT_REQUEST_CREATED), eq("Signed I-983 resubmitted"), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("hr-personal@x.com"), eq("Signed I-983 resubmitted"), anyString());
        verify(emailService).sendHtmlEmail(eq("admin-login@x.com"), eq("Signed I-983 resubmitted"), anyString());
    }

    @Test void rejectionEmailsTheRequester() throws Exception {
        stubEmailRecipients();
        submitted();
        org.mockito.Mockito.clearInvocations(notifications, emailService);

        service.reject("admin", "request-1", "Not eligible for STEM OPT");

        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_REJECTED), anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("employee-personal@x.com"), eq("Signed I-983 rejected"), anyString());
    }

    @Test void emailFailureNeverFailsTheI983ReturnAndKeepsTheInAppNotification() throws Exception {
        stubEmailRecipients();
        DocumentRequest request = submitted();
        org.mockito.Mockito.clearInvocations(notifications);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        service.returnForCorrection("hr", "request-1", "Section 5 is incomplete");

        assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.RETURNED_FOR_CORRECTION);
        verify(notifications).createInAppOnlyNotification(eq("org-1"), eq("employee"), eq(NotificationType.DOCUMENT_REQUEST_RETURNED), anyString(), anyString(), eq("request-1"), anyString());
    }
}
