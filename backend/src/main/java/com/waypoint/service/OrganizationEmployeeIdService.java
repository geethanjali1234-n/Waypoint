package com.waypoint.service;

import com.waypoint.entity.Organization;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class OrganizationEmployeeIdService {

    private static final int MINIMUM_SEQUENCE_WIDTH = 3;

    private final MongoTemplate mongoTemplate;
    private final OrganizationRepository organizationRepository;

    /**
     * Allocates one value from the organization's shared HR/Employee sequence.
     * Returning the document from before the atomic increment makes its
     * nextEmployeeSequence value the number allocated by this call.
     */
    public String generateNextLoginId(String organizationId, UserRole role) {
        if (role == UserRole.COMPANY_ADMIN) {
            Organization organization = findOrganization(organizationId);
            return normalizedCode(organization) + "ADMIN";
        }

        if (role != UserRole.HR && role != UserRole.EMPLOYEE) {
            throw new BusinessException("Employee IDs can only be generated for HR or Employee users");
        }

        Query query = Query.query(Criteria.where("_id").is(organizationId));
        Update increment = new Update().inc("nextEmployeeSequence", 1L);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(false).upsert(false);

        Organization organization = mongoTemplate.findAndModify(
                query, increment, options, Organization.class);

        if (organization == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        if (organization.getNextEmployeeSequence() == null
                || organization.getNextEmployeeSequence() < 1) {
            throw new BusinessException("Organization employee sequence is not initialized");
        }

        String sequence = String.format(
                Locale.ROOT, "%0" + MINIMUM_SEQUENCE_WIDTH + "d", organization.getNextEmployeeSequence());
        return normalizedCode(organization) + sequence;
    }

    private Organization findOrganization(String organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    private String normalizedCode(Organization organization) {
        if (organization.getOrganizationCode() == null
                || organization.getOrganizationCode().isBlank()) {
            throw new BusinessException("Organization code is required");
        }
        return organization.getOrganizationCode().trim().toUpperCase(Locale.ROOT);
    }
}
