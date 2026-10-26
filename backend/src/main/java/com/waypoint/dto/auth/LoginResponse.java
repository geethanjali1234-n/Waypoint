package com.waypoint.dto.auth;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String userId;

    private String email;

    private String loginId;

    private String organizationId;

    private UserRole role;

    private AccountStatus accountStatus;

    private Boolean isEmailVerified;

    private Boolean mustChangePassword;

    private String accessToken;

    private String tokenType;
}
