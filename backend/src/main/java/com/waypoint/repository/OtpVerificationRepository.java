package com.waypoint.repository;

import com.waypoint.entity.OtpVerification;
import com.waypoint.enums.OtpPurpose;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import java.time.LocalDateTime;

public interface OtpVerificationRepository extends MongoRepository<OtpVerification, String> {

    Optional<OtpVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, OtpPurpose purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(
            String email, OtpPurpose purpose, LocalDateTime createdAfter);
}
