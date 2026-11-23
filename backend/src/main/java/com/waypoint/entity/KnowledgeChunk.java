package com.waypoint.entity;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "knowledge_chunks")
public class KnowledgeChunk {

    @Id
    private String id;
    private String organizationId;
    private String sourceDocumentId;

    private String sourceDocumentName;

    // Indexes for this collection require an explicitly approved setup step.

    /** KnowledgeDocument this chunk belongs to. */
    private String knowledgeDocumentId;

    /** Ingestion that wrote this chunk; it serves only while this is its entry's active ingestionId. */
    private String ingestionId;
    private DocumentType documentType;
    private String ownerUserId;
    private ChunkAccessLevel accessLevel;

    private int chunkIndex;

    private String chunkText;

    private List<Double> embedding;

    /** Model that produced embedding; only vectors from the model used for the query are comparable. */
    private String embeddingModel;

    private String contentHash;
    private LocalDateTime indexedAt;
}
