package com.waypoint.service;

import com.waypoint.dto.myhr.ExternalProcessingApprovalResponse;
import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalCoverage;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Atomic replacement of the organization's Claude approval coverage. A fake transaction runner snapshots the in-memory
 * store and restores it when the work throws - the contract a MongoDB transaction gives - so these tests check that
 * the service does all its writes inside one transaction and leaves the previous approval in force on any failure.
 * No database is touched.
 */
class ExternalProcessingCoverageReplacementTest {

    private static final String ORG = "org-replace-71";
    private static final String OTHER_ORG = "org-replace-72";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 9, 0);
    private static final String REFERENCE = "DPA-2026-17 amendment signed by Legal";
    private static final String REASON = "Moving to automatic coverage";
    private static final ExternalProcessingApprovalCoverage AUTOMATIC =
            ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE;

    private final ExternalProcessingApprovalRepository repository = mock(ExternalProcessingApprovalRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final List<ExternalProcessingApproval> stored = new ArrayList<>();
    private final RollbackTransactions transactions = new RollbackTransactions();
    private final VerifiedMyHrActor admin = TestActors.companyAdmin("admin-71", ORG);
    private ExternalProcessingApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ExternalProcessingApprovalService(repository, mock(KnowledgeDocumentRepository.class), audit,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), true, true, transactions);
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
        stored.add(ExternalProcessingApproval.builder().id("approval-old").organizationId(ORG)
                .provider(ExternalAiProvider.ANTHROPIC_CLAUDE).scope(ExternalProcessingApprovalScope.ORGANIZATION)
                .status(ExternalProcessingApprovalStatus.ACTIVE).accessLevels(EnumSet.of(ChunkAccessLevel.COMPANY_WIDE))
                .approvalReference("Original DPA").approvedByUserId("admin-71").approvedAt(NOW.minusDays(30))
                .version(3L).build());
    }

    @Test
    void replacesTheActiveApprovalInOneTransactionAndAuditsAfterCommit() {
        ExternalProcessingApprovalResponse response = replace(admin, "approval-old");

        assertThat(transactions.runs).isEqualTo(1);
        ExternalProcessingApproval old = record("approval-old");
        assertThat(old.getStatus()).isEqualTo(ExternalProcessingApprovalStatus.REVOKED);
        assertThat(old.getRevocationReason()).isEqualTo(REASON);
        assertThat(old.getRevokedByUserId()).isEqualTo("admin-71");
        assertThat(old.getRevokedAt()).isEqualTo(NOW);
        assertThat(old.getCoverage()).as("the old record keeps its original meaning").isNull();

        List<ExternalProcessingApproval> active = activeOrganizationRecords(ORG);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getCoverage()).isEqualTo(AUTOMATIC);
        assertThat(active.get(0).getAccessLevels()).containsExactly(ChunkAccessLevel.COMPANY_WIDE);
        assertThat(active.get(0).getApprovalReference()).isEqualTo(REFERENCE);
        assertThat(response.coverage()).isEqualTo(AUTOMATIC);
        assertThat(response.effective()).isTrue();

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(2)).record(events.capture());
        assertThat(events.getAllValues()).extracting(AuditEvent::getActionType).containsExactly(
                AuditActionType.EXTERNAL_AI_PROCESSING_REVOKED, AuditActionType.EXTERNAL_AI_PROCESSING_APPROVED);
        assertThat(transactions.auditedInsideTransaction).isFalse();
    }

    @Test
    void aFailureWhileRecordingTheNewApprovalLeavesThePreviousApprovalActive() {
        doAnswer(invocation -> {
            ExternalProcessingApproval record = invocation.getArgument(0);
            if (record.getId() == null) throw new IllegalStateException("insert failed");
            return record;
        }).when(repository).save(any());

        assertThatThrownBy(() -> replace(admin, "approval-old")).isInstanceOf(IllegalStateException.class);

        assertPreviousApprovalUntouched();
        verifyNoInteractions(audit);
    }

    @Test
    void aConcurrentChangeToTheSameApprovalIsRefusedAndRolledBack() {
        // Another request revoked or replaced the record first: the optimistic lock on its version fails.
        doAnswer(invocation -> {
            ExternalProcessingApproval record = invocation.getArgument(0);
            if ("approval-old".equals(record.getId())) {
                throw new OptimisticLockingFailureException("version 3 is stale");
            }
            return record;
        }).when(repository).save(any());

        assertThatThrownBy(() -> replace(admin, "approval-old")).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.APPROVAL_CHANGED);

        assertPreviousApprovalUntouched();
        verifyNoInteractions(audit);
    }

    @Test
    void aDuplicateRequestForTheSameApprovalIsRefusedWithoutAnotherWrite() {
        replace(admin, "approval-old");
        int recordsAfterFirst = stored.size();

        assertThatThrownBy(() -> replace(admin, "approval-old")).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.APPROVAL_CHANGED);

        assertThat(stored).hasSize(recordsAfterFirst);
        assertThat(activeOrganizationRecords(ORG)).hasSize(1);
        verify(audit, times(2)).record(any());
    }

    @Test
    void replacingWithTheSameCoverageIsRefusedAndChangesNothing() {
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                "approval-old", ExternalProcessingApprovalCoverage.REVIEWED_VERSIONS, REFERENCE, true, REASON))
                .isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.COVERAGE_UNCHANGED);

        assertPreviousApprovalUntouched();
    }

    @Test
    void onlyACompanyAdminMayReplaceAndNothingIsWrittenOtherwise() {
        for (VerifiedMyHrActor notAdmin : List.of(TestActors.hr("hr-71", ORG),
                TestActors.actor("employee-71", ORG, UserRole.EMPLOYEE))) {
            assertThatThrownBy(() -> replace(notAdmin, "approval-old")).isInstanceOf(ForbiddenException.class);
        }
        assertThat(transactions.runs).isZero();
        assertPreviousApprovalUntouched();
        verify(repository, never()).save(any());
    }

    @Test
    void anotherOrganizationsAdminCannotReplaceThisApproval() {
        assertThatThrownBy(() -> replace(TestActors.companyAdmin("admin-72", OTHER_ORG), "approval-old"))
                .isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.APPROVAL_CHANGED);

        assertPreviousApprovalUntouched();
        assertThat(activeOrganizationRecords(OTHER_ORG)).isEmpty();
    }

    @Test
    void aGenuineReferenceAcknowledgementReasonAndExpectedApprovalAreRequired() {
        ExternalAiProvider claude = ExternalAiProvider.ANTHROPIC_CLAUDE;
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, claude, "approval-old", AUTOMATIC, " ", true,
                REASON)).isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.REFERENCE_REQUIRED);
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, claude, "approval-old", AUTOMATIC, REFERENCE,
                false, REASON)).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.ACKNOWLEDGEMENT_REQUIRED);
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, claude, "approval-old", AUTOMATIC, REFERENCE,
                true, "x")).isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.REASON_INVALID);
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, claude, " ", AUTOMATIC, REFERENCE, true,
                REASON)).isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.APPROVAL_CHANGED);
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, claude, "approval-old", null, REFERENCE, true,
                REASON)).isInstanceOf(BusinessException.class).hasMessage(ExternalProcessingApprovalService.COVERAGE_REQUIRED);
        assertThatThrownBy(() -> service.replaceOrganizationCoverage(admin, ExternalAiProvider.VOYAGE_AI, "approval-old",
                AUTOMATIC, REFERENCE, true, REASON)).isInstanceOf(BusinessException.class);

        assertThat(transactions.runs).isZero();
        assertPreviousApprovalUntouched();
    }

    @Test
    void withoutAnActiveApprovalOrWithoutTransactionsNothingIsReplaced() {
        record("approval-old").setStatus(ExternalProcessingApprovalStatus.REVOKED);
        assertThatThrownBy(() -> replace(admin, "approval-old")).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.APPROVAL_CHANGED);
        assertThat(stored).hasSize(1);

        record("approval-old").setStatus(ExternalProcessingApprovalStatus.ACTIVE);
        ExternalProcessingApprovalService withoutTransactions = new ExternalProcessingApprovalService(repository,
                mock(KnowledgeDocumentRepository.class), audit, Clock.systemUTC(), true, true);
        assertThatThrownBy(() -> withoutTransactions.replaceOrganizationCoverage(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                "approval-old", AUTOMATIC, REFERENCE, true, REASON)).isInstanceOf(BusinessException.class)
                .hasMessage(ExternalProcessingApprovalService.REPLACEMENT_UNAVAILABLE);
        assertPreviousApprovalUntouched();
    }

    // --- Helpers ----------------------------------------------------------------------------------------------

    private ExternalProcessingApprovalResponse replace(VerifiedMyHrActor actor, String expectedApprovalId) {
        return service.replaceOrganizationCoverage(actor, ExternalAiProvider.ANTHROPIC_CLAUDE, expectedApprovalId,
                AUTOMATIC, REFERENCE, true, REASON);
    }

    private void assertPreviousApprovalUntouched() {
        ExternalProcessingApproval old = record("approval-old");
        assertThat(old.getStatus()).isEqualTo(ExternalProcessingApprovalStatus.ACTIVE);
        assertThat(old.getRevokedAt()).isNull();
        assertThat(old.getRevocationReason()).isNull();
        assertThat(old.getCoverage()).isNull();
        assertThat(activeOrganizationRecords(ORG)).extracting(ExternalProcessingApproval::getId)
                .containsExactly("approval-old");
    }

    private ExternalProcessingApproval record(String id) {
        return stored.stream().filter(record -> id.equals(record.getId())).findFirst().orElseThrow();
    }

    private List<ExternalProcessingApproval> activeOrganizationRecords(String organizationId) {
        return stored.stream().filter(record -> organizationId.equals(record.getOrganizationId())
                && record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION
                && record.getStatus() == ExternalProcessingApprovalStatus.ACTIVE).toList();
    }

    /** Commits the work, or restores the store exactly as it was when the work throws. */
    private final class RollbackTransactions extends ExternalProcessingApprovalTransactions {
        int runs;
        boolean auditedInsideTransaction;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            runs++;
            List<ExternalProcessingApproval> snapshot = stored.stream().map(RollbackTransactions::copy).toList();
            int auditsBefore = org.mockito.Mockito.mockingDetails(audit).getInvocations().size();
            try {
                T result = work.get();
                auditedInsideTransaction = org.mockito.Mockito.mockingDetails(audit).getInvocations().size() > auditsBefore;
                return result;
            } catch (RuntimeException failure) {
                stored.clear();
                stored.addAll(snapshot);
                throw failure;
            }
        }

        private static ExternalProcessingApproval copy(ExternalProcessingApproval record) {
            return ExternalProcessingApproval.builder().id(record.getId()).organizationId(record.getOrganizationId())
                    .provider(record.getProvider()).scope(record.getScope()).status(record.getStatus())
                    .accessLevels(record.getAccessLevels() == null ? null : EnumSet.copyOf(record.getAccessLevels()))
                    .coverage(record.getCoverage()).knowledgeDocumentId(record.getKnowledgeDocumentId())
                    .ingestionId(record.getIngestionId()).sourceDocumentId(record.getSourceDocumentId())
                    .contentSha256(record.getContentSha256()).documentTitle(record.getDocumentTitle())
                    .documentAccessLevel(record.getDocumentAccessLevel()).approvalReference(record.getApprovalReference())
                    .approvedByUserId(record.getApprovedByUserId()).approvedAt(record.getApprovedAt())
                    .revokedByUserId(record.getRevokedByUserId()).revokedAt(record.getRevokedAt())
                    .revocationReason(record.getRevocationReason()).version(record.getVersion()).build();
        }
    }
}
