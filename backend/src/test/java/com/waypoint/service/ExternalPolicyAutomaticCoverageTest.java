package com.waypoint.service;

import com.waypoint.dto.myhr.ExternalProcessingApprovalResponse;
import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.ChunkAccessLevel;
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
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Organization-wide Claude coverage of published company-wide policies (ALL_PUBLISHED_COMPANY_WIDE): opt-in only,
 * never HR-only or private content, only the currently published version, explicit exclusions and unresolved
 * revocations always deny, organization revocation stops everything, and organizations stay isolated. The approval
 * service and the production authorization share one in-memory store; no database is touched.
 */
class ExternalPolicyAutomaticCoverageTest {

    private static final String ORG = "org-approval-61";
    private static final String OTHER_ORG = "org-approval-62";
    private static final String REFERENCE = "DPA-2026-17 signed by Legal";

    private final ExternalProcessingApprovalRepository repository = mock(ExternalProcessingApprovalRepository.class);
    private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final List<ExternalProcessingApproval> stored = new ArrayList<>();
    private final VerifiedMyHrActor admin = TestActors.companyAdmin("admin-61", ORG);
    private final VerifiedMyHrActor otherAdmin = TestActors.companyAdmin("admin-62", OTHER_ORG);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-61", ORG);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 4, 10, 0));
    private ExternalProcessingApprovalService service;
    private RecordedExternalPolicyProcessingAuthorization authorization;

    @BeforeEach
    void setUp() {
        service = new ExternalProcessingApprovalService(repository, documents, audit, clock, true, true);
        authorization = new RecordedExternalPolicyProcessingAuthorization(repository, documents);
        when(repository.save(any())).thenAnswer(invocation -> {
            ExternalProcessingApproval record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId("approval-" + (stored.size() + 1));
                stored.add(record);
            }
            return record;
        });
        when(repository.findByOrganizationIdAndProviderAndStatus(anyString(), any(), any())).thenAnswer(invocation ->
                stored.stream().filter(record -> record.getOrganizationId().equals(invocation.getArgument(0))
                        && record.getProvider() == invocation.getArgument(1)
                        && record.getStatus() == invocation.getArgument(2)).toList());
        when(repository.findByOrganizationIdOrderByApprovedAtDesc(anyString())).thenAnswer(invocation ->
                stored.stream().filter(record -> record.getOrganizationId().equals(invocation.getArgument(0))).toList());
        when(documents.findByIdAndOrganizationId(anyString(), anyString())).thenReturn(Optional.empty());
        givenDocument(ExternalProcessingApprovalServiceTest.liveDocument("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE));
    }

    // --- Compatibility: existing per-version behaviour is unchanged ---------------------------------------

    @Test
    void anOrganizationApprovalWithoutCoverageStillRequiresPerVersionApproval() {
        ExternalProcessingApprovalResponse approval = service.approveOrganization(admin,
                ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, true);

        assertThat(approval.coverage()).isEqualTo(ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS);
        assertThat(stored.get(0).getCoverage()).as("stored exactly like records written before coverage existed").isNull();
        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();

        service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1", REFERENCE, true);
        assertThat(permits(chunk("kd-1", "ing-1"))).isTrue();
    }

    @Test
    void aLegacyRecordWithoutCoverageIsNeverReadAsAutomaticCoverage() {
        stored.add(ExternalProcessingApproval.builder().id("legacy").organizationId(ORG)
                .provider(ExternalAiProvider.ANTHROPIC_CLAUDE).scope(ExternalProcessingApprovalScope.ORGANIZATION)
                .status(ExternalProcessingApprovalStatus.ACTIVE).accessLevels(Set.of(ChunkAccessLevel.COMPANY_WIDE))
                .approvalReference(REFERENCE).approvedAt(clock.now()).build());

        assertThat(stored.get(0).effectiveCoverage()).isEqualTo(ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS);
        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();
        assertThat(service.status(admin).approvals().get(0).coverage())
                .isEqualTo(ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS);
    }

    // --- Automatic eligibility -------------------------------------------------------------------------------

    @Test
    void automaticCoverageAdmitsTheCurrentlyPublishedCompanyWideVersionAndIsAudited() {
        approveAutomaticCoverage();

        assertThat(permits(chunk("kd-1", "ing-1"))).isTrue();
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeastOnce()).record(event.capture());
        assertThat(event.getValue().getActionType()).isEqualTo(AuditActionType.EXTERNAL_AI_PROCESSING_APPROVED);
        assertThat((java.util.Map<Object, Object>) (java.util.Map<?, ?>) event.getValue().getUpdatedValue())
                .containsEntry("coverage", ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE);
    }

