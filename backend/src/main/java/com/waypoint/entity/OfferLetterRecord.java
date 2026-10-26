package com.waypoint.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Offer variables and immutable generated-document history; legal wording remains server-side. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "offer_letter_records")
public class OfferLetterRecord {
    @Id private String id;
    @Indexed private String organizationId;
    @Indexed private String documentRequestId;
    @Indexed private String employeeUserId;
    private String offerType;
    private String employmentCategory;
    private String designation;
    private LocalDate offerDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String workLocation;
    private String reportingManager;
    private String internshipSchedule;
    private String compensationType;
    private String compensationAmount;
    private String payFrequency;
    private String timesheetRequirement;
    private String benefitsEligibility;
    private String issuerName;
    private String issuerDesignation;
    /** Immutable text snapshot of the letter HR reviewed before generation. */
    private String previewText;
    private String employeeName;
    private String employeeAddress;
    private String employeeId;
    private String employeeEmail;
    private String organizationName;
    private String organizationAddress;
    private String status;
    private List<OfferLetterVersion> versions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
