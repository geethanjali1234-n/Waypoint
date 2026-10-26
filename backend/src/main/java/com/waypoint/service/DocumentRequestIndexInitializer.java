package com.waypoint.service;

import com.waypoint.entity.DocumentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Ensures the worklist indexes exist even when automatic Mongo index creation is disabled. */
@Component
@RequiredArgsConstructor
class DocumentRequestIndexInitializer implements ApplicationRunner {
    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        var indexes = mongoTemplate.indexOps(DocumentRequest.class);
        indexes.ensureIndex(new Index().on("organizationId", Sort.Direction.ASC).on("requestedAt", Sort.Direction.DESC)
                .named("organization_requestedAt"));
        indexes.ensureIndex(new Index().on("organizationId", Sort.Direction.ASC).on("requestedByUserId", Sort.Direction.ASC)
                .on("requestedAt", Sort.Direction.DESC).named("organization_requester_requestedAt"));
        indexes.ensureIndex(new Index().on("organizationId", Sort.Direction.ASC).on("requestedForUserId", Sort.Direction.ASC)
                .on("requestedAt", Sort.Direction.DESC).named("organization_recipient_requestedAt"));
    }
}
