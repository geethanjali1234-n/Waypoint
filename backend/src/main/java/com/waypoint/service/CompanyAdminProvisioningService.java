package com.waypoint.service;

import com.waypoint.dto.myhr.CompanyAdminProvisioningRequest;
import com.waypoint.dto.myhr.CreatedAccountCredential;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyAdminProvisioningService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationEmployeeIdService organizationEmployeeIdService;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final PasswordEncoder passwordEncoder;

    public CreatedAccountCredential provision(CompanyAdminProvisioningRequest request) {
        if (request == null || request.organizationId() == null || request.organizationId().isBlank()
                || request.email() == null || request.email().isBlank()) {
            throw new BusinessException("Organization ID and Company Admin email are required");
        }
        Organization organization = organizationRepository.findById(request.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        warnIfAdminEmailDiffers(organization, email);
        String loginId = organizationEmployeeIdService.generateNextLoginId(organization.getId(), UserRole.COMPANY_ADMIN);
        User existingAdmin = userRepository.findByLoginId(loginId).orElse(null);
        if (existingAdmin != null) {
            if (existingAdmin.getRole() != UserRole.COMPANY_ADMIN
                    || !organization.getId().equals(existingAdmin.getOrganizationId())) {
                throw new BusinessException("TDADMIN belongs to a different role or organization");
            }
            return new CreatedAccountCredential(existingAdmin.getId(), null, existingAdmin.getLoginId(), null,
                    existingAdmin.getEmail(), existingAdmin.getRole(), existingAdmin.getOrganizationId(),
                    Boolean.TRUE.equals(existingAdmin.getMustChangePassword()), null);
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("Company Admin email already exists");
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        User saved = userRepository.save(User.builder()
                .loginId(loginId)
                .email(email)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .role(UserRole.COMPANY_ADMIN)
                .organizationId(organization.getId())
                .mustChangePassword(true)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());

        initializeAdminEmailIfEmpty(organization, email, now);

        return new CreatedAccountCredential(saved.getId(), null, loginId, null, email,
                UserRole.COMPANY_ADMIN, organization.getId(), true, temporaryPassword);
    }

    /**
     * Registration stores the Admin Email and the login email as one address. A development run can be given a different
     * email than the one already saved on the organization. Nothing is overwritten (the Account Email change flow is the
     * way to align them); this only makes the difference visible. Addresses are not logged.
     */
    private void warnIfAdminEmailDiffers(Organization organization, String provisionedEmail) {
        String existing = organization.getAdminEmail();
        if (existing != null && !existing.isBlank() && !existing.trim().equalsIgnoreCase(provisionedEmail)) {
            log.warn("Company Admin login email differs from the organization's existing Admin Email; the Admin Email was left unchanged "
                    + "[organizationId={}]. Use Profile > Account Email to align them.", organization.getId());
        }
    }

    /**
     * Registration stores the Admin Email and the admin's login email as the same address. A development organization
     * is created without an Admin Email, so give it the provisioned admin's email, but never replace one that exists.
     * The admin account is already saved, so a failure here must not fail provisioning.
     */
    private void initializeAdminEmailIfEmpty(Organization organization, String adminEmail, LocalDateTime now) {
        if (organization.getAdminEmail() != null && !organization.getAdminEmail().isBlank()) return;
        try {
            organization.setAdminEmail(adminEmail);
            organization.setUpdatedAt(now);
            organizationRepository.save(organization);
        } catch (RuntimeException notSaved) {
            log.warn("Admin Email could not be initialized for the provisioned organization [organizationId={}, exceptionType={}]",
                    organization.getId(), notSaved.getClass().getSimpleName());
        }
    }
}
