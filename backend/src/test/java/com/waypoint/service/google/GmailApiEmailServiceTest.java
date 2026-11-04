package com.waypoint.service.google;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.TokenCipher;
import com.waypoint.service.EmailAttachment;
import com.waypoint.service.EmailSenderIdentity;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GmailApiEmailServiceTest {

    private static final String SECRET_SUBJECT = "Subject-with-private-detail";
    private static final String SECRET_BODY = "Body-with-private-detail";

    private final Map<String, OrganizationGoogleConnection> store = new HashMap<>();
    private final Map<String, Organization> organizations = new HashMap<>();
    private final OrganizationGoogleConnectionRepository connections = mock(OrganizationGoogleConnectionRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final GoogleOAuthClient google = mock(GoogleOAuthClient.class);
    private final org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private final TokenCipher cipher = new TokenCipher(randomKey(), "v1");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private GmailApiEmailService service;
    private int accessTokenCounter;
    private int accessTokenLifetime = 3599;

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret-value");

        when(connections.findByOrganizationId(anyString())).thenAnswer(call -> Optional.ofNullable(store.get(call.<String>getArgument(0))));
        when(connections.save(any(OrganizationGoogleConnection.class))).thenAnswer(call -> call.getArgument(0));
        when(organizationRepository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(organizations.get(call.<String>getArgument(0))));
        // Each refresh token yields a distinct, recognisable access token: "access-for-<refreshToken>-<n>".
        when(google.refreshAccessToken(anyString())).thenAnswer(call ->
                new GoogleOAuthClient.AccessToken("access-for-" + call.getArgument(0) + "-" + (++accessTokenCounter), accessTokenLifetime));

        service = new GmailApiEmailService(properties, cipher, google, connections, organizationRepository, events);
        connect("org-itap", "hr@itap.example", "admin@itap.example", "refresh-itap");
        connect("org-abc", "hr@abc.example", "admin@abc.example", "refresh-abc");

        logs.start();
        ((Logger) LoggerFactory.getLogger(GmailApiEmailService.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(GmailApiEmailService.class)).detachAppender(logs);
        logs.stop();
    }

    private void connect(String orgId, String organizationEmail, String googleAccount, String refreshToken) {
        organizations.put(orgId, Organization.builder().id(orgId).organizationName(orgId).organizationEmail(organizationEmail)
                .adminEmail("admin-contact@" + orgId + ".example").build());
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(refreshToken, orgId);
        store.put(orgId, OrganizationGoogleConnection.builder().id("conn-" + orgId).organizationId(orgId)
                .status(GoogleConnectionStatus.CONNECTED).googleAccountEmail(googleAccount).googleSubject("sub-" + orgId)
                .refreshTokenCiphertext(encrypted.ciphertext()).refreshTokenIv(encrypted.iv()).encryptionKeyId(encrypted.keyId()).build());
    }

    private static EmailSenderIdentity identity(String orgId, String displayName, String replyTo, String googleAccount) {
        return new EmailSenderIdentity(displayName, replyTo, null).withGoogleSender(orgId, googleAccount);
    }

    private void sendAs(EmailSenderIdentity identity) {
        service.send(identity, "employee@personal.example", SECRET_SUBJECT, "<p>" + SECRET_BODY + "</p>", null, List.of());
    }

    private MimeMessage sentMessage(int index) throws Exception {
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(google, org.mockito.Mockito.atLeastOnce()).sendMessage(anyString(), raw.capture());
        byte[] bytes = Base64.getUrlDecoder().decode(raw.getAllValues().get(index));
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
    }

    private void assertSentNothingThroughGoogle() {
        verify(google, never()).sendMessage(anyString(), anyString());
    }

    // ------------------------------------------------------------------ each organization uses its own account

    @Test
    void itapSendsThroughItsOwnGoogleAccountWithItsOwnToken() throws Exception {
        sendAs(identity("org-itap", "HR ITAP", "hr@itap.example", "admin@itap.example"));

        verify(google).refreshAccessToken("refresh-itap");
        verify(google, never()).refreshAccessToken("refresh-abc");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(google).sendMessage(token.capture(), anyString());
        assertThat(token.getValue()).startsWith("access-for-refresh-itap");

        MimeMessage message = sentMessage(0);
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("admin@itap.example");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("HR ITAP");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("hr@itap.example");
        assertThat(message.getAllRecipients()).extracting(a -> ((InternetAddress) a).getAddress()).containsExactly("employee@personal.example");
        assertThat(message.getSubject()).isEqualTo(SECRET_SUBJECT);
        assertThat(message.getContentType()).startsWith("text/html");
        assertThat(message.getContent().toString()).contains(SECRET_BODY);
    }

    @Test
    void abcSendsThroughItsOwnGoogleAccountNeverItapsAndViceVersa() throws Exception {
        sendAs(identity("org-itap", "HR ITAP", "hr@itap.example", "admin@itap.example"));
        sendAs(identity("org-abc", "HR ABC", "hr@abc.example", "admin@abc.example"));

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(google, times(2)).sendMessage(tokens.capture(), anyString());
        assertThat(tokens.getAllValues().get(0)).contains("refresh-itap").doesNotContain("refresh-abc");
        assertThat(tokens.getAllValues().get(1)).contains("refresh-abc").doesNotContain("refresh-itap");
        assertThat(((InternetAddress) sentMessage(0).getFrom()[0]).getAddress()).isEqualTo("admin@itap.example");
        assertThat(((InternetAddress) sentMessage(1).getFrom()[0]).getAddress()).isEqualTo("admin@abc.example");
    }

    @Test
    void anIdentityNamingAnotherOrganizationsAccountIsRefusedAndNothingIsSent() {
        // ITAP's identity, but pointing at ABC's Google account.
        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@abc.example")))
                .isInstanceOf(GmailApiEmailService.Unavailable.class)
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("SENDER_MISMATCH");

        assertSentNothingThroughGoogle();
        verify(google, never()).refreshAccessToken("refresh-abc");
    }

    @Test
    void aTokenStoredForAnotherOrganizationCannotBeUsed() {
        // ABC's ciphertext copied into ITAP's connection: decryption is bound to the organization and fails.
        OrganizationGoogleConnection abc = store.get("org-abc");
        OrganizationGoogleConnection itap = store.get("org-itap");
        itap.setRefreshTokenCiphertext(abc.getRefreshTokenCiphertext());
        itap.setRefreshTokenIv(abc.getRefreshTokenIv());

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("TOKEN_UNREADABLE");

        verify(google, never()).refreshAccessToken(anyString());
        assertSentNothingThroughGoogle();
    }

    // ------------------------------------------------------------------ connection problems: nothing sent, SMTP may take over

    @Test
    void aMissingConnectionSendsNothing() {
        store.remove("org-itap");

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("NOT_CONNECTED");

        verifyNoInteractions(google);
    }

    @Test
    void aDisconnectedOrReauthRequiredConnectionSendsNothing() {
        store.get("org-itap").setStatus(GoogleConnectionStatus.DISCONNECTED);
        store.get("org-itap").setRefreshTokenCiphertext(null);
        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .isInstanceOf(GmailApiEmailService.Unavailable.class);

        store.get("org-abc").setStatus(GoogleConnectionStatus.REAUTH_REQUIRED);
        assertThatThrownBy(() -> sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("CONNECTION_REAUTH_REQUIRED");

        verifyNoInteractions(google);
    }

    @Test
    void theDomainIsCheckedAgainAtSendTime() {
        organizations.get("org-itap").setOrganizationEmail("hr@renamed-company.example");

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("DOMAIN_MISMATCH");

        verifyNoInteractions(google);
    }

    @Test
    void theFeatureFlagIsCheckedAtSendTime() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("FEATURE_DISABLED");

        verifyNoInteractions(google);
    }

    @Test
    void anIdentityWithoutAGoogleSenderIsRefused() {
        assertThatThrownBy(() -> sendAs(new EmailSenderIdentity("HR ITAP", "hr@itap.example", null)))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("NO_GOOGLE_IDENTITY");
        assertThatThrownBy(() -> sendAs(null)).isInstanceOf(GmailApiEmailService.Unavailable.class);

        verifyNoInteractions(google);
    }

    // ------------------------------------------------------------------ invalid or revoked credentials

    @Test
    void revokedCredentialsMarkTheConnectionForReauthenticationAndSendNothing() {
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("REAUTH_REQUIRED");

        assertThat(store.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.REAUTH_REQUIRED);
        assertThat(store.get("org-itap").getLastErrorCode()).isEqualTo("INVALID_GRANT");
        assertThat(store.get("org-abc").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED); // other tenants untouched
        assertSentNothingThroughGoogle();
    }

    @Test
    void aTemporaryTokenProblemSendsNothingButKeepsTheConnection() {
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("HTTP_503"));

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("TOKEN_UNAVAILABLE");

        assertThat(store.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
        assertSentNothingThroughGoogle();
    }

    // ------------------------------------------------------------------ Gmail API rejection and silence

    @Test
    void anUnauthorizedAnswerFromGmailMarksReauthenticationAndSendsNothingElse() {
        doThrow(new GoogleOAuthException("HTTP_401")).when(google).sendMessage(anyString(), anyString());

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("REAUTH_REQUIRED");

        assertThat(store.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.REAUTH_REQUIRED);
    }

    @Test
    void otherRejectionsFromGmailAreReportedAsNotSentWithoutChangingTheConnection() {
        for (String code : List.of("HTTP_403", "HTTP_429", "HTTP_500", "HTTP_400")) {
            doThrow(new GoogleOAuthException(code)).when(google).sendMessage(anyString(), anyString());

            assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                    .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("GMAIL_" + code);

            assertThat(store.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
            assertThat(store.get("org-itap").getLastErrorCode()).isEqualTo(code);
        }
    }

    @Test
    void noAnswerFromGmailIsDeliveryUncertainAndMustNotBeResent() {
        doThrow(new GoogleOAuthException(GoogleOAuthClient.NO_RESPONSE)).when(google).sendMessage(anyString(), anyString());

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .isInstanceOf(GmailApiEmailService.DeliveryUncertain.class);

        assertThat(store.get("org-itap").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }

    // ------------------------------------------------------------------ message content

    @Test
    void attachmentsAndAPlainTextAlternativeAreIncluded() throws Exception {
        service.send(identity("org-itap", "HR ITAP", "hr@itap.example", "admin@itap.example"), "employee@personal.example",
                "Offer letter", "<p>Hello</p>", "Hello",
                List.of(new EmailAttachment("offer-letter.pdf", "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII), "application/pdf")));

        MimeMessage message = sentMessage(0);
        assertThat(message.getContentType()).startsWith("multipart/");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        message.writeTo(out);
        String mime = out.toString(StandardCharsets.ISO_8859_1);
        assertThat(mime).contains("offer-letter.pdf").contains("application/pdf").contains("text/plain").contains("text/html");
    }

    @Test
    void anOversizedMessageIsNotSentThroughTheApi() {
        byte[] big = new byte[GmailApiEmailService.MAX_RAW_MESSAGE_BYTES];

        assertThatThrownBy(() -> service.send(identity("org-itap", "HR ITAP", null, "admin@itap.example"), "employee@personal.example",
                "Big", "<p>x</p>", null, List.of(new EmailAttachment("big.bin", big, "application/octet-stream"))))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("MESSAGE_TOO_LARGE");

        assertSentNothingThroughGoogle();
    }

    @Test
    void theReplyToComesFromTheIdentityAndAdminEmailIsNeverUsedAsASender() throws Exception {
        sendAs(identity("org-itap", "HR ITAP", "hr@itap.example", "admin@itap.example"));

        MimeMessage message = sentMessage(0);
        String headers = String.join("|", message.getHeader("From")) + String.join("|", message.getHeader("Reply-To"));
        assertThat(headers).doesNotContain("admin-contact@org-itap.example");
    }

    @Test
    void withoutAReplyToNoReplyToHeaderIsAdded() throws Exception {
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));

        assertThat(sentMessage(0).getHeader("Reply-To")).isNull();
    }

    // ------------------------------------------------------------------ access tokens

    @Test
    void anAccessTokenIsReusedInMemoryUntilItNearsExpiry() {
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));

        verify(google, times(1)).refreshAccessToken("refresh-itap");
    }

    @Test
    void aShortLivedTokenIsRefreshedForTheNextMessage() {
        accessTokenLifetime = 30; // inside the 60 second safety margin, so it is never reused
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));

        verify(google, times(2)).refreshAccessToken("refresh-itap");
    }

    @Test
    void reconnectingWithANewTokenNeverReusesTheOldAccessToken() {
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));
        TokenCipher.EncryptedValue replaced = cipher.encrypt("refresh-itap-v2", "org-itap");
        store.get("org-itap").setRefreshTokenCiphertext(replaced.ciphertext());
        store.get("org-itap").setRefreshTokenIv(replaced.iv());

        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));

        verify(google).refreshAccessToken("refresh-itap-v2");
    }

    @Test
    void cachedAccessTokensAreKeptPerOrganization() {
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));
        sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example"));
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));
        sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example"));

        verify(google, times(1)).refreshAccessToken("refresh-itap");
        verify(google, times(1)).refreshAccessToken("refresh-abc");
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(google, times(4)).sendMessage(tokens.capture(), anyString());
        assertThat(tokens.getAllValues().get(2)).contains("refresh-itap");
        assertThat(tokens.getAllValues().get(3)).contains("refresh-abc");
    }

    @Test
    void anAccessTokenIsNeverPersisted() {
        sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"));

        verify(connections, never()).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.toString().contains("access-for-") || String.valueOf(saved.getRefreshTokenCiphertext()).contains("access-for-")));
        assertThat(store.values().toString()).doesNotContain("access-for-");
    }

    // ------------------------------------------------------------------ logs

    @Test
    void noLogLineContainsTokensMessageContentOrAddresses() {
        sendAs(identity("org-itap", "HR ITAP", "hr@itap.example", "admin@itap.example"));
        doThrow(new GoogleOAuthException("HTTP_403")).when(google).sendMessage(anyString(), anyString());
        assertThatThrownBy(() -> sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example")));
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));
        store.get("org-itap").setRefreshTokenCiphertext(cipher.encrypt("refresh-itap", "org-itap").ciphertext());
        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")));
        doThrow(new GoogleOAuthException(GoogleOAuthClient.NO_RESPONSE)).when(google).sendMessage(anyString(), anyString());
        connect("org-abc", "hr@abc.example", "admin@abc.example", "refresh-abc");
        assertThatThrownBy(() -> sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example")));

        String everything = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(everything).doesNotContain("refresh-itap").doesNotContain("refresh-abc").doesNotContain("access-for-")
                .doesNotContain(SECRET_SUBJECT).doesNotContain(SECRET_BODY).doesNotContain("employee@personal.example")
                .doesNotContain("admin@itap.example").doesNotContain("admin@abc.example").doesNotContain("client-secret-value");
    }

    // ------------------------------------------------------------------ Phase 5: reconnect notification event, size budget

    @Test
    void theFirstRevocationRaisesExactlyOneReconnectEventForThatOrganization() {
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"))).isInstanceOf(GmailApiEmailService.Unavailable.class);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new GoogleConnectionReauthRequiredEvent("org-itap", "INVALID_GRANT"));
        assertThat(event.getValue().toString()).doesNotContain("refresh-itap");
    }

    @Test
    void aGmailUnauthorizedAnswerAlsoRaisesTheEventOnce() {
        doThrow(new GoogleOAuthException("HTTP_401")).when(google).sendMessage(anyString(), anyString());

        assertThatThrownBy(() -> sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example"))).isInstanceOf(GmailApiEmailService.Unavailable.class);

        verify(events).publishEvent(new GoogleConnectionReauthRequiredEvent("org-abc", "HTTP_401"));
    }

    @Test
    void aConnectionAlreadyFlaggedByAnotherSendDoesNotNotifyAgain() {
        OrganizationGoogleConnection connection = store.get("org-itap");
        when(google.refreshAccessToken("refresh-itap")).thenAnswer(call -> {
            connection.setStatus(GoogleConnectionStatus.REAUTH_REQUIRED); // another send flagged it first
            throw new GoogleOAuthException("INVALID_GRANT");
        });

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"))).isInstanceOf(GmailApiEmailService.Unavailable.class);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aLostRaceOnSavingRaisesNoEvent() {
        when(connections.save(any(OrganizationGoogleConnection.class))).thenThrow(new org.springframework.dao.OptimisticLockingFailureException("stale"));
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("REAUTH_REQUIRED");

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void failuresThatAreNotAuthorizationProblemsRaiseNoEvent() {
        doThrow(new GoogleOAuthException("HTTP_403")).when(google).sendMessage(anyString(), anyString());
        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"))).isInstanceOf(GmailApiEmailService.Unavailable.class);
        doThrow(new GoogleOAuthException(GoogleOAuthClient.NO_RESPONSE)).when(google).sendMessage(anyString(), anyString());
        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example"))).isInstanceOf(GmailApiEmailService.DeliveryUncertain.class);
        when(google.refreshAccessToken("refresh-abc")).thenThrow(new GoogleOAuthException("HTTP_503"));
        assertThatThrownBy(() -> sendAs(identity("org-abc", "HR ABC", null, "admin@abc.example"))).isInstanceOf(GmailApiEmailService.Unavailable.class);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aFailingNotificationNeverBreaksTheFallback() {
        when(google.refreshAccessToken("refresh-itap")).thenThrow(new GoogleOAuthException("INVALID_GRANT"));
        doThrow(new IllegalStateException("listener failed")).when(events).publishEvent(any(Object.class));

        assertThatThrownBy(() -> sendAs(identity("org-itap", "HR ITAP", null, "admin@itap.example")))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("REAUTH_REQUIRED");
    }

    @Test
    void theSizeCapKeepsTheEncodedRequestUnderTheOnlyFigureGoogleDocuments() {
        // base64url is 4/3 the size of the MIME bytes; the JSON wrapper is a few dozen bytes.
        long encoded = (GmailApiEmailService.MAX_RAW_MESSAGE_BYTES + 2) / 3 * 4;

        assertThat(encoded + 64).isLessThan(GmailApiEmailService.ENCODED_BODY_BUDGET_BYTES);
        assertThat(GmailApiEmailService.ENCODED_BODY_BUDGET_BYTES).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void aMessageJustUnderTheCapIsSentAndOneOverItFallsBack() {
        byte[] fits = new byte[(int) ((GmailApiEmailService.MAX_RAW_MESSAGE_BYTES - 20_000) / 1.37)]; // MIME base64 with line breaks is about 1.37x

        service.send(identity("org-itap", "HR ITAP", null, "admin@itap.example"), "employee@personal.example", "Big", "<p>x</p>", null,
                List.of(new EmailAttachment("fits.bin", fits, "application/octet-stream")));
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(google).sendMessage(anyString(), raw.capture());
        assertThat(raw.getValue().length()).isLessThan((int) GmailApiEmailService.ENCODED_BODY_BUDGET_BYTES);

        byte[] tooBig = new byte[GmailApiEmailService.MAX_RAW_MESSAGE_BYTES];
        assertThatThrownBy(() -> service.send(identity("org-itap", "HR ITAP", null, "admin@itap.example"), "employee@personal.example",
                "Big", "<p>x</p>", null, List.of(new EmailAttachment("big.bin", tooBig, "application/octet-stream"))))
                .extracting(f -> ((GmailApiEmailService.Unavailable) f).code()).isEqualTo("MESSAGE_TOO_LARGE");
    }
}
