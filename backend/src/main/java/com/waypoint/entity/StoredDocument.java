package com.waypoint.entity;

import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.StorageProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "documents")
public class StoredDocument {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String ownerUserId;

    @Indexed
    private DocumentType documentType;

    @Indexed
    private String relatedEntityType;

    @Indexed
    private String relatedEntityId;

    private String fileName;

    @Indexed
    private StorageProvider storageProvider;

    @Indexed(unique = true)
    private String storageKey;

    private String contentType;
    private Long fileSize;
    private Integer version;

    @Indexed
    private DocumentStatus status;

    private String uploadedByUserId;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime createdAt;
}
