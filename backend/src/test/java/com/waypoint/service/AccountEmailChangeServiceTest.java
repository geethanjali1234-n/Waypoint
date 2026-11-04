package com.waypoint.service;

import com.waypoint.dto.auth.AccountEmailChangeRequestedResponse;
import com.waypoint.dto.auth.ChangeAccountEmailRequest;
import com.waypoint.dto.auth.ConfirmAccountEmailChangeRequest;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.entity.Organization;
import com.waypoint.entity.PendingAccountEmailChange;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.PendingAccountEmailChangeRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountEmailChangeServiceTest {

    private static final String PASSWORD = "CorrectHorse1";
    private static final String OLD_EMAIL = "tardus.org.example@example.com";
    private static final String NEW_EMAIL = "tardus.admin.example@example.com";

    /** A clock the tests move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-04T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        void advanceMinutes(long minutes) { now = now.plusSeconds(minutes * 60); }
    }

    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Organization> organizations = new HashMap<>();
    private final Map<String, PendingAccountEmailChange> pendingStore = new HashMap<>();
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final PendingAccountEmailChangeRepository pendingRepository = mock(PendingAccountEmailChangeRepository.class);
    private final MongoOperations mongo = mock(MongoOperations.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final JwtService jwtService = new JwtService("unit-test-signing-key-that-is-long-enough-0123456789", 3_600_000L);
    private final OtpService otpService = mock(OtpService.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final MutableClock clock = new MutableClock();
    private AccountEmailChangeService service;
    private String lastCode;
    private boolean claimSucceeds = true;

    @BeforeEach
    void setUp() {
        when(userRepository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(users.get(call.<String>getArgument(0))));
        when(userRepository.findByEmail(anyString())).thenAnswer(call -> users.values().stream()
                .filter(user -> call.<String>getArgument(0).equals(user.getEmail())).findFirst());
        when(userRepository.existsByEmail(anyString())).thenAnswer(call -> users.values().stream()
                .anyMatch(user -> call.<String>getArgument(0).equals(user.getEmail())));
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            users.put(saved.getId(), saved);
            return saved;
        });
        when(organizationRepository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(organizations.get(call.<String>getArgument(0))));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));
        when(pendingRepository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(pendingStore.get(call.<String>getArgument(0))));
        when(pendingRepository.save(any(PendingAccountEmailChange.class))).thenAnswer(call -> {
            PendingAccountEmailChange saved = call.getArgument(0);
            pendingStore.put(saved.getId(), saved);
            return saved;
        });
        org.mockito.Mockito.doAnswer(call -> pendingStore.remove(call.<String>getArgument(0))).when(pendingRepository).deleteById(anyString());
        // The atomic single-use claim: succeeds once, then the stored claimedAt makes the next caller lose.
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PendingAccountEmailChange.class)))
                .thenAnswer(call -> {
                    if (!claimSucceeds) return null;
                    for (PendingAccountEmailChange pending : pendingStore.values()) {
                        if (pending.getClaimedAt() == null) {
                            pending.setClaimedAt(LocalDateTime.now(clock));
                            return pending;
                        }
                    }
                    return null;
                });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(PendingAccountEmailChange.class))).thenAnswer(call -> {
            Update update = call.getArgument(1);
            String rendered = update.getUpdateObject().toString();
            for (PendingAccountEmailChange pending : pendingStore.values()) {
                if (rendered.contains("failedAttempts")) pending.setFailedAttempts(pending.getFailedAttempts() + 1);
                if (rendered.contains("claimedAt")) pending.setClaimedAt(null);
            }
            return null;
        });
        org.mockito.Mockito.doAnswer(call -> {
            lastCode = call.getArgument(1);
            return null;
        }).when(otpService).sendAccountEmailChangeCode(anyString(), anyString());

        service = new AccountEmailChangeService(userRepository, organizationRepository, pendingRepository, mongo, encoder,
                jwtService, otpService, emailService, new EmailTemplateService(), auditService, clock);

        organizations.put("org-tardus", Organization.builder().id("org-tardus").adminEmail(OLD_EMAIL).build());
        organizations.put("org-itap", Organization.builder().id("org-itap").adminEmail("itap.admin.example@example.com").build());
        users.put("tardus-admin", admin("tardus-admin", "org-tardus", OLD_EMAIL));
        users.put("itap-admin", admin("itap-admin", "org-itap", "itap.admin.example@example.com"));
    }

    private User admin(String id, String orgId, String email) {
        return User.builder().id(id).organizationId(orgId).email(email).loginId(id.toUpperCase()).role(UserRole.COMPANY_ADMIN)
                .accountStatus(AccountStatus.ACTIVE).isEmailVerified(true).mustChangePassword(false)
                .passwordHash(encoder.encode(PASSWORD)).updatedAt(LocalDateTime.now(clock)).build();
    }

    private ChangeAccountEmailRequest request(String newEmail, String password) {
        ChangeAccountEmailRequest request = new ChangeAccountEmailRequest();
        request.setNewEmail(newEmail);
        request.setCurrentPassword(password);
        return request;
    }

    private ConfirmAccountEmailChangeRequest confirm(String otp) {
        ConfirmAccountEmailChangeRequest request = new ConfirmAccountEmailChangeRequest();
        request.setOtp(otp);
        return request;
    }

    private void requested() {
        service.requestChange("tardus-admin", request(NEW_EMAIL, PASSWORD));
    }

    private void assertAccountUnchanged() {
        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(OLD_EMAIL);
        assertThat(users.get("tardus-admin").getIsEmailVerified()).isTrue();
        assertThat(organizations.get("org-tardus").getAdminEmail()).isEqualTo(OLD_EMAIL);
    }

    // ------------------------------------------------------------------ request

    @Test
    void aRequestSendsTheCodeOnlyToTheNewAddressAndChangesNothing() {
        AccountEmailChangeRequestedResponse response = service.requestChange("tardus-admin", request(" Tardus.admin.example@Example.com ", PASSWORD));

        assertThat(response.newEmail()).isEqualTo(NEW_EMAIL);
        assertThat(response.expiresInSeconds()).isEqualTo(600);
        assertThat(response.resendAfterSeconds()).isEqualTo(60);
        verify(otpService).sendAccountEmailChangeCode(eq(NEW_EMAIL), anyString());
        verify(otpService, times(1)).sendAccountEmailChangeCode(anyString(), anyString());
        assertThat(lastCode).matches("\\d{6}");
        assertAccountUnchanged();
        verify(userRepository, never()).save(any(User.class));
        verify(organizationRepository, never()).save(any(Organization.class));
    }

    @Test
    void thePendingChangeIsBoundToTheUserAndOrganizationAndStoresOnlyAHashOfTheCode() {
        requested();

        PendingAccountEmailChange pending = pendingStore.get("tardus-admin");
        assertThat(pending.getId()).isEqualTo("tardus-admin");
        assertThat(pending.getOrganizationId()).isEqualTo("org-tardus");
        assertThat(pending.getNewEmail()).isEqualTo(NEW_EMAIL);
        assertThat(pending.getOtpHash()).isNotEqualTo(lastCode).doesNotContain(lastCode);
        assertThat(encoder.matches(lastCode, pending.getOtpHash())).isTrue();
        assertThat(pending.getExpiresAt()).isEqualTo(LocalDateTime.now(clock).plusMinutes(10));
        assertThat(pending.toString()).doesNotContain(pending.getOtpHash());
    }

    @Test
    void theCurrentPasswordIsRequiredAndAWrongOneSendsAndStoresNothing() {
        assertThatThrownBy(() -> service.requestChange("tardus-admin", request(NEW_EMAIL, "WrongPassword1")))
                .isInstanceOf(BusinessException.class).hasMessage("Current password is incorrect");

        verify(otpService, never()).sendAccountEmailChangeCode(anyString(), anyString());
        assertThat(pendingStore).isEmpty();
        assertAccountUnchanged();
    }

    @Test
    void onlyAnActiveCompanyAdminMayRequestAChange() {
        users.get("tardus-admin").setRole(UserRole.HR);
        assertThatThrownBy(() -> service.requestChange("tardus-admin", request(NEW_EMAIL, PASSWORD))).isInstanceOf(BusinessException.class);

        users.get("tardus-admin").setRole(UserRole.COMPANY_ADMIN);
        users.get("tardus-admin").setAccountStatus(AccountStatus.INACTIVE);
        assertThatThrownBy(() -> service.requestChange("tardus-admin", request(NEW_EMAIL, PASSWORD))).isInstanceOf(BusinessException.class);

        users.get("tardus-admin").setAccountStatus(AccountStatus.ACTIVE);
        users.get("tardus-admin").setOrganizationId(null);
        assertThatThrownBy(() -> service.requestChange("tardus-admin", request(NEW_EMAIL, PASSWORD))).isInstanceOf(BusinessException.class);

        verify(otpService, never()).sendAccountEmailChangeCode(anyString(), anyString());
        assertThat(pendingStore).isEmpty();
    }

    @Test
    void anUnchangedAddressAndAnAddressAlreadyRegisteredToAnyUserAreRefused() {
        assertThatThrownBy(() -> service.requestChange("tardus-admin", request(" TARDUS.ORG.EXAMPLE@example.com ", PASSWORD)))
                .isInstanceOf(BusinessException.class).hasMessage("New email must be different from the current email");
        // The other organization's admin address: the explicit message is kept, behind authentication and a password.
        assertThatThrownBy(() -> service.requestChange("tardus-admin", request("itap.admin.example@example.com", PASSWORD)))
                .isInstanceOf(BusinessException.class).hasMessage("Email already registered");

        verify(otpService, never()).sendAccountEmailChangeCode(anyString(), anyString());
        assertThat(pendingStore).isEmpty();
    }

    @Test
    void aSecondRequestWithinTheCooldownIsRefusedAndAfterItIsAllowed() {
        requested();
        String firstCode = lastCode;

        clock.advanceSeconds(30);
        assertThatThrownBy(this::requested).isInstanceOf(BusinessException.class)
                .hasMessage("Please wait before requesting another verification code");
        verify(otpService, times(1)).sendAccountEmailChangeCode(anyString(), anyString());
        assertThat(encoder.matches(firstCode, pendingStore.get("tardus-admin").getOtpHash())).isTrue();

        clock.advanceSeconds(31);
        requested();
        verify(otpService, times(2)).sendAccountEmailChangeCode(anyString(), anyString());
    }

    @Test
    void aNewRequestInvalidatesTheEarlierCodeAndResetsItsAttempts() {
        requested();
        String firstCode = lastCode;
        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(firstCode.equals("000000") ? "000001" : "000000")))
                .isInstanceOf(BusinessException.class);
        assertThat(pendingStore.get("tardus-admin").getFailedAttempts()).isEqualTo(1);

        clock.advanceSeconds(61);
        requested();

        assertThat(pendingStore.get("tardus-admin").getFailedAttempts()).isZero();
        assertThat(encoder.matches(firstCode, pendingStore.get("tardus-admin").getOtpHash())).isFalse();
    }

    @Test
    void theSendLimitIsKeptAcrossRequestsSoANewRequestCannotResetIt() {
        for (int i = 0; i < 5; i++) {
            requested();
            clock.advanceSeconds(61);
        }
        assertThatThrownBy(this::requested).isInstanceOf(BusinessException.class)
                .hasMessage("Verification code request limit reached. Please try again later");
        verify(otpService, times(5)).sendAccountEmailChangeCode(anyString(), anyString());

        clock.advanceMinutes(31);
        requested();
        verify(otpService, times(6)).sendAccountEmailChangeCode(anyString(), anyString());
    }

    @Test
    void aCodeThatCouldNotBeDeliveredDoesNotStayUsable() {
        doThrow(new BusinessException("Unable to send verification email. Please try again."))
                .when(otpService).sendAccountEmailChangeCode(anyString(), anyString());

        assertThatThrownBy(this::requested).isInstanceOf(BusinessException.class).hasMessageContaining("Unable to send");

        assertThat(pendingStore).isEmpty();
        assertAccountUnchanged();
    }

    // ------------------------------------------------------------------ confirm: success

    @Test
    void theRightCodeChangesTheLoginEmailAdminEmailAndVerificationTogetherAndReturnsAFreshSession() {
        requested();
        String oldToken = jwtService.generateToken("tardus-admin", OLD_EMAIL, "COMPANY_ADMIN");

        LoginResponse response = service.confirmChange("tardus-admin", confirm(lastCode));

        User user = users.get("tardus-admin");
        assertThat(user.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(user.getIsEmailVerified()).isTrue();
        assertThat(organizations.get("org-tardus").getAdminEmail()).isEqualTo(NEW_EMAIL);
        assertThat(response.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(response.getUserId()).isEqualTo("tardus-admin");
        assertThat(response.getOrganizationId()).isEqualTo("org-tardus");
        assertThat(jwtService.extractUserId(response.getAccessToken())).isEqualTo("tardus-admin");
        assertThat(jwtService.parseToken(response.getAccessToken()).get("email", String.class)).isEqualTo(NEW_EMAIL);
        // The earlier session is keyed by the user id, so it still resolves the same user; there is no revocation list.
        assertThat(jwtService.extractUserId(oldToken)).isEqualTo("tardus-admin");
        assertThat(pendingStore).isEmpty();
    }

    @Test
    void theOtherOrganizationIsNeverTouched() {
        requested();
        service.confirmChange("tardus-admin", confirm(lastCode));

        assertThat(users.get("itap-admin").getEmail()).isEqualTo("itap.admin.example@example.com");
        assertThat(organizations.get("org-itap").getAdminEmail()).isEqualTo("itap.admin.example@example.com");
        verify(organizationRepository, never()).save(organizations.get("org-itap"));
    }

    @Test
    void thePreviousAddressIsToldWithoutPublishingTheWholeNewAddress() {
        requested();
        service.confirmChange("tardus-admin", confirm(lastCode));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq(OLD_EMAIL), eq("Your Waypoint login email was changed"), html.capture());
        assertThat(html.getValue()).contains("t***@example.com").doesNotContain(NEW_EMAIL);
        verify(emailService, never()).sendHtmlEmail(eq(NEW_EMAIL), anyString(), anyString());
    }

    @Test
    void aFailedNoticeToThePreviousAddressNeverUndoesTheChange() {
        requested();
        doThrow(new IllegalStateException("smtp down")).when(emailService).sendHtmlEmail(eq(OLD_EMAIL), anyString(), anyString());

        LoginResponse response = service.confirmChange("tardus-admin", confirm(lastCode));

        assertThat(response.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(NEW_EMAIL);
    }

    @Test
    void theChangeIsAudited() {
        requested();
        service.confirmChange("tardus-admin", confirm(lastCode));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(event.capture());
        assertThat(event.getValue().getActionType()).isEqualTo(AuditActionType.ACCOUNT_EMAIL_CHANGED);
        assertThat(event.getValue().getOrganizationId()).isEqualTo("org-tardus");
        assertThat(event.getValue().getActorUserId()).isEqualTo("tardus-admin");
    }

    // ------------------------------------------------------------------ confirm: refusals keep the old login

    @Test
    void untilTheCodeIsRightTheExistingLoginEmailStaysActiveAndAWrongCodeCountsAnAttempt() {
        requested();
        String wrong = lastCode.equals("123456") ? "654321" : "123456";

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(wrong)))
                .isInstanceOf(BusinessException.class).hasMessage("Invalid or expired verification code");

        assertAccountUnchanged();
        assertThat(pendingStore.get("tardus-admin").getFailedAttempts()).isEqualTo(1);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void afterFiveWrongCodesEvenTheRightCodeIsRefused() {
        requested();
        String wrong = lastCode.equals("123456") ? "654321" : "123456";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(wrong))).isInstanceOf(BusinessException.class);
        }

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode)))
                .isInstanceOf(BusinessException.class).hasMessage("Invalid or expired verification code");
        assertAccountUnchanged();
    }

    @Test
    void anExpiredCodeIsRefused() {
        requested();
        clock.advanceMinutes(11);

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode)))
                .isInstanceOf(BusinessException.class).hasMessage("Invalid or expired verification code");
        assertAccountUnchanged();
    }

    @Test
    void aCodeWorksOnlyOnce() {
        requested();
        String code = lastCode;
        service.confirmChange("tardus-admin", confirm(code));

        // Replay: the pending change is gone, so there is nothing to confirm.
        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(code))).isInstanceOf(BusinessException.class);
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void aConcurrentConfirmationThatLosesTheClaimChangesNothing() {
        requested();
        claimSucceeds = false;

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode)))
                .isInstanceOf(BusinessException.class).hasMessage("Invalid or expired verification code");

        assertAccountUnchanged();
        verify(userRepository, never()).save(any(User.class));
        verify(organizationRepository, never()).save(any(Organization.class));
    }

    @Test
    void confirmingWithoutAPendingChangeIsRefused() {
        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm("123456")))
                .isInstanceOf(BusinessException.class).hasMessage("Invalid or expired verification code");
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    void anotherAdminCannotUseOrSeeSomeoneElsesPendingChange() {
        requested();
        String tardusCode = lastCode;

        // The ITAP admin has no pending change; the code from the Tardus admin's email is useless to them.
        assertThatThrownBy(() -> service.confirmChange("itap-admin", confirm(tardusCode))).isInstanceOf(BusinessException.class);

        assertThat(users.get("itap-admin").getEmail()).isEqualTo("itap.admin.example@example.com");
        assertThat(pendingStore).containsOnlyKeys("tardus-admin");
        assertAccountUnchanged();
    }

    @Test
    void aPendingChangeIsRefusedIfTheUserNowBelongsToAnotherOrganization() {
        requested();
        users.get("tardus-admin").setOrganizationId("org-itap");

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode))).isInstanceOf(BusinessException.class);

        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(OLD_EMAIL);
        assertThat(organizations.get("org-itap").getAdminEmail()).isEqualTo("itap.admin.example@example.com");
        assertThat(organizations.get("org-tardus").getAdminEmail()).isEqualTo(OLD_EMAIL);
    }

    @Test
    void aUserWhoIsNoLongerAnActiveCompanyAdminCannotConfirm() {
        requested();
        users.get("tardus-admin").setAccountStatus(AccountStatus.INACTIVE);

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode))).isInstanceOf(BusinessException.class);

        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(OLD_EMAIL);
    }

    @Test
    void theConfirmationRequestCannotCarryAnAddress() {
        assertThat(ConfirmAccountEmailChangeRequest.class.getDeclaredFields()).extracting("name").containsExactly("otp");
    }

    // ------------------------------------------------------------------ uniqueness at confirmation, partial failure

    @Test
    void anAddressTakenBetweenRequestAndConfirmationIsRefusedAndNothingChanges() {
        requested();
        users.put("someone", admin("someone", "org-other", NEW_EMAIL));

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode)))
                .isInstanceOf(BusinessException.class).hasMessage("Email already registered");

        assertAccountUnchanged();
        assertThat(users.get("someone").getEmail()).isEqualTo(NEW_EMAIL);
        verify(organizationRepository, never()).save(any(Organization.class));
        assertThat(pendingStore).isEmpty();
    }

    @Test
    void aDuplicateKeyFromTheDatabaseAtTheLastMomentRestoresTheOrganization() {
        requested();
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("email"));

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode)))
                .isInstanceOf(BusinessException.class).hasMessage("Email already registered");

        assertThat(organizations.get("org-tardus").getAdminEmail()).isEqualTo(OLD_EMAIL);
        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(OLD_EMAIL);
    }

    @Test
    void whenTheUserSaveFailsTheOrganizationIsPutBackAndTheAdminMayRetryWithTheSameCode() {
        requested();
        String code = lastCode;
        when(userRepository.save(any(User.class))).thenThrow(new IllegalStateException("mongo down"));

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(code))).isInstanceOf(IllegalStateException.class);

        assertThat(organizations.get("org-tardus").getAdminEmail()).isEqualTo(OLD_EMAIL);
        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(OLD_EMAIL);
        assertThat(users.get("tardus-admin").getIsEmailVerified()).isTrue();
        assertThat(pendingStore.get("tardus-admin").getClaimedAt()).isNull(); // claim released, not consumed
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(auditService, never()).record(any(AuditEvent.class));
    }

    @Test
    void whenTheOrganizationSaveFailsTheUserIsNeverTouched() {
        requested();
        when(organizationRepository.save(any(Organization.class))).thenThrow(new IllegalStateException("mongo down"));

        assertThatThrownBy(() -> service.confirmChange("tardus-admin", confirm(lastCode))).isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any(User.class));
        assertThat(users.get("tardus-admin").getEmail()).isEqualTo(OLD_EMAIL);
    }

    // ------------------------------------------------------------------ the masked notice helper

    @Test
    void theNoticeMaskKeepsOnlyTheFirstLetterAndTheDomain() {
        assertThat(AccountEmailChangeService.mask("tardus.admin.example@example.com")).isEqualTo("t***@example.com");
        assertThat(AccountEmailChangeService.mask("not-an-email")).isEqualTo("a new address");
        assertThat(AccountEmailChangeService.mask(null)).isEqualTo("a new address");
    }
}
