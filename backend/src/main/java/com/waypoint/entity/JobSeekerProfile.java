package com.waypoint.entity;

import com.waypoint.enums.EmploymentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "job_seeker_profiles")
public class JobSeekerProfile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private String firstName;

    private String lastName;

    private String address;

    private String city;

    private String state;

    private String phoneNumber;

    private String profilePicturePath;

    private String resumePath;

    @Indexed
    private List<String> skills;

    private List<String> preferredJobRoles;

    @Indexed
    private List<String> preferredLocations;

    private BigDecimal expectedSalaryMin;

    private BigDecimal expectedSalaryMax;

    private List<EmploymentType> preferredEmploymentType;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
