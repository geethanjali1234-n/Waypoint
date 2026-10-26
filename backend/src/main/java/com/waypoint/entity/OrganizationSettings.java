package com.waypoint.entity;

import com.waypoint.enums.PayrollPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "organization_settings")
public class OrganizationSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String organizationId;

    @Indexed
    private PayrollPeriod payrollPeriod;

    private String payrollTaxProvider;

    // Authorized Signature (Admin -> Organization Settings). The signature
    // image is the Profile signature of authorizedSignatoryUserId (the
    // Company Admin who configured it); only an active signature whose
    // effective date has arrived is used for signing.
    private String authorizedSignatoryUserId;
    private String authorizedSignatoryName;
    private String authorizedSignatoryTitle;
    private LocalDate signatureEffectiveDate;
    private Boolean signatureActive;
    private String updatedByUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
