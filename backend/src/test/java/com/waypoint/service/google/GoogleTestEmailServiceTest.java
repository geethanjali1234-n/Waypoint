package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.GoogleTestEmailResponse;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.service.AuditService;
import com.waypoint.service.EmailAttachment;
import com.waypoint.service.EmailSenderIdentity;
import com.waypoint.service.EmailService;
import com.waypoint.service.EmailTemplateService;
import com.waypoint.service.MailSenderAuthorization;
import com.waypoint.service.OrganizationEmailIdentityResolver;
import com.waypoint.service.RoutingEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleTestEmailServiceTest {

    private final Map<String, OrganizationGoogleConnection> connectionStore = new HashMap<>();
    private final Map<String, Organization> organizationStore = new HashMap<>();
    private final Map<String, User> userStore = new HashMap<>();
    private final OrganizationGoogleConnectionRepository connections = mock(OrganizationGoogleConnectionRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MyHrAuthenticatedUserService authenticated = mock(MyHrAuthenticatedUserService.class);
    private final GmailApiEmailService gmail = mock(GmailApiEmailService.class);
    private final AuditService audit = mock(AuditService.class);
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private GoogleTestEmailService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(connections.findByOrganizationId(anyString())).thenAnswer(call -> Optional.ofNullable(connectionStore.get(call.<String>getArgument(0))));
        when(organizations.findById(anyString())).thenAnswer(call -> Optional.ofNullable(organizationStore.get(call.<String>getArgument(0))));
        when(users.findById(anyString())).thenAnswer(call -> Optional.ofNullable(userStore.get(call.<String>getArgument(0))));

        GoogleSenderEligibility eligibility = new GoogleSenderEligibility(properties, connections);
        OrganizationEmailIdentityResolver resolver = new OrganizationEmailIdentityResolver(organizations, MailSenderAuthorization.none(), eligibility);
        service = new GoogleTestEmailService(properties, authenticated, organizations, resolver, eligibility, gmail,
                new EmailTemplateService(), new GoogleRateLimits(), audit);

        organization("org-itap", "ITAP", "hr@itap.example", "admin-contact@itap.example");
        organization("org-abc", "ABC Technologies", "hr@abc.example", "admin-contact@abc.example");
        connect("org-itap", "ops@itap.example");
        connect("org-abc", "ops@abc.example");
        admin("itap-admin", "org-itap", "jane@itap.example", true);
        admin("abc-admin", "org-abc", "sam@abc.example", true);
        when(authenticated.require(eq("employee-1"), anySet(), eq(false))).thenThrow(new ForbiddenException("You do not have access to this resource"));
    }

    private void organization(String id, String name, String organizationEmail, String adminEmail) {
        organizationStore.put(id, Organization.builder().id(id).organizationName(name).organizationEmail(organizationEmail).adminEmail(adminEmail).build());
    }

    private void connect(String orgId, String account) {
        connectionStore.put(orgId, OrganizationGoogleConnection.builder().id("c-" + orgId).organizationId(orgId)
                .status(GoogleConnectionStatus.CONNECTED).googleAccountEmail(account).googleSubject("sub-" + orgId)
                .refreshTokenCiphertext("cipher").refreshTokenIv("iv").build());
    }

    private void admin(String userId, String orgId, String email, boolean verified) {
        userStore.put(userId, User.builder().id(userId).organizationId(orgId).role(UserRole.COMPANY_ADMIN)
                .accountStatus(AccountStatus.ACTIVE).email(email).isEmailVerified(verified).build());
        when(authenticated.require(eq(userId), anySet(), eq(false))).thenReturn(new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                userId, userId, email, UserRole.COMPANY_ADMIN, orgId, AccountStatus.ACTIVE, false));
    }

    // ------------------------------------------------------------------ success

    @Test
    void sendsAFixedMessageToTheOrganizationEmailThroughTheirOrganizationsAccount() {
        GoogleTestEmailResponse response = service.sendTestEmail("itap-admin");

        assertThat(response).isEqualTo(new GoogleTestEmailResponse(true, "SENT", "hr@itap.example", "ops@itap.example"));
        ArgumentCaptor<EmailSenderIdentity> identity = ArgumentCaptor.forClass(EmailSenderIdentity.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(gmail).send(identity.capture(), eq("hr@itap.example"), eq(GoogleTestEmailService.SUBJECT), html.capture(), isNull(), eq(List.of()));
        assertThat(identity.getValue().gmailOrganizationId()).isEqualTo("org-itap");
        assertThat(identity.getValue().gmailSenderAddress()).isEqualTo("ops@itap.example");
        assertThat(identity.getValue().replyTo()).isEqualTo("hr@itap.example");
        assertThat(html.getValue()).contains("ITAP").contains("No action is needed");
        verify(audit).record(any(MyHrAuthenticatedUserService.AuthenticatedMyHrUser.class), eq(AuditActionType.GOOGLE_EMAIL_TEST_SENT),
                anyString(), eq("org-itap"), eq("ops@itap.example"), anyString(), any(), any());
    }

    @Test
    void neverUsesTheAdminEmailSettingOrTheLoginEmailAndTakesNoRecipientInput() {
        service.sendTestEmail("itap-admin");

        verify(gmail, never()).send(any(), eq("admin-contact@itap.example"), any(), any(), any(), anyList());
        verify(gmail, never()).send(any(), eq("jane@itap.example"), any(), any(), any(), anyList());
        assertThat(Arrays.stream(GoogleTestEmailService.class.getDeclaredMethods()).filter(m -> m.getName().equals("sendTestEmail")).findFirst().orElseThrow()
                .getParameterTypes()).containsExactly(String.class); // the only input is the authenticated user id
    }

    @Test
    void eachOrganizationsAdminTestsOnlyTheirOwnOrganization() {
        service.sendTestEmail("abc-admin");

        ArgumentCaptor<EmailSenderIdentity> identity = ArgumentCaptor.forClass(EmailSenderIdentity.class);
        verify(gmail).send(identity.capture(), eq("hr@abc.example"), anyString(), anyString(), isNull(), anyList());
        assertThat(identity.getValue().gmailOrganizationId()).isEqualTo("org-abc");
        assertThat(identity.getValue().gmailSenderAddress()).isEqualTo("ops@abc.example");
        assertThat(connectionStore.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }

    @Test
    void tardusLikeOrganizationSendsToItsOrganizationEmailNotToTheDifferentLoginOrAdminEmail() {
        organization("org-tardus", "Tardus", "tardus.org.example@gmail.com", "tardus.admin.example@gmail.com");
        connect("org-tardus", "tardus.org.example@gmail.com");
        admin("tardus-admin", "org-tardus", "someone.else@gmail.com", true);

        GoogleTestEmailResponse response = service.sendTestEmail("tardus-admin");

        assertThat(response).isEqualTo(new GoogleTestEmailResponse(true, "SENT", "tardus.org.example@gmail.com", "tardus.org.example@gmail.com"));
        verify(gmail).send(any(), eq("tardus.org.example@gmail.com"), anyString(), anyString(), isNull(), anyList());
        verify(gmail, never()).send(any(), eq("tardus.admin.example@gmail.com"), any(), any(), any(), anyList());
        verify(gmail, never()).send(any(), eq("someone.else@gmail.com"), any(), any(), any(), anyList());
    }

    @Test
    void itapSendsToItsOrganizationEmailEvenWhenAdminEmailAndLoginEmailMatchEachOther() {
        organization("org-itap2", "ITAP", "itap.org.example@gmail.com", "itap.admin.example@gmail.com");
        connect("org-itap2", "itap.org.example@gmail.com");
        admin("itap2-admin", "org-itap2", "itap.admin.example@gmail.com", true);

        service.sendTestEmail("itap2-admin");

        verify(gmail).send(any(), eq("itap.org.example@gmail.com"), anyString(), anyString(), isNull(), anyList());
        verify(gmail, never()).send(any(), eq("itap.admin.example@gmail.com"), any(), any(), any(), anyList());
    }

    @Test
    void replyToIsTheOrganizationEmailEvenWhenAReplyToOverrideIsConfigured() {
        organizationStore.get("org-itap").setReplyToEmail("override@elsewhere.example");

        service.sendTestEmail("itap-admin");

        ArgumentCaptor<EmailSenderIdentity> identity = ArgumentCaptor.forClass(EmailSenderIdentity.class);
        verify(gmail).send(identity.capture(), eq("hr@itap.example"), anyString(), anyString(), isNull(), anyList());
        assertThat(identity.getValue().replyTo()).isEqualTo("hr@itap.example");
    }

    @Test
    void theRecipientIsTrimmedAndFailuresCarryTheOrganizationEmailOnly() {
        organizationStore.get("org-abc").setOrganizationEmail("  hr@abc.example  ");
        assertThat(service.sendTestEmail("abc-admin").recipient()).isEqualTo("hr@abc.example");
    }

    // ------------------------------------------------------------------ no fallback, no other recipient

    @Test
    void thereIsNoSmtpPathAtAllSoAFailureIsNeverHiddenByAFallback() {
        for (Constructor<?> constructor : GoogleTestEmailService.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).doesNotContain(EmailService.class, RoutingEmailService.class,
                    org.springframework.mail.javamail.JavaMailSender.class, com.waypoint.service.JavaMailSenderEmailService.class);
        }
    }

    @Test
    void whenGoogleDoesNotSendTheResultIsAFailureWithTheReasonNotASuccess() {
        doThrow(new GmailApiEmailService.Unavailable("REAUTH_REQUIRED")).when(gmail).send(any(), any(), any(), any(), any(), anyList());
        GoogleTestEmailResponse unavailable = service.sendTestEmail("itap-admin");
        assertThat(unavailable.sent()).isFalse();
        assertThat(unavailable.code()).isEqualTo("REAUTH_REQUIRED");

        doThrow(new GmailApiEmailService.DeliveryUncertain()).when(gmail).send(any(), any(), any(), any(), any(), anyList());
        assertThat(service.sendTestEmail("itap-admin").code()).isEqualTo("NO_RESPONSE");

        doThrow(new IllegalStateException("anything")).when(gmail).send(any(), any(), any(), any(), any(), anyList());
        GoogleTestEmailResponse unexpected = service.sendTestEmail("itap-admin");
        assertThat(unexpected.sent()).isFalse();
        assertThat(unexpected.code()).isEqualTo("SEND_FAILED");
        verify(audit, never()).record(any(MyHrAuthenticatedUserService.AuthenticatedMyHrUser.class), eq(AuditActionType.GOOGLE_EMAIL_TEST_SENT),
                anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void connectionProblemsAreReportedWithoutCallingGoogle() {
        connectionStore.remove("org-itap");
        assertThat(service.sendTestEmail("itap-admin").code()).isEqualTo("NOT_CONNECTED");

        connect("org-itap", "ops@itap.example");
        connectionStore.get("org-itap").setStatus(GoogleConnectionStatus.DISCONNECTED);
        assertThat(service.sendTestEmail("itap-admin").code()).isEqualTo("NOT_CONNECTED");

        connectionStore.get("org-itap").setStatus(GoogleConnectionStatus.REAUTH_REQUIRED);
        assertThat(service.sendTestEmail("itap-admin").code()).isEqualTo("CONNECTION_REAUTH_REQUIRED");

        connect("org-itap", "ops@gmail.com");
        assertThat(service.sendTestEmail("itap-admin").code()).isEqualTo("DOMAIN_MISMATCH");

        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aReauthRequiredConnectionFailsCleanlyWithoutSendingOrFallingBack() {
        connectionStore.get("org-itap").setStatus(GoogleConnectionStatus.REAUTH_REQUIRED);

        GoogleTestEmailResponse response = service.sendTestEmail("itap-admin");

        assertThat(response.sent()).isFalse();
        assertThat(response.code()).isEqualTo("CONNECTION_REAUTH_REQUIRED");
        assertThat(response.recipient()).isEqualTo("hr@itap.example"); // still never the admin's own address
        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
        verify(audit, never()).record(any(MyHrAuthenticatedUserService.AuthenticatedMyHrUser.class), eq(AuditActionType.GOOGLE_EMAIL_TEST_SENT),
                anyString(), anyString(), anyString(), anyString(), any(), any());
        assertThat(connectionStore.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.REAUTH_REQUIRED);
    }

    @Test
    void aGmailOrganizationWhoseConnectedAccountIsNotItsOrganizationEmailSendsNothing() {
        organization("org-tardus", "Tardus", "tardus.org.example@gmail.com", "tardus.admin.example@gmail.com");
        connect("org-tardus", "someone.else@gmail.com");
        admin("tardus-admin", "org-tardus", "login@gmail.com", true);

        assertThat(service.sendTestEmail("tardus-admin").code()).isEqualTo("ACCOUNT_EMAIL_MISMATCH");

        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
    }

    // ------------------------------------------------------------------ recipient must be a valid Organization Email

    @Test
    void aBlankOrMalformedOrganizationEmailSendsNothing() {
        organizationStore.get("org-itap").setOrganizationEmail(" ");
        assertThat(service.sendTestEmail("itap-admin").code()).isEqualTo("RECIPIENT_UNAVAILABLE");

        organizationStore.get("org-itap").setOrganizationEmail("not-an-email");
        GoogleTestEmailResponse response = service.sendTestEmail("itap-admin");
        assertThat(response.code()).isEqualTo("RECIPIENT_UNAVAILABLE");
        assertThat(response.recipient()).isNull();

        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
    }

    // ------------------------------------------------------------------ who may use it, and how often

    @Test
    void onlyACompanyAdminMayUseItAndNothingIsSentOtherwise() {
        assertThatThrownBy(() -> service.sendTestEmail("employee-1")).isInstanceOf(ForbiddenException.class);

        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void itIsRefusedWhileTheFeatureIsOff() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.sendTestEmail("itap-admin")).isInstanceOf(BusinessException.class).hasMessageContaining("not enabled");

        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void itIsRateLimitedPerOrganizationWithoutAffectingOthers() {
        for (int i = 0; i < 5; i++) assertThat(service.sendTestEmail("itap-admin").sent()).isTrue();

        assertThatThrownBy(() -> service.sendTestEmail("itap-admin")).isInstanceOf(BusinessException.class).hasMessageContaining("Too many");
        assertThat(service.sendTestEmail("abc-admin").sent()).isTrue();
    }

    @Test
    void theMessageContentIsFixedAndSmall() {
        service.sendTestEmail("itap-admin");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(gmail).send(any(), anyString(), eq("WayPoint test email"), html.capture(), isNull(), eq(List.<EmailAttachment>of()));
        assertThat(html.getValue()).doesNotContain("refresh").doesNotContain("token");
    }
}
