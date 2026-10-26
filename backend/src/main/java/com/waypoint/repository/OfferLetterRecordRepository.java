package com.waypoint.repository;

import com.waypoint.entity.OfferLetterRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface OfferLetterRecordRepository extends MongoRepository<OfferLetterRecord, String> {
    Optional<OfferLetterRecord> findByDocumentRequestIdAndOrganizationId(String documentRequestId, String organizationId);
}
