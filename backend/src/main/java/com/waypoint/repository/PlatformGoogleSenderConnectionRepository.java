package com.waypoint.repository;

import com.waypoint.entity.PlatformGoogleSenderConnection;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Repository for the one, fixed-id platform Gmail authorization. */
public interface PlatformGoogleSenderConnectionRepository extends MongoRepository<PlatformGoogleSenderConnection, String> {
}
