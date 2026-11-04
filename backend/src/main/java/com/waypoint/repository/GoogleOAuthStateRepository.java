package com.waypoint.repository;

import com.waypoint.entity.GoogleOAuthState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import java.time.LocalDateTime;
import java.util.Optional;

public interface GoogleOAuthStateRepository extends MongoRepository<GoogleOAuthState, String> {

    Optional<GoogleOAuthState> findByStateHash(String stateHash);

    /**
     * Atomically consumes a state: succeeds (returns 1) only if it exists, has not been used and has not expired, so
     * two concurrent callbacks with the same state cannot both win. Callers must treat 0 as "invalid state".
     */
    @Query("{ 'stateHash': ?0, 'usedAt': null, 'expiresAt': { '$gt': ?1 } }")
    @Update("{ '$set': { 'usedAt': ?1 } }")
    long consume(String stateHash, LocalDateTime now);
}
