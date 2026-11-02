package com.waypoint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Global state for document request reminders. Holds one document ("activation"): the date reminders first ran
 * while enabled. Requests due before that date never get an overdue alert. Written once and never changed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "document_request_reminder_state")
public class DocumentRequestReminderState {
    public static final String ACTIVATION_ID = "activation";

    @Id
    private String id;
    private LocalDate activatedOn;
    private LocalDateTime activatedAt;
}
