package com.waypoint.service;

import com.waypoint.dto.myhr.KnowledgeContentPreviewResponse;
import com.waypoint.dto.myhr.KnowledgeContentReviewRequest;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.VerifiedMyHrActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Provides the exact normalized text used by ingestion and records a reviewer decision bound to
 * its source, inspection and text hashes. Document bytes are retained only while extracting and
 * are never logged or returned.
 */
@Service
public class KnowledgeContentReviewService {
    private static final int MAX_PREVIEW_TEXT_CHARS = 100_000;
    static final String NOT_FOUND = KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_NOT_FOUND;
    static final String NO_PENDING_REVIEW = "This knowledge document has no pending content review";
    static final String STALE_REVIEW = "The pending version changed; reopen the content preview before reviewing";
    static final String PREVIEW_UNAVAILABLE = "The pending source could not be verified for content review";
    static final String EXCLUSION_NOT_AVAILABLE =
            "Excluding flagged indexed text is not available until passage-level extraction offsets are preserved";
    static final String REVIEW_CONFLICT = "The pending version changed while the review was being saved";
    static final String PREVIEW_TOO_LARGE = "The pending extracted text is too large for secure review; approve a replacement";

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeSourceDocumentReader sourceReader;
    private final DocumentTextExtractor extractor;
    private final Clock clock;
    private final boolean managementEnabled;

    public KnowledgeContentReviewService(KnowledgeDocumentRepository documents,
                                         KnowledgeSourceDocumentReader sourceReader,
                                         DocumentTextExtractor extractor,
                                         Clock clock,
                                         @Value("${waypoint.myhr.knowledge.management.enabled:false}") boolean managementEnabled) {
        this.documents = documents;
        this.sourceReader = sourceReader;
        this.extractor = extractor;
        this.clock = clock;
        this.managementEnabled = managementEnabled;
    }

    /**
     * The only route that returns policy text, so it follows the management switch like the decision
     * it prepares: where management is off (for example a read-only shared environment) no decision
     * can be made, and the pending text is not served either.
     */
    public KnowledgeContentPreviewResponse preview(VerifiedMyHrActor actor, String documentId) {
        KnowledgeDocumentVersion pending = pending(actor, documentId);
        if (!managementEnabled) throw new BusinessException(KnowledgeManagementService.MANAGEMENT_DISABLED);
        DocumentTextExtractor.ExtractedText extracted = extract(actor, pending);
        verify(pending, extracted);
        if (extracted.text().length() > MAX_PREVIEW_TEXT_CHARS) throw new BusinessException(PREVIEW_TOO_LARGE);
        return response(pending, extracted);
    }

    public KnowledgeDocument review(VerifiedMyHrActor actor, String documentId, KnowledgeContentReviewRequest request) {
        if (!managementEnabled) throw new BusinessException(KnowledgeManagementService.MANAGEMENT_DISABLED);
        if (request == null) throw new BusinessException(STALE_REVIEW);
        KnowledgeDocument entry = entry(actor, documentId);
        if (entry.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new BusinessException(KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_REVOKED);
        }
        KnowledgeDocumentVersion pending = requireReviewable(entry);
        DocumentTextExtractor.ExtractedText extracted = extract(actor, pending);
        verify(pending, extracted);
        if (extracted.text().length() > MAX_PREVIEW_TEXT_CHARS) throw new BusinessException(PREVIEW_TOO_LARGE);
        if (!Objects.equals(pending.getSourceDocumentId(), request.expectedSourceDocumentId())
                || !Objects.equals(pending.getInspectionFindingsSha256(), request.findingsFingerprint())
                || !Objects.equals(pending.getIndexableTextSha256(), request.indexableTextFingerprint())) {
            throw new BusinessException(STALE_REVIEW);
        }
        List<String> excluded = request.excludedPassageIds() == null ? List.of() : List.copyOf(request.excludedPassageIds());
        if (request.decision() == KnowledgeContentReviewDecision.EXCLUDE_FLAGGED_TEXT) {
            // The current extractors normalize formatting before offsets survive. Reject rather than
            // remove a guessed range or silently change what the reviewer saw.
            throw new BusinessException(EXCLUSION_NOT_AVAILABLE);
        }
        if (!excluded.isEmpty()) throw new BusinessException(EXCLUSION_NOT_AVAILABLE);

        pending.setContentReviewDecision(request.decision());
        pending.setContentReviewedByUserId(actor.userId());
        pending.setContentReviewedAt(LocalDateTime.now(clock));
        pending.setReviewedSourceDocumentId(pending.getSourceDocumentId());
        pending.setReviewedFindingsSha256(pending.getInspectionFindingsSha256());
        pending.setReviewedIndexableTextSha256(pending.getIndexableTextSha256());
        if (request.decision() == KnowledgeContentReviewDecision.REPLACE_DOCUMENT
                || request.decision() == KnowledgeContentReviewDecision.DO_NOT_APPROVE) {
            pending.setStage(KnowledgeIngestionStage.FAILED);
            pending.setFailureReason(KnowledgeIngestionFailure.CONTENT_REVIEW_REJECTED);
            pending.setFailedAt(LocalDateTime.now(clock));
        } else if (request.decision() == KnowledgeContentReviewDecision.INCLUDE_FLAGGED_TEXT
                && (pending.getPagesWithoutText() == null || pending.getPagesWithoutText() == 0
                || pending.getIncompleteExtractionAcceptedAt() != null)) {
            pending.setStage(KnowledgeIngestionStage.APPROVED);
        } else {
            pending.setStage(KnowledgeIngestionStage.NEEDS_REVIEW);
        }
        try {
            return documents.save(entry);
        } catch (OptimisticLockingFailureException conflict) {
            throw new BusinessException(REVIEW_CONFLICT);
        }
    }

