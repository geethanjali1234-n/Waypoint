package com.waypoint.entity;

import com.waypoint.enums.OtpPurpose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "otp_verifications")
@CompoundIndex(name = "email_purpose", def = "{'email': 1, 'purpose': 1}")
public class OtpVerification {

    @Id
    private String id;

    private String email;

    private String otpHash;

    private OtpPurpose purpose;

    @Indexed(expireAfter = "0s")
    private LocalDateTime expiresAt;

    private Boolean isVerified;

    @Builder.Default
    private Integer failedAttempts = 0;

    private LocalDateTime invalidatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime verifiedAt;
}
