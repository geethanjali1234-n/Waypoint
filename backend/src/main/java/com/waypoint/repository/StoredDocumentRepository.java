package com.waypoint.repository;

import com.waypoint.entity.StoredDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface StoredDocumentRepository extends MongoRepository<StoredDocument, String> {

    Optional<StoredDocument> findByStorageKey(String storageKey);

    List<StoredDocument> findByIdIn(Collection<String> ids);
}
