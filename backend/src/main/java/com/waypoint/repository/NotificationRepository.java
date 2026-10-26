package com.waypoint.repository;

import com.waypoint.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends MongoRepository<Notification, String> {

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId, Pageable pageable);

    List<Notification> findByOrganizationIdAndRecipientUserIdOrderByCreatedAtDesc(
            String organizationId, String recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndIsReadFalse(String recipientUserId);

    Optional<Notification> findByIdAndRecipientUserId(String id, String recipientUserId);

    List<Notification> findByRecipientUserIdAndIsReadFalse(String recipientUserId);
}
