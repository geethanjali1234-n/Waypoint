package com.waypoint.service;

import com.waypoint.dto.platform.WaypointAdminOrganizationDetail;
import com.waypoint.dto.platform.WaypointAdminOrganizationPageResponse;
import com.waypoint.dto.platform.WaypointAdminOrganizationSummary;
import com.waypoint.entity.Organization;
import com.waypoint.enums.OrganizationStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.WaypointAdminAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WaypointAdminOrganizationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrganizationRepository organizationRepository;
    private final WaypointAdminAuthenticatedUserService authenticatedUserService;

    public WaypointAdminOrganizationPageResponse list(String actorUserId, int page, int size, String search) {
        authenticatedUserService.require(actorUserId);
        validatePage(page, size);

        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("organizationName"), Sort.Order.asc("id")));
        String normalizedSearch = search == null ? "" : search.trim();
        Page<Organization> organizations = normalizedSearch.isEmpty()
                ? organizationRepository.findAll(pageable)
                : organizationRepository
                        .findByOrganizationNameContainingIgnoreCaseOrOrganizationCodeContainingIgnoreCaseOrOrganizationEmailContainingIgnoreCase(
                                normalizedSearch, normalizedSearch, normalizedSearch, pageable);

        return new WaypointAdminOrganizationPageResponse(
                organizations.getContent().stream().map(this::toSummary).toList(),
                organizations.getNumber(), organizations.getSize(),
                organizations.getTotalElements(), organizations.getTotalPages());
    }

    public WaypointAdminOrganizationDetail get(String actorUserId, String organizationId) {
        authenticatedUserService.require(actorUserId);
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return toDetail(organization);
    }

    private void validatePage(int page, int size) {
        if (page < 0) throw new BusinessException("Page must be zero or greater");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("Size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private WaypointAdminOrganizationSummary toSummary(Organization organization) {
        return new WaypointAdminOrganizationSummary(
                organization.getId(), organization.getOrganizationCode(), organization.getOrganizationName(),
                statusOf(organization), organization.getOrganizationEmail(), organization.getCity(), organization.getState(),
                organization.getIndustry(), organization.getOrganizationSize(), organization.getCreatedAt());
    }

    private WaypointAdminOrganizationDetail toDetail(Organization organization) {
        return new WaypointAdminOrganizationDetail(
                organization.getId(), organization.getOrganizationCode(), organization.getOrganizationName(),
                statusOf(organization), organization.getOrganizationEmail(), organization.getPhoneNumber(), organization.getState(),
                organization.getCity(), organization.getStreetAddress(), organization.getZipCode(),
                organization.getOrganizationSize(), organization.getDescription(), organization.getIndustry(),
                organization.getWebsite(), organization.getCreatedAt(), organization.getUpdatedAt());
    }

    private OrganizationStatus statusOf(Organization organization) {
        return organization.getStatus() == null ? OrganizationStatus.ACTIVE : organization.getStatus();
    }
}
