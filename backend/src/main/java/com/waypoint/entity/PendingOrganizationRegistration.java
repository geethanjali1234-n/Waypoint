package com.waypoint.entity;

import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationRegistrationStatus;
import com.waypoint.enums.PayrollPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "pending_organization_registrations")
public class PendingOrganizationRegistration {

    @Id
    private String id;
    private String organizationId;
    private String organizationCode;
    private String organizationName;
    private String organizationEmail;
    private String phoneNumber;
    private String state;
    private String city;
    private String streetAddress;
    private String zipCode;
    private CompanySize organizationSize;
    private String industry;
    private String website;
    private String description;
    private String organizationLogoDocumentId;
    private String einNumber;
    private String eVerifyNumber;
    private PayrollPeriod payrollPeriod;
    private String adminName;
    private String adminPhoneNumber;
    private String adminLoginId;
    private String adminEmail;
    private OrganizationRegistrationStatus status;
    private String adminUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Indexed(expireAfterSeconds = 0)
    private LocalDateTime expiresAt;
}
