package com.waypoint.service;

import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The production Claude authorization: organization approval AND every excerpt company-wide AND every excerpt's exact
 * version approved. Repository mocked; no database is touched.
 */
class RecordedExternalPolicyProcessingAuthorizationTest {

    private static final String ORG = "org-recorded-71";

    private final ExternalProcessingApprovalRepository repository = mock(ExternalProcessingApprovalRepository.class);
    private final RecordedExternalPolicyProcessingAuthorization authorization =
            new RecordedExternalPolicyProcessingAuthorization(repository);
    private final List<ExternalProcessingApproval> active = new ArrayList<>();

    RecordedExternalPolicyProcessingAuthorizationTest() {
        when(repository.findByOrganizationIdAndProviderAndStatus(ORG, ExternalAiProvider.ANTHROPIC_CLAUDE,
                ExternalProcessingApprovalStatus.ACTIVE)).thenReturn(active);
    }

    @Test
    void withNoRecordsNothingIsPermitted() {
        assertThat(authorization.mayPermit(ORG)).isFalse();
        assertThat(authorization.permits(ORG, List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
    }

    @Test
    void anOrganizationApprovalAloneDoesNotPermitAnyDocument() {
        active.add(organization(Set.of(ChunkAccessLevel.COMPANY_WIDE)));

        assertThat(authorization.mayPermit(ORG)).isTrue();
        assertThat(authorization.permits(ORG, List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
    }

    @Test
    void exactlyTheApprovedVersionsArePermittedAndOnlyWhenEveryExcerptIsCovered() {
        active.add(organization(Set.of(ChunkAccessLevel.COMPANY_WIDE)));
        active.add(document("kd-1", "ing-1"));

        assertThat(authorization.permits(ORG, List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE)))).isTrue();
        // A new version of the same policy (new ingestion), or another source file, is not covered.
        assertThat(authorization.permits(ORG, List.of(chunk("kd-1", "ing-2", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
        RetrievedChunk otherSource = new RetrievedChunk("kd-1-00000", "kd-1", "Policy", "source-other", "p.pdf", "ing-1",
                0, ChunkAccessLevel.COMPANY_WIDE, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, "text");
        assertThat(authorization.permits(ORG, List.of(otherSource))).isFalse();
        // One unapproved excerpt blocks the whole request.
        assertThat(authorization.permits(ORG, List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE),
                chunk("kd-2", "ing-9", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
    }

    @Test
    void hrOnlyAndEmployeePrivateExcerptsAreNeverPermittedWhateverTheRecordsSay() {
        active.add(organization(Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY,
                ChunkAccessLevel.EMPLOYEE_PRIVATE)));
        ExternalProcessingApproval forged = document("kd-hr", "ing-hr");
        forged.setDocumentAccessLevel(ChunkAccessLevel.HR_ONLY);
        active.add(forged);

        for (ChunkAccessLevel level : List.of(ChunkAccessLevel.HR_ONLY, ChunkAccessLevel.EMPLOYEE_PRIVATE)) {
            assertThat(authorization.permits(ORG, List.of(chunk("kd-hr", "ing-hr", level)))).as(level.name()).isFalse();
        }
        // A document record that claims an HR-only version is not used even for a company-wide excerpt.
        assertThat(authorization.permits(ORG, List.of(chunk("kd-hr", "ing-hr", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
    }

    @Test
    void revokedForeignAndOtherProviderRecordsAreIgnored() {
        ExternalProcessingApproval revokedOrganization = organization(Set.of(ChunkAccessLevel.COMPANY_WIDE));
        revokedOrganization.setStatus(ExternalProcessingApprovalStatus.REVOKED);
        ExternalProcessingApproval foreign = organization(Set.of(ChunkAccessLevel.COMPANY_WIDE));
        foreign.setOrganizationId("org-other");
        ExternalProcessingApproval voyage = organization(Set.of(ChunkAccessLevel.COMPANY_WIDE));
        voyage.setProvider(ExternalAiProvider.VOYAGE_AI);
        active.addAll(List.of(revokedOrganization, foreign, voyage, document("kd-1", "ing-1")));

        assertThat(authorization.mayPermit(ORG)).isFalse();
        assertThat(authorization.permits(ORG, List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
    }

    @Test
    void anotherOrganizationIsNeverAskedAboutAndBadInputIsDenied() {
        active.add(organization(Set.of(ChunkAccessLevel.COMPANY_WIDE)));
        active.add(document("kd-1", "ing-1"));

        assertThat(authorization.permits("org-other", List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE))))
                .isFalse();
        assertThat(authorization.permits(null, List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE)))).isFalse();
        assertThat(authorization.permits(ORG, List.of())).isFalse();
        assertThat(authorization.permits(ORG, null)).isFalse();
        assertThat(authorization.mayPermit(" ")).isFalse();
    }

    @Test
    void aLookupFailurePropagatesSoTheChatServiceTreatsItAsNotAuthorized() {
        when(repository.findByOrganizationIdAndProviderAndStatus(ORG, ExternalAiProvider.ANTHROPIC_CLAUDE,
                ExternalProcessingApprovalStatus.ACTIVE)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> authorization.permits(ORG,
                List.of(chunk("kd-1", "ing-1", ChunkAccessLevel.COMPANY_WIDE)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voyageApprovalCoversHrOnlyOnlyWhenRecordedAndNeverEmployeePrivate() {
        VoyageEmbeddingAuthorization voyage = new VoyageEmbeddingAuthorization(repository);
        List<ExternalProcessingApproval> voyageRecords = new ArrayList<>();
        when(repository.findByOrganizationIdAndProviderAndStatus(ORG, ExternalAiProvider.VOYAGE_AI,
                ExternalProcessingApprovalStatus.ACTIVE)).thenReturn(voyageRecords);

        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.COMPANY_WIDE)).isTrue();
        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.HR_ONLY)).isFalse();
        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.EMPLOYEE_PRIVATE)).isFalse();
        assertThat(voyage.permitsDocumentEmbedding(ORG, null)).isFalse();

        // A Claude approval never counts for Voyage.
        active.add(organization(Set.of(ChunkAccessLevel.COMPANY_WIDE)));
        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.HR_ONLY)).isFalse();

        ExternalProcessingApproval hrOnly = organization(Set.of(ChunkAccessLevel.HR_ONLY));
        hrOnly.setProvider(ExternalAiProvider.VOYAGE_AI);
        voyageRecords.add(hrOnly);
        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.HR_ONLY)).isTrue();
        assertThat(voyage.permitsDocumentEmbedding("org-other", ChunkAccessLevel.HR_ONLY)).isFalse();
        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.EMPLOYEE_PRIVATE)).isFalse();

        when(repository.findByOrganizationIdAndProviderAndStatus(ORG, ExternalAiProvider.VOYAGE_AI,
                ExternalProcessingApprovalStatus.ACTIVE)).thenThrow(new IllegalStateException("database unavailable"));
        assertThat(voyage.permitsDocumentEmbedding(ORG, ChunkAccessLevel.HR_ONLY)).isFalse();
    }

    private static ExternalProcessingApproval organization(Set<ChunkAccessLevel> levels) {
        return ExternalProcessingApproval.builder().organizationId(ORG).provider(ExternalAiProvider.ANTHROPIC_CLAUDE)
                .scope(ExternalProcessingApprovalScope.ORGANIZATION).status(ExternalProcessingApprovalStatus.ACTIVE)
                .accessLevels(levels).build();
    }

    private static ExternalProcessingApproval document(String id, String ingestion) {
        return ExternalProcessingApproval.builder().organizationId(ORG).provider(ExternalAiProvider.ANTHROPIC_CLAUDE)
                .scope(ExternalProcessingApprovalScope.DOCUMENT_VERSION).status(ExternalProcessingApprovalStatus.ACTIVE)
                .knowledgeDocumentId(id).ingestionId(ingestion).sourceDocumentId("source-" + ingestion)
                .documentAccessLevel(ChunkAccessLevel.COMPANY_WIDE).build();
    }

    private static RetrievedChunk chunk(String id, String ingestion, ChunkAccessLevel level) {
        return new RetrievedChunk(id + "-00000", id, "Policy", "source-" + ingestion, id + ".pdf", ingestion, 0, level,
                LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, "text");
    }
}
