package com.waypoint.service;

import com.waypoint.entity.Organization;
import com.waypoint.enums.CompanySize;
import com.waypoint.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TardusOrganizationProvisioningService {

    public static final String ORGANIZATION_CODE = "TD";
    public static final String ORGANIZATION_NAME = "Tardus Inc";
    public static final String DEVELOPMENT_EMAIL = "myhr.dev.tardus@waypoint.local";
    public static final long INITIAL_EMPLOYEE_SEQUENCE = 1L;

    private final OrganizationRepository organizationRepository;

    public ProvisioningResult provision() {
        return organizationRepository.findByOrganizationCode(ORGANIZATION_CODE)
                .map(organization -> new ProvisioningResult(organization, false))
                .orElseGet(this::createOrganization);
    }

    private ProvisioningResult createOrganization() {
        LocalDateTime now = LocalDateTime.now();
        Organization organization = Organization.builder()
                .organizationCode(ORGANIZATION_CODE)
                .organizationName(ORGANIZATION_NAME)
                .organizationEmail(DEVELOPMENT_EMAIL)
                .organizationSize(CompanySize.MEDIUM)
                .nextEmployeeSequence(INITIAL_EMPLOYEE_SEQUENCE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            return new ProvisioningResult(organizationRepository.save(organization), true);
        } catch (DuplicateKeyException concurrentCreation) {
            Organization existing = organizationRepository.findByOrganizationCode(ORGANIZATION_CODE)
                    .orElseThrow(() -> concurrentCreation);
            return new ProvisioningResult(existing, false);
        }
    }

    public record ProvisioningResult(Organization organization, boolean created) {
    }
}
