package com.waypoint.repository;

import com.waypoint.entity.ContactSupport;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ContactSupportRepository extends MongoRepository<ContactSupport, String> {
}
