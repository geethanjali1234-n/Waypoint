package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Organization sender addresses end to end: database organization -> resolver -> authorization -> real MIME message.
 * Only the repositories and the SMTP transmission are mocked, so nothing is sent. The From address is the
 * organization's own Organization Email only when the provider-authorized sender list allows it; every other case is
 * sent from the global SMTP account.
 */
class OrganizationSenderAddressTest {
    private static final String SMTP_ACCOUNT = "smtp-account@example.test";

    private final List<MimeMessage> sent = new ArrayList<>();
    private final Map<String, Organization> database = new HashMap<>();
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
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
        when(organizations.findById(anyString())).thenAnswer(call -> Optional.ofNullable(database.get(call.<String>getArgument(0))));

        organization("org-itap", "ITAP", "hr@itap.example", "admin@itap.example");
        organization("org-tardus", "Tardus", "hr@tardus.example", "admin@tardus.example");

        logs.start();
        ((Logger) LoggerFactory.getLogger(OrganizationEmailIdentityResolver.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(OrganizationEmailIdentityResolver.class)).detachAppender(logs);
    }

    @Test
    void authorizedOrganizationEmailBecomesTheActualFromAddress_withItsOwnDisplayNameAndReplyTo() throws Exception {
        sendNotification("org-itap", "itap-user");

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertThat(replyTo(sent.get(0))).as("Reply-To defaults to the Organization Email, never Admin Email").isEqualTo("hr@itap.example");
        assertThat(headers(sent.get(0))).doesNotContain("admin@itap.example");
    }

    @Test
    void eachOrganizationResolvesItsOwnIdentity_andNeverTheOthers() throws Exception {
        MailSenderAuthorization both = new MailSenderAuthorization("itap.example, tardus.example");
        send("org-itap", both);
        send("org-tardus", both);

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertFrom(sent.get(1), "hr@tardus.example", "HR Tardus");
        assertThat(replyTo(sent.get(0))).isEqualTo("hr@itap.example");
        assertThat(replyTo(sent.get(1))).isEqualTo("hr@tardus.example");
        assertThat(headers(sent.get(0)).toLowerCase()).doesNotContain("tardus");
        assertThat(headers(sent.get(1)).toLowerCase()).doesNotContain("itap");
    }

    @Test
    void onlyTheAuthorizedOrganizationUsesItsOwnAddress_otherOrganizationsStayOnTheGlobalAccount() throws Exception {
        MailSenderAuthorization onlyItap = new MailSenderAuthorization("itap.example");
        send("org-itap", onlyItap);
        send("org-tardus", onlyItap);

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertFrom(sent.get(1), SMTP_ACCOUNT, "HR Tardus");
        // From fell back to the global account, but replies still go to the organization, not to the global mailbox or Admin Email.
        assertThat(replyTo(sent.get(1))).isEqualTo("hr@tardus.example");
        assertThat(headers(sent.get(1))).doesNotContain("admin@tardus.example");
    }

    @Test
    void changingAdminEmailNeverChangesTheSender_butChangingOrganizationEmailChangesTheNextAuthorizedMessage() throws Exception {
        MailSenderAuthorization authorization = new MailSenderAuthorization("itap.example");
        send("org-itap", authorization);

        database.get("org-itap").setAdminEmail("someone.else@itap.example");
        send("org-itap", authorization);

        database.get("org-itap").setOrganizationEmail("people@itap.example");
        send("org-itap", authorization);

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertFrom(sent.get(1), "hr@itap.example", "HR ITAP");     // Admin Email changed: sender unchanged
        assertFrom(sent.get(2), "people@itap.example", "HR ITAP"); // Organization Email changed: sender follows it
        assertThat(replyTo(sent.get(0))).isEqualTo("hr@itap.example");
        assertThat(replyTo(sent.get(1))).isEqualTo("hr@itap.example");      // Admin Email changed: Reply-To unchanged
        assertThat(replyTo(sent.get(2))).isEqualTo("people@itap.example");  // Organization Email changed: Reply-To follows it
        assertThat(sent).allSatisfy(message ->
                assertThat(headers(message)).doesNotContain("admin@itap.example").doesNotContain("someone.else@itap.example"));
    }