    private KnowledgeDocumentVersion pending(VerifiedMyHrActor actor, String documentId) {
        return requireReviewable(entry(actor, documentId));
    }

    private KnowledgeDocument entry(VerifiedMyHrActor actor, String documentId) {
        if (actor == null || !actor.canManageKnowledge()) throw new ResourceNotFoundException(NOT_FOUND);
        return documents.findByIdAndOrganizationId(documentId, actor.organizationId())
                .filter(found -> actor.organizationId().equals(found.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private static KnowledgeDocumentVersion requireReviewable(KnowledgeDocument entry) {
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        if (pending == null || !Boolean.TRUE.equals(pending.getContentReviewRequired())
                || pending.getStage() != KnowledgeIngestionStage.NEEDS_REVIEW) {
            throw new BusinessException(NO_PENDING_REVIEW);
        }
        return pending;
    }

    private DocumentTextExtractor.ExtractedText extract(VerifiedMyHrActor actor, KnowledgeDocumentVersion pending) {
        try {
            KnowledgeSourceDocumentReader.VerifiedSourceContent source = sourceReader.read(
                    actor.organizationId(), pending.getSourceDocumentId(), pending.getContentSha256());
            return extractor.extract(source.content(), pending.getSourceDocumentName());
        } catch (RuntimeException unavailable) {
            throw new BusinessException(PREVIEW_UNAVAILABLE);
        }
    }

    private static void verify(KnowledgeDocumentVersion pending, DocumentTextExtractor.ExtractedText extracted) {
        if (extracted == null || extracted.inspection() == null
                || !KnowledgeContentReviewMetadata.hasValidInspection(pending)
                || !KnowledgeContentReviewMetadata.hasValidReviewablePassages(pending, extracted)
                || !Objects.equals(pending.getInspectionFindingsSha256(),
                KnowledgeContentReviewMetadata.fingerprint(extracted.inspection()))
                || !Objects.equals(pending.getIndexableTextSha256(),
                KnowledgeContentReviewMetadata.textFingerprint(extracted.text()))) {
            throw new BusinessException(PREVIEW_UNAVAILABLE);
        }
    }

    private static KnowledgeContentPreviewResponse response(
            KnowledgeDocumentVersion pending, DocumentTextExtractor.ExtractedText extracted) {
        List<KnowledgeContentPreviewResponse.Passage> passages = extracted.inspection().reviewablePassages(extracted.text())
                .stream().map(p -> new KnowledgeContentPreviewResponse.Passage(p.id(), p.category(), p.sourceLocation(),
                        p.startOffset(), p.endOffset(), p.excludedFromIndex(), p.requiresReview(), p.text())).toList();
        return new KnowledgeContentPreviewResponse(pending.getSourceDocumentId(), pending.getInspectionFindingsSha256(),
                pending.getIndexableTextSha256(), extracted.text(), passages, true,
                pending.getPagesWithoutText() != null && pending.getPagesWithoutText() > 0
                        && pending.getIncompleteExtractionAcceptedAt() == null);
    }
}
