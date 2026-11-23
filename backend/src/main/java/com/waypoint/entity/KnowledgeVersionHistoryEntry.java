package com.waypoint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One former active knowledge version, embedded in its owning KnowledgeDocument. The version
 * contains approval and ingestion metadata only; document bytes and chunk data remain elsewhere.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeVersionHistoryEntry {

    /** The active version that a later activation superseded. */
    private KnowledgeDocumentVersion version;
    private LocalDateTime supersededAt;
    private String supersededByIngestionId;
    private String supersededByUserId;
}