    @Test
    void unverifiedOrUnconfiguredSendersFallBackToTheGlobalAccount_butRepliesStillGoToTheOrganizationEmail() throws Exception {
        send("org-itap", new MailSenderAuthorization("other.example"));   // valid address, not authorized
        send("org-itap", MailSenderAuthorization.none());                 // nothing authorized (default)

        assertThat(sent).hasSize(2).allSatisfy(message -> {
            assertFrom(message, SMTP_ACCOUNT, "HR ITAP");
            assertThat(replyTo(message)).isEqualTo("hr@itap.example");
            assertThat(headers(message)).doesNotContain("admin@itap.example");
        });
    }

    @Test
    void missingOrInvalidOrganizationEmailFallsBackToTheGlobalAccountWithNoReplyTo_andAdminEmailIsNeverSubstituted() throws Exception {
        database.get("org-itap").setOrganizationEmail("not-an-email");
        send("org-itap", new MailSenderAuthorization("itap.example"));

        database.get("org-itap").setOrganizationEmail(null);
        send("org-itap", new MailSenderAuthorization("itap.example"));

        assertThat(sent).hasSize(2).allSatisfy(message -> {
            assertFrom(message, SMTP_ACCOUNT, "HR ITAP");
            assertThat(replyTo(message)).as("no valid Organization Email, so no Reply-To").isNull();
            assertThat(headers(message)).doesNotContain("admin@itap.example");
        });
    }

    @Test
    void authorizationMatchesExactDomainsAndAddresses_notSubdomainsOrLookalikes() {
        MailSenderAuthorization authorization = new MailSenderAuthorization(" ITAP.example , ceo@solo.example ,");

        assertThat(authorization.authorizes("hr@itap.example")).isTrue();
        assertThat(authorization.authorizes("HR@ITAP.EXAMPLE")).isTrue();
        assertThat(authorization.authorizes("ceo@solo.example")).isTrue();
        assertThat(authorization.authorizes("other@solo.example")).isFalse();
        assertThat(authorization.authorizes("hr@mail.itap.example")).isFalse();
        assertThat(authorization.authorizes("hr@evilitap.example")).isFalse();
        assertThat(authorization.authorizes("hr@itap.example.evil.test")).isFalse();
        assertThat(authorization.authorizes(null)).isFalse();
        assertThat(MailSenderAuthorization.none().authorizes("hr@itap.example")).isFalse();
    }

    @Test
    void aValidExplicitReplyToOverridesTheOrganizationEmail_independentOfFrom() throws Exception {
        database.get("org-itap").setReplyToEmail("support@itap.example");
        send("org-itap", new MailSenderAuthorization("itap.example"));   // authorized From
        send("org-itap", MailSenderAuthorization.none());                // global From

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertFrom(sent.get(1), SMTP_ACCOUNT, "HR ITAP");
        assertThat(replyTo(sent.get(0))).isEqualTo("support@itap.example");
        assertThat(replyTo(sent.get(1))).isEqualTo("support@itap.example");
        assertThat(headers(sent.get(0))).doesNotContain("admin@itap.example");
    }

