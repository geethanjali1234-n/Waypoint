package com.waypoint.entity;

import com.waypoint.enums.DocumentRequestReminderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One automatic reminder for one document request and due date. The record is inserted (claimed) before the
 * notification is sent; the unique index means only one claim can ever exist for a request, type and due date,
 * so the same reminder is never sent twice - even by two application instances. Indexes are created by
 * DocumentRequestIndexInitializer because automatic index creation is disabled.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "document_request_reminders")
@CompoundIndex(name = "document_request_reminder_unique",
        def = "{'documentRequestId': 1, 'reminderType': 1, 'requiredBy': 1}", unique = true)
public class DocumentRequestReminder {

    public enum Status {
        /** Claimed; the notification is being sent. Never resent automatically if the run stops here. */
        PROCESSING,
        /** Handed to NotificationOrchestrator (its in-app and email delivery is best effort). */
        SENT,
        /** Not sent on purpose, e.g. the request is no longer pending or the recipient is inactive. */
        SKIPPED,
        /** Sending failed before the notification was handed off; may be retried a limited number of times. */
        FAILED
    }

    @Id
    private String id;

    private String organizationId;
    private String documentRequestId;
    private DocumentRequestReminderType reminderType;
    /** The request's due date when this reminder was claimed. */
    private LocalDate requiredBy;
    private String recipientUserId;
    private Status status;
    /** How many times sending was attempted (1 for the first claim). */
    private int attempts;
    /** Short skip reason or failure type; never personal data. */
    private String detail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
