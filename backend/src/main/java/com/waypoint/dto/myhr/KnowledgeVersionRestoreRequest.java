package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Selects one historical source; the server verifies it belongs to this document and organization. */
public record KnowledgeVersionRestoreRequest(
        @NotBlank @Size(max = 100) String expectedHistoricalSourceDocumentId) {
}
