package com.waypoint.entity;

import com.waypoint.enums.EmploymentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "employee_profiles")
@CompoundIndex(name = "organization_employee_unique",
        def = "{'organizationId': 1, 'employeeId': 1}", unique = true)
public class EmployeeProfile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    @Indexed
    private String organizationId;

    private String employeeId;

    /* Retained for compatibility with the existing account-creation foundation. */
    @Indexed
    private String name;

    private String firstName;
    private String lastName;

    @Indexed
    private String personalEmail;
    private String workEmail;
    private String phoneNumber;

    @Indexed
    private String designation;

    private BigDecimal hourlyRate;
    private EmploymentType employmentType;
    private LocalDate startDate;
    private String state;
    private String city;
    private String streetAddress;
    private String zipCode;
    private String profileDocumentId;
    private String createdByUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
