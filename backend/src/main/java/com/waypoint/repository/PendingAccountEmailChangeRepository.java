package com.waypoint.repository;

import com.waypoint.entity.PendingAccountEmailChange;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PendingAccountEmailChangeRepository extends MongoRepository<PendingAccountEmailChange, String> {
}
