package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Notification;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real notification service, resolver and JavaMail service; only the repositories and the SMTP transmission are
 * mocked. No authorized senders are configured here, so the global SMTP account (MAIL_FROM_ADDRESS) is the From address
 * of every message; the organization decides the From display name, and Reply-To is its Organization Email. Admin Email
 * is never the sender or Reply-To, and To is always the resolved recipient. (Authorized organization From addresses are covered by OrganizationSenderAddressTest.)
 */
class OrganizationEmailIdentityTest {
    private static final String SMTP_ACCOUNT = "smtp-account@example.test";

    private final List<MimeMessage> sent = new ArrayList<>();
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private InAppNotificationEmailService notifications;
    private JavaMailSenderEmailService mail;

    @BeforeEach
    void setUp() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(call -> sent.add(call.getArgument(0))).when(sender).send(any(MimeMessage.class));
        mail = new JavaMailSenderEmailService(sender);
        ReflectionTestUtils.setField(mail, "mailEnabled", true);
        ReflectionTestUtils.setField(mail, "fromAddress", SMTP_ACCOUNT);
        ReflectionTestUtils.setField(mail, "fromName", "Waypoint");
        notifications = new InAppNotificationEmailService(users, profiles, organizations, mail, new EmailTemplateService(),
                new OrganizationEmailIdentityResolver(organizations));
        ReflectionTestUtils.setField(notifications, "frontendBaseUrl", "https://app.waypoint.example");

