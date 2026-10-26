package com.waypoint.service;

import com.waypoint.dto.myhr.UpdateAuthorizedSignatureRequest;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizedSignatureServiceTest {
    private static final String PROFILE_SIGNATURE = "gridfs/abc/signature.png";

    @Mock OrganizationSettingsRepository settingsRepository;
    @Mock UserRepository users;
    @Mock MyHrAuthenticatedUserService authenticated;
    @Mock FileStorageService fileStorage;
    AuthorizedSignatureService service;
    User admin;

    @BeforeEach void setUp() {
        service = new AuthorizedSignatureService(settingsRepository, users, authenticated, fileStorage);
        admin = User.builder().id("admin").loginId("admin@tardus").organizationId("org-1").role(UserRole.COMPANY_ADMIN)
                .accountStatus(AccountStatus.ACTIVE).build();
        lenient().when(users.findById("admin")).thenReturn(Optional.of(admin));
        lenient().when(authenticated.require(eq("admin"), eq(Set.of(UserRole.COMPANY_ADMIN)), eq(false))).thenReturn(
                new MyHrAuthenticatedUserService.AuthenticatedMyHrUser("admin", "admin@tardus", "admin@test", UserRole.COMPANY_ADMIN,
                        "org-1", AccountStatus.ACTIVE, false));
        lenient().when(settingsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(fileStorage.findStoredPath("user-signatures", "admin")).thenReturn(Optional.of(PROFILE_SIGNATURE));
        lenient().when(fileStorage.load(PROFILE_SIGNATURE))
                .thenReturn(new FileStorageService.StoredFile(new byte[] {1, 2, 3}, "signature.png", MediaType.IMAGE_PNG));
    }

    private void settings(Boolean active, LocalDate effectiveDate) {
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.of(OrganizationSettings.builder()
                .organizationId("org-1").authorizedSignatoryUserId("admin").authorizedSignatoryName("Jane Admin")
                .authorizedSignatoryTitle("President").signatureActive(active).signatureEffectiveDate(effectiveDate).build()));
    }

    @Test void activeSignatureUsesTheCompanyAdminsProfileSignature() {
        settings(true, LocalDate.now());
        var active = service.findActive("org-1").orElseThrow();
        assertThat(active.signatoryName()).isEqualTo("Jane Admin");
        assertThat(active.signatoryTitle()).isEqualTo("President");
        assertThat(active.image()).containsExactly(1, 2, 3);
        assertThat(active.source()).isEqualTo(AuthorizedSignatureService.SIGNATURE_SOURCE);
        verify(fileStorage).findStoredPath("user-signatures", "admin");
    }

    @Test void inactiveFutureDatedOrMissingSignaturesAreNeverUsed() {
        settings(false, LocalDate.now());
        assertThat(service.findActive("org-1")).isEmpty();
        settings(null, LocalDate.now());
        assertThat(service.findActive("org-1")).isEmpty();
        settings(true, LocalDate.now().plusDays(1));
        assertThat(service.findActive("org-1")).isEmpty();
        settings(true, LocalDate.now());
        when(fileStorage.findStoredPath("user-signatures", "admin")).thenReturn(Optional.empty());
        assertThat(service.findActive("org-1")).isEmpty();
    }

    @Test void aDeactivatedSignatoryAdminNoLongerSigns() {
        settings(true, LocalDate.now());
        admin.setAccountStatus(AccountStatus.INACTIVE);
        assertThat(service.findActive("org-1")).isEmpty();
    }

    @Test void savingMakesTheActingAdminTheSignatory() {
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.empty());
        var response = service.update("admin", new UpdateAuthorizedSignatureRequest(" Jane Admin ", "President", LocalDate.now(), true));
        assertThat(response.signatoryName()).isEqualTo("Jane Admin");
        assertThat(response.signatureOwnerUserId()).isEqualTo("admin");
        assertThat(response.signatureOwnerIsViewer()).isTrue();
        assertThat(response.signatureUrl()).isEqualTo("/uploads/" + PROFILE_SIGNATURE);
        assertThat(response.active()).isTrue();
        assertThat(response.inEffect()).isTrue();
    }

    @Test void aSignatureCannotBeActivatedWithoutAProfileSignature() {
        when(fileStorage.findStoredPath("user-signatures", "admin")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("admin", new UpdateAuthorizedSignatureRequest("Jane", "President", LocalDate.now(), true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Profile");
        verify(settingsRepository, never()).save(any());
    }

    @Test void aProfileSignatureImageDoesNotActivateTheOrganizationSignature() {
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.empty());
        var response = service.get("admin");
        assertThat(response.signatureConfigured()).isTrue();
        assertThat(response.signatureUrl()).isEqualTo("/uploads/" + PROFILE_SIGNATURE);
        assertThat(response.active()).isFalse();
        assertThat(response.inEffect()).isFalse();
        assertThat(response.signatoryName()).isNull();
        assertThat(service.findActive("org-1")).isEmpty();
    }

    @Test void savingAsInactiveKeepsTheDetailsAndStaysInactiveWhileTheImageRemains() {
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.empty());
        var saved = service.update("admin", new UpdateAuthorizedSignatureRequest("Jane Admin", "President", LocalDate.now(), false));
        assertThat(saved.signatoryName()).isEqualTo("Jane Admin");
        assertThat(saved.signatoryTitle()).isEqualTo("President");
        assertThat(saved.effectiveDate()).isEqualTo(LocalDate.now());
        assertThat(saved.active()).isFalse();
        assertThat(saved.inEffect()).isFalse();
        assertThat(saved.signatureConfigured()).isTrue();
    }

    @Test void aFutureDatedActivationIsSavedButNotInEffectUntilItsDate() {
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.empty());
        var response = service.update("admin", new UpdateAuthorizedSignatureRequest("Jane Admin", "President", LocalDate.now().plusDays(3), true));
        assertThat(response.active()).isTrue();
        assertThat(response.inEffect()).isFalse();
        assertThat(response.effectiveDate()).isEqualTo(LocalDate.now().plusDays(3));
    }

    @Test void theSavedActiveConfigurationIsReturnedByAFreshGetAfterReload() {
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.empty());
        service.update("admin", new UpdateAuthorizedSignatureRequest("Jane Admin", "President", LocalDate.now(), true));
        var captor = org.mockito.ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(settingsRepository).save(captor.capture());
        when(settingsRepository.findByOrganizationId("org-1")).thenReturn(Optional.of(captor.getValue()));

        var reloaded = service.get("admin");
        assertThat(reloaded.signatoryName()).isEqualTo("Jane Admin");
        assertThat(reloaded.signatoryTitle()).isEqualTo("President");
        assertThat(reloaded.active()).isTrue();
        assertThat(reloaded.inEffect()).isTrue();
        assertThat(service.findActive("org-1")).isPresent();
    }

    @Test void aReplacedProfileSignatureIsWhatSigningUsesNextWithoutChangingTheAuthorization() {
        settings(true, LocalDate.now());
        when(fileStorage.load(PROFILE_SIGNATURE))
                .thenReturn(new FileStorageService.StoredFile(new byte[] {9, 8}, "signature.png", MediaType.IMAGE_PNG));
        var active = service.findActive("org-1").orElseThrow();
        assertThat(active.image()).containsExactly(9, 8);
        assertThat(service.get("admin").active()).isTrue();
    }

    @Test void removingTheProfileSignatureStopsSigningButDoesNotDeactivateTheSavedAuthorization() {
        settings(true, LocalDate.now());
        when(fileStorage.findStoredPath("user-signatures", "admin")).thenReturn(Optional.empty());
        assertThat(service.findActive("org-1")).isEmpty();
        var response = service.get("admin");
        assertThat(response.active()).isTrue();
        assertThat(response.signatureConfigured()).isFalse();
        assertThat(response.inEffect()).isFalse();
        assertThat(response.signatureUrl()).isNull();
    }
}
