package com.waypoint.dto.auth;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.CompanySize;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmployerRegistrationResponse {

    private String userId;

    private String employerProfileId;

    private String email;

    private String companyName;

    private CompanySize companySize;

    private Boolean isEmailVerified;

    private AccountStatus accountStatus;

    private LocalDateTime createdAt;

    private String devOtp;
}