        organization("org-itap", "ITAP", "hr@itap.example", "admin@itap.example");
        organization("org-tardus", "Tardus", "hr@tardus.example", "admin@tardus.example");
    }

    @Test
    void itapAndTardusEmployeeAndHrEmailsEachUseTheirOwnIdentityAndNeverTheOther() throws Exception {
        account("itap-emp", "org-itap", UserRole.EMPLOYEE, "emp@itap-login.example", "emp.personal@itap.example");
        account("itap-hr", "org-itap", UserRole.HR, "hr.login@itap.example", null);
        account("tardus-emp", "org-tardus", UserRole.EMPLOYEE, "emp@tardus-login.example", null);

        notifications.deliver(notification("org-itap", "itap-emp"));
        notifications.deliver(notification("org-itap", "itap-hr"));
        notifications.deliver(notification("org-tardus", "tardus-emp"));

        assertThat(sent).hasSize(3);
        assertIdentity(sent.get(0), "HR ITAP", "hr@itap.example", "emp.personal@itap.example");
        assertIdentity(sent.get(1), "HR ITAP", "hr@itap.example", "hr.login@itap.example");
        assertIdentity(sent.get(2), "HR Tardus", "hr@tardus.example", "emp@tardus-login.example");
        for (MimeMessage message : sent.subList(0, 2)) {
            assertThat((String.join("|", message.getHeader("From")) + String.join("|", message.getHeader("Reply-To"))).toLowerCase())
                    .doesNotContain("tardus").doesNotContain("admin@");
        }
    }

    @Test
    void companyAdminReceivesMailAtTheirAdminEmail_butRepliesGoToTheOrganizationEmailNotAdminEmail() throws Exception {
        account("itap-admin", "org-itap", UserRole.COMPANY_ADMIN, "admin.login@itap.example", null);

        notifications.deliver(notification("org-itap", "itap-admin"));

        assertThat(sent).hasSize(1);
        // Admin Email is the administrator's contact address, so it is the recipient; the sender stays the organization's identity.
        assertIdentity(sent.get(0), "HR ITAP", "hr@itap.example", "admin@itap.example");
        assertThat(String.join("|", sent.get(0).getHeader("From")) + String.join("|", sent.get(0).getHeader("Reply-To")))
                .doesNotContain("admin@itap.example");
    }

    @Test
    void anInvalidOrMissingOrganizationEmailFallsBackToTheGlobalSenderWithNoReplyTo_andAdminEmailIsNeverSubstituted() throws Exception {
        account("itap-emp", "org-itap", UserRole.EMPLOYEE, "emp@itap.example", null);

        organization("org-itap", "ITAP", "also bad", "admin@itap.example");
        notifications.deliver(notification("org-itap", "itap-emp"));
        organization("org-itap", "ITAP", null, "admin@itap.example");
        notifications.deliver(notification("org-itap", "itap-emp"));

        for (MimeMessage message : sent) {
            assertIdentity(message, "HR ITAP", null, "emp@itap.example");
            assertThat(String.join("|", message.getHeader("From"))).doesNotContain("admin@itap.example");
        }
        assertThat(sent).hasSize(2);
    }

    @Test
    void unknownOrganizationUsesTheGlobalSenderUnchanged() throws Exception {
        mail.sendHtmlEmail("someone@example.test", "Subject", "<p>Hi</p>", null,
                new OrganizationEmailIdentityResolver(organizations).forOrganization("missing-org"));

        MimeMessage message = sent.get(0);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(SMTP_ACCOUNT);
        assertThat(from.getPersonal()).isEqualTo("Waypoint");
        assertThat(message.getHeader("Reply-To")).isNull();
    }

    @Test
    void attachmentEmailsCarryTheSameIdentityAsPlainEmails() throws Exception {
        mail.sendHtmlEmailWithAttachments("jane@example.test", "Doc", "<p>Hi</p>",
                List.of(new EmailAttachment("a.pdf", new byte[]{1}, "application/pdf")),
                new OrganizationEmailIdentityResolver(organizations).forOrganization("org-itap"));

        assertIdentity(sent.get(0), "HR ITAP", "hr@itap.example", "jane@example.test");
    }

    @Test
    void emailsWithoutAnIdentityKeepTheGlobalSenderAndNoReplyTo() throws Exception {
        mail.sendHtmlEmail("otp@example.test", "Code", "<p>123456</p>");

        MimeMessage message = sent.get(0);
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo(SMTP_ACCOUNT);
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Waypoint");
        assertThat(message.getHeader("Reply-To")).isNull();
    }

    /** @param replyTo the expected Reply-To address, or null when no Reply-To header must be present */
    private void assertIdentity(MimeMessage message, String displayName, String replyTo, String to) throws Exception {
        message.saveChanges();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).as("no authorized sender is configured here, so From is the global SMTP account").isEqualTo(SMTP_ACCOUNT);
        assertThat(from.getPersonal()).isEqualTo(displayName);
        if (replyTo == null) {
            assertThat(message.getHeader("Reply-To")).as("no valid Organization Email or explicit reply address, so no Reply-To").isNull();
        } else {
            Address[] replyToAddresses = message.getReplyTo();
            assertThat(replyToAddresses).extracting(a -> ((InternetAddress) a).getAddress()).containsExactly(replyTo);
        }
        assertThat(message.getAllRecipients()).extracting(a -> ((InternetAddress) a).getAddress()).containsExactly(to);
    }

    private void organization(String id, String name, String organizationEmail, String adminEmail) {
        when(organizations.findById(id)).thenReturn(Optional.of(Organization.builder().id(id).organizationName(name)
                .organizationEmail(organizationEmail).adminEmail(adminEmail).build()));
    }

    private void account(String id, String orgId, UserRole role, String email, String personalEmail) {
        when(users.findById(id)).thenReturn(Optional.of(User.builder().id(id).organizationId(orgId).role(role)
                .accountStatus(AccountStatus.ACTIVE).email(email).build()));
        when(profiles.findByUserId(id)).thenReturn(personalEmail == null ? Optional.empty()
                : Optional.of(EmployeeProfile.builder().userId(id).organizationId(orgId).personalEmail(personalEmail).build()));
    }

    private Notification notification(String orgId, String userId) {
        return Notification.builder().organizationId(orgId).recipientUserId(userId)
                .notificationType(NotificationType.DOCUMENT_REQUEST_CREATED).title("New document request")
                .message("A document was requested.").actionUrl("/myhr/hr/document-requests/1").build();
    }
}
