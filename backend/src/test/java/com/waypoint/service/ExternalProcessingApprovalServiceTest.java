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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Company Admin approval of external AI processing: denied by default, admin-only changes, never for employee-private
 * content, never HR-only for Claude, exact-version document approvals, and an append-only, audited history. An
 * in-memory list stands in for MongoDB; no database is touched.
 */
class ExternalProcessingApprovalServiceTest {

    private static final String ORG = "org-approval-61";
    private static final String OTHER_ORG = "org-approval-62";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);
    private static final String REFERENCE = "DPA-2026-17 signed by Legal";

    private final ExternalProcessingApprovalRepository repository = mock(ExternalProcessingApprovalRepository.class);
    private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final List<ExternalProcessingApproval> stored = new ArrayList<>();
    private final VerifiedMyHrActor admin = TestActors.companyAdmin("admin-61", ORG);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-61", ORG);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-61", ORG, UserRole.EMPLOYEE);
    private ExternalProcessingApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ExternalProcessingApprovalService(repository, documents, audit,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), false, true);
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
    }

    // --- Default deny and who may act ----------------------------------------------------------------------

    @Test
    void nothingIsApprovedByDefault() {
        ExternalProcessingStatusResponse status = service.status(admin);

        assertThat(status.claudeApprovedForCompanyWide()).isFalse();
        assertThat(status.voyageApprovedForHrOnly()).isFalse();
        assertThat(status.claudeSwitchEnabled()).isFalse();
        assertThat(status.approvals()).isEmpty();
    }

    @Test
    void onlyACompanyAdminMayApproveOrRevokeAndOnlyKnowledgeManagersMayView() {
        for (VerifiedMyHrActor notAdmin : List.of(hr, employee)) {
            assertThatThrownBy(() -> service.approveOrganization(notAdmin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                    Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, true)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.revokeOrganization(notAdmin, ExternalAiProvider.ANTHROPIC_CLAUDE, "Ended"))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.approveDocument(notAdmin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1",
                    "ing-1", REFERENCE, true)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.revokeDocument(notAdmin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1",
                    "Ended")).isInstanceOf(ForbiddenException.class);
        }
        assertThat(service.status(hr).approvals()).isEmpty();
        assertThatThrownBy(() -> service.status(employee)).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(audit);
    }

    // --- Organization approvals ----------------------------------------------------------------------------

    @Test
    void anOrganizationApprovalNeedsAnExplicitAcknowledgementAndAReference() {
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, false))
                .hasMessage(ExternalProcessingApprovalService.ACKNOWLEDGEMENT_REQUIRED);
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), "  ", true))
                .hasMessage(ExternalProcessingApprovalService.REFERENCE_REQUIRED);
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), "x".repeat(201), true))
                .hasMessage(ExternalProcessingApprovalService.REFERENCE_INVALID);
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(), REFERENCE, true)).hasMessage(ExternalProcessingApprovalService.ACCESS_LEVELS_REQUIRED);
        assertThatThrownBy(() -> service.approveOrganization(admin, null, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true)).hasMessage(ExternalProcessingApprovalService.PROVIDER_REQUIRED);
        assertThat(stored).isEmpty();
    }

    @Test
    void employeePrivateIsNeverApprovableAndClaudeIsNeverApprovableForHrOnly() {
        for (ExternalAiProvider provider : ExternalAiProvider.values()) {
            assertThatThrownBy(() -> service.approveOrganization(admin, provider,
                    Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.EMPLOYEE_PRIVATE), REFERENCE, true))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(ExternalProcessingApprovalService.PRIVATE_NEVER_APPROVABLE);
        }
        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY), REFERENCE, true))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(ExternalProcessingApprovalService.CLAUDE_COMPANY_WIDE_ONLY);
        assertThat(stored).isEmpty();

        // Voyage may be approved for HR-only policy text: a separate decision from Claude.
        ExternalProcessingApprovalResponse voyage = service.approveOrganization(admin, ExternalAiProvider.VOYAGE_AI,
                Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY), REFERENCE, true);
        assertThat(voyage.accessLevels()).containsExactlyInAnyOrder(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
        assertThat(service.status(admin).claudeApprovedForCompanyWide()).isFalse();
        assertThat(service.status(admin).voyageApprovedForHrOnly()).isTrue();
    }

    @Test
    void anOrganizationApprovalRecordsWhoWhenWhatAndWhyAndIsAudited() {
        ExternalProcessingApprovalResponse approved = service.approveOrganization(admin,
                ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE), "  " + REFERENCE + " ", true);

        ExternalProcessingApproval record = stored.getFirst();
        assertThat(record.getOrganizationId()).isEqualTo(ORG);
        assertThat(record.getProvider()).isEqualTo(ExternalAiProvider.ANTHROPIC_CLAUDE);
        assertThat(record.getScope()).isEqualTo(ExternalProcessingApprovalScope.ORGANIZATION);
        assertThat(record.getStatus()).isEqualTo(ExternalProcessingApprovalStatus.ACTIVE);
        assertThat(record.getApprovedByUserId()).isEqualTo("admin-61");
        assertThat(record.getApprovedAt()).isEqualTo(NOW);
        assertThat(record.getApprovalReference()).isEqualTo(REFERENCE);
        assertThat(approved.effective()).isTrue();
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getActionType()).isEqualTo(AuditActionType.EXTERNAL_AI_PROCESSING_APPROVED);
        assertThat(event.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(event.getValue().getActorUserId()).isEqualTo("admin-61");

        assertThatThrownBy(() -> service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), REFERENCE, true))
                .hasMessage(ExternalProcessingApprovalService.ORGANIZATION_ALREADY_APPROVED);
    }

    @Test
    void revocationKeepsTheHistoryAndAllowsANewApprovalLater() {
        assertThatThrownBy(() -> service.revokeOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "No longer needed"))
                .hasMessage(ExternalProcessingApprovalService.NOTHING_TO_REVOKE);
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true);

        List<ExternalProcessingApprovalResponse> revoked =
                service.revokeOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "Agreement ended");

        assertThat(revoked).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(ExternalProcessingApprovalStatus.REVOKED);
            assertThat(record.effective()).isFalse();
            assertThat(record.revokedByUserId()).isEqualTo("admin-61");
            assertThat(record.revokedAt()).isEqualTo(NOW);
            assertThat(record.revocationReason()).isEqualTo("Agreement ended");
        });
        assertThat(service.status(admin).claudeApprovedForCompanyWide()).isFalse();
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                "DPA-2027-01", true);
        assertThat(stored).hasSize(2).extracting(ExternalProcessingApproval::getStatus)
                .containsExactly(ExternalProcessingApprovalStatus.REVOKED, ExternalProcessingApprovalStatus.ACTIVE);
        verify(repository, never()).delete(any());
        verify(audit, times(3)).record(any(AuditEvent.class));
    }

    // --- Document approvals ----------------------------------------------------------------------------------

    @Test
    void aDocumentApprovalRequiresTheOrganizationsClaudeApprovalFirst() {
        givenDocument(liveDocument("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE));

        assertThatThrownBy(() -> service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "ing-1",
                REFERENCE, true)).hasMessage(ExternalProcessingApprovalService.ORGANIZATION_APPROVAL_REQUIRED);
        assertThatThrownBy(() -> service.approveDocument(admin, ExternalAiProvider.VOYAGE_AI, "kd-1", "ing-1",
                REFERENCE, true)).hasMessage(ExternalProcessingApprovalService.DOCUMENTS_CLAUDE_ONLY);
    }

    @Test
    void onlyTheReviewedActivatedVersionOfALiveCompanyWidePolicyCanBeApproved() {
        approveClaudeForOrganization();
        KnowledgeDocument hrOnly = liveDocument("kd-hr", "ing-hr", ChunkAccessLevel.HR_ONLY);
        KnowledgeDocument revoked = liveDocument("kd-revoked", "ing-r", ChunkAccessLevel.COMPANY_WIDE);
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        KnowledgeDocument notActivated = liveDocument("kd-pending", "ing-p", ChunkAccessLevel.COMPANY_WIDE);
        notActivated.getActiveVersion().setActivatedAt(null);
        givenDocument(hrOnly);
        givenDocument(revoked);
        givenDocument(notActivated);
        givenDocument(liveDocument("kd-1", "ing-current", ChunkAccessLevel.COMPANY_WIDE));
        when(documents.findByIdAndOrganizationId("kd-other", ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approveDocument("kd-hr", "ing-hr")).isInstanceOf(ForbiddenException.class)
                .hasMessage(ExternalProcessingApprovalService.DOCUMENT_NOT_COMPANY_WIDE);
        assertThatThrownBy(() -> approveDocument("kd-revoked", "ing-r"))
                .hasMessage(ExternalProcessingApprovalService.DOCUMENT_NOT_ACTIVE);
        assertThatThrownBy(() -> approveDocument("kd-pending", "ing-p"))
                .hasMessage(ExternalProcessingApprovalService.DOCUMENT_NOT_ACTIVE);
        assertThatThrownBy(() -> approveDocument("kd-other", "ing-1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> approveDocument("kd-1", "ing-old"))
                .hasMessage(ExternalProcessingApprovalService.VERSION_CHANGED);
        assertThat(stored).hasSize(1);

        ExternalProcessingApprovalResponse approved = approveDocument("kd-1", "ing-current");

        ExternalProcessingApproval record = stored.getLast();
        assertThat(record.getScope()).isEqualTo(ExternalProcessingApprovalScope.DOCUMENT_VERSION);
        assertThat(record.getKnowledgeDocumentId()).isEqualTo("kd-1");
        assertThat(record.getIngestionId()).isEqualTo("ing-current");
        assertThat(record.getSourceDocumentId()).isEqualTo("source-ing-current");
        assertThat(record.getContentSha256()).isEqualTo("a".repeat(64));
        assertThat(record.getDocumentAccessLevel()).isEqualTo(ChunkAccessLevel.COMPANY_WIDE);
        assertThat(approved.effective()).isTrue();
        assertThatThrownBy(() -> approveDocument("kd-1", "ing-current"))
                .hasMessage(ExternalProcessingApprovalService.DOCUMENT_ALREADY_APPROVED);
    }

    @Test
    void anotherOrganizationsDocumentCannotBeApprovedEvenIfTheLookupReturnedIt() {
        approveClaudeForOrganization();
        KnowledgeDocument foreign = liveDocument("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE);
        foreign.setOrganizationId(OTHER_ORG);
        when(documents.findByIdAndOrganizationId("kd-1", ORG)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> approveDocument("kd-1", "ing-1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anApprovalStopsTakingEffectWhenThePolicyChangesIsRevokedOrTheOrganizationApprovalEnds() {
        approveClaudeForOrganization();
        KnowledgeDocument policy = liveDocument("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE);
        givenDocument(policy);
        approveDocument("kd-1", "ing-1");
        assertThat(documentApproval().effective()).isTrue();

        policy.getActiveVersion().setIngestionId("ing-2");
        assertThat(documentApproval().effective()).isFalse();
        policy.getActiveVersion().setIngestionId("ing-1");
        policy.setStatus(KnowledgeDocumentStatus.REVOKED);
        assertThat(documentApproval().effective()).isFalse();
        policy.setStatus(KnowledgeDocumentStatus.LIVE);
        service.revokeOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "Agreement ended");
        assertThat(documentApproval().effective()).isFalse();
    }

    @Test
    void revokingADocumentRevokesEveryActiveVersionApprovalOfIt() {
        approveClaudeForOrganization();
        KnowledgeDocument policy = liveDocument("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE);
        givenDocument(policy);
        approveDocument("kd-1", "ing-1");
        policy.getActiveVersion().setIngestionId("ing-2");
        approveDocument("kd-1", "ing-2");

        List<ExternalProcessingApprovalResponse> revoked =
                service.revokeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Policy withdrawn");

        assertThat(revoked).hasSize(2).allSatisfy(record ->
                assertThat(record.status()).isEqualTo(ExternalProcessingApprovalStatus.REVOKED));
        assertThat(stored.getFirst().getStatus()).isEqualTo(ExternalProcessingApprovalStatus.ACTIVE);
        assertThatThrownBy(() -> service.revokeDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, "kd-1", "Again"))
                .hasMessage(ExternalProcessingApprovalService.NOTHING_TO_REVOKE);
    }

    @Test
    void anotherOrganizationsApprovalsAreNeverListed() {
        stored.add(ExternalProcessingApproval.builder().id("foreign").organizationId(OTHER_ORG)
                .provider(ExternalAiProvider.ANTHROPIC_CLAUDE).scope(ExternalProcessingApprovalScope.ORGANIZATION)
                .status(ExternalProcessingApprovalStatus.ACTIVE).accessLevels(Set.of(ChunkAccessLevel.COMPANY_WIDE))
                .build());

        ExternalProcessingStatusResponse status = service.status(admin);

        assertThat(status.approvals()).isEmpty();
        assertThat(status.claudeApprovedForCompanyWide()).isFalse();
    }

    // --- Helpers ----------------------------------------------------------------------------------------------

    private void approveClaudeForOrganization() {
        service.approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                REFERENCE, true);
    }

    private ExternalProcessingApprovalResponse approveDocument(String id, String ingestion) {
        return service.approveDocument(admin, ExternalAiProvider.ANTHROPIC_CLAUDE, id, ingestion, REFERENCE, true);
    }

    private ExternalProcessingApprovalResponse documentApproval() {
        return service.status(admin).approvals().stream()
                .filter(record -> record.scope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION)
                .findFirst().orElseThrow();
    }

    private void givenDocument(KnowledgeDocument document) {
        when(documents.findByIdAndOrganizationId(document.getId(), ORG)).thenReturn(Optional.of(document));
    }

    static KnowledgeDocument liveDocument(String id, String ingestion, ChunkAccessLevel level) {
        KnowledgeDocumentVersion active = KnowledgeDocumentVersion.builder()
                .sourceDocumentId("source-" + ingestion).sourceDocumentName(id + ".pdf")
                .documentType(DocumentType.COMPANY_POLICY).accessLevel(level).contentSha256("a".repeat(64))
                .stage(KnowledgeIngestionStage.INDEXED).ingestionId(ingestion).chunkCount(1)
                .embeddingModel("voyage-4-lite").embeddingDimensions(1024)
                .activatedAt(NOW.minusDays(1)).activatedByUserId("hr-earlier").build();
        return KnowledgeDocument.builder().id(id).organizationId(ORG).title("Policy " + id)
                .status(KnowledgeDocumentStatus.LIVE).activeVersion(active)
                .sourceDocumentIds(List.of("source-" + ingestion)).build();
    }
}
