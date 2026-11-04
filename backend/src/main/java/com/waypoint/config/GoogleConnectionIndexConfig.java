package com.waypoint.config;

import com.waypoint.entity.GoogleOAuthState;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.GoogleConnectionStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Ensures the Google connection indexes exist even though automatic Mongo index creation is off by default. Only runs
 * when the feature is enabled, so a disabled environment never touches these collections.
 *
 * <p>These indexes carry security guarantees: one connection per organization, one CONNECTED organization per Google
 * account, one use per OAuth state, and automatic expiry of abandoned states.</p>
 */
@Component
@ConditionalOnProperty(prefix = "waypoint.google", name = "enabled", havingValue = "true")
public class GoogleConnectionIndexConfig implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    public GoogleConnectionIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndexes(mongoTemplate);
    }

    static void ensureIndexes(MongoTemplate mongoTemplate) {
        var connections = mongoTemplate.indexOps(OrganizationGoogleConnection.class);
        connections.ensureIndex(new Index().on("organizationId", Sort.Direction.ASC).unique()
                .named("google_connection_one_per_organization"));
        connections.ensureIndex(new Index().on("googleSubject", Sort.Direction.ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("status").is(GoogleConnectionStatus.CONNECTED.name())))
                .named("google_connection_one_organization_per_account"));

        var states = mongoTemplate.indexOps(GoogleOAuthState.class);
        states.ensureIndex(new Index().on("stateHash", Sort.Direction.ASC).unique()
                .named("google_oauth_state_hash_unique"));
        states.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0)
                .named("google_oauth_state_expiry"));
    }
}