    @Test
    void automaticCoverageRequiresAFreshOrganizationApproval() {
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true);

        assertThatThrownBy(this::approveAutomaticCoverage).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.ORGANIZATION_ALREADY_APPROVED);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCoverage()).isNull();
    }

    @Test
    void onlyACompanyAdminCanRecordAutomaticCoverageAndOnlyForClaude() {
        assertThatThrownBy(() -> service.approveOrganization(hr, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, true,
                ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.VOYAGE_AI,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, true,
                ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE)).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.AUTOMATIC_COVERAGE_CLAUDE_ONLY);
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, false,
                ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY), REFERENCE, true,
                ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE)).isInstanceOf(ForbiddenException.class);
        assertThat(stored).isEmpty();
    }

    // --- Content and publication limits ----------------------------------------------------------------------

    @Test
    void hrOnlyAndEmployeePrivateContentIsNeverCovered() {
        approveAutomaticCoverage();
        givenDocument(ExternalProcessingApprovalServiceTest.liveDocument("kd-hr", "ing-hr", ChunkAccessLevel.HR_ONLY));

        assertThat(permits(chunk("kd-hr", "ing-hr", ChunkAccessLevel.HR_ONLY))).isFalse();
        assertThat(permits(chunk("kd-hr", "ing-hr", ChunkAccessLevel.EMPLOYEE_PRIVATE))).isFalse();
        // An excerpt claiming company-wide access from a policy whose published version is HR-only is refused.
        assertThat(permits(chunk("kd-hr", "ing-hr", ChunkAccessLevel.COMPANY_WIDE))).isFalse();
        // One HR-only excerpt blocks the whole request.
        assertThat(permits(chunk("kd-1", "ing-1"), chunk("kd-hr", "ing-hr", ChunkAccessLevel.HR_ONLY))).isFalse();
    }

    @Test
    void onlyThePublishedVersionIsCoveredNotPendingSupersededOrRevokedOnes() {
        approveAutomaticCoverage();

        // A newer version that has not passed review and publication (not the active version) is not covered.
        assertThat(permits(chunk("kd-1", "ing-2"))).isFalse();
        // Another source file under the active ingestion is not covered.
        assertThat(permits(new RetrievedChunk("kd-1-00000", "kd-1", "Policy", "source-other", "p.pdf", "ing-1", 0,
                ChunkAccessLevel.COMPANY_WIDE, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, "text"))).isFalse();

        // Once the new version is published it is covered and the superseded one no longer is.
        givenDocument(ExternalProcessingApprovalServiceTest.liveDocument("kd-1", "ing-2", ChunkAccessLevel.COMPANY_WIDE));
        assertThat(permits(chunk("kd-1", "ing-2"))).isTrue();
        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();

        KnowledgeDocument revoked = ExternalProcessingApprovalServiceTest.liveDocument("kd-1", "ing-2",
                ChunkAccessLevel.COMPANY_WIDE);
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        givenDocument(revoked);
        assertThat(permits(chunk("kd-1", "ing-2"))).isFalse();

        KnowledgeDocument notIndexed = ExternalProcessingApprovalServiceTest.liveDocument("kd-1", "ing-2",
                ChunkAccessLevel.COMPANY_WIDE);
        notIndexed.getActiveVersion().setStage(KnowledgeIngestionStage.INGESTING);
        givenDocument(notIndexed);
        assertThat(permits(chunk("kd-1", "ing-2"))).isFalse();

        // An unknown document (never published in this organization) is not covered.
        assertThat(permits(chunk("kd-unknown", "ing-9"))).isFalse();
    }

    // --- Explicit exclusion -------------------------------------------------------------------------------------

    @Test
    void anExclusionOverridesAutomaticCoverageAndExplicitVersionApprovalsUntilLifted() {
        approveAutomaticCoverage();
        service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1", REFERENCE, true);
        assertThat(permits(chunk("kd-1", "ing-1"))).isTrue();

        ExternalProcessingApprovalResponse exclusion =
                service.excludeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Legal review pending");

        assertThat(exclusion.scope()).isEqualTo(ExternalProcessingApprovalScope.DOCUMENT_EXCLUSION);
        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();
        assertThat(service.status(admin).approvals()).filteredOn(r -> r.scope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION)
                .allMatch(r -> !r.effective());
        assertThatThrownBy(() -> service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1",
                REFERENCE, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.excludeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Again"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.DOCUMENT_ALREADY_EXCLUDED);

        clock.advanceMinutes(5);
        service.liftExclusion(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Legal review complete");
        assertThat(permits(chunk("kd-1", "ing-1"))).isTrue();

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeastOnce()).record(events.capture());
        assertThat(events.getAllValues()).extracting(AuditEvent::getActionType)
                .contains(AuditActionType.EXTERNAL_AI_PROCESSING_EXCLUDED, AuditActionType.EXTERNAL_AI_PROCESSING_REVOKED);
    }

    @Test
    void anExclusionAlsoAppliesUnderPerVersionApprovalAndCanPrecedePublication() {
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true);
        service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1", REFERENCE, true);
        KnowledgeDocument draft = ExternalProcessingApprovalServiceTest.liveDocument("kd-draft", "ing-d",
                ChunkAccessLevel.COMPANY_WIDE);
        draft.setActiveVersion(null);
        givenDocument(draft);

        service.excludeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Withdrawn");
        service.excludeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-draft", "Never share");

        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();
    }

    @Test
    void onlyACompanyAdminMayExcludeOrLiftAndOnlyWithinTheirOrganization() {
        approveAutomaticCoverage();

        assertThatThrownBy(() -> service.excludeDocument(hr, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Not for Claude"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.excludeDocument(TestActors.actor("e-1", ORG, UserRole.EMPLOYEE),
                ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Not for Claude")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.excludeDocument(otherAdmin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Not for Claude"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.excludeDocument(admin, ExternalAiProvider.VOYAGE_AI, "kd-1", "Not for Claude"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.liftExclusion(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Nothing"))
                .isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.NOTHING_TO_REVOKE);
        assertThat(stored).hasSize(1);
    }

    // --- Historical revocations deny ------------------------------------------------------------------------

    @Test
    void aPolicyWhoseApprovalWasRevokedStaysDeniedUntilAnExplicitLaterDecision() {
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true);
        service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1", REFERENCE, true);
        clock.advanceMinutes(1);
        service.revokeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Withdrawn by Legal");
        clock.advanceMinutes(1);
        service.revokeOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "Switching to automatic coverage");
        clock.advanceMinutes(1);
        approveAutomaticCoverage();

        // The later organization-wide decision does not silently re-admit the policy that was withdrawn.
        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();
        // Another published company-wide policy without that history is covered.
        givenDocument(ExternalProcessingApprovalServiceTest.liveDocument("kd-2", "ing-2", ChunkAccessLevel.COMPANY_WIDE));
        assertThat(permits(chunk("kd-2", "ing-2"))).isTrue();

        // An explicit later decision about that policy resolves it.
        clock.advanceMinutes(1);
        service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1", REFERENCE, true);
        assertThat(permits(chunk("kd-1", "ing-1"))).isTrue();
    }

    @Test
    void aRevocationWithoutTimestampOrAnUndatedDecisionIsTreatedAsUnresolved() {
        ExternalProcessingApproval undated = ExternalProcessingApproval.builder().knowledgeDocumentId("kd-1")
                .scope(ExternalProcessingApprovalScope.DOCUMENT_VERSION).status(ExternalProcessingApprovalStatus.REVOKED)
                .build();
        assertThat(RecordedExternalPolicyProcessingAuthorization.hasUnresolvedRevocation(List.of(undated), "kd-1")).isTrue();

        ExternalProcessingApproval revoked = ExternalProcessingApproval.builder().knowledgeDocumentId("kd-1")
                .scope(ExternalProcessingApprovalScope.DOCUMENT_VERSION).status(ExternalProcessingApprovalStatus.REVOKED)
                .revokedAt(clock.now()).build();
        ExternalProcessingApproval earlierApproval = ExternalProcessingApproval.builder().knowledgeDocumentId("kd-1")
                .scope(ExternalProcessingApprovalScope.DOCUMENT_VERSION).status(ExternalProcessingApprovalStatus.ACTIVE)
                .approvedAt(clock.now().minusDays(1)).build();
        ExternalProcessingApproval undatedApproval = ExternalProcessingApproval.builder().knowledgeDocumentId("kd-1")
                .scope(ExternalProcessingApprovalScope.DOCUMENT_VERSION).status(ExternalProcessingApprovalStatus.ACTIVE)
                .build();
        assertThat(RecordedExternalPolicyProcessingAuthorization.hasUnresolvedRevocation(
                List.of(revoked, earlierApproval, undatedApproval), "kd-1")).isTrue();
        assertThat(RecordedExternalPolicyProcessingAuthorization.hasUnresolvedRevocation(List.of(revoked), "kd-other"))
                .isFalse();
    }

    // --- Organization revocation and isolation --------------------------------------------------------------

    @Test
    void revokingTheOrganizationApprovalStopsAutomaticCoverageImmediately() {
        approveAutomaticCoverage();
        assertThat(permits(chunk("kd-1", "ing-1"))).isTrue();

        service.revokeOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "Contract ended");

        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();
        assertThat(authorization.mayPermit(ORG)).isFalse();
    }

    @Test
    void organizationsAreIsolated() {
        approveAutomaticCoverage();

        // The same document id asked for by another organization: no approval there, and its lookup is scoped.
        assertThat(authorization.permits(OTHER_ORG, List.of(chunk("kd-1", "ing-1")))).isFalse();

        // Another organization's automatic coverage never covers this organization's documents.
        service.revokeOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "Ended");
        service.approveOrganization(otherAdmin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, true,
                ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE);
        assertThat(permits(chunk("kd-1", "ing-1"))).isFalse();

        // A document record returned for this organization but owned by another is refused.
        approveAutomaticCoverage();
        KnowledgeDocument foreign = ExternalProcessingApprovalServiceTest.liveDocument("kd-f", "ing-f",
                ChunkAccessLevel.COMPANY_WIDE);
        foreign.setOrganizationId(OTHER_ORG);
        givenDocument(foreign);
        assertThat(permits(chunk("kd-f", "ing-f"))).isFalse();
    }

    @Test
    void aDocumentLookupFailurePropagatesSoTheChatServiceTreatsItAsNotAuthorized() {
        approveAutomaticCoverage();
        when(documents.findByIdAndOrganizationId("kd-1", ORG)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> permits(chunk("kd-1", "ing-1"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutTheDocumentRepositoryAutomaticCoverageNeverPermits() {
        approveAutomaticCoverage();

        assertThat(new RecordedExternalPolicyProcessingAuthorization(repository)
                .permits(ORG, List.of(chunk("kd-1", "ing-1")))).isFalse();
    }

    // --- Helpers ----------------------------------------------------------------------------------------------

    private void approveAutomaticCoverage() {
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true, ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE);
    }

    private boolean permits(RetrievedChunk... chunks) {
        return authorization.permits(ORG, List.of(chunks));
    }

    private void givenDocument(KnowledgeDocument document) {
        when(documents.findByIdAndOrganizationId(document.getId(), ORG)).thenReturn(Optional.of(document));
    }

    private static RetrievedChunk chunk(String id, String ingestion) {
        return chunk(id, ingestion, ChunkAccessLevel.COMPANY_WIDE);
    }

    private static RetrievedChunk chunk(String id, String ingestion, ChunkAccessLevel level) {
        return new RetrievedChunk(id + "-00000", id, "Policy", "source-" + ingestion, id + ".pdf", ingestion, 0, level,
                LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, "text");
    }

    /** A clock the test moves forward, so revocations and later decisions have distinct times. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(LocalDateTime start) {
            this.instant = start.toInstant(ZoneOffset.UTC);
        }

        LocalDateTime now() {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }

        void advanceMinutes(long minutes) {
            instant = instant.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
