package com.waypoint.service;

import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.service.DocumentTextExtractor.ContentInspection;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validation for metadata persisted with a pending knowledge version; it never stores document text. */
final class KnowledgeContentReviewMetadata {
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private KnowledgeContentReviewMetadata() {
    }

    static String fingerprint(ContentInspection inspection) {
        return KnowledgeApprovalService.sha256(inspection.canonicalForm().getBytes(StandardCharsets.UTF_8));
    }

    static String textFingerprint(String text) {
        return KnowledgeApprovalService.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String passagesFingerprint(List<DocumentTextExtractor.ReviewablePassage> passages) {
        String canonical = passages == null ? "" : passages.stream()
                .map(p -> p.id() + "|" + p.category() + "|" + p.sourceLocation() + "|" + p.startOffset()
                        + "|" + p.endOffset() + "|" + p.excludedFromIndex() + "|" + p.requiresReview()
                        + "|" + textFingerprint(p.text() == null ? "" : p.text()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return textFingerprint(canonical);
    }

    static boolean hasValidReviewablePassages(KnowledgeDocumentVersion version,
                                              DocumentTextExtractor.ExtractedText extracted) {
        return version != null && extracted != null && validHash(version.getReviewablePassagesSha256())
                && Objects.equals(version.getReviewablePassagesSha256(),
                passagesFingerprint(extracted.inspection().reviewablePassages(extracted.text())));
    }

    static boolean hasValidInspection(KnowledgeDocumentVersion version) {
        if (version == null || version.getInspectionFormatVersion() == null
                || version.getInspectionFormatVersion() != ContentInspection.FORMAT_VERSION
                || !validCount(version.getHiddenDocxRunsExcluded())
                || !validCount(version.getSuspiciousTextFragments())
                || !validCount(version.getInvisibleUnicodeCharacters())
                || !validCount(version.getUnicodeTagCharactersRemoved())
                || !validCount(version.getHiddenCommentBlocks())
                || !validCount(version.getInstructionLikeContentMatches())
                || !validCount(version.getPagesRequiringOcrReview())
                || version.getContentReviewRequired() == null
                || !validHash(version.getInspectionFindingsSha256())
                || !validHash(version.getIndexableTextSha256())) {
            return false;
        }
        ContentInspection inspection = inspection(version);
        if (!Objects.equals(version.getInspectionFindingsSha256(), fingerprint(inspection))
                || version.getContentReviewRequired() != requiresContentReview(inspection)) {
            return false;
        }
        if (!version.getContentReviewRequired()) {
            return version.getContentReviewDecision() == null && version.getContentReviewedByUserId() == null
                    && version.getContentReviewedAt() == null && version.getReviewedSourceDocumentId() == null
                    && version.getReviewedFindingsSha256() == null && version.getReviewedIndexableTextSha256() == null;
        }
        return reviewFieldsAreAllUnset(version) || hasCompleteReviewRecord(version);
    }

    static boolean hasSatisfiedContentReview(KnowledgeDocumentVersion version) {
        if (version == null || !Boolean.TRUE.equals(version.getContentReviewRequired())) {
            return version != null && Boolean.FALSE.equals(version.getContentReviewRequired());
        }
        // EXCLUDE_FLAGGED_TEXT cannot authorize ingestion until passage offsets exist: ingestion would
        // index the full text, so a stored exclusion decision would silently include what it excludes.
        return version.getContentReviewDecision() == KnowledgeContentReviewDecision.INCLUDE_FLAGGED_TEXT
                && !isBlank(version.getContentReviewedByUserId()) && version.getContentReviewedAt() != null
                && Objects.equals(version.getSourceDocumentId(), version.getReviewedSourceDocumentId())
                && Objects.equals(version.getInspectionFindingsSha256(), version.getReviewedFindingsSha256())
                && Objects.equals(version.getIndexableTextSha256(), version.getReviewedIndexableTextSha256());
    }

    private static boolean hasCompleteReviewRecord(KnowledgeDocumentVersion version) {
        return version.getContentReviewDecision() != null && !isBlank(version.getContentReviewedByUserId())
                && version.getContentReviewedAt() != null && !isBlank(version.getReviewedSourceDocumentId())
                && validHash(version.getReviewedFindingsSha256()) && validHash(version.getReviewedIndexableTextSha256());
    }

    /** Missing PDF text has its established incomplete-extraction review; these are G2 content findings. */
    static boolean requiresContentReview(ContentInspection inspection) {
        return inspection.hiddenDocxRunsExcluded() > 0 || inspection.suspiciousTextFragments() > 0
                || inspection.invisibleUnicodeCharacters() > 0 || inspection.hiddenCommentBlocks() > 0
                || inspection.instructionLikeContentMatches() > 0;
    }

    static ContentInspection inspection(KnowledgeDocumentVersion version) {
        return new ContentInspection(version.getHiddenDocxRunsExcluded(), version.getSuspiciousTextFragments(),
                version.getInvisibleUnicodeCharacters(), version.getUnicodeTagCharactersRemoved(),
                version.getHiddenCommentBlocks(), version.getInstructionLikeContentMatches(),
                version.getPagesRequiringOcrReview());
    }

    private static boolean reviewFieldsAreAllUnset(KnowledgeDocumentVersion version) {
        return version.getContentReviewDecision() == null && version.getContentReviewedByUserId() == null
                && version.getContentReviewedAt() == null && version.getReviewedSourceDocumentId() == null
                && version.getReviewedFindingsSha256() == null && version.getReviewedIndexableTextSha256() == null;
    }

    private static boolean validCount(Integer value) {
        return value != null && value >= 0;
    }

    private static boolean validHash(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
