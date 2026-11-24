package com.waypoint.service;

import com.waypoint.dto.myhr.ExternalProcessingApprovalResponse;
import com.waypoint.dto.myhr.ExternalProcessingStatusResponse;
import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalCoverage;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.VerifiedMyHrActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Records, revokes and lists Company Admin approvals for sending company content to an external AI provider.
 *
 * <p>Rules that no approval can override: EMPLOYEE_PRIVATE content is never approvable; Claude may only ever be
 * approved for COMPANY_WIDE content; Voyage may be approved for COMPANY_WIDE and HR_ONLY policy text. A document
 * approval covers exactly one activated version of a LIVE company-wide policy and requires the organization's own
 * approval for the same provider first. Internal read permission and knowledge-base approval are not external
 * approval: nothing here is ever granted without a Company Admin's recorded decision. The one broader decision is an
 * organization approval recorded with ALL_PUBLISHED_COMPANY_WIDE coverage, which covers each policy's published
 * company-wide version for Claude unless the policy is excluded (DOCUMENT_EXCLUSION) or its approval was revoked
 * without a later decision. Existing records without a coverage keep per-version approval.
 *
 * <p>Only a Company Admin may approve or revoke; HR may view. Every change is written to the audit trail.
 */
@Service
public class ExternalProcessingApprovalService {

    static final String ADMIN_ONLY = "Only a Company Admin can approve or revoke external AI processing";
    static final String VIEW_NOT_PERMITTED = "Only HR or company administrators can view external AI processing approvals";
    static final String PROVIDER_REQUIRED = "An external AI provider is required";
    static final String ACKNOWLEDGEMENT_REQUIRED =
            "Confirm that your organization has approved sending this content to the external AI provider";
    static final String REFERENCE_REQUIRED = "An approval reference (for example the agreement or ticket) is required";
    static final String REFERENCE_INVALID = "Approval references must be 3 to 200 characters of plain text";
    static final String REASON_INVALID = "Revocation reasons must be 3 to 500 characters of plain text";
    static final String ACCESS_LEVELS_REQUIRED = "Choose the content access levels this approval covers";
    static final String PRIVATE_NEVER_APPROVABLE = "Employee-private content can never be approved for external AI processing";
    static final String CLAUDE_COMPANY_WIDE_ONLY =
            "Claude can only be approved for company-wide policy; HR-only content always stays on the local model";
    static final String ORGANIZATION_ALREADY_APPROVED =
            "External processing is already approved for this provider; revoke it first to change its scope";
    static final String NOTHING_TO_REVOKE = "There is no active approval to revoke";
    static final String DOCUMENTS_CLAUDE_ONLY = "Document approvals apply to Claude answer generation only";
    static final String ORGANIZATION_APPROVAL_REQUIRED =
            "Approve Claude for company-wide policy at organization level before approving documents";
    static final String DOCUMENT_ID_REQUIRED = "Knowledge document ID is required";
    static final String DOCUMENT_NOT_FOUND = "Knowledge document not found";
    static final String DOCUMENT_NOT_ACTIVE =
            "Only a live policy with an activated version can be approved for external AI processing";
    static final String DOCUMENT_NOT_COMPANY_WIDE =
            "Only company-wide policies can be approved for Claude; HR-only policies always stay on the local model";
    static final String VERSION_CHANGED = "The policy's active version changed; reload it and review the new version";
    static final String DOCUMENT_ALREADY_APPROVED = "This policy version is already approved for Claude";
    static final String DOCUMENT_EXCLUDED = "This policy is excluded from Claude; lift the exclusion first";
    static final String DOCUMENT_ALREADY_EXCLUDED = "This policy is already excluded from Claude";
    static final String COVERAGE_REQUIRED = "Choose the coverage for the new approval";
    static final String COVERAGE_UNCHANGED = "The organization approval already has this coverage";
    static final String APPROVAL_CHANGED =
            "The organization approval changed while you were editing it; reload and try again";
    static final String REPLACEMENT_UNAVAILABLE = "Replacing the organization approval is not available right now";
    static final String AUTOMATIC_COVERAGE_CLAUDE_ONLY =
            "Coverage of every published company-wide policy applies to Claude only";

