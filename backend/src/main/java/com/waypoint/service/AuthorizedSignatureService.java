package com.waypoint.service;

import com.waypoint.dto.myhr.AuthorizedSignatureResponse;
import com.waypoint.dto.myhr.UpdateAuthorizedSignatureRequest;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * The organization's Authorized Signature (Admin -> Organization Settings):
 * signatory name/title, effective date and active flag on
 * OrganizationSettings. The signature image itself is not uploaded here -
 * it is the Profile signature (My HR -> Profile) of the Company Admin who
 * saved these settings, so admins manage their handwritten signature in one
 * place. Management is COMPANY_ADMIN-only; {@link #findActive} is the single
 * place signing workflows (the Signed I-983) read the signature from, so it
 * is never hard-coded or uploaded per request.
 */
@Service
@RequiredArgsConstructor
public class AuthorizedSignatureService {
    private static final Logger log = LoggerFactory.getLogger(AuthorizedSignatureService.class);
    private static final Set<UserRole> MANAGE_ROLES = Set.of(UserRole.COMPANY_ADMIN);
    public static final String SIGNATURE_SOURCE =
            "Company Admin Profile signature (Organization Settings - Authorized Signature)";
    public static final String PROFILE_SIGNATURE_SOURCE = "Company Admin Profile signature (My HR Profile)";

    private final OrganizationSettingsRepository organizationSettingsRepository;
    private final UserRepository userRepository;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final FileStorageService fileStorageService;

    public AuthorizedSignatureResponse get(String actorUserId) {
        var actor = authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);
        return toResponse(actor, organizationSettingsRepository.findByOrganizationId(actor.organizationId()).orElse(null));
    }

    /** Saving makes the acting Company Admin the signatory whose Profile signature is used. */
    public AuthorizedSignatureResponse update(String actorUserId, UpdateAuthorizedSignatureRequest request) {
        var actor = authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);
        if (request.active() && profileSignaturePath(actor.userId()).isEmpty())
            throw new BusinessException("Upload your signature in My HR Profile before marking the authorized signature active");
        OrganizationSettings settings = organizationSettingsRepository.findByOrganizationId(actor.organizationId())
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now();
                    return OrganizationSettings.builder().organizationId(actor.organizationId()).createdAt(now).updatedAt(now).build();
                });
        settings.setAuthorizedSignatoryUserId(actor.userId());
        settings.setAuthorizedSignatoryName(request.signatoryName().trim());
        settings.setAuthorizedSignatoryTitle(request.signatoryTitle().trim());
        settings.setSignatureEffectiveDate(request.effectiveDate());
        settings.setSignatureActive(request.active());
        settings.setUpdatedByUserId(actor.userId());
        settings.setUpdatedAt(LocalDateTime.now());
        return toResponse(actor, organizationSettingsRepository.save(settings));
    }

    /**
     * The organization's signature currently in effect, with its image bytes,
     * or empty when none is configured, it is inactive, its effective date is
     * still in the future, the signatory is no longer an active Company Admin
     * of the organization, or their Profile signature was removed.
     */
    public Optional<ActiveSignature> findActive(String organizationId) {
        OrganizationSettings settings = organizationSettingsRepository.findByOrganizationId(organizationId).orElse(null);
        if (settings == null || !Boolean.TRUE.equals(settings.getSignatureActive())
                || (settings.getSignatureEffectiveDate() != null && settings.getSignatureEffectiveDate().isAfter(LocalDate.now()))
                || activeSignatory(settings.getAuthorizedSignatoryUserId(), organizationId).isEmpty())
            return Optional.empty();
        Optional<String> path = profileSignaturePath(settings.getAuthorizedSignatoryUserId());
        if (path.isEmpty()) return Optional.empty();
        try {
            FileStorageService.StoredFile image = fileStorageService.load(path.get());
            return Optional.of(new ActiveSignature(settings.getAuthorizedSignatoryName(), settings.getAuthorizedSignatoryTitle(),
                    settings.getSignatureEffectiveDate(), image.content(), image.mediaType().toString(), SIGNATURE_SOURCE));
        } catch (RuntimeException failure) {
            log.warn("Authorized signature image for organization {} could not be loaded", organizationId, failure);
            return Optional.empty();
        }
    }

    /**
     * A user's own My HR Profile signature with its image bytes (signed in their name), or empty when they have
     * not uploaded one or it cannot be loaded. Used when a document is signed by a specific Company Admin account.
     */
    public Optional<ActiveSignature> findProfileSignature(String userId, String signatoryName, String signatoryTitle) {
        Optional<String> path = profileSignaturePath(userId);
        if (path.isEmpty()) return Optional.empty();
        try {
            FileStorageService.StoredFile image = fileStorageService.load(path.get());
            return Optional.of(new ActiveSignature(signatoryName, signatoryTitle, null, image.content(),
                    image.mediaType().toString(), PROFILE_SIGNATURE_SOURCE));
        } catch (RuntimeException failure) {
            log.warn("Profile signature image for user {} could not be loaded", userId, failure);
            return Optional.empty();
        }
    }

    private Optional<User> activeSignatory(String userId, String organizationId) {
        if (userId == null) return Optional.empty();
        return userRepository.findById(userId)
                .filter(user -> organizationId.equals(user.getOrganizationId()))
                .filter(user -> user.getRole() == UserRole.COMPANY_ADMIN && user.getAccountStatus() == AccountStatus.ACTIVE);
    }

    private Optional<String> profileSignaturePath(String userId) {
        return userId == null ? Optional.empty()
                : fileStorageService.findStoredPath(EmployeeManagementService.USER_SIGNATURE_SUBDIRECTORY, userId);
    }

    private AuthorizedSignatureResponse toResponse(MyHrAuthenticatedUserService.AuthenticatedMyHrUser viewer, OrganizationSettings settings) {
        // Until an admin saves these settings, preview the viewing admin's own Profile signature.
        String ownerId = settings != null && settings.getAuthorizedSignatoryUserId() != null
                ? settings.getAuthorizedSignatoryUserId() : viewer.userId();
        Optional<String> path = profileSignaturePath(ownerId);
        User owner = userRepository.findById(ownerId).orElse(null);
        boolean active = settings != null && Boolean.TRUE.equals(settings.getSignatureActive());
        boolean inEffect = active && path.isPresent()
                && activeSignatory(settings.getAuthorizedSignatoryUserId(), viewer.organizationId()).isPresent()
                && (settings.getSignatureEffectiveDate() == null || !settings.getSignatureEffectiveDate().isAfter(LocalDate.now()));
        return new AuthorizedSignatureResponse(
                settings == null ? null : settings.getAuthorizedSignatoryName(),
                settings == null ? null : settings.getAuthorizedSignatoryTitle(),
                settings == null ? null : settings.getSignatureEffectiveDate(),
                active, ownerId, owner == null ? null : owner.getLoginId(), ownerId.equals(viewer.userId()),
                path.isPresent(), inEffect, path.map(value -> "/uploads/" + value).orElse(null));
    }

    public record ActiveSignature(String signatoryName, String signatoryTitle, LocalDate effectiveDate,
                                  byte[] image, String contentType, String source) {}}
