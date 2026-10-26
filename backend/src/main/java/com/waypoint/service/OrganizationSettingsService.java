package com.waypoint.service;

import com.waypoint.dto.myhr.OrganizationSettingsResponse;
import com.waypoint.dto.myhr.UpdateOrganizationSettingsRequest;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * COMPANY_ADMIN organization settings: company information (stored on the
 * existing Organization document) plus the payroll period (stored on the
 * existing OrganizationSettings document).
 *
 * Authorization contract: every operation resolves the acting organization
 * solely from the authenticated user's own User record via
 * MyHrAuthenticatedUserService.require(..., Set.of(COMPANY_ADMIN), false).
 * No organization id is ever accepted from the caller, so a COMPANY_ADMIN
 * can only ever read or write their own organization; HR and EMPLOYEE are
 * rejected by require() before any data is touched.
 *
 * Data-integrity contract: updateForActor mutates every field carried by
 * UpdateOrganizationSettingsRequest plus OrganizationSettings.payrollPeriod.
 * organizationCode, organizationId, nextEmployeeSequence, and createdAt are
 * never touched - organizationCode in particular participates in employee-ID
 * generation, so it (and the other provisioning/internal fields) are left off
 * the request DTO entirely. organizationLogoDocumentId is only ever set via
 * uploadLogo (multipart upload, stored through FileStorageService) or cleared
 * via updateForActor's removeLogo flag - never taken as a raw string value.
 */
@Service
@RequiredArgsConstructor
public class OrganizationSettingsService {

    private static final Set<UserRole> MANAGE_ROLES = Set.of(UserRole.COMPANY_ADMIN);
    /** Identifiers whose values are never copied into the audit trail, only the fact that they changed. */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("EIN Number", "E-Verify Number");
    private static final String LOGO_SUBDIRECTORY = "company-logos";
    private static final String SIGNATURE_SUBDIRECTORY = "company-signatures";
    // Package-visible: the same rules apply to a user's own profile signature
    // (EmployeeManagementService.uploadOwnSignature).
    static final Set<String> SIGNATURE_CONTENT_TYPES = Set.of("image/png", "image/jpeg");
    static final long SIGNATURE_MAX_BYTES = 2 * 1024 * 1024;

    private final OrganizationRepository organizationRepository;
    private final OrganizationSettingsRepository organizationSettingsRepository;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;

    public OrganizationSettingsResponse getForActor(String actorUserId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);

        Organization organization = loadOrganization(actor.organizationId());
        OrganizationSettings settings = organizationSettingsRepository
                .findByOrganizationId(actor.organizationId())
                .orElse(null);

