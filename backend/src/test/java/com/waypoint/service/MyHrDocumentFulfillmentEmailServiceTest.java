package com.waypoint.service;

import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MyHrDocumentFulfillmentEmailServiceTest {
    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    @Mock OrganizationRepository organizations;
    @Mock EmailService emailService;

    private MyHrDocumentFulfillmentEmailService service() {
        return new MyHrDocumentFulfillmentEmailService(users, profiles, organizations, emailService,
                new OfferLetterEmailTemplateService());
    }

    private void itap(DocumentRequest request) {
        request.setOrganizationId("org-itap");
        when(organizations.findById("org-itap")).thenReturn(Optional.of(Organization.builder().id("org-itap")
                .organizationName("ITAP").organizationEmail("hr@itap.example").build()));
    }

    @Test void sendsTheCompletedDocumentToTheEmployeeAccountEmail() {
        MyHrDocumentFulfillmentEmailService service = service();
        DocumentRequest request = DocumentRequest.builder().id("request-1").requestedByUserId("employee")
                .documentType(RequestedDocumentType.EMPLOYMENT_LETTER).requestTitle("Employment letter").build();
        itap(request);
        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").loginId("Jane")
                .email("jane@example.com").role(UserRole.EMPLOYEE).build()));

        service.send(request, new EmailAttachment("employment-letter.pdf", new byte[]{1, 2}, "application/pdf"), null);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<EmailAttachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtmlEmailWithAttachments(eq("jane@example.com"), eq("Your Employment Letter from ITAP"),
                html.capture(), attachments.capture(), eq("HR ITAP"));
        assertThat(html.getValue()).contains("Please find your Employment Letter attached.")
                .contains("HR Team").contains("ITAP").doesNotContain("Tardus");
        assertThat(attachments.getValue()).singleElement().satisfies(attachment -> {
            assertThat(attachment.fileName()).isEqualTo("employment-letter.pdf");
            assertThat(attachment.content()).containsExactly(1, 2);
            assertThat(attachment.contentType()).isEqualTo("application/pdf");
        });
        verifyNoMoreInteractions(emailService);
    }

    @Test void skipsMissingEmployeeEmailAndSwallowsDeliveryFailures() {
        MyHrDocumentFulfillmentEmailService service = service();
        DocumentRequest request = DocumentRequest.builder().id("request-1").requestedByUserId("employee")
                .requestTitle("Employment letter").build();
        itap(request);
        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").role(UserRole.EMPLOYEE).build()));

        assertThatCode(() -> service.send(request, new EmailAttachment("letter.pdf", new byte[]{1}, "application/pdf"), null))
                .doesNotThrowAnyException();
        verifyNoInteractions(emailService);

        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").loginId("Jane")
                .email("jane@example.com").role(UserRole.EMPLOYEE).build()));
        doThrow(new IllegalStateException("Email delivery is disabled")).when(emailService)
                .sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(), anyList(), anyString());

        assertThatCode(() -> service.send(request, new EmailAttachment("letter.pdf", new byte[]{1}, "application/pdf"), null))
                .doesNotThrowAnyException();
    }

    @Test void preparesAProfessionalExperienceAndRelievingLetterEmail() {
        MyHrDocumentFulfillmentEmailService service = service();
        DocumentRequest request = DocumentRequest.builder().requestedByUserId("employee")
                .documentType(RequestedDocumentType.EXPERIENCE_LETTER).requestTitle("Experience Letter").build();
        itap(request);
        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").loginId("Jane")
                .email("jane@example.com").role(UserRole.EMPLOYEE).build()));
        when(profiles.findByUserId("employee")).thenReturn(Optional.of(EmployeeProfile.builder()
                .userId("employee").firstName("Lilly").name("Lilly Parker").build()));

        var draft = service.draft(request, null);

        assertThat(draft.subject()).isEqualTo("Your Experience and Relieving Letter from ITAP");
        assertThat(draft.message()).contains("Hi Lilly,").doesNotContain("Hi Jane,")
                .contains("employment experience and relieving details")
                .contains("HR Team");
    }

    @Test void preparesAProfessionalEmploymentVerificationEmail() {
        MyHrDocumentFulfillmentEmailService service = service();
        DocumentRequest request = DocumentRequest.builder().requestedByUserId("employee")
                .documentType(RequestedDocumentType.EMPLOYMENT_VERIFICATION).build();
        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").loginId("Jane")
                .email("jane@example.com").role(UserRole.EMPLOYEE).build()));

        itap(request);
        var draft = service.draft(request, null);

        assertThat(draft.subject()).isEqualTo("Your Employment Verification Letter from ITAP");
        assertThat(draft.message()).contains("employment verification purposes")
                .contains("HR Team");
    }

    @Test void preparesDocumentSpecificI983AndOtherEmailDrafts() {
        MyHrDocumentFulfillmentEmailService service = service();
        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").loginId("Jane")
                .email("jane@example.com").role(UserRole.EMPLOYEE).build()));

        DocumentRequest i983Request = DocumentRequest.builder().requestedByUserId("employee")
                .documentType(RequestedDocumentType.I983).build();
        DocumentRequest otherRequest = DocumentRequest.builder().requestedByUserId("employee")
                .documentType(RequestedDocumentType.OTHER).requestTitle("Immigration Support Letter").build();
        itap(i983Request);
        itap(otherRequest);
        var i983 = service.draft(i983Request, null);
        var other = service.draft(otherRequest, null);

        assertThat(i983.subject()).isEqualTo("Your Form I-983 from ITAP");
        assertThat(i983.message()).contains("Form I-983");
        assertThat(other.subject()).isEqualTo("Your Immigration Support Letter from ITAP");
        assertThat(other.message()).contains("Immigration Support Letter").contains("HR Team");
    }

    @Test void doesNotUseTheEmployeeLoginIdWhenProfileNameIsUnavailable() {
        MyHrDocumentFulfillmentEmailService service = service();
        DocumentRequest request = DocumentRequest.builder().requestedByUserId("employee")
                .documentType(RequestedDocumentType.EMPLOYMENT_LETTER).build();
        itap(request);
        when(users.findById("employee")).thenReturn(Optional.of(User.builder().id("employee").loginId("TD002")
                .email("lilly@example.com").role(UserRole.EMPLOYEE).build()));
        when(profiles.findByUserId("employee")).thenReturn(Optional.empty());

        var draft = service.draft(request, null);

        assertThat(draft.message()).contains("Hi there,").doesNotContain("TD002");
    }
}
