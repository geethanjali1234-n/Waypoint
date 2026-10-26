package com.waypoint.service;

import com.waypoint.dto.myhr.OfferLetterEmailDraftResponse;
import com.waypoint.dto.myhr.OfferLetterEmailRequest;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.StoredDocument;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.EmailDeliveryException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.*;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfferLetterServiceEmailTest {
        private static final byte[] PDF = "%PDF-1.7 existing offer letter".getBytes(StandardCharsets.US_ASCII);
        private static final Set<UserRole> MANAGERS = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);

        @Mock
        OfferLetterRecordRepository records;
        @Mock
        DocumentRequestRepository requests;
        @Mock
        EmployeeProfileRepository profiles;
        @Mock
        OrganizationRepository organizations;
        @Mock
        UserRepository users;
        @Mock
        ReportingAssignmentRepository assignments;
        @Mock
        MyHrAuthenticatedUserService auth;
        @Mock
        DocumentService documents;
        @Mock
        OfferLetterPdfService pdf;
        @Mock
        AuditService audit;
        @Mock
        EmailService emailService;
        @Captor
        ArgumentCaptor<List<EmailAttachment>> attachments;

        OfferLetterStaticAttachmentService staticAttachments = new OfferLetterStaticAttachmentService();
        OfferLetterService service;
        DocumentRequest request;

        @BeforeEach
        void setUp() {
                service = new OfferLetterService(records, requests, profiles, organizations, users, assignments, auth,
                                documents,
                                pdf, audit, emailService, new OfferLetterEmailTemplateService(), staticAttachments);
                request = DocumentRequest.builder().id("request-1").organizationId("org-1")
                                .requestedByUserId("employee-1")
                                .requestedForUserId("hr-1").documentType(RequestedDocumentType.OFFER_LETTER)
                                .requestTitle("Offer Letter")
                                .status(DocumentRequestStatus.FULFILLED).fulfilledDocumentId("doc-1")
                .approvedByUserId("admin").approvedAt(java.time.LocalDateTime.now())
                .offerLetterApproval(com.waypoint.entity.OfferLetterApprovalDetails.builder().employeeName("Jane Doe").designation("Developer").submittedByUserId("hr-1").build()).build();
                lenient().when(auth.require("admin", MANAGERS, false))
                                .thenReturn(actor("admin", UserRole.COMPANY_ADMIN));
                lenient().when(auth.require("hr-1", MANAGERS, false)).thenReturn(actor("hr-1", UserRole.HR));
                lenient().when(auth.require("hr-2", MANAGERS, false)).thenReturn(actor("hr-2", UserRole.HR));
                lenient().when(auth.require("employee-1", MANAGERS, false))
                                .thenThrow(new ForbiddenException("You do not have access to this resource"));
                lenient().when(requests.findByIdAndOrganizationId("request-1", "org-1"))
                                .thenReturn(Optional.of(request));
                lenient().when(profiles.findByUserId("employee-1")).thenReturn(Optional.of(EmployeeProfile.builder()
                                .userId("employee-1").name("Venkata Naga Sai Vasavi Anna")
                                .personalEmail("profile@example.com").build()));
                lenient().when(users.findById("employee-1")).thenReturn(Optional.of(User.builder().id("employee-1")
                                .email("employee@example.com").build()));
                lenient().when(documents.getStoredContent("doc-1")).thenReturn(content("org-1", "request-1", PDF));
                lenient().when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1")
                                .organizationName("ITAP").organizationEmail("hr@itap.example").build()));
        }

        @Test
        void companyAdminEmailsTheExistingDeliveredPdfWithEditedSubjectAndMessage() {
                service.email("admin", "request-1",
                                input("another@example.com", "Edited subject", "Hi **there**\n\nSee hr@tardusinc.com"));

                ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
                verify(emailService).sendHtmlEmailWithAttachments(eq("another@example.com"), eq("Edited subject"),
                                html.capture(),
                                attachments.capture(), eq("HR ITAP"));
                assertThat(attachments.getValue()).extracting(EmailAttachment::fileName).containsExactly(
                                "Offer Letter - Venkata Naga Sai Vasavi Anna.pdf", "Direct Deposit Authorization.pdf",
                                "I-9.pdf", "W-4 Form.pdf");
                assertThat(attachments.getValue()).allSatisfy(a -> {
                        assertThat(a.contentType()).isEqualTo("application/pdf");
                        assertThat(new String(a.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
                });
                assertThat(attachments.getValue().get(0).content()).isEqualTo(PDF);
                assertThat(html.getValue()).contains("Hi <strong>there</strong>",
                                "<a href=\"mailto:hr@tardusinc.com\"");
                verify(documents).getStoredContent("doc-1");
                verify(documents, never()).getContent(anyString());
                verifyNoInteractions(pdf);
                verify(audit).record(any(AuthenticatedMyHrUser.class), eq(AuditActionType.OFFER_EMAILED),
                                eq("DOCUMENT_REQUEST"),
                                eq("request-1"), eq("Offer Letter"), eq("Offer letter emailed to employee"), eq(null),
                                eq(Map.of("documentType", "OFFER_LETTER", "recipientEmail", "another@example.com")));
        }

        @Test
        void editedEmployeeNameNamesTheAttachmentForThisSendOnlyAndTheProfileIsUntouched() {
                service.email("admin", "request-1",
                                new OfferLetterEmailRequest("employee@example.com", "Subject",
                                                "Hi Sridevi Sowmya Grandhi,",
                                                "  Sridevi Sowmya Grandhi "));

                verify(emailService).sendHtmlEmailWithAttachments(eq("employee@example.com"), eq("Subject"),
                                anyString(), attachments.capture(),
                                eq("HR ITAP"));
                assertThat(attachments.getValue()).extracting(EmailAttachment::fileName).containsExactly(
                                "Offer Letter - Sridevi Sowmya Grandhi.pdf", "Direct Deposit Authorization.pdf",
                                "I-9.pdf", "W-4 Form.pdf");
                // The delivered PDF (generated from the same edited name) is attached as
                // stored, never regenerated.
                assertThat(attachments.getValue().get(0).content()).isEqualTo(PDF);
                verifyNoInteractions(pdf);
                verify(profiles, never()).save(any());
                verify(users, never()).save(any());
                verify(requests, never()).save(any());
        }

        @Test
        void blankEditedNameFallsBackToTheProfileName() {
                service.email("admin", "request-1",
                                new OfferLetterEmailRequest("employee@example.com", "Subject", "Message", "  "));

                verify(emailService).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                attachments.capture(), anyString());
                assertThat(attachments.getValue().get(0).fileName())
                                .isEqualTo("Offer Letter - Venkata Naga Sai Vasavi Anna.pdf");
        }

        @Test
        void overriddenRecipientIsUsedOnlyForThisSendAndNothingIsPersisted() {
                service.email("admin", "request-1", input("  another@example.com ", "Subject", "Message"));

                verify(emailService).sendHtmlEmailWithAttachments(eq("another@example.com"), anyString(), anyString(),
                                anyList(), anyString());
                verify(requests, never()).save(any());
                verify(profiles, never()).save(any());
                verify(users, never()).save(any());
                verifyNoInteractions(records);
                assertThat(request.getStatus()).isEqualTo(DocumentRequestStatus.FULFILLED);
        }

        @Test
        void assignedHrCanEmailTheOfferLetter() {
                service.email("hr-1", "request-1", input("employee@example.com", "Subject", "Message"));

                verify(emailService).sendHtmlEmailWithAttachments(eq("employee@example.com"), eq("Subject"),
                                anyString(), anyList(), anyString());
        }

        @Test
        void hrWhoIsNotAssignedToTheRequestIsRejected() {
                assertThatThrownBy(() -> service.email("hr-2", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(ForbiddenException.class);
                verifyNoInteractions(emailService);
        }

        @Test
        void employeeRoleIsRejectedByTheManagerOnlyRoleCheck() {
                assertThatThrownBy(() -> service.email("employee-1", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(ForbiddenException.class);
                assertThatThrownBy(() -> service.emailDraft("employee-1", "request-1", "Developer", null))
                                .isInstanceOf(ForbiddenException.class);
                verify(auth, times(2)).require("employee-1", MANAGERS, false);
                verifyNoInteractions(emailService, documents);
        }

        @Test
        void requestFromAnotherOrganizationIsNotFound() {
                when(requests.findByIdAndOrganizationId("foreign-request", "org-1")).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.email("admin", "foreign-request",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(ResourceNotFoundException.class);
                verifyNoInteractions(emailService, documents);
        }

        @Test
        void documentBelongingToAnotherOrganizationOrRequestIsNeverAttached() {
                when(documents.getStoredContent("doc-1")).thenReturn(content("org-2", "request-1", PDF));
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(ResourceNotFoundException.class);

                when(documents.getStoredContent("doc-1")).thenReturn(content("org-1", "other-request", PDF));
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(ResourceNotFoundException.class);
                verifyNoInteractions(emailService);
        }

        @Test
        void nonOfferLetterRequestIsRejected() {
                request.setDocumentType(RequestedDocumentType.EMPLOYMENT_LETTER);

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class)
                                .hasMessage("This is not an offer letter request");
                verifyNoInteractions(emailService);
        }

        @Test
        void offerLetterMustBeDeliveredBeforeItCanBeEmailed() {
                request.setStatus(DocumentRequestStatus.PENDING);
                request.setFulfilledDocumentId(null);

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class)
                                .hasMessage("Send the offer letter to the employee before emailing it");
                verifyNoInteractions(emailService, documents);
        }

        @Test
        void signedOfferLetterIsNotEmailedAgain() {
                request.setSignedResponseDocumentId("signed-doc");

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class);
                verifyNoInteractions(emailService);
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        void missingRecipientIsRejected(String recipient) {
                assertThatThrownBy(() -> service.email("admin", "request-1", input(recipient, "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage("Recipient email is required");
                verifyNoInteractions(emailService);
        }

        @ParameterizedTest
        @ValueSource(strings = { "not-an-email", "a@b", "a@example.com,b@example.com", "Name <a@example.com>",
                        "a b@example.com", "a@example.com\nBcc: x@example.com", "abc", "abc@", "abc.com@",
                        "hello@invalid" })
        void invalidRecipientIsRejected(String recipient) {
                assertThatThrownBy(() -> service.email("admin", "request-1", input(recipient, "Subject", "Message")))
                                .isInstanceOf(BusinessException.class)
                                .hasMessage("Enter a valid recipient email address");
                verifyNoInteractions(emailService);
        }

        @Test
        void blankOrMultiLineSubjectAndBlankMessageAreRejected() {
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", " ", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage("Email subject is required");
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Line\nBcc: x@example.com", "Message")))
                                .isInstanceOf(BusinessException.class)
                                .hasMessage("Email subject must be a single line");
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "  ")))
                                .isInstanceOf(BusinessException.class).hasMessage("Email message is required");
                verifyNoInteractions(emailService);
        }

        @Test
        void smtpFailureReturnsSafeErrorAndIsNotAuditedAsSent() {
                doThrow(new MailAuthenticationException("535 Username and Password not accepted"))
                                .when(emailService).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                                anyList(), anyString());

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(EmailDeliveryException.class)
                                .hasMessage("The offer letter email could not be sent. Please try again later.");
                verifyNoInteractions(audit);
                verify(requests, never()).save(any());
        }

        @Test
        void synchronousSmtpRecipientRejectionIsReportedClearlyWithoutSmtpDetails() throws Exception {
                jakarta.mail.Address bad = new jakarta.mail.internet.InternetAddress(
                                "nonexistent-address-123456789@gmail.com");
                doThrow(new org.springframework.mail.MailSendException(
                                "Failed messages: 550-5.1.1 The email account does not exist. password=secret",
                                new jakarta.mail.SendFailedException("550 5.1.1 no such user", null, null, null,
                                                new jakarta.mail.Address[] { bad })))
                                .when(emailService).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                                anyList(), anyString());

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("nonexistent-address-123456789@gmail.com", "Subject", "Message")))
                                .isInstanceOf(EmailDeliveryException.class)
                                .hasMessage("Email could not be delivered to this recipient. Please check the email address and try again.")
                                .message().doesNotContain("550", "secret", "password");
                verifyNoInteractions(audit);
        }

        @Test
        void readTimeoutAfterTheBodyWasSentIsUnconfirmedNotFailedAndIsAuditedOnceWithoutResend() {
                doThrow(new org.springframework.mail.MailSendException("Mail server connection failed",
                                new jakarta.mail.MessagingException("Exception reading response",
                                                new java.net.SocketTimeoutException("Read timed out"))))
                                .when(emailService).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                                anyList(), anyString());

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(com.waypoint.exception.EmailDeliveryUnconfirmedException.class)
                                .message().doesNotContain("could not be sent").contains("could not be confirmed");
                verify(emailService, times(1)).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                anyList(), anyString());
                verify(audit).record(any(AuthenticatedMyHrUser.class), eq(AuditActionType.OFFER_EMAILED),
                                eq("DOCUMENT_REQUEST"), eq("request-1"),
                                eq("Offer Letter"), eq("Offer letter emailed to employee"), eq(null),
                                eq(Map.of("documentType", "OFFER_LETTER", "recipientEmail", "employee@example.com",
                                                "deliveryConfirmed", "false")));
        }

        @Test
        void connectionFailureBeforeTheMessageIsAcceptedStillFailsAndIsNotAudited() {
                doThrow(new org.springframework.mail.MailSendException("Mail server connection failed",
                                new jakarta.mail.MessagingException("Could not connect",
                                                new java.net.SocketTimeoutException("connect timed out"))))
                                .when(emailService).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                                anyList(), anyString());

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(EmailDeliveryException.class)
                                .hasMessage("The offer letter email could not be sent. Please try again later.");
                verifyNoInteractions(audit);
        }

        @Test
        void auditFailureAfterASuccessfulSendDoesNotTurnTheSendIntoAReportedFailure() {
                doThrow(new RuntimeException("audit store down")).when(audit).record(any(AuthenticatedMyHrUser.class),
                                any(), anyString(),
                                anyString(), any(), anyString(), any(), any());

                service.email("admin", "request-1", input("employee@example.com", "Subject", "Message"));

                verify(emailService, times(1)).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                anyList(), anyString());
        }

        @Test
        void disabledEmailDeliveryIsReportedClearly() {
                doThrow(new IllegalStateException(EmailService.DELIVERY_DISABLED_MESSAGE))
                                .when(emailService).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(),
                                                anyList(), anyString());

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(EmailDeliveryException.class)
                                .hasMessageContaining("Email delivery is not enabled");
                verifyNoInteractions(audit);
        }

        @Test
        void missingStoredPdfFailsClearlyAndNothingIsSubstituted() {
                String unavailable = "Offer Letter PDF is not available. Please generate the Offer Letter again before sending.";

                // Object missing from storage, or a placeholder/size mismatch rejected by
                // getStoredContent.
                when(documents.getStoredContent("doc-1")).thenThrow(new ObjectStorageException("missing", null));
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage(unavailable);

                // Metadata record deleted.
                reset(documents);
                when(documents.getStoredContent("doc-1"))
                                .thenThrow(new ResourceNotFoundException("Document not found"));
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage(unavailable);

                // Legacy (non-R2) document.
                reset(documents);
                when(documents.getStoredContent("doc-1")).thenThrow(
                                new BusinessException("This Document Service currently manages new R2 documents only"));
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage(unavailable);

                // Stored bytes that are not a PDF.
                reset(documents);
                when(documents.getStoredContent("doc-1")).thenReturn(
                                content("org-1", "request-1", "plain text".getBytes(StandardCharsets.UTF_8)));
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage(unavailable);

                // Delivered request whose document reference is missing.
                reset(documents);
                request.setFulfilledDocumentId(null);
                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessage(unavailable);

                verify(documents, never()).getContent(anyString());
                verifyNoInteractions(emailService, pdf, audit);
        }

        @Test
        void missingPredefinedAttachmentBlocksTheEmailWithAClearError() {
                OfferLetterStaticAttachmentService broken = mock(OfferLetterStaticAttachmentService.class);
                when(broken.load()).thenThrow(new BusinessException(OfferLetterStaticAttachmentService.UNAVAILABLE));
                service = new OfferLetterService(records, requests, profiles, organizations, users, assignments, auth,
                                documents,
                                pdf, audit, emailService, new OfferLetterEmailTemplateService(), broken);

                assertThatThrownBy(() -> service.email("admin", "request-1",
                                input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class)
                                .hasMessage(OfferLetterStaticAttachmentService.UNAVAILABLE);
                verifyNoInteractions(emailService, audit);
        }

        @Test
        void anOfferLetterThatTheAdminHasNotApprovedCannotBeEmailedEvenWhenItIsDelivered() {
                request.setApprovedByUserId(null);
                request.setApprovedAt(null);

                assertThatThrownBy(() -> service.email("admin", "request-1", input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class).hasMessageContaining("not been approved by the Company Admin");
                verifyNoInteractions(emailService, audit);
        }

        @Test
        void anOfferLetterWaitingForApprovalCannotBeEmailed() {
                request.setStatus(DocumentRequestStatus.SUBMITTED);
                request.setFulfilledDocumentId(null);
                request.setApprovedByUserId(null);
                request.setApprovedAt(null);

                assertThatThrownBy(() -> service.email("admin", "request-1", input("employee@example.com", "Subject", "Message")))
                                .isInstanceOf(BusinessException.class);
                verifyNoInteractions(emailService, audit);
        }

        @Test
        void theRecordBasedSendEndpointIsClosedBecauseItWouldBypassApproval() {
                assertThatThrownBy(() -> service.send("hr-1", "request-1")).isInstanceOf(BusinessException.class)
                                .hasMessage(DocumentRequestService.OFFER_LETTER_NEEDS_APPROVAL);
                verify(requests, never()).save(any());
                verifyNoInteractions(emailService);
        }

        @Test
        void draftPrePopulatesRecipientSubjectMessageAndAttachmentName() {
                OfferLetterEmailDraftResponse draft = service.emailDraft("hr-1", "request-1", "Software Developer",
                                "Vasavi Anna");

                // Profile personal email (kept current by approved profile change requests),
                // not the stale account email.
                assertThat(draft.recipientEmail()).isEqualTo("profile@example.com");
                assertThat(draft.subject()).isEqualTo("Congratulations! Offer with ITAP");
                assertThat(draft.message()).startsWith("Hi Vasavi Anna,")
                                .contains("offer you as a **Software Developer** with our organization");
                // The entered name wins over the stale profile name for greeting and attachment
                // alike.
                assertThat(draft.attachmentFileName()).isEqualTo("Vasavi_Anna_Offer_Letter.pdf");
                verifyNoInteractions(emailService, documents);
                verify(requests, never()).save(any());
        }

        @Test
        void draftWithoutAnEditedNameUsesTheProfileNameForGreetingAndAttachment() {
                OfferLetterEmailDraftResponse draft = service.emailDraft("hr-1", "request-1", "Software Developer",
                                "  ");

                assertThat(draft.message()).startsWith("Hi Venkata Naga Sai Vasavi Anna,");
                assertThat(draft.attachmentFileName()).isEqualTo("Venkata_Naga_Sai_Vasavi_Anna_Offer_Letter.pdf");
        }

        @Test
        void draftWithAnEditedNameUsesItForGreetingAndAttachmentAndNeverTouchesTheProfile() {
                OfferLetterEmailDraftResponse draft = service.emailDraft("hr-1", "request-1", "UI/UX",
                                " Sridevi Sowmya Grandhi ");

                assertThat(draft.message()).startsWith("Hi Sridevi Sowmya Grandhi,");
                assertThat(draft.attachmentFileName()).isEqualTo("Sridevi_Sowmya_Grandhi_Offer_Letter.pdf");
                assertThat(draft.subject()).isEqualTo("Congratulations! Offer with ITAP");
                verify(profiles, never()).save(any());
                verify(users, never()).save(any());
        }

        @Test
        void draftRecipientFallsBackToAccountEmailThenWorkEmail() {
                when(profiles.findByUserId("employee-1")).thenReturn(Optional.of(EmployeeProfile.builder()
                                .userId("employee-1").name("Jane Doe").personalEmail(" ")
                                .workEmail("jane@work.example.com").build()));
                assertThat(service.emailDraft("admin", "request-1", "Developer", null).recipientEmail())
                                .isEqualTo("employee@example.com");

                when(users.findById("employee-1")).thenReturn(Optional.of(User.builder().id("employee-1").build()));
                assertThat(service.emailDraft("admin", "request-1", "Developer", null).recipientEmail())
                                .isEqualTo("jane@work.example.com");

                when(profiles.findByUserId("employee-1")).thenReturn(Optional.empty());
                assertThat(service.emailDraft("admin", "request-1", "Developer", null).recipientEmail()).isNull();
        }

        @Test
        void draftIsRejectedForUnassignedHr() {
                assertThatThrownBy(() -> service.emailDraft("hr-2", "request-1", "Developer", null))
                                .isInstanceOf(ForbiddenException.class);
        }

        private static OfferLetterEmailRequest input(String recipient, String subject, String message) {
                return new OfferLetterEmailRequest(recipient, subject, message);
        }

        private static AuthenticatedMyHrUser actor(String id, UserRole role) {
                return new AuthenticatedMyHrUser(id, id, id + "@example.com", role, "org-1", AccountStatus.ACTIVE,
                                false);
        }

        private static DocumentService.DocumentContent content(String organizationId, String relatedEntityId,
                        byte[] bytes) {
                StoredDocument metadata = StoredDocument.builder().id("doc-1").organizationId(organizationId)
                                .relatedEntityType("DOCUMENT_REQUEST").relatedEntityId(relatedEntityId)
                                .fileName("offer-letter.pdf").build();
                return new DocumentService.DocumentContent(metadata, bytes, "application/pdf", bytes.length);
        }
}
