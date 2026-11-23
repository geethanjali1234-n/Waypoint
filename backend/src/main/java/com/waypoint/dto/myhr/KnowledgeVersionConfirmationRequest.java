package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Names the exact version HR reviewed: the pending stored document when accepting incomplete
 * text, or the pending ingestion when activating.
 */
public record KnowledgeVersionConfirmationRequest(
        @NotBlank(message = "The reviewed version is required")
        @Size(max = 100, message = "The reviewed version is invalid")
        String expectedVersionId) {
}