    static final int MAX_REFERENCE_CHARS = 200;
    static final int MAX_REASON_CHARS = 500;
    private static final Set<ChunkAccessLevel> CLAUDE_APPROVABLE = EnumSet.of(ChunkAccessLevel.COMPANY_WIDE);
    private static final Set<ChunkAccessLevel> VOYAGE_APPROVABLE =
            EnumSet.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
    private static final String ENTITY_TYPE = "EXTERNAL_AI_PROCESSING_APPROVAL";
    private static final Logger log = LoggerFactory.getLogger(ExternalProcessingApprovalService.class);

    private final ExternalProcessingApprovalRepository approvals;
    private final KnowledgeDocumentRepository knowledgeDocuments;
    private final AuditService auditService;
    private final Clock clock;
    private final boolean claudeSwitch;
    private final boolean ingestionSwitch;
    /** Needed only to replace an organization approval atomically; without it replacement is refused. */
    private final ExternalProcessingApprovalTransactions transactions;

    public ExternalProcessingApprovalService(
            ExternalProcessingApprovalRepository approvals,
            KnowledgeDocumentRepository knowledgeDocuments,
            AuditService auditService,
            Clock clock,
            boolean claudeSwitch,
            boolean ingestionSwitch) {
        this(approvals, knowledgeDocuments, auditService, clock, claudeSwitch, ingestionSwitch, null);
    }

    @Autowired
    public ExternalProcessingApprovalService(
            ExternalProcessingApprovalRepository approvals,
            KnowledgeDocumentRepository knowledgeDocuments,
            AuditService auditService,
            Clock clock,
            @Value("${waypoint.myhr.chat.knowledge-grounding.allow-external-provider:false}") boolean claudeSwitch,
            @Value("${waypoint.myhr.knowledge.ingestion.enabled:false}") boolean ingestionSwitch,
            ExternalProcessingApprovalTransactions transactions) {
        this.transactions = transactions;
        this.approvals = approvals;
        this.knowledgeDocuments = knowledgeDocuments;
        this.auditService = auditService;
        this.clock = clock;
        this.claudeSwitch = claudeSwitch;
        this.ingestionSwitch = ingestionSwitch;
    }

    // --- Viewing --------------------------------------------------------------------------------------------

    /** The organization's approvals, newest first, ACTIVE and REVOKED, with whether each still takes effect. */
    public ExternalProcessingStatusResponse status(VerifiedMyHrActor actor) {
        requireViewer(actor);
        List<ExternalProcessingApproval> records = approvals.findByOrganizationIdOrderByApprovedAtDesc(actor.organizationId())
                .stream().filter(record -> record != null && actor.organizationId().equals(record.getOrganizationId()))
                .toList();
        Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();
        List<ExternalProcessingApprovalResponse> responses = records.stream()
                .map(record -> toResponse(record, records, documents, actor.organizationId()))
                .toList();
        return new ExternalProcessingStatusResponse(claudeSwitch, ingestionSwitch,
                activeOrganizationApproval(records, ExternalAiProvider.ANTHROPIC_CLAUDE, ChunkAccessLevel.COMPANY_WIDE) != null,
                activeOrganizationApproval(records, ExternalAiProvider.VOYAGE_AI, ChunkAccessLevel.HR_ONLY) != null,
                responses);
    }

    // --- Organization approvals -----------------------------------------------------------------------------

    public ExternalProcessingApprovalResponse approveOrganization(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                                  Set<ChunkAccessLevel> accessLevels,
                                                                  String approvalReference, boolean acknowledged) {
        return approveOrganization(actor, provider, accessLevels, approvalReference, acknowledged, null);
    }

