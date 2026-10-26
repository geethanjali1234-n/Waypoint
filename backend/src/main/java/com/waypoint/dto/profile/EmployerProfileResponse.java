package com.waypoint.dto.profile;

import com.waypoint.enums.CompanySize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployerProfileResponse {

    private String employerProfileId;
    private String userId;
    private String companyName;
    private String companyDescription;
    private String contactPersonName;
    private String industry;
    private String email;
    private String companyAddress;
    private String phoneNumber;
    private CompanySize companySize;
    private Integer foundedYear;
    private String careersContactEmail;
    private String website;
    private String companyLogoUrl;
    private int completionPercentage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
