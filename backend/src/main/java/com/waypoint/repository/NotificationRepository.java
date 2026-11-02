package com.waypoint.repository;

import com.waypoint.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends MongoRepository<Notification, String> {

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId, Pageable pageable);

    List<Notification> findByOrganizationIdAndRecipientUserIdOrderByCreatedAtDesc(
            String organizationId, String recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndIsReadFalse(String recipientUserId);

    Optional<Notification> findByIdAndRecipientUserId(String id, String recipientUserId);

    List<Notification> findByRecipientUserIdAndIsReadFalse(String recipientUserId);

    /**
     * Targeted read-state updates. They $set only isRead/readAt, so a notification is never re-saved as a whole
     * (which would overwrite a stored notificationType this build reads as UNKNOWN).
     */
    @Query("{ '_id': ?0, 'recipientUserId': ?1, 'isRead': { $ne: true } }")
    @Update("{ '$set': { 'isRead': true, 'readAt': ?2 } }")
    long markReadByIdAndRecipientUserId(String id, String recipientUserId, LocalDateTime readAt);

    @Query("{ 'recipientUserId': ?0, 'isRead': false }")
    @Update("{ '$set': { 'isRead': true, 'readAt': ?1 } }")
    long markAllReadByRecipientUserId(String recipientUserId, LocalDateTime readAt);
}
