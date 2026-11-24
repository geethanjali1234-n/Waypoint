package com.waypoint.dto.myhr;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalCoverage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** Request bodies for recording and revoking external AI processing approvals. Identity always comes from the token. */
public final class ExternalProcessingApprovalRequests {

    private ExternalProcessingApprovalRequests() {
    }

    /**
     * acknowledgeExternalTransmission must be true: the admin confirms the organization approved sending this
     * content to the provider; approvalReference names that decision.
     */
    public record OrganizationApproval(
            @NotNull ExternalAiProvider provider,
            @Size(max = 3) Set<ChunkAccessLevel> accessLevels,
            @Size(max = 200) String approvalReference,
            boolean acknowledgeExternalTransmission,
            ExternalProcessingApprovalCoverage coverage) {

        /** Without a coverage: REVIEWED_VERSIONS, the original per-version behaviour. */
        public OrganizationApproval(ExternalAiProvider provider, Set<ChunkAccessLevel> accessLevels,
                                    String approvalReference, boolean acknowledgeExternalTransmission) {
            this(provider, accessLevels, approvalReference, acknowledgeExternalTransmission, null);
        }
    }

    /** expectedIngestionId is the activated version the admin reviewed; a different active version is refused. */
    public record DocumentApproval(
            @NotNull ExternalAiProvider provider,
            @Size(max = 100) String expectedIngestionId,
            @Size(max = 200) String approvalReference,
            boolean acknowledgeExternalTransmission) {
    }

    /**
     * Atomically replaces the active organization approval (expectedApprovalId, the one the admin saw) with one of the
     * given coverage. reason explains why the previous approval is revoked; approvalReference names the new decision.
     */
    public record CoverageReplacement(
            @NotNull ExternalAiProvider provider,
            @Size(max = 100) String expectedApprovalId,
            @NotNull ExternalProcessingApprovalCoverage coverage,
            @Size(max = 200) String approvalReference,
            boolean acknowledgeExternalTransmission,
            @Size(max = 500) String reason) {
    }

    public record Revocation(
            @NotNull ExternalAiProvider provider,
            @Size(max = 500) String reason) {
    }
}
