package com.waypoint.dto.auth;

import com.waypoint.enums.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpResponse {

    private String userId;

    private String email;

    private Boolean isEmailVerified;

    private AccountStatus accountStatus;

    private LocalDateTime verifiedAt;
}
