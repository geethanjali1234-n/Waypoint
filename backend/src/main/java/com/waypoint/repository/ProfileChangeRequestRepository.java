package com.waypoint.repository;

import com.waypoint.entity.ProfileChangeRequest;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.ProfileChangeField;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProfileChangeRequestRepository extends MongoRepository<ProfileChangeRequest, String> {

    List<ProfileChangeRequest> findByOrganizationIdAndRequesterUserIdOrderByRequestedAtDesc(
            String organizationId, String requesterUserId);

    List<ProfileChangeRequest> findByOrganizationIdAndAssignedToUserIdAndStatusOrderByRequestedAtDesc(
            String organizationId, String assignedToUserId, ProfileChangeStatus status);

    List<ProfileChangeRequest> findByOrganizationIdAndAssignedToUserIdOrderByRequestedAtDesc(
            String organizationId, String assignedToUserId);

    List<ProfileChangeRequest> findByOrganizationIdOrderByRequestedAtDesc(String organizationId);

    boolean existsByOrganizationIdAndRequesterUserIdAndFieldNameAndStatus(
            String organizationId, String requesterUserId, ProfileChangeField fieldName, ProfileChangeStatus status);
}
