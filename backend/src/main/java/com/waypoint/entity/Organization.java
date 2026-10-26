package com.waypoint.entity;

import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationStatus;
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
@Document(collection = "organizations")
public class Organization {

    @Id
    private String id;

    @Indexed(unique = true)
    private String organizationCode;

    @Indexed
    private String organizationName;

    @Indexed(unique = true)
    private String organizationEmail;

    private String phoneNumber;
    private String state;
    private String city;
    private String streetAddress;
    private String zipCode;
    private CompanySize organizationSize;
    private String organizationLogoDocumentId;
    private String organizationSignatureDocumentId;
    private String description;

    @Indexed
    private String industry;

    private String website;
    private String einNumber;
    private String eVerifyNumber;
    private String adminName;
    private String adminEmail;
    private String adminPhoneNumber;
    private Long nextEmployeeSequence;

    @Builder.Default
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
