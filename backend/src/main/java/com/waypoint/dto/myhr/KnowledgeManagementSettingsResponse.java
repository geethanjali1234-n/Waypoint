package com.waypoint.dto.myhr;

import com.waypoint.enums.ChunkAccessLevel;

import java.util.List;

/**
 * Which knowledge base switches are on for this deployment, so the HR interface can explain why
 * an action is unavailable. Contains no secrets or provider details.
 */
public record KnowledgeManagementSettingsResponse(
        boolean managementEnabled,
        boolean ingestionEnabled,
        boolean activationEnabled,
        boolean retrievalEnabled,
        boolean chatGroundingEnabled,
        List<String> acceptedFileExtensions,
        long maxFileSizeBytes,
        List<ChunkAccessLevel> accessLevels) {
}
