package com.waypoint.dto.myhr;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** Editable variables only. Template clauses are selected and rendered on the server. */
public record OfferLetterInput(
        @NotBlank String offerType, @NotBlank String employmentCategory, @NotBlank @Size(max = 160) String designation,
        @NotNull LocalDate offerDate, @NotNull LocalDate startDate, LocalDate endDate,
        @NotBlank @Size(max = 240) String workLocation, @Size(max = 160) String reportingManager,
        @Size(max = 240) String internshipSchedule, String compensationType, String compensationAmount,
        String payFrequency, String timesheetRequirement, String benefitsEligibility,
        @NotBlank @Size(max = 160) String issuerName, @NotBlank @Size(max = 160) String issuerDesignation,
        @Size(max = 50000) String previewText) { }
