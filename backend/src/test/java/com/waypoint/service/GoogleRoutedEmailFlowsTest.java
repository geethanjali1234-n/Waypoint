package com.waypoint.service;

import com.waypoint.config.DocumentRequestReminderProperties;
import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.EmployeeDocumentRequestCreateRequest;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Notification;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.DocumentRequestRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.NotificationRepository;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.TokenCipher;
import com.waypoint.service.google.GmailApiEmailService;
import com.waypoint.service.google.GoogleConnectionProblemNotifier;
import com.waypoint.service.google.GoogleConnectionReauthRequiredEvent;
import com.waypoint.service.google.GoogleOAuthClient;
import com.waypoint.service.google.GoogleOAuthException;
import com.waypoint.service.google.GoogleSenderEligibility;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The real notification flows end to end: DocumentRequestService / InAppNotificationService / NotificationOrchestrator
 * -> InAppNotificationEmailService -> OrganizationEmailIdentityResolver + GoogleSenderEligibility -> RoutingEmailService
 * -> GmailApiEmailService (or the real JavaMailSenderEmailService). Only the repositories and the two network
 * transports (Google, SMTP) are mocked, so nothing is sent.
 */
class GoogleRoutedEmailFlowsTest {

    private static final String SMTP_ACCOUNT = "smtp-account@example.test";

