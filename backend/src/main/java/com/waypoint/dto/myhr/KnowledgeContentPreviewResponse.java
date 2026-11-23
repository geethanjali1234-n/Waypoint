package com.waypoint.dto.myhr;

import com.waypoint.service.DocumentTextExtractor.FindingCategory;

import java.util.List;

/**
 * Bounded, read-only review representation. It contains normalized plain text only: never source
 * bytes, markup, storage details, user ids, or credentials. Clients must render text as text.
 */
public record KnowledgeContentPreviewResponse(
        String sourceDocumentId,
        String findingsFingerprint,
        String indexableTextFingerprint,
        String indexableText,
        List<Passage> passages,
        boolean contentReviewRequired,
        boolean incompleteTextReviewRequired) {

    public record Passage(String id, FindingCategory category, String sourceLocation,
                          int startOffset, int endOffset, boolean excludedFromIndex,
                          boolean requiresReview, String text) {
    }
}
