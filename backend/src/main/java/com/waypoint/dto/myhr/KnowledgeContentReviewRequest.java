package com.waypoint.dto.myhr;

import com.waypoint.enums.KnowledgeContentReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The fingerprints prove the reviewer saw this exact pending source and normalized text. */
public record KnowledgeContentReviewRequest(
        @NotBlank @Size(max = 100) String expectedSourceDocumentId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String findingsFingerprint,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String indexableTextFingerprint,
        @NotNull KnowledgeContentReviewDecision decision,
        @Size(max = 32) List<@NotBlank @Size(max = 100) String> excludedPassageIds) {
}
