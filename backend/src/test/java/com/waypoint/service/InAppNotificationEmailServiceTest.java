package com.waypoint.service;

import com.waypoint.entity.Notification;
import com.waypoint.entity.User;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.AccountStatus;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.enums.UserRole;
import com.waypoint.entity.Organization;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InAppNotificationEmailServiceTest {

    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    @Mock OrganizationRepository organizations;
    @Mock EmailService emailService;
    private InAppNotificationEmailService service;

    @BeforeEach
    void setUp() {
        service = new InAppNotificationEmailService(users, profiles, organizations, emailService, new EmailTemplateService());
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://app.waypoint.example");
    }

    @Test
    void deliversDocumentNotificationWithMatchingMessageAndActionLink() {
        Notification notification = notification(NotificationType.DOCUMENT_REQUEST_CREATED, "/myhr/hr/document-requests/request-1");
        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").accountStatus(AccountStatus.ACTIVE).organizationId("org-1")
                .email("lilly@example.com").build()));

        service.deliver(notification);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("lilly@example.com"), eq("Waypoint: New document request"), body.capture());
        assertThat(body.getValue()).contains("Lilly requested an Offer Letter.")
                .contains("View document request")
                .contains("https://app.waypoint.example/myhr/hr/document-requests/request-1");
    }

    @Test
    void skipsDeliveryForTenantMismatchOrNoRecipientEmail() {
        Notification notification = notification(NotificationType.TIMESHEET_APPROVED, "/myhr/employee/timesheets");
        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").accountStatus(AccountStatus.ACTIVE).organizationId("other-org")
                .email("lilly@example.com").build()));

        service.deliver(notification);
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());

        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").accountStatus(AccountStatus.ACTIVE).organizationId("org-1").email(" ").build()));
        service.deliver(notification);
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void unsafeActionUrlOmitsTheCtaAndDeliveryFailureIsContained() {
        Notification notification = notification(NotificationType.PROFILE_CHANGE_APPROVED, "//untrusted.example/path");
        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").accountStatus(AccountStatus.ACTIVE).organizationId("org-1")
                .email("lilly@example.com").build()));

        service.deliver(notification);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("lilly@example.com"), anyString(), body.capture());
        assertThat(body.getValue()).doesNotContain("View profile change", "untrusted.example");

        doThrow(new IllegalStateException("smtp unavailable")).when(emailService)
                .sendHtmlEmail(eq("lilly@example.com"), anyString(), anyString());
        service.deliver(notification);
    }

    private Notification notification(NotificationType type, String actionUrl) {
        return Notification.builder().organizationId("org-1").recipientUserId("user-1").notificationType(type)
                .title("New document request").message("Lilly requested an Offer Letter.").actionUrl(actionUrl).build();
    }

    @Test
    void prefersThePersonalEmailThenTheAccountEmailAndSkipsInactiveUsers() {
        Notification notification = notification(NotificationType.PROFILE_CHANGE_APPROVED, "/myhr/employee/profile-change-requests");
        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").accountStatus(AccountStatus.ACTIVE)
                .organizationId("org-1").email("login@example.com").build()));
        when(profiles.findByUserId("user-1")).thenReturn(Optional.of(EmployeeProfile.builder().userId("user-1")
                .organizationId("org-1").personalEmail("personal@example.com").workEmail("work@example.com").build()));

        service.deliver(notification);
        verify(emailService).sendHtmlEmail(eq("personal@example.com"), anyString(), anyString());

        when(profiles.findByUserId("user-1")).thenReturn(Optional.empty());
        service.deliver(notification);
        verify(emailService).sendHtmlEmail(eq("login@example.com"), anyString(), anyString());

        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").accountStatus(AccountStatus.INACTIVE)
                .organizationId("org-1").email("login@example.com").build()));
        service.deliver(notification);
        verify(emailService, org.mockito.Mockito.times(1)).sendHtmlEmail(eq("login@example.com"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("work@example.com"), anyString(), anyString());
    }

    private User admin(String accountEmail) {
        return User.builder().id("user-1").role(UserRole.COMPANY_ADMIN).accountStatus(AccountStatus.ACTIVE)
                .organizationId("org-1").email(accountEmail).build();
    }

    @Test
    void companyAdminNotificationsGoToTheOrganizationSettingsAdminEmailThenTheAccountEmail() {
        Notification notification = notification(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED, "/myhr/admin/offer-letter-approvals/request-1");
        when(users.findById("user-1")).thenReturn(Optional.of(admin("admin@tardus.test")));
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1").adminEmail(" notify.admin@example.com ").build()));

        service.deliver(notification);
        verify(emailService).sendHtmlEmail(eq("notify.admin@example.com"), anyString(), anyString());

        // Updating the Admin Email in Organization Settings is picked up by the next notification.
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1").adminEmail("updated.admin@example.com").build()));
        service.deliver(notification);
        verify(emailService).sendHtmlEmail(eq("updated.admin@example.com"), anyString(), anyString());

        // No Admin Email configured: the login account email is the fallback, never the organization email.
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1")
                .organizationEmail("hr@tardus.test").adminEmail(" ").build()));
        service.deliver(notification);
        verify(emailService).sendHtmlEmail(eq("admin@tardus.test"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("hr@tardus.test"), anyString(), anyString());
    }

    private void givenAdminEmail(String adminEmail) {
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1").adminEmail(adminEmail).build()));
    }

    @Test
    void aMalformedAdminEmailFallsBackToTheValidAccountEmailAndNeverReceivesMail() {
        Notification notification = notification(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED, "/myhr/admin/offer-letter-approvals/request-1");
        when(users.findById("user-1")).thenReturn(Optional.of(admin("admin@tardus.test")));

        for (String malformed : new String[]{"not-an-email", "admin@", "@tardus.example", "a@b", "two@x.example,three@x.example",
                "name <name@x.example>", "spaced out@x.example"}) {
            givenAdminEmail(malformed);
            service.deliver(notification);
        }

        verify(emailService, org.mockito.Mockito.times(7)).sendHtmlEmail(eq("admin@tardus.test"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(org.mockito.ArgumentMatchers.argThat(to -> !"admin@tardus.test".equals(to)),
                anyString(), anyString());
    }

    @Test
    void aValidAdminEmailIsTrimmedAndUsedExactlyOnce() {
        Notification notification = notification(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED, "/myhr/admin/offer-letter-approvals/request-1");
        when(users.findById("user-1")).thenReturn(Optional.of(admin("admin@tardus.test")));
        givenAdminEmail("  TardusInc27@gmail.com ");

        service.deliver(notification);

        verify(emailService, org.mockito.Mockito.times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("TardusInc27@gmail.com"), anyString(), anyString());
    }

    @Test
    void anAdminWithNoValidAdminEmailAndNoValidAccountEmailGetsNoEmailAndNothingThrows() {
        Notification notification = notification(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED, "/myhr/admin/offer-letter-approvals/request-1");
        when(users.findById("user-1")).thenReturn(Optional.of(admin("not-an-email")));
        givenAdminEmail("also bad");

        service.deliver(notification);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void aMissingOrganizationRecordFallsBackToTheAccountEmailAndUsesTheAdminsOwnOrganization() {
        Notification notification = notification(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED, "/myhr/admin/offer-letter-approvals/request-1");
        when(users.findById("user-1")).thenReturn(Optional.of(admin("admin@tardus.test")));
        when(organizations.findById("org-1")).thenReturn(Optional.empty());

        service.deliver(notification);

        verify(organizations).findById("org-1");
        verify(emailService).sendHtmlEmail(eq("admin@tardus.test"), anyString(), anyString());
    }

    @Test
    void aMalformedPersonalEmailFallsBackToTheAccountEmailAndMalformedBothSendNothing() {
        Notification notification = notification(NotificationType.PROFILE_CHANGE_APPROVED, "/myhr/employee/profile-change-requests");
        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").role(UserRole.EMPLOYEE)
                .accountStatus(AccountStatus.ACTIVE).organizationId("org-1").email("login@example.com").build()));
        when(profiles.findByUserId("user-1")).thenReturn(Optional.of(EmployeeProfile.builder().userId("user-1")
                .organizationId("org-1").personalEmail("nope").build()));

        service.deliver(notification);
        verify(emailService).sendHtmlEmail(eq("login@example.com"), anyString(), anyString());

        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").role(UserRole.EMPLOYEE)
                .accountStatus(AccountStatus.ACTIVE).organizationId("org-1").email("also nope").build()));
        service.deliver(notification);
        verify(emailService, org.mockito.Mockito.times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void nonAdminRecipientsNeverUseTheOrganizationAdminEmail() {
        Notification notification = notification(NotificationType.PROFILE_CHANGE_APPROVED, "/myhr/employee/profile-change-requests");
        when(users.findById("user-1")).thenReturn(Optional.of(User.builder().id("user-1").role(UserRole.HR)
                .accountStatus(AccountStatus.ACTIVE).organizationId("org-1").email("hr-login@example.com").build()));

        service.deliver(notification);

        verify(emailService).sendHtmlEmail(eq("hr-login@example.com"), anyString(), anyString());
        verify(organizations, never()).findById(anyString());
    }
}