        return toResponse(organization, settings);
    }

    public OrganizationSettingsResponse updateForActor(String actorUserId, UpdateOrganizationSettingsRequest request) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);

        Organization organization = loadOrganization(actor.organizationId());
        OrganizationSettings settings = loadOrCreateSettings(actor.organizationId());

        String nextEmail = request.organizationEmail().trim();
        if (!nextEmail.equalsIgnoreCase(organization.getOrganizationEmail())) {
            organizationRepository.findByOrganizationEmail(nextEmail)
                    .filter(other -> !other.getId().equals(organization.getId()))
                    .ifPresent(other -> {
                        throw new BusinessException("Organization email is already in use");
                    });
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, String> before = auditSnapshot(organization);
        var previousPayrollPeriod = settings.getPayrollPeriod();

        organization.setOrganizationName(request.organizationName().trim());
        organization.setOrganizationEmail(nextEmail);
        organization.setPhoneNumber(trimToNull(request.phoneNumber()));
        organization.setStreetAddress(trimToNull(request.streetAddress()));
        organization.setCity(trimToNull(request.city()));
        organization.setState(trimToNull(request.state()));
        organization.setZipCode(trimToNull(request.zipCode()));
        organization.setOrganizationSize(request.organizationSize());
        organization.setIndustry(trimToNull(request.industry()));
        organization.setWebsite(trimToNull(request.website()));
        organization.setEinNumber(normalizeEin(request.einNumber()));
        organization.setEVerifyNumber(trimToNull(request.eVerifyNumber()));
        organization.setAdminName(trimToNull(request.adminName()));
        organization.setAdminEmail(trimToNull(request.adminEmail()));
        organization.setAdminPhoneNumber(trimToNull(request.adminPhoneNumber()));
        organization.setDescription(trimToNull(request.description()));
        if (request.removeLogo() && isNotBlank(organization.getOrganizationLogoDocumentId())) {
            fileStorageService.remove(LOGO_SUBDIRECTORY, organization.getId());
            organization.setOrganizationLogoDocumentId(null);
        }
        if (request.removeSignature() && isNotBlank(organization.getOrganizationSignatureDocumentId())) {
            fileStorageService.remove(SIGNATURE_SUBDIRECTORY, organization.getId());
            organization.setOrganizationSignatureDocumentId(null);
        }
        organization.setUpdatedAt(now);
        Organization savedOrganization = organizationRepository.save(organization);

        settings.setPayrollPeriod(request.payrollPeriod());
        settings.setUpdatedByUserId(actor.userId());
        settings.setUpdatedAt(now);
        OrganizationSettings savedSettings = organizationSettingsRepository.save(settings);

        recordSettingsAudit(actor, savedOrganization, before, previousPayrollPeriod, savedSettings);
        return toResponse(savedOrganization, savedSettings);
    }

    /** Audit History entries for a save; AuditService contains its own failures so a save is never undone by auditing. */
    private void recordSettingsAudit(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, Organization organization,
                                     Map<String, String> before, Object previousPayrollPeriod, OrganizationSettings settings) {
        Map<String, String> after = auditSnapshot(organization);
        Map<String, String> previousChanged = new LinkedHashMap<>();
        Map<String, String> updatedChanged = new LinkedHashMap<>();
        after.forEach((field, value) -> {
            if (!Objects.equals(before.get(field), value)) {
                boolean masked = SENSITIVE_FIELDS.contains(field);
                previousChanged.put(field, masked ? "Changed" : before.get(field));
                updatedChanged.put(field, masked ? "Changed" : value);
            }
        });
        if (!updatedChanged.isEmpty()) {
            auditService.record(actor, AuditActionType.ORGANIZATION_SETTINGS_UPDATED,
                    "ORGANIZATION", organization.getId(), organization.getOrganizationName(),
                    "Updated organization settings: " + String.join(", ", updatedChanged.keySet()),
                    previousChanged, updatedChanged);
        }
        if (!Objects.equals(previousPayrollPeriod, settings.getPayrollPeriod())) {
            auditService.record(actor, AuditActionType.PAYROLL_PERIOD_CHANGED,
                    "ORGANIZATION", organization.getId(), organization.getOrganizationName(),
                    "Payroll period changed to " + settings.getPayrollPeriod(),
                    Map.of("Payroll Period", String.valueOf(previousPayrollPeriod)),
                    Map.of("Payroll Period", String.valueOf(settings.getPayrollPeriod())));
        }
    }

    private Map<String, String> auditSnapshot(Organization organization) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("Organization Name", organization.getOrganizationName());
        snapshot.put("Organization Email", organization.getOrganizationEmail());
        snapshot.put("Phone Number", organization.getPhoneNumber());
        snapshot.put("Street Address", organization.getStreetAddress());
        snapshot.put("City", organization.getCity());
        snapshot.put("State", organization.getState());
        snapshot.put("ZIP Code", organization.getZipCode());
        snapshot.put("Company Size", organization.getOrganizationSize() == null ? null : organization.getOrganizationSize().name());
        snapshot.put("Industry", organization.getIndustry());
        snapshot.put("Website", organization.getWebsite());
        snapshot.put("EIN Number", organization.getEinNumber());
        snapshot.put("E-Verify Number", organization.getEVerifyNumber());
        snapshot.put("Admin Name", organization.getAdminName());
        snapshot.put("Admin Email", organization.getAdminEmail());
        snapshot.put("Admin Contact Number", organization.getAdminPhoneNumber());
        snapshot.put("Description", organization.getDescription());
        snapshot.put("Logo", isNotBlank(organization.getOrganizationLogoDocumentId()) ? "Uploaded" : null);
        return snapshot;
    }

    public OrganizationSettingsResponse uploadLogo(String actorUserId, MultipartFile file) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);

        Organization organization = loadOrganization(actor.organizationId());
        OrganizationSettings settings = organizationSettingsRepository
                .findByOrganizationId(actor.organizationId())
                .orElse(null);

        organization.setOrganizationLogoDocumentId(fileStorageService.store(file, LOGO_SUBDIRECTORY, organization.getId()));
        organization.setUpdatedAt(LocalDateTime.now());
        Organization savedOrganization = organizationRepository.save(organization);

        return toResponse(savedOrganization, settings);
    }

    private Organization loadOrganization(String organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    /**
     * One settings document per organization: an existing row is loaded and
     * mutated in place (never duplicated); a missing row is built in memory
     * here and only persisted by the caller's save(). No startup initializer
     * or provisioning hook creates this row.
     */
    private OrganizationSettings loadOrCreateSettings(String organizationId) {
        return organizationSettingsRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now();
                    return OrganizationSettings.builder()
                            .organizationId(organizationId)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                });
    }

    private OrganizationSettingsResponse toResponse(Organization organization, OrganizationSettings settings) {
        return new OrganizationSettingsResponse(
                organization.getId(),
                organization.getOrganizationCode(),
                organization.getOrganizationEmail(),
                organization.getOrganizationName(),
                organization.getPhoneNumber(),
                organization.getStreetAddress(),
                organization.getCity(),
                organization.getState(),
                organization.getZipCode(),
                organization.getOrganizationSize(),
                organization.getIndustry(),
                organization.getWebsite(),
                organization.getEinNumber(),
                organization.getEVerifyNumber(),
                organization.getAdminName(),
                organization.getAdminEmail(),
                organization.getAdminPhoneNumber(),
                organization.getDescription(),
                settings == null ? null : settings.getPayrollPeriod(),
                isNotBlank(organization.getOrganizationLogoDocumentId())
                        ? "/uploads/" + organization.getOrganizationLogoDocumentId() : null,
                isNotBlank(organization.getOrganizationSignatureDocumentId())
                        ? "/uploads/" + organization.getOrganizationSignatureDocumentId() : null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Stores valid EINs in the conventional 12-3456789 format. */
    private String normalizeEin(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String digits = trimmed.replace("-", "");
        return digits.substring(0, 2) + "-" + digits.substring(2);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
