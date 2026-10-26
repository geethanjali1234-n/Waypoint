package com.waypoint.service;

import com.waypoint.dto.myhr.OfferLetterEmailDraftResponse;
import com.waypoint.dto.myhr.OfferLetterEmailRequest;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.StoredDocument;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.*;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the real Offer Letter send path (OfferLetterService -> real
 * JavaMailSenderEmailService -> real MimeMessageHelper) and inspects the
 * MimeMessage that would be handed to SMTP. Only the database, storage and
 * the final SMTP transmission are replaced.
 */
class OfferLetterEmailMimeHeadersTest {
    private static final byte[] PDF = "%PDF-1.7 offer".getBytes(StandardCharsets.US_ASCII);
    private static final Set<UserRole> MANAGERS = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);

    private final List<MimeMessage> sent = new ArrayList<>();
    private OfferLetterService offerLetters;

    private void wire(String mailFromAddress, String globalMailFromName) {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(call -> sent.add(call.getArgument(0))).when(mailSender).send(any(MimeMessage.class));
        JavaMailSenderEmailService mail = new JavaMailSenderEmailService(mailSender);
        ReflectionTestUtils.setField(mail, "mailEnabled", true);
        ReflectionTestUtils.setField(mail, "fromAddress", mailFromAddress);
        ReflectionTestUtils.setField(mail, "fromName", globalMailFromName);

        MyHrAuthenticatedUserService auth = mock(MyHrAuthenticatedUserService.class);
        DocumentRequestRepository requests = mock(DocumentRequestRepository.class);
        EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
        UserRepository users = mock(UserRepository.class);
        DocumentService documents = mock(DocumentService.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);

        when(auth.require("admin", MANAGERS, false))
                .thenReturn(new AuthenticatedMyHrUser("admin", "admin", "admin@example.com", UserRole.COMPANY_ADMIN, "org-1", AccountStatus.ACTIVE, false));
        DocumentRequest request = DocumentRequest.builder().id("request-1").organizationId("org-1").requestedByUserId("employee-1")
                .requestedForUserId("hr-1").documentType(RequestedDocumentType.OFFER_LETTER).requestTitle("Offer Letter")
                .status(DocumentRequestStatus.FULFILLED).fulfilledDocumentId("doc-1")
                .approvedByUserId("admin").approvedAt(java.time.LocalDateTime.now())
                .offerLetterApproval(com.waypoint.entity.OfferLetterApprovalDetails.builder().employeeName("Jane Doe").designation("Developer").submittedByUserId("hr-1").build()).build();
        when(requests.findByIdAndOrganizationId("request-1", "org-1")).thenReturn(Optional.of(request));
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1")
                .organizationName("ITAP").organizationEmail("hr@itap.example").build()));
        when(profiles.findByUserId("employee-1")).thenReturn(Optional.of(EmployeeProfile.builder()
                .userId("employee-1").name("Jane Doe").personalEmail("jane@example.com").build()));
        when(users.findById(any())).thenReturn(Optional.of(User.builder().id("employee-1").email("jane@example.com").build()));
        StoredDocument metadata = StoredDocument.builder().id("doc-1").organizationId("org-1")
                .relatedEntityType("DOCUMENT_REQUEST").relatedEntityId("request-1").fileName("offer-letter.pdf").build();
        when(documents.getStoredContent("doc-1")).thenReturn(new DocumentService.DocumentContent(metadata, PDF, "application/pdf", PDF.length));

        offerLetters = new OfferLetterService(mock(OfferLetterRecordRepository.class), requests, profiles, organizations, users,
                mock(ReportingAssignmentRepository.class), auth, documents, mock(OfferLetterPdfService.class), mock(AuditService.class),
                mail, new OfferLetterEmailTemplateService(), new OfferLetterStaticAttachmentService());
    }

    @Test
    void draftSubjectIsTheDefaultAndTheSentMimeMessageHasHrFromAndThatSubject() throws Exception {
        // Mirrors the IntelliJ run configuration: global name WayPoint, address from MAIL_FROM_ADDRESS.
        wire("tardusinc27@gmail.com", "WayPoint");

        OfferLetterEmailDraftResponse draft = offerLetters.emailDraft("admin", "request-1", "UI/UX", "Jane Doe");
        assertThat(draft.subject()).isEqualTo("Congratulations! Offer with ITAP");

        // The frontend posts back whatever the draft returned.
        offerLetters.email("admin", "request-1", new OfferLetterEmailRequest("jane@example.com", draft.subject(), draft.message()));

        assertThat(sent).hasSize(1);
        MimeMessage message = sent.get(0);
        message.saveChanges();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("HR ITAP");
        assertThat(from.getAddress()).isEqualTo("tardusinc27@gmail.com");
        assertThat(message.getHeader("From")[0]).isEqualTo("HR ITAP <tardusinc27@gmail.com>");
        assertThat(message.getSubject()).isEqualTo("Congratulations! Offer with ITAP");

        List<String> names = new ArrayList<>();
        attachmentNames(message, names);
        assertThat(names).containsExactlyInAnyOrder("Offer Letter - Jane Doe.pdf", "Direct Deposit Authorization.pdf",
                "I-9.pdf", "W-4 Form.pdf");
    }

    private static void attachmentNames(Part part, List<String> names) throws Exception {
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                attachmentNames(child, names);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            names.add(part.getFileName());
        }
    }
}
