package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.service.EmailSenderIdentity;
import com.waypoint.service.MailSenderAuthorization;
import com.waypoint.service.OrganizationEmailIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleSenderEligibilityTest {

    private final Map<String, OrganizationGoogleConnection> store = new HashMap<>();
    private final Map<String, Organization> organizations = new HashMap<>();
    private final OrganizationGoogleConnectionRepository connections = mock(OrganizationGoogleConnectionRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private GoogleSenderEligibility eligibility;
    private OrganizationEmailIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(connections.findByOrganizationId(anyString())).thenAnswer(call -> Optional.ofNullable(store.get(call.<String>getArgument(0))));
        when(organizationRepository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(organizations.get(call.<String>getArgument(0))));
        eligibility = new GoogleSenderEligibility(properties, connections);
        resolver = new OrganizationEmailIdentityResolver(organizationRepository, MailSenderAuthorization.none(), eligibility);

        organization("org-itap", "ITAP", "hr@itap.example", "admin-contact@itap.example");
        organization("org-abc", "ABC Technologies", "hr@abc.example", "admin-contact@abc.example");
        connection("org-itap", "Admin@ITAP.example", GoogleConnectionStatus.CONNECTED);
        connection("org-abc", "ops@abc.example", GoogleConnectionStatus.CONNECTED);
    }

    private void organization(String id, String name, String organizationEmail, String adminEmail) {
        organizations.put(id, Organization.builder().id(id).organizationName(name).organizationEmail(organizationEmail).adminEmail(adminEmail).build());
    }

    private void connection(String orgId, String account, GoogleConnectionStatus status) {
        store.put(orgId, OrganizationGoogleConnection.builder().id("c-" + orgId).organizationId(orgId).status(status)
                .googleAccountEmail(account).googleSubject("sub-" + orgId).refreshTokenCiphertext("cipher").refreshTokenIv("iv").build());
    }

    private Optional<String> eligible(String orgId) {
        return eligibility.eligibleSender(organizations.get(orgId));
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void aConnectedAccountOnTheOrganizationEmailsDomainIsEligibleAndLowercased() {
        assertThat(eligible("org-itap")).contains("admin@itap.example");
        assertThat(eligible("org-abc")).contains("ops@abc.example");
    }

    @Test
    void aGmailOrganizationIsEligibleOnlyWithItsOwnOrganizationEmailAsTheAccount() {
        organization("org-tardus", "Tardus", "Tardus.org.example@gmail.com", "someone@gmail.com");
        connection("org-tardus", "tardus.org.example@gmail.com", GoogleConnectionStatus.CONNECTED);
        assertThat(eligible("org-tardus")).contains("tardus.org.example@gmail.com");
        assertThat(eligibility.diagnose(organizations.get("org-tardus"))).isNull();

        connection("org-tardus", "another.person@gmail.com", GoogleConnectionStatus.CONNECTED);
        assertThat(eligible("org-tardus")).as("same consumer domain, different account").isEmpty();
        assertThat(eligibility.diagnose(organizations.get("org-tardus"))).isEqualTo("ACCOUNT_EMAIL_MISMATCH");
    }

    @Test
    void aCompanyDomainOrganizationKeepsTheDomainRule() {
        connection("org-itap", "another.mailbox@itap.example", GoogleConnectionStatus.CONNECTED);
        assertThat(eligible("org-itap")).contains("another.mailbox@itap.example");
    }

    @Test
    void everyOtherSituationIsNotEligible() {
        properties.setEnabled(false);
        assertThat(eligible("org-itap")).isEmpty();
        properties.setEnabled(true);

        store.remove("org-itap");
        assertThat(eligible("org-itap")).as("no connection").isEmpty();

        connection("org-itap", "admin@itap.example", GoogleConnectionStatus.DISCONNECTED);
        assertThat(eligible("org-itap")).as("disconnected").isEmpty();
        connection("org-itap", "admin@itap.example", GoogleConnectionStatus.REAUTH_REQUIRED);
        assertThat(eligible("org-itap")).as("needs reauthentication").isEmpty();

        connection("org-itap", "admin@itap.example", GoogleConnectionStatus.CONNECTED);
        store.get("org-itap").setRefreshTokenCiphertext(null);
        assertThat(eligible("org-itap")).as("no stored token").isEmpty();

        connection("org-itap", "admin@gmail.com", GoogleConnectionStatus.CONNECTED);
        assertThat(eligible("org-itap")).as("account on another domain").isEmpty();

        connection("org-itap", "admin@itap.example", GoogleConnectionStatus.CONNECTED);
        store.get("org-itap").setOrganizationId("org-abc");
        assertThat(eligible("org-itap")).as("connection belongs to a different organization").isEmpty();

        assertThat(eligibility.eligibleSender(null)).isEmpty();
        assertThat(eligibility.eligibleSender(Organization.builder().build())).isEmpty();
    }

    @Test
    void aRepositoryFailureMeansNotEligibleRatherThanAnError() {
        when(connections.findByOrganizationId(anyString())).thenThrow(new IllegalStateException("db down"));

        assertThat(eligible("org-itap")).isEmpty();
    }

    @Test
    void theFeatureBeingOffNeverTouchesTheDatabase() {
        properties.setEnabled(false);
        GoogleOAuthProperties off = new GoogleOAuthProperties();
        OrganizationGoogleConnectionRepository untouched = mock(OrganizationGoogleConnectionRepository.class);

        assertThat(new GoogleSenderEligibility(off, untouched).eligibleSender(organizations.get("org-itap"))).isEmpty();

        verifyNoInteractions(untouched);
    }

    // ------------------------------------------------------------------ resolver

    @Test
    void theResolverRoutesEachOrganizationToItsOwnAccount() {
        EmailSenderIdentity itap = resolver.forOrganization("org-itap");
        EmailSenderIdentity abc = resolver.forOrganization("org-abc");

        assertThat(itap.usesGoogleSender()).isTrue();
        assertThat(itap.gmailOrganizationId()).isEqualTo("org-itap");
        assertThat(itap.gmailSenderAddress()).isEqualTo("admin@itap.example");
        assertThat(itap.displayName()).isEqualTo("HR ITAP");
        assertThat(itap.replyTo()).isEqualTo("hr@itap.example");
        assertThat(abc.gmailOrganizationId()).isEqualTo("org-abc");
        assertThat(abc.gmailSenderAddress()).isEqualTo("ops@abc.example");
        assertThat(abc.displayName()).isEqualTo("HR ABC Technologies");
        assertThat(abc.replyTo()).isEqualTo("hr@abc.example");
    }

    @Test
    void theSmtpFieldsAreUnchangedWhenAGoogleSenderIsAdded() {
        MailSenderAuthorization authorizesItap = new MailSenderAuthorization("itap.example");
        OrganizationEmailIdentityResolver both = new OrganizationEmailIdentityResolver(organizationRepository, authorizesItap, eligibility);
        OrganizationEmailIdentityResolver smtpOnly = new OrganizationEmailIdentityResolver(organizationRepository, authorizesItap);

        EmailSenderIdentity withGoogle = both.forOrganization("org-itap");
        EmailSenderIdentity without = smtpOnly.forOrganization("org-itap");

        assertThat(withGoogle.displayName()).isEqualTo(without.displayName());
        assertThat(withGoogle.replyTo()).isEqualTo(without.replyTo());
        assertThat(withGoogle.fromAddress()).isEqualTo(without.fromAddress()).isEqualTo("hr@itap.example");
        assertThat(without.usesGoogleSender()).isFalse();
    }

    @Test
    void anOrganizationWithoutAUsableConnectionResolvesExactlyAsBefore() {
        store.remove("org-abc");

        EmailSenderIdentity abc = resolver.forOrganization("org-abc");

        assertThat(abc.usesGoogleSender()).isFalse();
        assertThat(abc.gmailOrganizationId()).isNull();
        assertThat(abc.gmailSenderAddress()).isNull();
        assertThat(abc.fromAddress()).isNull();
        assertThat(abc.displayName()).isEqualTo("HR ABC Technologies");
        assertThat(abc.replyTo()).isEqualTo("hr@abc.example");
    }

    @Test
    void unknownOrMissingOrganizationsResolveToTheGlobalSender() {
        assertThat(resolver.forOrganization("missing-org").usesGoogleSender()).isFalse();
        assertThat(resolver.forOrganization((String) null)).isEqualTo(EmailSenderIdentity.none());
        assertThat(resolver.forOrganization("")).isEqualTo(EmailSenderIdentity.none());
    }

    @Test
    void adminEmailIsNeverTheSenderOrReplyToOnTheGooglePath() {
        organization("org-itap", "ITAP", "hr@itap.example", "admin-contact@itap.example");

        EmailSenderIdentity itap = resolver.forOrganization("org-itap");

        assertThat(itap.toString()).doesNotContain("admin-contact@itap.example");
        assertThat(itap.gmailSenderAddress()).isNotEqualTo("admin-contact@itap.example");
        assertThat(itap.replyTo()).isNotEqualTo("admin-contact@itap.example");
    }

    @Test
    void anExplicitReplyToStillOverridesTheOrganizationEmail() {
        organizations.get("org-itap").setReplyToEmail("support@itap.example");

        assertThat(resolver.forOrganization("org-itap").replyTo()).isEqualTo("support@itap.example");
    }

    @Test
    void aResolverBuiltWithoutTheGoogleFeatureNeverRoutesToGoogle() {
        OrganizationEmailIdentityResolver legacy = new OrganizationEmailIdentityResolver(organizationRepository);

        assertThat(legacy.forOrganization("org-itap").usesGoogleSender()).isFalse();
    }

    @Test
    void theNewIdentityFieldsKeepTheOlderConstructorsWorking() {
        EmailSenderIdentity old = new EmailSenderIdentity("HR ITAP", "hr@itap.example", "hr@itap.example");

        assertThat(old.usesGoogleSender()).isFalse();
        assertThat(new EmailSenderIdentity("HR", "r@x.example").fromAddress()).isNull();
        assertThat(EmailSenderIdentity.none().usesGoogleSender()).isFalse();
        assertThat(EmailSenderIdentity.ofDisplayName("HR").usesGoogleSender()).isFalse();
        assertThat(old.withGoogleSender("org", "a@x.example").usesGoogleSender()).isTrue();
        assertThat(old.withGoogleSender("org", " ").usesGoogleSender()).isFalse();
    }
}
