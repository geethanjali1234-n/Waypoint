package com.waypoint.entity;

import com.waypoint.enums.KnowledgeDocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One logical MyHR knowledge base entry, such as "Leave Policy", in an organization. Its HR
 * approval is the only way a document reaches the knowledge base.
 *
 * <p>This single document decides which version serves: a KnowledgeChunk is active only while
 * status is LIVE and the chunk's ingestionId equals activeVersion.ingestionId. MongoDB updates one
 * document atomically, so promoting pendingVersion to activeVersion, or revoking, switches every
 * chunk at once without relying on multi-document writes. {@code @Version} makes each such save
 * conditional on nothing having changed since the entry was read.
 *
 * <p>sourceDocumentIds lists the stored documents held by activeVersion and pendingVersion and is
 * written in the same save. It backs the planned unique index that keeps a stored document in at
 * most one LIVE entry. That index is created only by an explicitly approved setup step, so this
 * class deliberately carries no index annotations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "knowledge_documents")
public class KnowledgeDocument {

    /** Activation must reject a new history record once this bound has been reached. */
    public static final int MAX_VERSION_HISTORY_ENTRIES = 100;

    @Id
    private String id;

    private String organizationId;
    private String title;
    private KnowledgeDocumentStatus status;

    private KnowledgeDocumentVersion activeVersion;
    private KnowledgeDocumentVersion pendingVersion;
    private List<KnowledgeVersionHistoryEntry> versionHistory;
    private List<String> sourceDocumentIds;

    private String createdByUserId;
    private LocalDateTime createdAt;
    private String revokedByUserId;
    private LocalDateTime revokedAt;

    @Version
    private Long version;

    /**
     * Legacy documents predate versionHistory. Expose their absent field as an immutable empty
     * list so callers can safely inspect it, while future activation explicitly replaces it with
     * a new list when it appends a record.
     */
    public List<KnowledgeVersionHistoryEntry> getVersionHistory() {
        return versionHistory == null ? List.of() : List.copyOf(versionHistory);
    }

    /** Avoid retaining a caller-owned or shared mutable collection. */
    public void setVersionHistory(List<KnowledgeVersionHistoryEntry> versionHistory) {
        this.versionHistory = versionHistory == null ? null : new ArrayList<>(versionHistory);
    }

    /** True only while a future activation can preserve, rather than discard, its history record. */
    public boolean hasVersionHistoryCapacity() {
        return getVersionHistory().size() < MAX_VERSION_HISTORY_ENTRIES;
    }
}
