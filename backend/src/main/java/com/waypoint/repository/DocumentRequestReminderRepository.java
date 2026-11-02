package com.waypoint.repository;

import com.waypoint.entity.DocumentRequestReminder;
import com.waypoint.enums.DocumentRequestReminderType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRequestReminderRepository extends MongoRepository<DocumentRequestReminder, String> {

    /** The existing claim for a request, type and due date (used after a duplicate-key insert). */
    Optional<DocumentRequestReminder> findByDocumentRequestIdAndReminderTypeAndRequiredBy(
            String documentRequestId, DocumentRequestReminderType reminderType, LocalDate requiredBy);

    /** All existing claims for these requests, loaded once per run so already-handled reminders are skipped cheaply. */
    List<DocumentRequestReminder> findByDocumentRequestIdIn(Collection<String> documentRequestIds);

    /**
     * Atomically re-claims a FAILED reminder for another attempt. Returns 1 only for the single caller that wins,
     * and only while attempts is below maxAttempts, so a retry can never be sent twice.
     */
    @Query("{ '_id': ?0, 'status': 'FAILED', 'attempts': { '$lt': ?1 } }")
    @Update("{ '$set': { 'status': 'PROCESSING', 'updatedAt': ?2 }, '$inc': { 'attempts': 1 } }")
    long claimFailedForRetry(String id, int maxAttempts, LocalDateTime at);
}