    private final List<MimeMessage> smtpSent = new ArrayList<>();
    private final Map<String, OrganizationGoogleConnection> connectionStore = new HashMap<>();
    private final Map<String, Organization> organizationStore = new HashMap<>();
    private final DocumentRequestRepository requests = mock(DocumentRequestRepository.class);
    private final ReportingAssignmentRepository assignments = mock(ReportingAssignmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationGoogleConnectionRepository connections = mock(OrganizationGoogleConnectionRepository.class);
    private final MyHrAuthenticatedUserService authenticated = mock(MyHrAuthenticatedUserService.class);
    private final NotificationRepository notificationStore = mock(NotificationRepository.class);
    private final GoogleOAuthClient google = mock(GoogleOAuthClient.class);
    private final JavaMailSender smtpTransport = mock(JavaMailSender.class);
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private final TokenCipher cipher = new TokenCipher(randomKey(), "v1");

    private RoutingEmailService router;
    private InAppNotificationEmailService emailDelivery;
    private NotificationOrchestrator orchestrator;
    private InAppNotificationService inApp;

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");

        when(smtpTransport.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(call -> smtpSent.add(call.getArgument(0))).when(smtpTransport).send(any(MimeMessage.class));
        JavaMailSenderEmailService smtp = new JavaMailSenderEmailService(smtpTransport);
        ReflectionTestUtils.setField(smtp, "mailEnabled", true);
        ReflectionTestUtils.setField(smtp, "fromAddress", SMTP_ACCOUNT);
        ReflectionTestUtils.setField(smtp, "fromName", "Waypoint");

        when(connections.findByOrganizationId(anyString())).thenAnswer(call -> Optional.ofNullable(connectionStore.get(call.<String>getArgument(0))));
        when(connections.save(any(OrganizationGoogleConnection.class))).thenAnswer(call -> call.getArgument(0));
        when(organizations.findById(anyString())).thenAnswer(call -> Optional.ofNullable(organizationStore.get(call.<String>getArgument(0))));
        when(google.refreshAccessToken(anyString())).thenAnswer(call -> new GoogleOAuthClient.AccessToken("access-for-" + call.getArgument(0), 3599));

        GmailApiEmailService gmail = new GmailApiEmailService(properties, cipher, google, connections, organizations,
                mock(org.springframework.context.ApplicationEventPublisher.class));
        router = new RoutingEmailService(smtp, gmail);
        ReflectionTestUtils.setField(router, "mailEnabled", true);

        OrganizationEmailIdentityResolver resolver = new OrganizationEmailIdentityResolver(organizations, MailSenderAuthorization.none(),
                new GoogleSenderEligibility(properties, connections));
        emailDelivery = new InAppNotificationEmailService(users, profiles, organizations, router, new EmailTemplateService(), resolver);
        ReflectionTestUtils.setField(emailDelivery, "frontendBaseUrl", "https://app.waypoint.example");
        when(notificationStore.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
        inApp = new InAppNotificationService(notificationStore, emailDelivery);
        orchestrator = new NotificationOrchestrator(inApp, emailDelivery);

        when(requests.save(any())).thenAnswer(call -> {
            DocumentRequest request = call.getArgument(0);
            if (request.getId() == null) request.setId("request-1");
            return request;
        });
        when(profiles.findByOrganizationId(anyString())).thenReturn(List.of());
        when(users.findByIdIn(any())).thenReturn(List.of());

        organization("org-itap", "ITAP", "hr@itap.example");
        organization("org-abc", "ABC Technologies", "hr@abc.example");
        connect("org-itap", "admin@itap.example", "refresh-itap");
        connect("org-abc", "ops@abc.example", "refresh-abc");
        people("org-itap");
        people("org-abc");
    }

    // ------------------------------------------------------------------ fixtures

    private void organization(String id, String name, String organizationEmail) {
        organizationStore.put(id, Organization.builder().id(id).organizationName(name).organizationEmail(organizationEmail)
                .adminEmail("admin-contact@" + id + ".example").build());
    }

    private void connect(String orgId, String account, String refreshToken) {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(refreshToken, orgId);
        connectionStore.put(orgId, OrganizationGoogleConnection.builder().id("c-" + orgId).organizationId(orgId)
                .status(GoogleConnectionStatus.CONNECTED).googleAccountEmail(account).googleSubject("sub-" + orgId)
                .refreshTokenCiphertext(encrypted.ciphertext()).refreshTokenIv(encrypted.iv()).encryptionKeyId(encrypted.keyId()).build());
    }

    /** An employee and their HR manager in the organization; the manager receives employee document requests. */
    private void people(String orgId) {
        String employee = "emp-" + orgId;
        String manager = "hr-" + orgId;
        when(authenticated.require(eq(employee), anySet(), eq(false))).thenReturn(
                new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(employee, employee, employee + "@login.example",
                        UserRole.EMPLOYEE, orgId, AccountStatus.ACTIVE, false));
        when(assignments.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(employee, RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId(orgId).employeeUserId(employee)
                        .reportingManagerUserId(manager).relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER).isActive(true).build()));
        when(users.findById(manager)).thenReturn(Optional.of(User.builder().id(manager).loginId(manager).organizationId(orgId)
                .role(UserRole.HR).email(manager + "@login.example").accountStatus(AccountStatus.ACTIVE).build()));
        when(users.findById(employee)).thenReturn(Optional.of(User.builder().id(employee).loginId(employee).organizationId(orgId)
                .role(UserRole.EMPLOYEE).email(employee + "@login.example").accountStatus(AccountStatus.ACTIVE).build()));
        when(profiles.findByUserId(manager)).thenReturn(Optional.of(EmployeeProfile.builder().userId(manager).organizationId(orgId)
                .personalEmail(manager + "@personal.example").build()));
        when(profiles.findByUserId(employee)).thenReturn(Optional.of(EmployeeProfile.builder().userId(employee).organizationId(orgId)
                .personalEmail(employee + "@personal.example").build()));
    }

    private DocumentRequestService documentRequests() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DocumentService> documents = mock(ObjectProvider.class);
        return new DocumentRequestService(requests, assignments, users, profiles, authenticated, documents, mock(AuditService.class),
                orchestrator, mock(MyHrDocumentFulfillmentEmailService.class), mock(MongoTemplate.class),
                Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC), new DocumentRequestReminderProperties());
    }

    private void employeeRequestsDocument(String orgId) {
        documentRequests().createSelfServiceRequest("emp-" + orgId,
                new EmployeeDocumentRequestCreateRequest("EMPLOYMENT_LETTER", "Employment letter", "Needed", null), List.of());
    }

    private MimeMessage googleMessage(int index) throws Exception {
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(google, org.mockito.Mockito.atLeastOnce()).sendMessage(anyString(), raw.capture());
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw.getAllValues().get(index))));
    }

    private static String fromAddress(MimeMessage message) throws Exception {
        message.saveChanges();
        return ((InternetAddress) message.getFrom()[0]).getAddress();
    }

    private static String fromName(MimeMessage message) throws Exception {
        return ((InternetAddress) message.getFrom()[0]).getPersonal();
    }

    // ------------------------------------------------------------------ new employee document request

    @Test
    void itapsNewEmployeeDocumentRequestEmailGoesThroughItsOwnGoogleAccount() throws Exception {
        employeeRequestsDocument("org-itap");

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(google, times(1)).sendMessage(token.capture(), anyString());
        assertThat(token.getValue()).isEqualTo("access-for-refresh-itap");
        MimeMessage message = googleMessage(0);
        assertThat(message.getSubject()).isEqualTo("New employee document request");
        assertThat(fromAddress(message)).isEqualTo("admin@itap.example");
        assertThat(fromName(message)).isEqualTo("HR ITAP");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("hr@itap.example");
        assertThat(message.getAllRecipients()).extracting(a -> ((InternetAddress) a).getAddress()).containsExactly("hr-org-itap@personal.example");
        verify(smtpTransport, never()).send(any(MimeMessage.class));
    }

    @Test
    void abcsRequestUsesAbcsAccountAndNeverItapsEvenWhenBothSendInTheSameRun() throws Exception {
        employeeRequestsDocument("org-itap");
        employeeRequestsDocument("org-abc");

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(google, times(2)).sendMessage(tokens.capture(), anyString());
        assertThat(tokens.getAllValues()).containsExactly("access-for-refresh-itap", "access-for-refresh-abc");
        assertThat(fromAddress(googleMessage(0))).isEqualTo("admin@itap.example");
        assertThat(fromAddress(googleMessage(1))).isEqualTo("ops@abc.example");
        verify(google, never()).refreshAccessToken(eq("refresh-unknown"));
        verify(smtpTransport, never()).send(any(MimeMessage.class));
    }

    @Test
    void anOrganizationWithoutAConnectionKeepsUsingSmtpExactlyAsBefore() throws Exception {
        organization("org-new", "New Co", "hr@newco.example");
        people("org-new");

        employeeRequestsDocument("org-new");

        verifyNoInteractions(google);
        assertThat(smtpSent).hasSize(1);
        assertThat(fromAddress(smtpSent.get(0))).isEqualTo(SMTP_ACCOUNT);
        assertThat(fromName(smtpSent.get(0))).isEqualTo("HR New Co");
        assertThat(((InternetAddress) smtpSent.get(0).getReplyTo()[0]).getAddress()).isEqualTo("hr@newco.example");
    }

    @Test
    void aDisconnectedAccountFallsBackToSmtpWithoutTouchingGoogle() throws Exception {
        connectionStore.get("org-itap").setStatus(GoogleConnectionStatus.DISCONNECTED);
        connectionStore.get("org-itap").setRefreshTokenCiphertext(null);

        employeeRequestsDocument("org-itap");

        verifyNoInteractions(google);
        assertThat(smtpSent).hasSize(1);
        assertThat(fromAddress(smtpSent.get(0))).isEqualTo(SMTP_ACCOUNT);
    }

    @Test
    void revokedCredentialsFallBackToSmtpOnceAndFlagTheConnectionWithoutTouchingOtherTenants() throws Exception {
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));

        employeeRequestsDocument("org-itap");

        assertThat(smtpSent).hasSize(1);
        assertThat(fromAddress(smtpSent.get(0))).isEqualTo(SMTP_ACCOUNT);
        assertThat(fromName(smtpSent.get(0))).isEqualTo("HR ITAP");
        assertThat(connectionStore.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.REAUTH_REQUIRED);
        assertThat(connectionStore.get("org-abc").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
        verify(google, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void aGmailApiRejectionFallsBackToSmtpAndTheEmployeeStillGetsExactlyOneEmail() throws Exception {
        doThrow(new GoogleOAuthException("HTTP_403")).when(google).sendMessage(anyString(), anyString());

        employeeRequestsDocument("org-itap");

        verify(google, times(1)).sendMessage(anyString(), anyString());
        assertThat(smtpSent).hasSize(1);
        assertThat(smtpSent.get(0).getSubject()).isEqualTo("New employee document request");
        assertThat(fromAddress(smtpSent.get(0))).isEqualTo(SMTP_ACCOUNT);
    }

    @Test
    void whenGmailGivesNoAnswerTheEmailIsNotResentAndTheRequestStillSucceeds() {
        doThrow(new GoogleOAuthException(GoogleOAuthClient.NO_RESPONSE)).when(google).sendMessage(anyString(), anyString());

        employeeRequestsDocument("org-itap");

        assertThat(smtpSent).isEmpty();
        verify(requests, org.mockito.Mockito.atLeastOnce()).save(any(DocumentRequest.class));
        verify(notificationStore).save(any(Notification.class));
    }

    @Test
    void theDomainBeingChangedAfterConnectingStopsGoogleSendingImmediately() throws Exception {
        organizationStore.get("org-itap").setOrganizationEmail("hr@another-domain.example");

        employeeRequestsDocument("org-itap");

        verifyNoInteractions(google);
        assertThat(smtpSent).hasSize(1);
    }

    // ------------------------------------------------------------------ other existing flows

    @Test
    void timesheetApprovalAndRejectionEmailsGoThroughTheEmployeesOrganizationAccount() throws Exception {
        inApp.createNotification("org-itap", "emp-org-itap", NotificationType.TIMESHEET_REJECTED, "Timesheet rejected",
                "Your timesheet was rejected. Reason: Hours mismatch.", "ts-1", "/myhr/employee/timesheets?timesheetId=ts-1");
        inApp.createNotification("org-abc", "emp-org-abc", NotificationType.TIMESHEET_APPROVED, "Timesheet approved",
                "Your timesheet was approved.", "ts-2", "/myhr/employee/timesheets?timesheetId=ts-2");

        verify(notificationStore, times(2)).save(any(Notification.class)); // the in-app part is unchanged
        assertThat(fromAddress(googleMessage(0))).isEqualTo("admin@itap.example");
        assertThat(googleMessage(0).getSubject()).isEqualTo("Waypoint: Timesheet rejected");
        assertThat(fromAddress(googleMessage(1))).isEqualTo("ops@abc.example");
        assertThat(googleMessage(1).getSubject()).isEqualTo("Waypoint: Timesheet approved");
        verify(smtpTransport, never()).send(any(MimeMessage.class));
    }

    @Test
    void documentRemindersGoThroughTheRecipientsOrganizationAccount() throws Exception {
        orchestrator.notify(NotificationEvent.builder().organizationId("org-abc").recipientUserId("emp-org-abc")
                .type(NotificationType.DOCUMENT_REQUEST_REMINDER).title("Document due in 3 days")
                .message("Please upload the requested document.").relatedEntityId("request-9")
                .actionPath("/myhr/employee/document-requests/request-9").email(true).actionLabel("Open Request").build());

        verify(google, times(1)).sendMessage(eq("access-for-refresh-abc"), anyString());
        assertThat(fromAddress(googleMessage(0))).isEqualTo("ops@abc.example");
        assertThat(googleMessage(0).getSubject()).isEqualTo("Document due in 3 days");
        verify(notificationStore).save(any(Notification.class));
    }

    @Test
    void aNotificationNeverUsesTheAccountOfAnotherOrganizationEvenForAMismatchedRecipient() {
        // The event says ITAP, the recipient is ABC's employee: the existing tenant check refuses before any send.
        inApp.createNotification("org-itap", "emp-org-abc", NotificationType.TIMESHEET_REJECTED, "Timesheet rejected",
                "Reason.", "ts-3", "/myhr/employee/timesheets");

        verifyNoInteractions(google);
        assertThat(smtpSent).isEmpty();
    }

    // ------------------------------------------------------------------ platform emails and the flag

    @Test
    void platformEmailsLikeRegistrationOtpStillUseTheGlobalSenderEvenWhenOrganizationsAreConnected() throws Exception {
        router.sendHtmlEmail("new.admin@company.example", "Your verification code", "<p>123456</p>");

        verifyNoInteractions(google);
        assertThat(smtpSent).hasSize(1);
        assertThat(fromAddress(smtpSent.get(0))).isEqualTo(SMTP_ACCOUNT);
        assertThat(fromName(smtpSent.get(0))).isEqualTo("Waypoint");
        assertThat(smtpSent.get(0).getHeader("Reply-To")).isNull();
    }

    @Test
    void withTheFeatureFlagOffEveryOrganizationStillUsesSmtpEvenIfConnectionRowsExist() throws Exception {
        properties.setEnabled(false);

        employeeRequestsDocument("org-itap");
        employeeRequestsDocument("org-abc");

        verifyNoInteractions(google);
        assertThat(smtpSent).hasSize(2);
        assertThat(smtpSent).allSatisfy(message -> assertThat(fromAddress(message)).isEqualTo(SMTP_ACCOUNT));
    }

    @Test
    void whileEmailDeliveryIsDisabledNothingIsSentAnywhere() {
        ReflectionTestUtils.setField(router, "mailEnabled", false);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(router, "smtp"), "mailEnabled", false);

        employeeRequestsDocument("org-itap");

        verifyNoInteractions(google);
        assertThat(smtpSent).isEmpty();
    }

    // ------------------------------------------------------------------ Phase 5: reconnect notification, end to end

    private GmailApiEmailService gmailWiredToNotifier() {
        GoogleConnectionProblemNotifier notifier = new GoogleConnectionProblemNotifier(users, inApp);
        org.springframework.context.ApplicationEventPublisher publisher = event -> {
            if (event instanceof GoogleConnectionReauthRequiredEvent reauth) notifier.onReauthRequired(reauth);
        };
        return new GmailApiEmailService(properties, cipher, google, connections, organizations, publisher);
    }

    private void rewire(GmailApiEmailService gmail) {
        JavaMailSenderEmailService smtp = (JavaMailSenderEmailService) ReflectionTestUtils.getField(router, "smtp");
        router = new RoutingEmailService(smtp, gmail);
        ReflectionTestUtils.setField(router, "mailEnabled", true);
        OrganizationEmailIdentityResolver resolver = new OrganizationEmailIdentityResolver(organizations, MailSenderAuthorization.none(),
                new GoogleSenderEligibility(properties, connections));
        emailDelivery = new InAppNotificationEmailService(users, profiles, organizations, router, new EmailTemplateService(), resolver);
        ReflectionTestUtils.setField(emailDelivery, "frontendBaseUrl", "https://app.waypoint.example");
        inApp = new InAppNotificationService(notificationStore, emailDelivery);
        orchestrator = new NotificationOrchestrator(inApp, emailDelivery);
    }

    private void companyAdmin(String orgId, String adminId) {
        when(users.findByOrganizationIdAndRoleAndAccountStatus(orgId, UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)).thenReturn(List.of(
                User.builder().id(adminId).loginId(adminId).organizationId(orgId).role(UserRole.COMPANY_ADMIN)
                        .email(adminId + "@login.example").accountStatus(AccountStatus.ACTIVE).build()));
        when(users.findById(adminId)).thenReturn(Optional.of(User.builder().id(adminId).loginId(adminId).organizationId(orgId)
                .role(UserRole.COMPANY_ADMIN).email(adminId + "@login.example").accountStatus(AccountStatus.ACTIVE).build()));
    }

    @Test
    void revokedAccessNotifiesOnlyThatOrganizationsAdminOnceAndNeverLoopsThroughGoogleAgain() throws Exception {
        rewire(gmailWiredToNotifier());
        companyAdmin("org-itap", "itap-admin");
        companyAdmin("org-abc", "abc-admin");
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));

        employeeRequestsDocument("org-itap");
        employeeRequestsDocument("org-itap"); // a second message after the connection was flagged

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationStore, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        List<Notification> reconnect = saved.getAllValues().stream()
                .filter(n -> "Reconnect your Google email account".equals(n.getTitle())).toList();
        assertThat(reconnect).hasSize(1);
        assertThat(reconnect.get(0).getRecipientUserId()).isEqualTo("itap-admin");
        assertThat(reconnect.get(0).getOrganizationId()).isEqualTo("org-itap");
        assertThat(reconnect.get(0).getMessage()).doesNotContain("refresh").doesNotContain("INVALID_GRANT");
        // The admin is also emailed, and by SMTP: the connection is already flagged, so Google is not tried again.
        verify(google, times(1)).refreshAccessToken("refresh-itap");
        verify(google, never()).refreshAccessToken("refresh-abc");
        assertThat(smtpSent.stream().map(m -> {
            try { return m.getSubject(); } catch (Exception e) { return ""; }
        }).filter(subject -> subject.contains("Reconnect your Google email account"))).hasSize(1);
        assertThat(connectionStore.get("org-abc").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }
}