    /**
     * coverage: REVIEWED_VERSIONS (the default when null) keeps per-version approval; ALL_PUBLISHED_COMPANY_WIDE lets
     * Claude use the published version of every company-wide policy that is not excluded. Only Claude supports the
     * automatic coverage, and changing coverage needs a fresh approval (revoke, then approve again).
     */
    public ExternalProcessingApprovalResponse approveOrganization(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                                  Set<ChunkAccessLevel> accessLevels,
                                                                  String approvalReference, boolean acknowledged,
                                                                  ExternalProcessingApprovalCoverage coverage) {
        requireAdmin(actor);
        requireProvider(provider);
        requireAcknowledged(acknowledged);
        String reference = requireText(approvalReference, MAX_REFERENCE_CHARS, REFERENCE_REQUIRED, REFERENCE_INVALID);
        Set<ChunkAccessLevel> levels = requireApprovableLevels(provider, accessLevels);
        ExternalProcessingApprovalCoverage scopeOfDocuments =
                coverage == null ? ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS : coverage;
        if (scopeOfDocuments == ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE
                && provider != ExternalAiProvider.ANTHROPIC_CLAUDE) {
            throw new BusinessException(AUTOMATIC_COVERAGE_CLAUDE_ONLY);
        }
        boolean alreadyActive = approvals.findByOrganizationIdAndProviderAndStatus(
                        actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                .anyMatch(record -> record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION
                        && actor.organizationId().equals(record.getOrganizationId()));
        if (alreadyActive) {
            throw new BusinessException(ORGANIZATION_ALREADY_APPROVED);
        }
        ExternalProcessingApproval saved = approvals.save(ExternalProcessingApproval.builder()
                .organizationId(actor.organizationId())
                .provider(provider)
                .scope(ExternalProcessingApprovalScope.ORGANIZATION)
                .status(ExternalProcessingApprovalStatus.ACTIVE)
                .accessLevels(levels)
                .coverage(scopeOfDocuments == ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS ? null : scopeOfDocuments)
                .approvalReference(reference)
                .approvedByUserId(actor.userId())
                .approvedAt(LocalDateTime.now(clock))
                .build());
        audit(actor, AuditActionType.EXTERNAL_AI_PROCESSING_APPROVED, saved,
                label(provider) + " approved for " + levels + " content at organization level"
                        + (scopeOfDocuments == ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE
                        ? ", covering every published company-wide policy that is not excluded" : ""));
        log.info("External AI processing approved: provider={} scope=ORGANIZATION accessLevels={} coverage={}",
                provider, levels, scopeOfDocuments);
        return toResponse(saved, List.of(saved), new LinkedHashMap<>(), actor.organizationId());
    }

    /**
     * Replaces the organization's active Claude approval with one of a different coverage in a single transaction:
     * the old record is revoked (with reason) and the new one recorded, or - on any failure, including a concurrent
     * change - neither, so the previous approval stays in force. expectedApprovalId is the approval the admin was
     * looking at; if it is no longer the active one the request is refused (duplicate or concurrent change).
     * Audit events are written after commit, so a rolled-back replacement is never audited as done.
     */
    public ExternalProcessingApprovalResponse replaceOrganizationCoverage(
            VerifiedMyHrActor actor, ExternalAiProvider provider, String expectedApprovalId,
            ExternalProcessingApprovalCoverage coverage, String approvalReference, boolean acknowledged, String reason) {
        requireAdmin(actor);
        requireProvider(provider);
        if (provider != ExternalAiProvider.ANTHROPIC_CLAUDE) {
            throw new BusinessException(AUTOMATIC_COVERAGE_CLAUDE_ONLY);
        }
        if (coverage == null) {
            throw new BusinessException(COVERAGE_REQUIRED);
        }
        requireAcknowledged(acknowledged);
        String reference = requireText(approvalReference, MAX_REFERENCE_CHARS, REFERENCE_REQUIRED, REFERENCE_INVALID);
        String why = requireText(reason, MAX_REASON_CHARS, REASON_INVALID, REASON_INVALID);
        if (isBlank(expectedApprovalId)) {
            throw new BusinessException(APPROVAL_CHANGED);
        }
        if (transactions == null) {
            throw new BusinessException(REPLACEMENT_UNAVAILABLE);
        }
        ExternalProcessingApproval[] result;
        try {
            result = transactions.inTransaction(() -> {
                List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                                actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                        .filter(record -> record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION
                                && actor.organizationId().equals(record.getOrganizationId()))
                        .toList();
                if (active.size() != 1 || !expectedApprovalId.equals(active.get(0).getId())) {
                    throw new BusinessException(APPROVAL_CHANGED);
                }
                ExternalProcessingApproval previous = active.get(0);
                if (previous.effectiveCoverage() == coverage) {
                    throw new BusinessException(COVERAGE_UNCHANGED);
                }
                LocalDateTime now = LocalDateTime.now(clock);
                previous.setStatus(ExternalProcessingApprovalStatus.REVOKED);
                previous.setRevokedByUserId(actor.userId());
                previous.setRevokedAt(now);
                previous.setRevocationReason(why);
                ExternalProcessingApproval revoked = approvals.save(previous);
                ExternalProcessingApproval replacement = approvals.save(ExternalProcessingApproval.builder()
                        .organizationId(actor.organizationId())
                        .provider(provider)
                        .scope(ExternalProcessingApprovalScope.ORGANIZATION)
                        .status(ExternalProcessingApprovalStatus.ACTIVE)
                        .accessLevels(EnumSet.copyOf(previous.getAccessLevels()))
                        .coverage(coverage == ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS ? null : coverage)
                        .approvalReference(reference)
                        .approvedByUserId(actor.userId())
                        .approvedAt(now)
                        .build());
                return new ExternalProcessingApproval[] {revoked, replacement};
            });
        } catch (TransientDataAccessException | TransactionException concurrent) {
            // Optimistic-lock and MongoDB write conflicts (a concurrent change); the transaction rolled back.
            throw new BusinessException(APPROVAL_CHANGED);
        }
        audit(actor, AuditActionType.EXTERNAL_AI_PROCESSING_REVOKED, result[0],
                label(provider) + " organization approval revoked to change its coverage");
        audit(actor, AuditActionType.EXTERNAL_AI_PROCESSING_APPROVED, result[1],
                label(provider) + " approved for " + result[1].getAccessLevels() + " content at organization level with "
                        + coverage + " coverage, replacing approval " + result[0].getId());
        log.info("External AI processing coverage replaced: provider={} coverage={}", provider, coverage);
        return toResponse(result[1], List.of(result[1]), new LinkedHashMap<>(), actor.organizationId());
    }

    /** Revokes the provider's organization approval; every document approval under it stops taking effect too. */
    public List<ExternalProcessingApprovalResponse> revokeOrganization(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                                       String reason) {
        requireAdmin(actor);
        requireProvider(provider);
        String why = requireText(reason, MAX_REASON_CHARS, REASON_INVALID, REASON_INVALID);
        List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                        actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                .filter(record -> record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION
                        && actor.organizationId().equals(record.getOrganizationId()))
                .toList();
        return revokeAll(actor, active, why, label(provider) + " organization approval revoked");
    }

    // --- Document approvals ---------------------------------------------------------------------------------

    /**
     * Approves exactly the activated version the admin reviewed (expectedIngestionId) of a LIVE company-wide policy
     * for Claude. A later version, a revocation, or a change of access level is never covered.
     */
    public ExternalProcessingApprovalResponse approveDocument(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                              String knowledgeDocumentId, String expectedIngestionId,
                                                              String approvalReference, boolean acknowledged) {
        requireAdmin(actor);
        requireProvider(provider);
        if (provider != ExternalAiProvider.ANTHROPIC_CLAUDE) {
            throw new BusinessException(DOCUMENTS_CLAUDE_ONLY);
        }
        requireAcknowledged(acknowledged);
        String reference = requireText(approvalReference, MAX_REFERENCE_CHARS, REFERENCE_REQUIRED, REFERENCE_INVALID);
        if (isBlank(knowledgeDocumentId) || isBlank(expectedIngestionId)) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
        List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                .filter(record -> actor.organizationId().equals(record.getOrganizationId())).toList();
        if (activeOrganizationApproval(active, provider, ChunkAccessLevel.COMPANY_WIDE) == null) {
            throw new BusinessException(ORGANIZATION_APPROVAL_REQUIRED);
        }
        KnowledgeDocument entry = knowledgeDocuments.findByIdAndOrganizationId(knowledgeDocumentId, actor.organizationId())
                .filter(found -> actor.organizationId().equals(found.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(DOCUMENT_NOT_FOUND));
        KnowledgeDocumentVersion version = entry.getActiveVersion();
        if (!isActivated(entry)) {
            throw new BusinessException(DOCUMENT_NOT_ACTIVE);
        }
        if (version.getAccessLevel() != ChunkAccessLevel.COMPANY_WIDE) {
            throw new ForbiddenException(DOCUMENT_NOT_COMPANY_WIDE);
        }
        if (!expectedIngestionId.equals(version.getIngestionId())) {
            throw new BusinessException(VERSION_CHANGED);
        }
        if (RecordedExternalPolicyProcessingAuthorization.isExcluded(active, entry.getId())) {
            throw new BusinessException(DOCUMENT_EXCLUDED);
        }
        boolean alreadyApproved = active.stream().anyMatch(record ->
                record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION
                        && entry.getId().equals(record.getKnowledgeDocumentId())
                        && version.getIngestionId().equals(record.getIngestionId()));
        if (alreadyApproved) {
            throw new BusinessException(DOCUMENT_ALREADY_APPROVED);
        }
        ExternalProcessingApproval saved = approvals.save(ExternalProcessingApproval.builder()
                .organizationId(actor.organizationId())
                .provider(provider)
                .scope(ExternalProcessingApprovalScope.DOCUMENT_VERSION)
                .status(ExternalProcessingApprovalStatus.ACTIVE)
                .knowledgeDocumentId(entry.getId())
                .ingestionId(version.getIngestionId())
                .sourceDocumentId(version.getSourceDocumentId())
                .contentSha256(version.getContentSha256())
                .documentTitle(entry.getTitle())
                .documentAccessLevel(version.getAccessLevel())
                .approvalReference(reference)
                .approvedByUserId(actor.userId())
                .approvedAt(LocalDateTime.now(clock))
                .build());
        audit(actor, AuditActionType.EXTERNAL_AI_PROCESSING_APPROVED, saved,
                label(provider) + " approved for one version of a company-wide policy");
        log.info("External AI processing approved: provider={} scope=DOCUMENT_VERSION knowledgeDocumentId={} ingestionId={}",
                provider, entry.getId(), version.getIngestionId());
        Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();
        documents.put(entry.getId(), entry);
        return toResponse(saved, active, documents, actor.organizationId());
    }

    /** Revokes every active approval of the document for the provider, whichever version it covered. */
    public List<ExternalProcessingApprovalResponse> revokeDocument(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                                   String knowledgeDocumentId, String reason) {
        requireAdmin(actor);
        requireProvider(provider);
        if (isBlank(knowledgeDocumentId)) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
        String why = requireText(reason, MAX_REASON_CHARS, REASON_INVALID, REASON_INVALID);
        List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                        actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                .filter(record -> record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION
                        && actor.organizationId().equals(record.getOrganizationId())
                        && knowledgeDocumentId.equals(record.getKnowledgeDocumentId()))
                .toList();
        return revokeAll(actor, active, why, label(provider) + " policy approval revoked");
    }

    // --- Document exclusions --------------------------------------------------------------------------------

    /**
     * Excludes one policy (every version) from Claude. It overrides both automatic coverage and version approvals
     * until lifted. reason is recorded as the exclusion's reference. Any document of the organization may be excluded,
     * whatever its state, so a policy can be excluded before it is published.
     */
    public ExternalProcessingApprovalResponse excludeDocument(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                              String knowledgeDocumentId, String reason) {
        requireAdmin(actor);
        requireProvider(provider);
        if (provider != ExternalAiProvider.ANTHROPIC_CLAUDE) {
            throw new BusinessException(DOCUMENTS_CLAUDE_ONLY);
        }
        if (isBlank(knowledgeDocumentId)) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
        String why = requireText(reason, MAX_REASON_CHARS, REASON_INVALID, REASON_INVALID);
        KnowledgeDocument entry = knowledgeDocuments.findByIdAndOrganizationId(knowledgeDocumentId, actor.organizationId())
                .filter(found -> actor.organizationId().equals(found.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(DOCUMENT_NOT_FOUND));
        List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                .filter(record -> actor.organizationId().equals(record.getOrganizationId())).toList();
        if (RecordedExternalPolicyProcessingAuthorization.isExcluded(active, entry.getId())) {
            throw new BusinessException(DOCUMENT_ALREADY_EXCLUDED);
        }
        ExternalProcessingApproval saved = approvals.save(ExternalProcessingApproval.builder()
                .organizationId(actor.organizationId())
                .provider(provider)
                .scope(ExternalProcessingApprovalScope.DOCUMENT_EXCLUSION)
                .status(ExternalProcessingApprovalStatus.ACTIVE)
                .knowledgeDocumentId(entry.getId())
                .documentTitle(entry.getTitle())
                .approvalReference(why)
                .approvedByUserId(actor.userId())
                .approvedAt(LocalDateTime.now(clock))
                .build());
        audit(actor, AuditActionType.EXTERNAL_AI_PROCESSING_EXCLUDED, saved,
                label(provider) + " excluded for every version of a policy");
        log.info("External AI processing excluded: provider={} knowledgeDocumentId={}", provider, entry.getId());
        return toResponse(saved, List.of(saved), new LinkedHashMap<>(), actor.organizationId());
    }

    /** Lifts the policy's active exclusion; recorded and audited as a revocation of the exclusion record. */
    public List<ExternalProcessingApprovalResponse> liftExclusion(VerifiedMyHrActor actor, ExternalAiProvider provider,
                                                                  String knowledgeDocumentId, String reason) {
        requireAdmin(actor);
        requireProvider(provider);
        if (isBlank(knowledgeDocumentId)) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
        String why = requireText(reason, MAX_REASON_CHARS, REASON_INVALID, REASON_INVALID);
        List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                        actor.organizationId(), provider, ExternalProcessingApprovalStatus.ACTIVE).stream()
                .filter(record -> record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_EXCLUSION
                        && actor.organizationId().equals(record.getOrganizationId())
                        && knowledgeDocumentId.equals(record.getKnowledgeDocumentId()))
                .toList();
        return revokeAll(actor, active, why, label(provider) + " policy exclusion lifted");
    }

    // --- Shared ---------------------------------------------------------------------------------------------

    private List<ExternalProcessingApprovalResponse> revokeAll(VerifiedMyHrActor actor,
                                                               List<ExternalProcessingApproval> active, String reason,
                                                               String description) {
        if (active.isEmpty()) {
            throw new BusinessException(NOTHING_TO_REVOKE);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<ExternalProcessingApproval> revoked = active.stream().map(record -> {
            record.setStatus(ExternalProcessingApprovalStatus.REVOKED);
            record.setRevokedByUserId(actor.userId());
            record.setRevokedAt(now);
            record.setRevocationReason(reason);
            ExternalProcessingApproval saved = approvals.save(record);
            audit(actor, AuditActionType.EXTERNAL_AI_PROCESSING_REVOKED, saved, description);
            log.info("External AI processing revoked: provider={} scope={}", saved.getProvider(), saved.getScope());
            return saved;
        }).toList();
        return revoked.stream().map(record -> toResponse(record, revoked, new LinkedHashMap<>(), actor.organizationId()))
                .toList();
    }

    /** The provider's active organization approval covering level, from already organization-scoped records. */
    static ExternalProcessingApproval activeOrganizationApproval(List<ExternalProcessingApproval> records,
                                                                 ExternalAiProvider provider, ChunkAccessLevel level) {
        return records.stream()
                .filter(record -> record.getProvider() == provider
                        && record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION
                        && record.getStatus() == ExternalProcessingApprovalStatus.ACTIVE
                        && record.getAccessLevels() != null && record.getAccessLevels().contains(level)
                        && level != ChunkAccessLevel.EMPLOYEE_PRIVATE)
                .findFirst().orElse(null);
    }

    static boolean isActivated(KnowledgeDocument entry) {
        KnowledgeDocumentVersion version = entry == null ? null : entry.getActiveVersion();
        return entry != null && entry.getStatus() == KnowledgeDocumentStatus.LIVE && version != null
                && version.getStage() == KnowledgeIngestionStage.INDEXED && version.getActivatedAt() != null
                && version.getDocumentType() == DocumentType.COMPANY_POLICY && !isBlank(version.getIngestionId());
    }

    private ExternalProcessingApprovalResponse toResponse(ExternalProcessingApproval record,
                                                          List<ExternalProcessingApproval> organizationRecords,
                                                          Map<String, KnowledgeDocument> documents,
                                                          String organizationId) {
        boolean effective = record.getStatus() == ExternalProcessingApprovalStatus.ACTIVE;
        if (effective && record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION) {
            KnowledgeDocument entry = documents.computeIfAbsent(record.getKnowledgeDocumentId(), id ->
                    knowledgeDocuments.findByIdAndOrganizationId(id, organizationId).orElse(null));
            effective = isActivated(entry) && organizationId.equals(entry.getOrganizationId())
                    && Objects.equals(entry.getActiveVersion().getIngestionId(), record.getIngestionId())
                    && entry.getActiveVersion().getAccessLevel() == ChunkAccessLevel.COMPANY_WIDE
                    && activeOrganizationApproval(organizationRecords, record.getProvider(),
                    ChunkAccessLevel.COMPANY_WIDE) != null
                    && !RecordedExternalPolicyProcessingAuthorization.isExcluded(organizationRecords.stream()
                    .filter(other -> other.getStatus() == ExternalProcessingApprovalStatus.ACTIVE).toList(),
                    record.getKnowledgeDocumentId());
        }
        return ExternalProcessingApprovalResponse.of(record, effective);
    }

    private void audit(VerifiedMyHrActor actor, AuditActionType action, ExternalProcessingApproval record,
                       String description) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", record.getProvider());
        value.put("scope", record.getScope());
        value.put("status", record.getStatus());
        if (record.getAccessLevels() != null) value.put("accessLevels", record.getAccessLevels());
        if (record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION) value.put("coverage", record.effectiveCoverage());
        if (record.getKnowledgeDocumentId() != null) {
            value.put("knowledgeDocumentId", record.getKnowledgeDocumentId());
            value.put("ingestionId", record.getIngestionId());
            value.put("documentTitle", record.getDocumentTitle());
        }
        value.put("approvalReference", record.getApprovalReference());
        if (record.getRevocationReason() != null) value.put("revocationReason", record.getRevocationReason());
        auditService.record(AuditEvent.builder()
                .organizationId(actor.organizationId())
                .actorUserId(actor.userId())
                .actorRole(actor.role())
                .actionType(action)
                .entityType(ENTITY_TYPE)
                .entityId(record.getId())
                .entityDisplayName(record.getDocumentTitle() != null ? record.getDocumentTitle() : label(record.getProvider()))
                .description(description)
                .updatedValue(value)
                .build());
    }

    private static Set<ChunkAccessLevel> requireApprovableLevels(ExternalAiProvider provider, Set<ChunkAccessLevel> levels) {
        // Immutable sets throw on contains(null), so nulls are found by streaming.
        if (levels == null || levels.isEmpty() || levels.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ACCESS_LEVELS_REQUIRED);
        }
        if (levels.contains(ChunkAccessLevel.EMPLOYEE_PRIVATE)) {
            throw new ForbiddenException(PRIVATE_NEVER_APPROVABLE);
        }
        Set<ChunkAccessLevel> approvable = provider == ExternalAiProvider.ANTHROPIC_CLAUDE ? CLAUDE_APPROVABLE : VOYAGE_APPROVABLE;
        if (!approvable.containsAll(levels)) {
            throw new ForbiddenException(CLAUDE_COMPANY_WIDE_ONLY);
        }
        return EnumSet.copyOf(levels);
    }

    private static void requireAdmin(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException("Authentication is required");
        }
        if (actor.role() != UserRole.COMPANY_ADMIN) {
            throw new ForbiddenException(ADMIN_ONLY);
        }
    }

    private static void requireViewer(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException("Authentication is required");
        }
        if (!actor.canManageKnowledge()) {
            throw new ForbiddenException(VIEW_NOT_PERMITTED);
        }
    }

    private static void requireProvider(ExternalAiProvider provider) {
        if (provider == null) {
            throw new BusinessException(PROVIDER_REQUIRED);
        }
    }

    private static void requireAcknowledged(boolean acknowledged) {
        if (!acknowledged) {
            throw new BusinessException(ACKNOWLEDGEMENT_REQUIRED);
        }
    }

    /** Trimmed plain text within bounds; control characters (other than none) are rejected. */
    private static String requireText(String value, int max, String missing, String invalid) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(missing);
        }
        String text = value.strip();
        if (text.length() < 3 || text.length() > max || text.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(invalid);
        }
        return text;
    }

    static String label(ExternalAiProvider provider) {
        return provider == ExternalAiProvider.ANTHROPIC_CLAUDE ? "Claude" : "Voyage AI";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