    @Test
    void anInvalidExplicitReplyToIsIgnoredWithAWarning_theOrganizationEmailIsUsed_andAdminEmailIsNot() throws Exception {
        database.get("org-itap").setReplyToEmail("not an address");
        send("org-itap", new MailSenderAuthorization("itap.example"));

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertThat(replyTo(sent.get(0))).isEqualTo("hr@itap.example");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("CONFIGURED_REPLY_TO_INVALID"))
                .noneMatch(message -> message.contains("not an address") || message.contains("itap.example"));
    }

    @Test
    void unknownOrganizationAndEmailsWithoutOrganizationContextKeepTheGlobalIdentity() throws Exception {
        OrganizationEmailIdentityResolver resolver =
                new OrganizationEmailIdentityResolver(organizations, new MailSenderAuthorization("itap.example"));
        mail.sendHtmlEmail("someone@example.test", "Unknown org", "<p>x</p>", null, resolver.forOrganization("org-missing"));
        // OTP, Company Admin registration and job-seeker/employer notifications use the three-argument send.
        mail.sendHtmlEmail("someone@example.test", "Verification code", "<p>123456</p>");

        assertThat(sent).hasSize(2).allSatisfy(message -> {
            assertFrom(message, SMTP_ACCOUNT, "Waypoint");
            assertThat(message.getHeader("Reply-To")).isNull();
        });
    }

    @Test
    void attachmentEmailsUseTheSameAuthorizedFromAddress() throws Exception {
        EmailSenderIdentity identity = new OrganizationEmailIdentityResolver(
                organizations, new MailSenderAuthorization("itap.example")).forOrganization("org-itap");
        mail.sendHtmlEmailWithAttachments("jane@example.test", "Offer", "<p>x</p>",
                List.of(new EmailAttachment("offer.pdf", new byte[] {1}, "application/pdf")), identity);

        assertFrom(sent.get(0), "hr@itap.example", "HR ITAP");
        assertThat(replyTo(sent.get(0))).isEqualTo("hr@itap.example");
    }

    @Test
    void noConfiguredSenders_logsOneInfoPerOrganization_withoutAddressesAndNotPerEmail() {
        OrganizationEmailIdentityResolver resolver =
                new OrganizationEmailIdentityResolver(organizations, MailSenderAuthorization.none());
        for (int i = 0; i < 5; i++) resolver.forOrganization("org-itap");
        resolver.forOrganization("org-tardus");
        resolver.forOrganization("org-tardus");

        assertThat(logs.list).filteredOn(event -> event.getFormattedMessage().contains("NO_AUTHORIZED_SENDERS_CONFIGURED"))
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                    assertThat(event.getFormattedMessage()).doesNotContain("@").contains("MAIL_AUTHORIZED_SENDER_DOMAINS");
                });
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(message -> message.contains("NO_AUTHORIZED_SENDERS_CONFIGURED"))
                .anyMatch(message -> message.contains("org-itap")).anyMatch(message -> message.contains("org-tardus"));
    }

    @Test
    void fallbackWarningsNeverContainAddressesOrSecrets() throws Exception {
        database.get("org-itap").setOrganizationEmail("hr@itap.example");
        send("org-itap", new MailSenderAuthorization("other.example"));
        database.get("org-itap").setOrganizationEmail("broken address");
        send("org-itap", new MailSenderAuthorization("other.example"));

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("SENDER_NOT_AUTHORIZED_BY_PROVIDER_CONFIG"))
                .anyMatch(message -> message.contains("ORGANIZATION_EMAIL_MISSING_OR_INVALID"))
                .noneMatch(message -> message.contains("itap.example") || message.contains("broken address")
                        || message.toLowerCase().contains("password"));
    }

    private void send(String organizationId, MailSenderAuthorization authorization) {
        EmailSenderIdentity identity = new OrganizationEmailIdentityResolver(organizations, authorization).forOrganization(organizationId);
        mail.sendHtmlEmail("recipient@example.test", "Subject", "<p>Body</p>", null, identity);
    }

    /** The IPAT notification path: InAppNotificationEmailService with the real resolver. */
    private void sendNotification(String organizationId, String userId) {
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).organizationId(organizationId)
                .role(UserRole.EMPLOYEE).accountStatus(AccountStatus.ACTIVE).email("emp@login.example").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(EmployeeProfile.builder().userId(userId)
                .organizationId(organizationId).personalEmail("emp.personal@example.test").build()));
        InAppNotificationEmailService notifications = new InAppNotificationEmailService(users, profiles, organizations, mail,
                new EmailTemplateService(), new OrganizationEmailIdentityResolver(organizations, new MailSenderAuthorization("itap.example")));
        ReflectionTestUtils.setField(notifications, "frontendBaseUrl", "https://app.waypoint.example");
        notifications.deliver(Notification.builder().organizationId(organizationId).recipientUserId(userId)
                .notificationType(NotificationType.DOCUMENT_REQUEST_CREATED).title("New document request")
                .message("A document was requested.").actionUrl("/myhr/employee/document-requests/1").build());
    }

    private void organization(String id, String name, String organizationEmail, String adminEmail) {
        database.put(id, Organization.builder().id(id).organizationName(name)
                .organizationEmail(organizationEmail).adminEmail(adminEmail).build());
    }

    private static void assertFrom(MimeMessage message, String address, String displayName) {
        try {
            InternetAddress from = (InternetAddress) message.getFrom()[0];
            assertThat(from.getAddress()).isEqualTo(address);
            assertThat(from.getPersonal()).isEqualTo(displayName);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    /** The Reply-To header address, or null when the message has none (getReplyTo() would fall back to From). */
    private static String replyTo(MimeMessage message) throws Exception {
        String[] header = message.getHeader("Reply-To");
        return header == null ? null : new InternetAddress(header[0]).getAddress();
    }

    private static String headers(MimeMessage message) throws Exception {
        return String.join("\n", java.util.Collections.list(message.getAllHeaderLines()));
    }
}
